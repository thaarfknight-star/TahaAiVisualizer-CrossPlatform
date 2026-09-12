package com.tahdigi.visualizer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

/**
 * Minimal foreground service of type "mediaProjection".
 *
 * <p>Required on Android 14 (API 34)+: MediaProjectionManager.getMediaProjection()
 * throws SecurityException unless the app already runs a foreground service with
 * FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION. The plugin starts this service before
 * showing the screen-capture consent dialog and stops it when capture ends, so the
 * user only sees the notification while capture is active.
 */
public class AudioCaptureService extends Service {

    private static final String CHANNEL_ID = "tahaai_visualizer_capture";
    private static final int NOTIFICATION_ID = 4107;

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TahaAi Visualizer")
            .setContentText("Capturing device playback audio")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        return START_STICKY;
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Audio capture",
                NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
