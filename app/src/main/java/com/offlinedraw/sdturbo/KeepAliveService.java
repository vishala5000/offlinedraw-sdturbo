
package com.offlinedraw.sdturbo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** Keeps long downloads / generation alive while the app is in the background. */
public class KeepAliveService extends Service {
    private static final String CH = "offlinedraw_work";
    private static final int ID = 7;
    private Handler h;

    public static void start(Context c) {
        c.startForegroundService(new Intent(c, KeepAliveService.class));
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, KeepAliveService.class));
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH, "OfflineDraw work", NotificationManager.IMPORTANCE_LOW));
        startForeground(ID, build("working"));
        h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override public void run() {
                NotificationManager n = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                n.notify(ID, build(Worker.I.stage));
                h.postDelayed(this, 2000);
            }
        }, 2000);
    }

    @Override public int onStartCommand(Intent i, int flags, int id) { return START_NOT_STICKY; }

    @Override public void onDestroy() { if (h != null) h.removeCallbacksAndMessages(null); }

    private Notification build(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH)
                .setContentTitle("OfflineDraw")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
