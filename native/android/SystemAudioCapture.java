package com.tahdigi.visualizer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.util.ArrayList;
import java.util.List;

@CapacitorPlugin(
    name = "SystemAudioCapture",
    permissions = { @Permission(strings = { android.Manifest.permission.RECORD_AUDIO }, alias = "recordAudio") }
)
public class SystemAudioCapture extends Plugin {
    private static final int REQUEST_CAPTURE = 4107;
    private AudioRecord recorder;
    private MediaProjection projection;
    private Thread worker;
    private volatile boolean running = false;
    private final Handler main = new Handler(Looper.getMainLooper());

    @PluginMethod
    public void start(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            call.reject("System audio capture requires Android 10 or newer.");
            return;
        }
        if (running) {
            call.resolve();
            return;
        }
        if (!hasRequiredPermissions()) {
            requestAllPermissions(call, "permissionResult");
            return;
        }
        beginProjection(call);
    }

    @PermissionCallback
    private void permissionResult(PluginCall call) {
        if (!hasRequiredPermissions()) {
            call.reject("Microphone permission is required by Android for playback capture.");
            return;
        }
        beginProjection(call);
    }

    private void beginProjection(PluginCall call) {
        MediaProjectionManager mgr =
            (MediaProjectionManager) getContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mgr == null) {
            call.reject("MediaProjection is unavailable on this device.");
            return;
        }
        saveCall(call);
        startActivityForResult(call, mgr.createScreenCaptureIntent(), "captureResult");
    }

    @PluginMethod
    public void listApps(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            call.reject("App audio selection requires Android 10 or newer.");
            return;
        }
        try {
            PackageManager pm = getContext().getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN, null);
            launcher.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> infos = pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL);
            ArrayList<JSObject> apps = new ArrayList<>();
            String ownPackage = getContext().getPackageName();
            for (ResolveInfo info : infos) {
                if (info.activityInfo == null || info.activityInfo.applicationInfo == null) continue;
                ApplicationInfo ai = info.activityInfo.applicationInfo;
                String pkg = ai.packageName;
                if (pkg == null || pkg.equals(ownPackage)) continue;
                CharSequence labelCs = pm.getApplicationLabel(ai);
                String label = labelCs != null ? labelCs.toString() : pkg;
                JSObject item = new JSObject();
                item.put("label", label);
                item.put("package", pkg);
                item.put("uid", ai.uid);
                apps.add(item);
            }
            apps.sort((a, b) -> a.getString("label", "").compareToIgnoreCase(b.getString("label", "")));
            JSObject result = new JSObject();
            result.put("apps", JSArray.from(apps));
            call.resolve(result);
        } catch (Throwable t) {
            call.reject("Could not list installed apps: " + t.getMessage());
        }
    }

    @ActivityCallback
    private void captureResult(PluginCall call, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            call.reject("Phone audio capture permission was cancelled.");
            return;
        }
        try {
            MediaProjectionManager mgr =
                (MediaProjectionManager) getContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = mgr.getMediaProjection(resultCode, data);

            Integer selectedUid = call.getData().getInteger("uid", -1);
            AudioPlaybackCaptureConfiguration.Builder captureBuilder =
                new AudioPlaybackCaptureConfiguration.Builder(projection);
            if (selectedUid != null && selectedUid > 0) {
                captureBuilder.addMatchingUid(selectedUid);
            } else {
                captureBuilder
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME);
            }
            AudioPlaybackCaptureConfiguration config = captureBuilder.build();

            int sampleRate = 48000;
            AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();

            int min = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            );
            int bufferSize = Math.max(min, 4096) * 2;

            recorder = new AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(config)
                .build();

            recorder.startRecording();
            running = true;
            worker = new Thread(this::captureLoop, "TahaAi-SystemAudio");
            worker.start();
            call.resolve();
        } catch (Throwable t) {
            stopInternal();
            call.reject("Could not start system audio capture: " + t.getMessage());
        }
    }


    private void captureLoop() {
        final int N = 1024;
        short[] samples = new short[N];
        double[] real = new double[N];
        double[] imag = new double[N];

        while (running && recorder != null) {
            int read = recorder.read(samples, 0, N);
            if (read <= 0) continue;

            double sum = 0;
            for (int i = 0; i < N; i++) {
                double x = i < read ? samples[i] / 32768.0 : 0.0;
                double w = 0.5 - 0.5 * Math.cos((2.0 * Math.PI * i) / (N - 1));
                real[i] = x * w;
                imag[i] = 0.0;
                sum += x * x;
            }

            fft(real, imag);

            final int bands = 64;
            JSArray arr = new JSArray();
            double nyquist = 24000.0;
            double minHz = 35.0;
            for (int b = 0; b < bands; b++) {
                double lo = minHz * Math.pow(nyquist / minHz, b / (double) bands);
                double hi = minHz * Math.pow(nyquist / minHz, (b + 1) / (double) bands);
                int loBin = Math.max(1, (int) Math.floor(lo * N / 48000.0));
                int hiBin = Math.min(N / 2 - 1, (int) Math.ceil(hi * N / 48000.0));
                double peak = 0;
                for (int k = loBin; k <= hiBin; k++) {
                    double mag = Math.sqrt(real[k] * real[k] + imag[k] * imag[k]) / N * 2.0;
                    if (mag > peak) peak = mag;
                }
                double v = Math.min(1.0, Math.sqrt(peak) * 3.2);
                try {
                    arr.put(v);
                } catch (org.json.JSONException ignored) {
                    // Skip this band if the JSON array rejects the value.
                }
            }

            double rms = Math.sqrt(sum / Math.max(1, read));
            JSObject data = new JSObject();
            data.put("rms", Math.min(1.0, rms * 4.0));
            data.put("bands", arr);
            main.post(() -> notifyListeners("audioLevel", data));
        }
    }

    private void fft(double[] real, double[] imag) {
        int n = real.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double tr = real[i]; real[i] = real[j]; real[j] = tr;
                double ti = imag[i]; imag[i] = imag[j]; imag[j] = ti;
            }
        }

        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            double wLenR = Math.cos(ang);
            double wLenI = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double wr = 1.0, wi = 0.0;
                int half = len >> 1;
                for (int j = 0; j < half; j++) {
                    int u = i + j;
                    int v = u + half;
                    double vr = real[v] * wr - imag[v] * wi;
                    double vi = real[v] * wi + imag[v] * wr;
                    real[v] = real[u] - vr;
                    imag[v] = imag[u] - vi;
                    real[u] += vr;
                    imag[u] += vi;
                    double nextWr = wr * wLenR - wi * wLenI;
                    wi = wr * wLenI + wi * wLenR;
                    wr = nextWr;
                }
            }
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        stopInternal();
        call.resolve();
    }

    private void stopInternal() {
        running = false;
        if (recorder != null) {
            try { recorder.stop(); } catch (Throwable ignored) {}
            try { recorder.release(); } catch (Throwable ignored) {}
            recorder = null;
        }
        if (projection != null) {
            try { projection.stop(); } catch (Throwable ignored) {}
            projection = null;
        }
        main.post(() -> notifyListeners("captureStopped", new JSObject()));
    }

    @Override
    protected void handleOnDestroy() {
        stopInternal();
        super.handleOnDestroy();
    }
}
