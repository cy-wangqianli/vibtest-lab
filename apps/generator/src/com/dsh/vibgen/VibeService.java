package com.dsh.vibgen;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 前台服务：把震动波形交给 VibratorService 后由系统侧播，
 * 应用切到后台也不会被掐断，另一个页面（震摇测试）就能读到。
 */
public class VibeService extends Service {

    public static final String ACTION_STOP = "com.dsh.vibgen.STOP";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_FREQ = "freq";
    public static final String EXTRA_AMP = "amp";
    public static final String EXTRA_COUNT = "count";
    public static final String EXTRA_GAP = "gap";
    public static final String EXTRA_CADENCE = "cadence";
    public static final String EXTRA_DRIFT = "drift";
    public static final String EXTRA_INTERVAL = "interval";
    public static final String EXTRA_HILL = "hill";
    public static final String EXTRA_RAMP = "ramp";

    private static final String CH = "vibgen_run";
    private static final int NID = 1001;
    private static final long LOOP_MS = 6000L;

    private Handler h;
    private Vibrator vib;
    private boolean running = false;
    private int mode = 0, amp = 255, count = 10, gap = 600, idx = 0, cadence = 180;
    private int drift = 0, interval = 0, hill = 0, ramp = 0;
    private long startedAt = 0L;
    private float freq = 10f;

