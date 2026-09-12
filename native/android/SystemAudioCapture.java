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
import android.os.SystemClock;
import android.util.Log;

import androidx.activity.result.ActivityResult;
import androidx.core.content.ContextCompat;

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

/**
 * Captures device playback audio via Android's AudioPlaybackCapture API (Android 10+)
 * and streams FFT band levels to the web visualizer through "audioLevel" events.
 *
 * <p>Flow: RECORD_AUDIO runtime permission -> MediaProjection consent dialog ->
 * (Android 14+) mediaProjection foreground service -> AudioRecord -> throttled events.
 */
@CapacitorPlugin(
    name = "SystemAudioCapture",
    permissions = { @Permission(strings = { android.Manifest.permission.RECORD_AUDIO }, alias = "recordAudio") }
)
public class SystemAudioCapture extends Plugin {

    private static final String TAG = "SystemAudioCapture";
    private static final int SAMPLE_RATE = 48000;
    private static final int FFT_SIZE = 1024;
    private static final int BANDS = 64;
    /** Cap JS events at ~20fps: plenty for a visualizer, keeps the bridge healthy. */
    private static final long EMIT_INTERVAL_MS = 50;

    private AudioRecord recorder;
    private MediaProjection projection;
    private Thread worker;
    private volatile boolean running = false;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            stopInternal();
            main.post(() -> notifyListeners("captureStopped", new JSObject()));
        }
    };

    // ------------------------------------------------------------------ API

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

    @PluginMethod
    public void stop(PluginCall call) {
        stopInternal();
        call.resolve();
    }

    // ------------------------------------------------------------- permissions

    @PermissionCallback
    private void permissionResult(PluginCall call) {
        if (call == null) {
            return;
        }
        if (!hasRequiredPermissions()) {
            call.reject("Microphone permission is required by Android for playback capture.");
            return;
        }
        beginProjection(call);
    }

    // ------------------------------------------------------------ projection

    private void beginProjection(PluginCall call) {
        MediaProjectionManager mgr =
            (MediaProjectionManager) getContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mgr == null) {
            call.reject("MediaProjection is unavailable on this device.");
            return;
        }
        // Android 14+: a mediaProjection-type foreground service must already be running
        // before getMediaProjection() is called, otherwise it throws SecurityException.
        // Start it BEFORE the consent dialog so there is no race when the user answers.
        startCaptureService();
        saveCall(call);
        startActivityForResult(call, mgr.createScreenCaptureIntent(), "captureResult");
    }

    /**
     * IMPORTANT: the (PluginCall, ActivityResult) signature is required by Capacitor 3+.
     * The legacy (PluginCall, int, Intent) form is never invoked by the framework, which
     * leaves the call hanging and can crash the app when the consent dialog returns.
     */
    @ActivityCallback
    private void captureResult(PluginCall call, ActivityResult result) {
        if (call == null) {
            return;
        }
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            stopCaptureService();
            call.reject("Phone audio capture was cancelled.");
            return;
        }
        try {
            MediaProjectionManager mgr =
                (MediaProjectionManager) getContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = mgr.getMediaProjection(result.getResultCode(), result.getData());
            if (projection == null) {
                throw new IllegalStateException("Android did not return a MediaProjection token.");
            }
            projection.registerCallback(projectionCallback, main);

            // Capture the device playback mix exposed by Android's public
            // AudioPlaybackCapture API. Android only exposes capturable app playback
            // (MEDIA/GAME/UNKNOWN) - not calls, alarms or DRM-protected streams.
            AudioPlaybackCaptureConfiguration config =
                new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();

            AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();

            int minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            );
            if (minBuf <= 0) {
                throw new IllegalStateException("Device reported an invalid audio buffer size: " + minBuf);
            }

            recorder = new AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(Math.max(minBuf, 4096) * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build();

            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException(
                    "Android could not start the playback capture stream. The source app may block capture.");
            }

            running = true;
            worker = new Thread(this::captureLoop, "TahaAi-SystemAudio");
            worker.start();
            call.resolve();
        } catch (SecurityException se) {
            stopInternal();
            call.reject("Android blocked the capture (" + se.getMessage() + ").");
        } catch (Throwable t) {
            stopInternal();
            call.reject("Could not start system audio capture: " + t.getMessage());
        }
    }

    // ---------------------------------------------------------------- capture

    private void captureLoop() {
        final int N = FFT_SIZE;
        short[] samples = new short[N];
        double[] real = new double[N];
        double[] imag = new double[N];
        long lastEmit = 0;

        while (running) {
            AudioRecord rec = recorder;
            if (rec == null) {
                break;
            }
            int read = rec.read(samples, 0, N);
            if (read <= 0) {
                continue;
            }

            double sum = 0;
            for (int i = 0; i < N; i++) {
                double x = i < read ? samples[i] / 32768.0 : 0.0;
                double w = 0.5 - 0.5 * Math.cos((2.0 * Math.PI * i) / (N - 1));
                real[i] = x * w;
                imag[i] = 0.0;
                sum += x * x;
            }

            fft(real, imag);

            long now = SystemClock.uptimeMillis();
            if (now - lastEmit < EMIT_INTERVAL_MS) {
                continue;
            }
            lastEmit = now;

            JSArray arr = new JSArray();
            double nyquist = SAMPLE_RATE / 2.0;
            double minHz = 35.0;
            for (int b = 0; b < BANDS; b++) {
                double lo = minHz * Math.pow(nyquist / minHz, b / (double) BANDS);
                double hi = minHz * Math.pow(nyquist / minHz, (b + 1) / (double) BANDS);
                int loBin = Math.max(1, (int) Math.floor(lo * N / SAMPLE_RATE));
                int hiBin = Math.min(N / 2 - 1, (int) Math.ceil(hi * N / SAMPLE_RATE));
                double peak = 0;
                for (int k = loBin; k <= hiBin; k++) {
                    double mag = Math.sqrt(real[k] * real[k] + imag[k] * imag[k]) / N * 2.0;
                    if (mag > peak) {
                        peak = mag;
                    }
                }
                double v = Math.min(1.0, Math.sqrt(peak) * 3.2);
                try {
                    arr.put(v);
                } catch (org.json.JSONException ignored) {
                    // Skip an invalid band value; keep capture running.
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

    // ------------------------------------------------------------------ stop

    private void stopInternal() {
        running = false;
        if (recorder != null) {
            try { recorder.stop(); } catch (Throwable ignored) {}
            try { recorder.release(); } catch (Throwable ignored) {}
            recorder = null;
        }
        if (projection != null) {
            try { projection.unregisterCallback(projectionCallback); } catch (Throwable ignored) {}
            try { projection.stop(); } catch (Throwable ignored) {}
            projection = null;
        }
        stopCaptureService();
        main.post(() -> notifyListeners("captureStopped", new JSObject()));
    }

    // ------------------------------------------------- foreground service (API 34+)

    private void startCaptureService() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return;
        }
        try {
            Context app = getContext().getApplicationContext();
            ContextCompat.startForegroundService(app, new Intent(app, AudioCaptureService.class));
        } catch (Throwable t) {
            Log.w(TAG, "Could not start capture service: " + t.getMessage());
        }
    }

    private void stopCaptureService() {
        try {
            Context app = getContext().getApplicationContext();
            app.stopService(new Intent(app, AudioCaptureService.class));
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------ misc API

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

    @Override
    protected void handleOnDestroy() {
        stopInternal();
        super.handleOnDestroy();
    }
}
