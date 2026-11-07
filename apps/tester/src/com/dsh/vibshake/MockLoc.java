package com.dsh.vibshake;

import android.content.Context;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * 把闭环路线喂给系统定位（测试 provider）。
 * 前置条件：开发者选项 → 选择模拟位置信息应用 → 选「震摇测试」。
 */
public class MockLoc {

    public interface Status { void onStatus(String text); }

    private static final String[] PROVIDERS = {"gps", "network"};

    private final Context ctx;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<double[]> pts = new ArrayList<>();   // {lat, lng, cumMeters}
    private double total = 0;
    private double pos = 0;
    private double speed = 2.8;      // m/s
    private boolean running = false;
    private boolean fixed = false;
    private double fixedLat = 0, fixedLng = 0;
    private LocationManager lm;
    private Status status;

    public MockLoc(Context c) { ctx = c; }

    public void setStatus(Status s) { status = s; }

    public void setLoop(String json, int lengthMeters) {
        pts.clear();
        total = 0;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONArray p = arr.getJSONArray(i);
                double lat = p.getDouble(0), lng = p.getDouble(1);
                if (!pts.isEmpty()) {
                    double[] prev = pts.get(pts.size() - 1);
                    total += hav(prev[0], prev[1], lat, lng);
                }
                pts.add(new double[]{lat, lng, total});
            }
        } catch (Throwable t) {
            pts.clear();
        }
        fixed = false;
        emit("闭环已载入：" + Math.round(total) + " m");
    }

    public boolean hasLoop() { return pts.size() >= 3 && total > 10; }

    public void setSpeed(double mps) { speed = Math.max(0.5, mps); }

    public boolean startFixed(double lat, double lng) {
        fixed = true;
        fixedLat = lat;
        fixedLng = lng;
        return start();
    }

    public boolean start() {
        if (!fixed && !hasLoop()) { emit("先在地图上闭合一条路线"); return false; }
        lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) { emit("没有定位服务"); return false; }
        try {
            for (String p : PROVIDERS) {
                try { lm.removeTestProvider(p); } catch (Throwable ignore) { }
                lm.addTestProvider(p, false, true, false, false, true, true, true,
                        Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
                lm.setTestProviderEnabled(p, true);
            }
        } catch (SecurityException e) {
            emit("没有模拟定位权限：开发者选项 → 选择模拟位置信息应用");
            return false;
        } catch (Throwable t) {
            emit("创建测试 provider 失败：" + t.getMessage());
            return false;
        }
        running = true;
        pos = 0;
        h.removeCallbacks(tick);
        h.post(tick);
        emit("模拟定位已开始，速度 " + String.format("%.1f", speed) + " m/s");
        return true;
    }

    public void stop() {
        running = false;
        h.removeCallbacks(tick);
        try {
            for (String p : PROVIDERS) lm.setTestProviderEnabled(p, false);
        } catch (Throwable ignore) { }
        emit("模拟定位已停止");
    }

    public boolean isRunning() { return running; }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            double[] p = fixed ? new double[]{fixedLat, fixedLng, 0} : pointAt(pos);
            push(p);
            pos += speed;
            if (pos >= total) pos -= total;
            h.postDelayed(this, 1000L);
        }
    };

    private void push(double[] p) {
        if (lm == null || p == null) return;
        try {
            for (String prov : PROVIDERS) {
                Location loc = new Location(prov);
                loc.setLatitude(p[0]);
                loc.setLongitude(p[1]);
                loc.setAltitude(480);
                loc.setAccuracy(2f);
                loc.setSpeed((float) speed);
                loc.setBearing((float) p[2]);
                loc.setTime(System.currentTimeMillis());
                loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
                lm.setTestProviderLocation(prov, loc);
            }
            emit(String.format("模拟中 %.5f, %.5f · %d m / %d m",
                    p[0], p[1], (int) pos, (int) total));
        } catch (Throwable t) {
            emit("推送位置失败：" + t.getMessage());
        }
    }

    /** 返回 {lat, lng, bearing} */
    private double[] pointAt(double s) {
        if (pts.isEmpty()) return null;
        if (s <= 0) return new double[]{pts.get(0)[0], pts.get(0)[1], 0};
        for (int i = 1; i < pts.size(); i++) {
            double[] a = pts.get(i - 1), b = pts.get(i);
            if (s <= b[2]) {
                double seg = b[2] - a[2];
                double k = seg > 0 ? (s - a[2]) / seg : 0;
                double lat = a[0] + (b[0] - a[0]) * k;
                double lng = a[1] + (b[1] - a[1]) * k;
                return new double[]{lat, lng, bearing(a[0], a[1], b[0], b[1])};
            }
        }
        double[] last = pts.get(pts.size() - 1);
        return new double[]{last[0], last[1], 0};
    }

    private static double hav(double la1, double lo1, double la2, double lo2) {
        double R = 6371000, dLat = Math.toRadians(la2 - la1), dLon = Math.toRadians(lo2 - lo1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    private static double bearing(double la1, double lo1, double la2, double lo2) {
        double y = Math.sin(Math.toRadians(lo2 - lo1)) * Math.cos(Math.toRadians(la2));
        double x = Math.cos(Math.toRadians(la1)) * Math.sin(Math.toRadians(la2))
                - Math.sin(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2))
                * Math.cos(Math.toRadians(lo2 - lo1));
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    private void emit(String s) { if (status != null) status.onStatus(s); }
}
