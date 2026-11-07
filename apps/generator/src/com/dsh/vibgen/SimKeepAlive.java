package com.dsh.vibgen;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** 模拟运动期间的保活服务：只挂一条通知，防止后台被杀导致广播中断 */
public class SimKeepAlive extends Service {

    private static final String CH = "vibgen_sim";
    private static final int NID = 1002;

    @Override public int onStartCommand(Intent it, int flags, int startId) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(
                    CH, "模拟运动", NotificationManager.IMPORTANCE_LOW));
            int icon = getResources().getIdentifier("ic_launcher", "drawable", getPackageName());
            Notification n = new Notification.Builder(this, CH)
                    .setContentTitle("模拟运动进行中")
                    .setContentText("正在向测试器推送轨迹")
                    .setSmallIcon(icon != 0 ? icon : android.R.drawable.stat_notify_sync)
                    .setOngoing(true)
                    .build();
            startForeground(NID, n);
        } catch (Throwable ignore) { }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent it) { return null; }
}