    @Override public void onCreate() {
        super.onCreate();
        h = new Handler(Looper.getMainLooper());
        vib = resolveVibrator();
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(
                    CH, "震动运行", NotificationManager.IMPORTANCE_LOW));
        } catch (Throwable ignore) { }
    }

    @Override public int onStartCommand(Intent it, int flags, int startId) {
        if (it == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(it.getAction())) {
            stopAll();
            stopSelf();
            return START_NOT_STICKY;
        }
        mode = it.getIntExtra(EXTRA_MODE, 0);
        freq = it.getFloatExtra(EXTRA_FREQ, 10f);
        amp = it.getIntExtra(EXTRA_AMP, 255);
        count = it.getIntExtra(EXTRA_COUNT, 10);
        gap = it.getIntExtra(EXTRA_GAP, 600);
        cadence = it.getIntExtra(EXTRA_CADENCE, 180);
        drift = it.getIntExtra(EXTRA_DRIFT, 0);
        interval = it.getIntExtra(EXTRA_INTERVAL, 0);
        hill = it.getIntExtra(EXTRA_HILL, 0);
        ramp = it.getIntExtra(EXTRA_RAMP, 0);
        startedAt = System.currentTimeMillis();

        try {
            int icon = getResources().getIdentifier("ic_launcher", "drawable", getPackageName());
            Notification n = new Notification.Builder(this, CH)
                    .setContentTitle("震动发生器运行中")
                    .setContentText("切到其他页面也会继续震动")
                    .setSmallIcon(icon != 0 ? icon : android.R.drawable.stat_notify_sync)
                    .setOngoing(true)
                    .build();
            startForeground(NID, n);
        } catch (Throwable ignore) { }

        idx = 0;
        running = true;
        h.removeCallbacksAndMessages(null);
        tick();
        return START_STICKY;
    }

    private void tick() {
        if (!running) return;
        if (vib == null || !vib.hasVibrator()) { stopAll(); stopSelf(); return; }

        if (mode == 3) {          // 计数模式：一次一个脉冲
            if (idx >= count) { stopAll(); stopSelf(); return; }
            idx++;
            vibrateOne();
            h.postDelayed(this::tick, Math.max(300, gap));
            return;
        }
        vibratePattern();
        h.postDelayed(this::tick, LOOP_MS - 150L);
    }

    private void vibrateOne() {
        int a = vib.hasAmplitudeControl()
                ? Math.min(255, Math.max(1, amp)) : VibrationEffect.DEFAULT_AMPLITUDE;
        vib.vibrate(VibrationEffect.createOneShot(250, a));
    }

    private void vibratePattern() {
        long[] t;
        int[] a;
        if (mode == 4) {          // 跑步模拟
            long el = startedAt == 0 ? 0 : System.currentTimeMillis() - startedAt;
            int spm = cadence;
            if (drift == 1) spm = Math.round(spm * (1f + 0.05f * (float) Math.sin(el / 40000.0 * 2 * Math.PI)));
            if (interval == 1) spm += ((el % 90000L) < 30000L) ? 12 : -28;
            if (ramp == 1) spm = Math.round(spm * (1f + 0.15f * Math.min(1f, el / 120000f)));
            spm = Math.max(90, Math.min(230, spm));
            long period = Math.max(200L, Math.round(60000f / Math.max(80, spm)));
            long strike = Math.max(18L, Math.round(period * 0.16f));
            long rebound = Math.max(12L, Math.round(period * 0.12f));
            long rest = Math.max(20L, period - strike - rebound);
            int steps = (int) Math.max(1, LOOP_MS / period);
            float[] jitter = {0.96f, 1.0f, 0.91f, 1.0f, 0.94f, 0.98f, 0.90f, 1.0f};
            int base = vib.hasAmplitudeControl() ? Math.min(255, Math.max(1, amp)) : 200;
            if (hill == 1) base = Math.max(20, Math.round(base * (1f + 0.25f * (float) Math.sin(el / 16000.0 * 2 * Math.PI))));
            t = new long[steps * 3 + 1];
            a = new int[steps * 3 + 1];
            t[0] = 0; a[0] = 0;
            for (int i = 0; i < steps; i++) {
                int k = i * 3;
                t[k + 1] = strike;
                a[k + 1] = Math.max(1, Math.round(base * jitter[i % jitter.length]));
                t[k + 2] = rebound;
                a[k + 2] = Math.max(1, Math.round(base * 0.35f));
                t[k + 3] = rest;
                a[k + 3] = 0;
            }
        } else if (mode == 2) {          // 扫频
            List<Long> tl = new ArrayList<>();
            List<Integer> al = new ArrayList<>();
            tl.add(0L); al.add(0);
            long total = 0;
            float f = 2f;
            while (total < LOOP_MS - 100) {
                long period = Math.max(3L, Math.round(1000f / f));
                long on = Math.max(1L, period / 2);
                tl.add(on); al.add(amp);
                tl.add(period - on); al.add(0);
                total += period;
                f = Math.min(20f, f + 0.08f);
            }
            t = new long[tl.size()];
            a = new int[tl.size()];
            for (int i = 0; i < t.length; i++) { t[i] = tl.get(i); a[i] = al.get(i); }
        } else {
            long period = Math.max(2L, Math.round(1000f / Math.max(1f, freq)));
            long on = mode == 1 ? Math.max(1L, Math.round(period * 0.12f))
                    : Math.max(1L, period / 2);
            long off = Math.max(1L, period - on);
            int cycles = (int) Math.max(1, LOOP_MS / (on + off));
            t = new long[cycles * 2 + 1];
            a = new int[cycles * 2 + 1];
            t[0] = 0; a[0] = 0;
            int val = vib.hasAmplitudeControl() ? Math.min(255, Math.max(1, amp))
                    : VibrationEffect.DEFAULT_AMPLITUDE;
            for (int i = 0; i < cycles; i++) {
                t[i * 2 + 1] = on;  a[i * 2 + 1] = val;
                t[i * 2 + 2] = off; a[i * 2 + 2] = 0;
            }
        }
        try {
            vib.vibrate(VibrationEffect.createWaveform(t, a, -1));
        } catch (Throwable e) {
            try { vib.vibrate(VibrationEffect.createWaveform(t, -1)); } catch (Throwable ignore) { }
        }
    }

    private void stopAll() {
        running = false;
        h.removeCallbacksAndMessages(null);
        try { if (vib != null) vib.cancel(); } catch (Throwable ignore) { }
    }

    private Vibrator resolveVibrator() {
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
            if (vm != null) return vm.getDefaultVibrator();
        }
        return (Vibrator) getSystemService(VIBRATOR_SERVICE);
    }

    @Override public void onDestroy() {
        stopAll();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent it) { return null; }

    @SuppressWarnings("unused")
    private String describe() {
        return String.format(Locale.US, "mode=%d freq=%.1f amp=%d", mode, freq, amp);
    }
}
