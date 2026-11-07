package com.dsh.vibshake;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener, android.location.LocationListener {

    private static final int BG = 0xFF121212;
    private static final int FG = 0xFFE8E8E8;
    private static final int DIM = 0xFF9AA0A6;
    private static final int ACC = 0xFF7FD1AE;

    private SensorManager sm;
    private Sensor accel;
    private Vibrator vib;

    private TextView tvLive, tvPeak, tvCount, tvLog, tvAmpVal, tvThVal, tvRun;
    private SeekBar sbThreshold, sbAmp;
    private CheckBox cbShake;
    private Button btnToggle, btnSelf;
    private SeekBar sbSelf;
    private boolean selftest = false;
    private boolean stepArmed = true;
    private float prevLinear = 0f;
    private float dFloor = 0f;
    private long lastFastAt = 0L;
    private int expected = 0;
    private final Handler h = new Handler(Looper.getMainLooper());

    private float gravityEst = SensorManager.GRAVITY_EARTH;
    private float peak = 0f;
    private int shakeCount = 0;
    private long lastShakeAt = 0L;

    // 跑步检测（模拟运动软件的做法）
    private long lastStepAt = 0L;
    private int stepCount = 0;
    private final long[] stepTs = new long[16];
    private int tsIdx = 0;
    private float lpVal = 0f;
    private float noiseFloor = 0f;

    // 真人模式
    private CheckBox cbReal;
    private SeekBar sbStride;
    private boolean calibrating = false;
    private long calStart = 0L;
    private float calSum = 0f, calMax = 0f;
    private int calN = 0;
    private float realThr = 3.0f;

    // 运动会话
    private TextView tvSession;
    private LeafletMapView rv;
    private float rx = 0f, ry = 0f, rhead = (float) (Math.random() * Math.PI * 2);
    private Button btnSession;
    private boolean sessionOn = false;
    private TextView tvMapState;
    private StatsChart chart;
    private SplitChart splitChart;
    private TextView tvCompare;
    private TextView tvSplits, tvHistory, tvEnv;
    private final java.util.List<String> splitRows = new java.util.ArrayList<>();
    private float nextSplitAt = 1000f;
    private long prevSplitSec = 0L;
    private long lastSampleSec = -1L;
    private int validRuns = 0;
    private boolean devWarned = false;
    private final java.util.ArrayDeque<Long> geoDts = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<Float> geoSpd = new java.util.ArrayDeque<>();
    private long lastFixAt = 0L;
    private double lastFixLat = Double.NaN, lastFixLng = Double.NaN;
    private int dupFixes = 0;
    private int fixCount = 0;
    private float lastAccuracy = -1f;
    private String lastProvider = "--";
    private final java.util.List<String> samples = new java.util.ArrayList<>();
    private float sessDist = 0f;
    private double lastLat = Double.NaN, lastLng = Double.NaN;
    private long lastGeoAt = 0L;
    private boolean centeredOnce = false;
    private volatile boolean usingBroadcast = false;
    private float gpsSpeedKmh = 0f;
    private android.location.LocationManager lm;
    private boolean locStarted = false;
    private long sessionStart = 0L;
    private int sessSteps = 0;
    private int prevSteps = 0;
    private float maxKmh = 0f, minKmh = 0f;
    private float bestPace = 0f;
    private long lastStepGapMs = 0L;
    private final Handler timer = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!sessionOn) return;
            updateSessionUi();
            if (sessionOn && chart != null) {
                long sec = (System.currentTimeMillis() - sessionStart) / 1000;
                if (sec != lastSampleSec) {
                    lastSampleSec = sec;
                    chart.add(sec, gpsFresh() ? gpsSpeedKmh : curCadence() / 60f * stride() * 3.6f);
                }
            }
            timer.postDelayed(this, 300L);
        }
    };
    private boolean running = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accel = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        vib = resolveVibrator();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        root.addView(title("震摇测试"), lp(0));
        root.addView(sub("加速度计 · 震动 API 能力探测"), lp(0));

        // ---------- 实时数据 ----------
        tvLive = body("等待传感器…");
        root.addView(card(tvLive));

        tvPeak = label("峰值 0.00 m/s²");
        tvCount = label("摇动次数 0");
        root.addView(tvPeak);
        root.addView(tvCount);

        // ---------- 摇一摇设置 ----------
        root.addView(section("摇一摇"));
        tvThVal = label("阈值 12.0 m/s²");
        sbThreshold = new SeekBar(this);
        sbThreshold.setMax(59);           // 0.5..30，步长 0.5
        sbThreshold.setProgress(23);       // 12.0
        sbThreshold.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                tvThVal.setText(String.format(Locale.US, "阈值 %.1f m/s²", threshold()));
            }
        });
        root.addView(tvThVal);
        root.addView(sbThreshold);

        cbShake = new CheckBox(this);
        cbShake.setText("摇动时震动（用下面的强度）");
        cbShake.setTextColor(FG);
        cbShake.setChecked(true);
        root.addView(cbShake);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        btnToggle = button("开始监听");
        btnToggle.setOnClickListener(v -> toggleSensor());
        Button btnReset = button("清零");
        btnReset.setOnClickListener(v -> {
            shakeCount = 0; peak = 0f;
            tvCount.setText("摇动次数 0");
            tvPeak.setText("峰值 0.00 m/s²");
            log("已清零");
        });
        row1.addView(btnToggle, weight());
        row1.addView(btnReset, weight());
        root.addView(row1);

        root.addView(section("震动自测（本机自震自数）"));
        sbSelf = new SeekBar(this);
        sbSelf.setMax(19);
        sbSelf.setProgress(9);
        btnSelf = button("自测 10 次");
        sbSelf.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar s2, int p2, boolean u) {
                btnSelf.setText("自测 " + (p2 + 1) + " 次");
            }
        });
        root.addView(sbSelf);
        btnSelf.setOnClickListener(v -> startSelfTest());
        root.addView(btnSelf, lp(6));

        // ---------- 震动测试 ----------
        root.addView(section("跑步检测（模拟运动软件）"));
        tvRun = body("步数 0　步频 -- spm");
        root.addView(card(tvRun));
        LinearLayout rowRun = new LinearLayout(this);
        rowRun.setOrientation(LinearLayout.HORIZONTAL);
        Button bRunReset = button("跑步清零");
        bRunReset.setOnClickListener(v -> {
            stepCount = 0; tsIdx = 0; lastStepAt = 0L; noiseFloor = 0f; lpVal = 0f;
            sessSteps = 0; maxKmh = 0f; minKmh = 0f; bestPace = 0f; lastStepGapMs = 0L;
            if (sessionOn) sessionStart = System.currentTimeMillis();
            tvRun.setText("步数 0　步频 -- spm");
            log("跑步数据清零");
        });
        rowRun.addView(bRunReset, weight());
        root.addView(rowRun);

        cbReal = new CheckBox(this);
        cbReal.setText("真人模式（用标定阈值，步幅可调）");
        cbReal.setTextColor(FG);
        root.addView(cbReal);

        sbStride = new SeekBar(this);
        sbStride.setMax(18);      // 0.5 .. 1.4 m，步长 0.05
        sbStride.setProgress(8);  // 0.9 m
        root.addView(sbStride);

        Button bCal = button("静止标定 3 秒（人别动）");
        bCal.setOnClickListener(v -> startCalibration());
        root.addView(bCal, lp(6));

        root.addView(section("运动记录"));
        tvSession = body("未开始");
        root.addView(card(tvSession));
        root.addView(section("真人轨迹（只读）"));
        tvMapState = label("地图加载中…");
        root.addView(tvMapState);

        Button bLocate = button("回到我的位置");
        bLocate.setOnClickListener(v -> { if (rv != null) rv.resetView(); });
        root.addView(bLocate, lp(6));
        rv = new LeafletMapView(this);
        rv.setListener(new LeafletMapView.Listener() {
            @Override public void onConsole(String msg) { log("JS " + msg); }
            @Override public void onReady() {
                log("地图页面已就绪");
                if (tvMapState != null) tvMapState.setText("地图已就绪");
            }
        });
        root.addView(rv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(280)));



        btnSession = button("开始记录");
        btnSession.setOnClickListener(v -> {
            if (sessionOn) stopSession(); else startSession();
        });
        root.addView(btnSession, lp(6));

        root.addView(section("模拟数据 / 环境"));
        tvEnv = label("开发者选项：--");
        root.addView(tvEnv);

        Button bExport = button("导出本次测试报告（CSV）");
        bExport.setOnClickListener(v -> exportReport());
        root.addView(bExport, lp(6));

        Button bSim = button("生成模拟路线并发给发生器");
        bSim.setOnClickListener(v -> exportSimRoute());
        root.addView(bSim, lp(6));

        root.addView(section("统计"));
        chart = new StatsChart(this);
        root.addView(chart, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(120)));
        tvSplits = label("每公里分段：--");
        root.addView(tvSplits);
        tvHistory = label("历史：--");
        root.addView(tvHistory);

        splitChart = new SplitChart(this);
        root.addView(splitChart, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(90)));
        tvCompare = label("多轮对比：--");
        root.addView(tvCompare);
        loadHistory();
        updateCompare();

        root.addView(section("震动"));
        tvAmpVal = label("强度 255 / 255");
        sbAmp = new SeekBar(this);
        sbAmp.setMax(254);                // 1..255
        sbAmp.setProgress(254);
        sbAmp.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                tvAmpVal.setText("强度 " + amplitude() + " / 255");
            }
        });
        root.addView(tvAmpVal);
        root.addView(sbAmp);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        Button b1 = button("短震 50ms");
        b1.setOnClickListener(v -> oneShot(50));
        Button b2 = button("长震 800ms");
        b2.setOnClickListener(v -> oneShot(800));
        row2.addView(b1, weight());
        row2.addView(b2, weight());
        root.addView(row2);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        Button b3 = button("心跳节奏");
        b3.setOnClickListener(v -> pattern());
        Button b4 = button("强度扫描");
        b4.setOnClickListener(v -> sweep());
        row3.addView(b3, weight());
        row3.addView(b4, weight());
        root.addView(row3);

        LinearLayout row4 = new LinearLayout(this);
        row4.setOrientation(LinearLayout.HORIZONTAL);
        Button b5 = button("预置 CLICK");
        b5.setOnClickListener(v -> predefined(VibrationEffect.EFFECT_CLICK, "CLICK"));
        Button b6 = button("停止");
        b6.setOnClickListener(v -> { if (vib != null) vib.cancel(); log("cancel()"); });
        row4.addView(b5, weight());
        row4.addView(b6, weight());
        root.addView(row4);

        // ---------- 能力日志 ----------
        root.addView(section("设备能力"));
        tvLog = new TextView(this);
        tvLog.setTextColor(DIM);
        tvLog.setTextSize(11f);
        tvLog.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvLog.setTextIsSelectable(true);
        root.addView(card(tvLog));

        setContentView(scroll);
        dumpCapabilities();
        refreshEnv();
        ensureLocation();
    }

    // ================= 传感器 =================

    private void startSelfTest() {
        if (vib == null || !vib.hasVibrator()) { log("设备无振动器"); return; }
        expected = sbSelf.getProgress() + 1;
        selftest = true;
        if (!running) toggleSensor();
        shakeCount = 0;
        peak = 0f;
        tvCount.setText("摇动次数 0");
        tvPeak.setText("峰值 0.00 m/s²");
        log(String.format(Locale.US, "自测开始：设定 %d 次，阈值 %.1f", expected, threshold()));

        h.postDelayed(new Runnable() {
            int i = 0;
            @Override public void run() {
                if (!selftest) return;
                if (i >= expected) { h.postDelayed(MainActivity.this::finishSelfTest, 900); return; }
                i++;
                int a = vib.hasAmplitudeControl() ? 255 : VibrationEffect.DEFAULT_AMPLITUDE;
                vib.vibrate(VibrationEffect.createOneShot(250, a));
                h.postDelayed(this, 600);
            }
        }, 300);
    }

    private void finishSelfTest() {
        if (!selftest) return;
        selftest = false;
        String r = String.format(Locale.US, "自测结束：设定 %d，检测 %d", expected, shakeCount);
        log(r);
        Toast.makeText(this, r, Toast.LENGTH_LONG).show();
    }

    private void toggleSensor() {
        if (accel == null) { log("没有加速度计"); return; }
        if (!running) {
            running = sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_FASTEST);
            btnToggle.setText(running ? "停止监听" : "开始监听");
            log(running ? "已注册加速度计" : "注册失败");
        } else {
            sm.unregisterListener(this);
            running = false;
            btnToggle.setText("开始监听");
            log("已停止监听");
        }
    }

    // ---------- 模拟事件入口（由 MockShakeReceiver 调用） ----------

    private static MainActivity INSTANCE;

    static void dispatchMockStep(int seq) {
        final MainActivity a = INSTANCE;
        if (a == null) return;
        a.runOnUiThread(() -> a.onMockStep(seq));
    }

    private void onMockStep(int seq) {
        long now = System.currentTimeMillis();
        stepCount++;
        stepTs[tsIdx % stepTs.length] = now;
        tsIdx++;
        if (!running) { running = true; btnToggle.setText("停止监听"); }
        updateRunUi();
        onStepForSession(now);
        log("模拟脚步 #" + seq + " → 总步数 " + stepCount);
    }

    static void dispatchLoop(String json) {
        final MainActivity a = INSTANCE;
        if (a == null || json == null) return;
        a.runOnUiThread(() -> {
            if (a.rv != null) a.rv.showLoop(json);
            a.log("收到模拟路线");
        });
    }

    static void dispatchGeo(double lat, double lng) {
        final MainActivity a = INSTANCE;
        if (a == null) return;
        a.runOnUiThread(() -> a.onGeoBroadcast(lat, lng));
    }

    private void onGeoBroadcast(double lat, double lng) {
        if (!Double.isNaN(lastLat)
                && System.currentTimeMillis() - lastGeoAt < 1200) {
            return;   // 系统定位还在更新，优先用系统的
        }
        usingBroadcast = true;
        scoreFixes(lat, lng);
        if (rv != null) {
            double[] g = GeoUtil.wgs2gcj(lat, lng);
            rv.setHere(g[0], g[1]);
            rv.addTrackLatLng(g[0], g[1]);
            if (!centeredOnce) { centeredOnce = true; rv.postDelayed(rv::resetView, 300L); }
        }
        onGeo(lat, lng);
    }

    private void ensureLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, 3001);
            return;
        }
        lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) { log("没有定位服务"); return; }
        try {
            lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 1000L, 0.5f, this);
            lm.requestLocationUpdates(android.location.LocationManager.NETWORK_PROVIDER, 1000L, 0.5f, this);
            locStarted = true;
            log("已订阅系统定位");
        } catch (Throwable t) {
            log("订阅定位失败：" + t.getMessage());
        }
    }

    @Override
    public void onLocationChanged(android.location.Location loc) {
        if (loc == null) return;
        usingBroadcast = false;
        fixCount++;
        lastAccuracy = loc.hasAccuracy() ? loc.getAccuracy() : -1f;
        lastProvider = loc.getProvider() == null ? "--" : loc.getProvider();
        if (sessionOn) {
            long sec = Math.max(0, (System.currentTimeMillis() - sessionStart) / 1000);
            samples.add(String.format(Locale.US, "%d,%.1f,%.2f,%.1f,%.1f",
                    sec, sessionDistance(), gpsSpeedKmh, curCadence(), lastAccuracy));
        }
        scoreFixes(loc.getLatitude(), loc.getLongitude());
        if (rv != null) {
            double[] g = GeoUtil.wgs2gcj(loc.getLatitude(), loc.getLongitude());
            rv.setHere(g[0], g[1]);
            rv.addTrackLatLng(g[0], g[1]);
            if (!centeredOnce) { centeredOnce = true; rv.postDelayed(rv::resetView, 300L); }
        }
        onGeo(loc.getLatitude(), loc.getLongitude());
        if (loc.hasSpeed() && loc.getSpeed() > 0) gpsSpeedKmh = loc.getSpeed() * 3.6f;
    }

    @Override public void onProviderEnabled(String p) { }
    @Override public void onProviderDisabled(String p) { }
    @Override public void onStatusChanged(String p, int s, android.os.Bundle b) { }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 3001 && res.length > 0
                && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ensureLocation();
        }
    }

    private void scoreFixes(double lat, double lng) {
        long now = System.currentTimeMillis();
        if (!Double.isNaN(lastFixLat)) {
            long dt = now - lastFixAt;
            double d = havMeters(lastFixLat, lastFixLng, lat, lng);
            if (dt > 200) {
                geoDts.addLast(dt);
                geoSpd.addLast((float) (d / (dt / 1000.0)));
                if (d < 0.3) dupFixes++;
                while (geoDts.size() > 20) { geoDts.removeFirst(); geoSpd.removeFirst(); }
            }
        }
        lastFixLat = lat; lastFixLng = lng; lastFixAt = now;

        if (geoDts.size() < 6) return;
        double sum = 0; for (long v : geoDts) sum += v;
        double mean = sum / geoDts.size();
        double var = 0; for (long v : geoDts) var += (v - mean) * (v - mean);
        double dtStd = Math.sqrt(var / geoDts.size());

        double sSum = 0; for (float v : geoSpd) sSum += v;
        double sMean = sSum / geoSpd.size();
        double sVar = 0; for (float v : geoSpd) sVar += (v - sMean) * (v - sMean);
        double cv = sMean > 0.05 ? Math.sqrt(sVar / geoSpd.size()) / sMean : 0;

        int suspicion = 0;
        StringBuilder why = new StringBuilder();

        // 轨迹合理性：不可能的速度 / 位置跳变
        if (sMean > 15) { suspicion += 30; why.append("速度超人类 "); }
        double lastD = geoSpd.size() > 0 ? geoSpd.peekLast() : 0;
        double lastDt = geoDts.size() > 0 ? geoDts.peekLast() / 1000.0 : 1;
        if (lastD * lastDt > 500) { suspicion += 40; why.append("位置跳变 "); }
        if (dtStd < 25) { suspicion += 40; why.append("间隔过于规律 "); }
        if (cv < 0.06) { suspicion += 40; why.append("速度过于平稳 "); }
        if (dupFixes > 4) { suspicion += 20; why.append("重复落点 "); }
        int score = Math.max(0, 100 - suspicion);
        String tag = score >= 70 ? "真实" : score >= 40 ? "可疑" : "高度可疑";

        if (tvEnv != null) {
            tvEnv.setText(String.format(Locale.US,
                    "开发者选项：%s\n数据可信度 %d%%（%s）%s",
                    devWarned ? "已开启" : "未开启", score, tag,
                    why.length() > 0 ? "｜" + why.toString().trim() : ""));
        }
    }

    private boolean gpsFresh() {
        return lastGeoAt > 0 && System.currentTimeMillis() - lastGeoAt < 6000;
    }

    private void onGeo(double lat, double lng) {
        long now = System.currentTimeMillis();
        if (!Double.isNaN(lastLat)) {
            double d = havMeters(lastLat, lastLng, lat, lng);
            double dt = (now - lastGeoAt) / 1000.0;
            if (dt > 0.3 && dt < 6 && d < 30 * dt + 20) {
                if (sessionOn) sessDist += (float) d;
                gpsSpeedKmh = (float) (d / dt * 3.6);
            }
        } else {
            log("收到模拟定位，按 GPS 计距");
        }
        if (sessionOn && gpsSpeedKmh > 0.5f && sessDist > 20f) {
            if (gpsSpeedKmh > maxKmh) maxKmh = gpsSpeedKmh;
            if (minKmh == 0f || gpsSpeedKmh < minKmh) minKmh = gpsSpeedKmh;
            float pace = 60f / gpsSpeedKmh;          // km/h → min/km
            if (pace > 0.5f && pace < 40f && (bestPace == 0f || pace < bestPace)) bestPace = pace;
        }
        lastLat = lat;
        lastLng = lng;
        lastGeoAt = now;
        if (sessionOn) checkSplit(now);
    }

    private static double havMeters(double la1, double lo1, double la2, double lo2) {
        double R = 6371000, dLat = Math.toRadians(la2 - la1), dLon = Math.toRadians(lo2 - lo1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    private float sessionDistance() {
        return gpsFresh() ? sessDist : sessSteps * stride();
    }

    private void checkSplit(long now) {
        float d = sessionDistance();
        if (d < nextSplitAt) return;
        long sec = (now - sessionStart) / 1000;
        long delta = sec - prevSplitSec;
        prevSplitSec = sec;
        splitRows.add(String.format(Locale.US, "%d km  %s", (int) (nextSplitAt / 1000f), fmtClock(delta)));
        if (splitChart != null && delta > 0) splitChart.add(nextSplitAt / 1000f, delta / 60f);
        nextSplitAt += 1000f;
        if (tvSplits != null) {
            StringBuilder sb = new StringBuilder("每公里分段：\n");
            for (String r : splitRows) sb.append("  ").append(r).append('\n');
            tvSplits.setText(sb.toString().trim());
        }
    }

    static void dispatchMock(int seq) {
        final MainActivity a = INSTANCE;
        if (a == null) return;
        a.runOnUiThread(() -> a.onMockShake(seq));
    }

    private void onMockShake(int seq) {
        shakeCount++;
        tvCount.setText("摇动次数 " + shakeCount + "（含模拟）");
        log("模拟事件 #" + seq + " → 计数 " + shakeCount);
    }

    @Override protected void onResume() {
        super.onResume();
        INSTANCE = this;
        refreshEnv();
    }

    @Override protected void onPause() {
        super.onPause();
        if (INSTANCE == this) INSTANCE = null;
    }

    @Override public void onSensorChanged(SensorEvent e) {
        float x = e.values[0], y = e.values[1], z = e.values[2];
        float mag = (float) Math.sqrt(x * x + y * y + z * z);
        gravityEst = gravityEst * 0.8f + mag * 0.2f;
        float linear = Math.abs(mag - gravityEst);
        if (linear > peak) {
            peak = linear;
            tvPeak.setText(String.format(Locale.US, "峰值 %.2f m/s²", peak));
        }
        tvLive.setText(String.format(Locale.US,
                "X %+.2f   Y %+.2f   Z %+.2f\n合力 %.2f   线性 %.2f m/s²",
                x, y, z, mag, linear));

        long now = System.currentTimeMillis();

        // ---- 标定采集 ----
        if (calibrating) {
            calSum += linear;
            calN++;
            if (linear > calMax) calMax = linear;
            if (now - calStart > 3000L) finishCalibration();
        }

        // ---- 步态检测：低通 → 阈值 → 峰值 + 260ms 去抖 ----
        lpVal += 0.35f * (linear - lpVal);
        noiseFloor += 0.004f * (lpVal - noiseFloor);
        float stepThr;
        if (cbReal != null && cbReal.isChecked()) {
            stepThr = realThr;
        } else {
            stepThr = Math.max(1.2f, noiseFloor * 2.0f);
        }

        // 快通道：原始信号差分，专门抓短脉冲
        float dNow = linear - prevLinear;
        prevLinear = linear;
        dFloor += 0.004f * (Math.abs(dNow) - dFloor);
        boolean fastHit = dNow > Math.max(2.0f, dFloor * 3.5f) && now - lastFastAt > 150L;

        // 双阈值回滞：低于 60% 阈值才重新武装
        if (!stepArmed && lpVal < stepThr * 0.6f) stepArmed = true;

        // 自适应去抖：按上一步间隔的 50% 设窗口，限幅 180–600 ms
        long refr = 260L;
        if (lastStepGapMs > 0) {
            refr = Math.max(180L, Math.min(320L, Math.round(lastStepGapMs * 0.5f)));
        }
        boolean slowHit = stepArmed && lpVal > stepThr;
        if ((slowHit || fastHit) && now - lastStepAt > refr) {
            stepArmed = false;
            if (fastHit) lastFastAt = now;
            lastStepAt = now;
            stepCount++;
            stepTs[tsIdx % stepTs.length] = now;
            tsIdx++;
            updateRunUi();
            onStepForSession(now);
        }
        if (linear > threshold() && now - lastShakeAt > 350L) {
            lastShakeAt = now;
            shakeCount++;
            tvCount.setText("摇动次数 " + shakeCount);
            log(String.format(Locale.US, "shake #%d  %.1f m/s²", shakeCount, linear));
            if (cbShake.isChecked()) oneShotQuiet(120);
        }
    }

    @Override public void onAccuracyChanged(Sensor s, int a) { }

    private float threshold() { return (sbThreshold.getProgress() + 1) * 0.5f; }
    private int amplitude() { return sbAmp.getProgress() + 1; }

    // ================= 震动 =================

    private Vibrator resolveVibrator() {
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vm != null) return vm.getDefaultVibrator();
        }
        return (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
    }

    private void oneShot(int ms) {
        if (vib == null || !vib.hasVibrator()) { log("设备无振动器"); return; }
        int amp = vib.hasAmplitudeControl() ? amplitude() : VibrationEffect.DEFAULT_AMPLITUDE;
        vib.vibrate(VibrationEffect.createOneShot(ms, amp));
        log(String.format(Locale.US, "oneShot %dms amp=%d", ms, amp));
    }

    private void oneShotQuiet(int ms) {
        if (vib == null || !vib.hasVibrator()) return;
        int amp = vib.hasAmplitudeControl() ? amplitude() : VibrationEffect.DEFAULT_AMPLITUDE;
        vib.vibrate(VibrationEffect.createOneShot(ms, amp));
    }

    private void pattern() {
        if (vib == null || !vib.hasVibrator()) { log("设备无振动器"); return; }
        long[] times = {0, 90, 70, 90, 70, 220};
        vib.vibrate(VibrationEffect.createWaveform(times, -1));
        log("waveform 心跳 " + times.length + " 段");
    }

    private void sweep() {
        if (vib == null || !vib.hasVibrator()) { log("设备无振动器"); return; }
        if (!vib.hasAmplitudeControl()) { log("不支持振幅控制，改用默认强度"); oneShot(400); return; }
        long[] times = {0, 150, 60, 150, 60, 150, 60, 150, 60, 150};
        int[] amps = {0, 20, 0, 70, 0, 130, 0, 190, 0, 255};
        vib.vibrate(VibrationEffect.createWaveform(times, amps, -1));
        log("waveform 强度扫描 20→255");
    }

    private void predefined(int effect, String name) {
        if (vib == null || !vib.hasVibrator()) { log("设备无振动器"); return; }
        if (Build.VERSION.SDK_INT >= 30) {
            vib.vibrate(VibrationEffect.createPredefined(effect));
            log("predefined " + name);
        } else {
            log("API<30，无预置波形");
        }
    }

    // ================= 能力探测 =================

    private void dumpCapabilities() {
        StringBuilder sb = new StringBuilder();
        sb.append("机型  ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("系统  Android ").append(Build.VERSION.RELEASE)
          .append("  SDK ").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("ABI   ").append(Build.SUPPORTED_ABIS[0]).append('\n');
        sb.append('\n');

        if (vib == null) {
            sb.append("振动器  ✗ 取不到服务\n");
        } else {
            sb.append("振动器  ").append(vib.hasVibrator() ? "✓" : "✗").append('\n');
            sb.append("振幅控制 ").append(vib.hasAmplitudeControl() ? "✓ 支持" : "✗ 不支持").append('\n');
            if (Build.VERSION.SDK_INT >= 31) {
                sb.append("振动器 ID ").append(vib.getId()).append('\n');
                int[] kind = {
                        VibrationEffect.EFFECT_CLICK, VibrationEffect.EFFECT_DOUBLE_CLICK,
                        VibrationEffect.EFFECT_TICK, VibrationEffect.EFFECT_HEAVY_CLICK};
                String[] names = {"CLICK", "DOUBLE_CLICK", "TICK", "HEAVY_CLICK"};
                int[] sup = vib.areEffectsSupported(kind);
                StringBuilder yes = new StringBuilder();
                StringBuilder no = new StringBuilder();
                for (int i = 0; i < kind.length; i++) {
                    boolean ok = sup != null && i < sup.length
                            && sup[i] == Vibrator.VIBRATION_EFFECT_SUPPORT_YES;
                    (ok ? yes : no).append(names[i]).append(' ');
                }
                sb.append("预置效果 支持[").append(yes.toString().trim()).append(']')
                  .append("  不支持[").append(no.toString().trim()).append("]\n");
            }
        }
        sb.append('\n');

        if (accel == null) {
            sb.append("加速度计 ✗ 没有\n");
        } else {
            sb.append("加速度计 ").append(accel.getName()).append('\n');
            sb.append("厂商  ").append(accel.getVendor()).append('\n');
            sb.append("量程  ").append(accel.getMaximumRange()).append(" m/s²\n");
            sb.append("分辨率 ").append(accel.getResolution()).append('\n');
            sb.append("最小延迟 ").append(accel.getMinDelay()).append(" µs")
              .append("  ≈ ").append(accel.getMinDelay() > 0 ? (1000000 / accel.getMinDelay()) : 0).append(" Hz\n");
            sb.append("采样   SENSOR_DELAY_FASTEST\n");
        }
        sb.append("\n权限  VIBRATE 已在清单声明，无需运行时授权");
        tvLog.setText(sb.toString());
    }

    private float stride() { return 0.5f + sbStride.getProgress() * 0.05f; }

    private void startSession() {
        if (!running) {
            toggleSensor();
            log("已自动开始监听传感器");
        }
        sessionOn = true;
        sessionStart = System.currentTimeMillis();
        sessDist = 0f;
        samples.clear();
        fixCount = 0;
        gpsSpeedKmh = 0f;
        if (rv != null) rv.clearAll();
        lastLat = Double.NaN;
        lastLng = Double.NaN;
        gpsSpeedKmh = 0f;
        centeredOnce = false;
        splitRows.clear();
        nextSplitAt = 1000f;
        prevSplitSec = 0L;
        lastSampleSec = -1L;
        if (chart != null) chart.clear();
        if (splitChart != null) splitChart.clear();
        if (tvSplits != null) tvSplits.setText("每公里分段：--");
        sessSteps = 0;
        prevSteps = stepCount;
        maxKmh = 0f; minKmh = 0f; bestPace = 0f; lastStepGapMs = 0L;
        btnSession.setText("结束记录");
        btnSession.setBackgroundColor(0xFFB3261E);
        timer.postDelayed(tick, 300L);
        log("运动记录开始");
    }

    private void stopSession() {
        sessionOn = false;
        timer.removeCallbacks(tick);
        btnSession.setText("开始记录");
        btnSession.setBackgroundColor(0xFF7FD1AE);
        updateSessionUi();
        float km = sessionDistance() / 1000f;
        long sec = Math.max(1, (System.currentTimeMillis() - sessionStart) / 1000);
        float kcal = 1.036f * 65f * km;
        if (km >= 1f) validRuns++;
        String row = String.format(Locale.US, "%s｜%.2f km｜%s｜%.0f kcal",
                new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new java.util.Date()),
                km, fmtClock(sec), kcal);
        saveHistory(row);
        saveRunMetric(km, sec);
        log("本次 " + row + " · 有效次数 " + validRuns);
        loadHistory();
    }

    private void advanceRoute(float meters) {
        rhead += (Math.random() - 0.5) * 0.35f;
        rx += (float) (meters * Math.cos(rhead));
        ry += (float) (meters * Math.sin(rhead));
        if (rv != null) rv.addOwn(rx, ry);
    }

    static void dispatchRoute(float x, float y) {
        final MainActivity a = INSTANCE;
        if (a == null) return;
        a.runOnUiThread(() -> { if (a.rv != null) a.rv.addRemote(x, y); });
    }

    private void onStepForSession(long now) {
        advanceRoute(stride());
        if (rv != null) rv.step(stride());
        if (!sessionOn) return;
        sessSteps++;

        if (!gpsFresh()) checkSplit(now);
        if (lastStepGapMs > 0 && !gpsFresh()) {
            float inst = stride() / (lastStepGapMs / 1000f) * 3.6f;   // km/h
            if (sessSteps > 3) {
                if (inst > maxKmh) maxKmh = inst;
                if (minKmh == 0f || inst < minKmh) minKmh = inst;
            }
        }
        if (sessSteps >= 2 && stepTs[(tsIdx - 1 + stepTs.length) % stepTs.length] > 0) {
            long prev = stepTs[(tsIdx - 2 + stepTs.length) % stepTs.length];
            lastStepGapMs = now - prev;
        }
        // 最佳配速：无 GPS 时才用步数窗口
        if (gpsFresh()) { updateSessionUi(); return; }
        int k = 0;
        for (int i = 0; i < Math.min(tsIdx, stepTs.length); i++) {
            if (now - stepTs[i] <= 10000L) k++;
        }
        if (k >= 3) {
            float pace = 166.67f / (k * stride());     // min/km
            if (bestPace == 0f || pace < bestPace) bestPace = pace;
        }
        updateSessionUi();
    }

    private void refreshEnv() {
        try {
            int dev = android.provider.Settings.Global.getInt(
                    getContentResolver(),
                    android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0);
            String mock = "--";
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                mock = "（需系统设置里确认）";
            }
            if (tvEnv != null) {
                tvEnv.setText("开发者选项：" + (dev == 1 ? "已开启" : "未开启") + mock);
            }
            if (dev == 1 && !devWarned) {
                devWarned = true;
                new android.app.AlertDialog.Builder(this)
                        .setTitle("检测到开发者选项已开启")
                        .setMessage("当前设备的开发者选项处于开启状态，"
                                + "依赖模拟定位的应用会因此被识别为可疑环境。\n\n"
                                + "如非必要，建议关闭：设置 → 开发者选项。")
                        .setPositiveButton("知道了", null)
                        .show();
            }
        } catch (Throwable t) {
            if (tvEnv != null) tvEnv.setText("开发者选项：读取失败");
        }
    }

    /** 生成一条环形模拟路线（GCJ-02），显示并广播给发生器 */
    private void exportReport() {
        if (samples.isEmpty()) { log("还没有样本"); return; }
        StringBuilder sb = new StringBuilder("sec,dist_m,speed_kmh,cadence,accuracy_m\n");
        for (String s : samples) sb.append(s).append('\n');
        try {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                    "vib_test_" + System.currentTimeMillis() + ".csv");
            cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/csv");
            cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/DSH-Go");
            android.net.Uri uri = getContentResolver().insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) { log("创建文件失败"); return; }
            java.io.OutputStream os = getContentResolver().openOutputStream(uri);
            os.write(sb.toString().getBytes("UTF-8"));
            os.close();
            log("报告已保存到 Download/DSH-Go：" + sb.length() + " 字节");
        } catch (Throwable t) {
            log("导出失败：" + t.getMessage());
        }
    }

    private void exportSimRoute() {
        double baseLat = Double.isNaN(lastLat) ? 31.1289 : lastLat;
        double baseLng = Double.isNaN(lastLng) ? 104.3979 : lastLng;
        double[] g = GeoUtil.wgs2gcj(baseLat, baseLng);
        double lat0 = g[0], lng0 = g[1];

        int n = 14;
        double radiusM = 120.0;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            double ang = 2 * Math.PI * i / n;
            double r = radiusM * (0.9 + Math.random() * 0.2);
            double dLat = r * Math.cos(ang) / 111320.0;
            double dLng = r * Math.sin(ang) / (111320.0 * Math.cos(Math.toRadians(lat0)));
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.US, "[%.6f,%.6f]", lat0 + dLat, lng0 + dLng));
        }
        sb.append(']');
        String json = sb.toString();

        if (rv != null) rv.showLoop(json);
        try {
            android.content.Intent it = new android.content.Intent("com.dsh.vibshake.SIM_LOOP");
            it.putExtra("json", json);
            it.putExtra("len", (int) (2 * Math.PI * radiusM));
            it.setPackage("com.dsh.vibgen");
            sendBroadcast(it);
            log("已生成模拟路线并发送给发生器（半径 " + (int) radiusM + " m）");
            if (tvEnv != null) tvEnv.setText("已发送模拟路线：" + n + " 点，约 "
                    + (int) (2 * Math.PI * radiusM) + " m");
        } catch (Throwable t) {
            log("发送失败：" + t.getMessage());
        }
    }

    private String fmtClock(long sec) {
        return String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60);
    }

    private void saveRunMetric(float km, long sec) {
        try {
            android.content.SharedPreferences sp = getSharedPreferences("metrics", MODE_PRIVATE);
            org.json.JSONArray arr = new org.json.JSONArray(sp.getString("list", "[]"));
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("km", km);
            o.put("sec", sec);
            arr.put(o);
            while (arr.length() > 20) arr.remove(0);
            sp.edit().putString("list", arr.toString()).apply();
            updateCompare();
        } catch (Throwable ignore) { }
    }

    private void updateCompare() {
        try {
            android.content.SharedPreferences sp = getSharedPreferences("metrics", MODE_PRIVATE);
            org.json.JSONArray arr = new org.json.JSONArray(sp.getString("list", "[]"));
            int n = arr.length();
            if (n == 0 || tvCompare == null) { if (tvCompare != null) tvCompare.setText("多轮对比：--"); return; }
            double sumPace = 0, sumKm = 0;
            java.util.List<Double> paces = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                double km = o.optDouble("km", 0), sec2 = o.optDouble("sec", 1);
                double pace = sec2 > 1 && km > 0.01 ? (sec2 / 60.0) / km : 0;
                if (pace > 0) { paces.add(pace); sumPace += pace; }
                sumKm += km;
            }
            if (paces.isEmpty()) { tvCompare.setText("多轮对比：--"); return; }
            double mean = sumPace / paces.size();
            double var = 0;
            for (double p2 : paces) var += (p2 - mean) * (p2 - mean);
            double sd = Math.sqrt(var / paces.size());
            tvCompare.setText(String.format(Locale.US,
                    "多轮对比：%d 次 · 总距离 %.2f km\n平均配速 %.1f' ± %.1f'（离散度 %.0f%%）",
                    paces.size(), sumKm, mean, sd, mean > 0 ? sd / mean * 100 : 0));
        } catch (Throwable ignore) { }
    }

    private void saveHistory(String row) {
        try {
            android.content.SharedPreferences sp = getSharedPreferences("runs", MODE_PRIVATE);
            org.json.JSONArray arr = new org.json.JSONArray(sp.getString("list", "[]"));
            arr.put(row);
            while (arr.length() > 20) arr.remove(0);
            sp.edit().putString("list", arr.toString()).apply();
        } catch (Throwable ignore) { }
    }

    private void loadHistory() {
        try {
            android.content.SharedPreferences sp = getSharedPreferences("runs", MODE_PRIVATE);
            org.json.JSONArray arr = new org.json.JSONArray(sp.getString("list", "[]"));
            StringBuilder sb = new StringBuilder("历史记录：");
            int from = Math.max(0, arr.length() - 5);
            for (int i = arr.length() - 1; i >= from; i--) sb.append('\n').append("  ").append(arr.getString(i));
            if (tvHistory != null) tvHistory.setText(sb.toString());
        } catch (Throwable ignore) { }
    }

    private void updateSessionUi() {
        if (tvSession == null) return;
        if (!sessionOn && sessSteps == 0) { tvSession.setText("未开始"); return; }
        long el = sessionStart == 0 ? 0
                : (sessionOn ? System.currentTimeMillis() - sessionStart
                             : sessionStart == 0 ? 0 : System.currentTimeMillis() - sessionStart);
        long sec = Math.max(0, el / 1000);
        float dist = sessionDistance();
        float avgKmh = sec > 0 ? dist / sec * 3.6f : 0f;
        String avgPace = avgKmh > 0.5f ? fmtPace(1000f / (avgKmh / 3.6f) / 60f) : "--";
        String bp = bestPace > 0 ? fmtPace(bestPace) : "--";
        if (rv != null && !gpsFresh() && sessionOn) {
            rv.setStats("-- m", "等待定位（可开发生器）");
        } else if (rv != null) {
            rv.setStats(String.format(Locale.US, "%.0f m", dist),
                    String.format(Locale.US, "%s · %s", fmtClock(sec), avgPace));
        }
        String maxS = maxKmh > 0 ? String.format(Locale.US, "%.1f", maxKmh) : "--";
        String minS = minKmh > 0 ? String.format(Locale.US, "%.1f", minKmh) : "--";
        float stepKmh = curCadence() / 60f * stride() * 3.6f;
        float div = (gpsFresh() && gpsSpeedKmh > 1f && stepKmh > 1f)
                ? Math.abs(gpsSpeedKmh - stepKmh) / Math.max(gpsSpeedKmh, stepKmh) * 100f : -1f;

        tvSession.setText(String.format(Locale.US,
                "定位：%s ±%.0f m　采样 %d　双源偏差 %s\n",
                lastProvider, Math.max(0f, lastAccuracy), fixCount,
                div < 0 ? "--" : String.format(Locale.US, "%.0f%%", div))
                + String.format(Locale.US,
                "时间 %02d:%02d　步数 %d　%s\n步频 %.0f spm　步幅 %.2f m\n"
                        + "距离 %.0f m　平均 %s（%.1f km/h）\n"
                        + "最快 %s　最慢 %s km/h\n最佳配速 %s",
                sec / 60, sec % 60, sessSteps, gpsFresh() ? (usingBroadcast ? "广播" : "GPS") : "步幅估", curCadence(), stride(),
                dist, avgPace, avgKmh, maxS, minS, bp));
    }

    private String fmtPace(float minPerKm) {
        if (minPerKm <= 0 || minPerKm > 60) return "--";
        int mm = (int) minPerKm;
        int ss = Math.round((minPerKm - mm) * 60);
        if (ss == 60) { mm++; ss = 0; }
        return String.format(Locale.US, "%d'%02d\"/km", mm, ss);
    }

    private float curCadence() {
        int n = Math.min(tsIdx, stepTs.length);
        if (n < 4) return 0f;
        long sum = 0; int cnt = 0; long prev = 0;
        for (int i = Math.max(0, tsIdx - 8); i < tsIdx; i++) {
            long t = stepTs[i % stepTs.length];
            if (prev > 0) { sum += t - prev; cnt++; }
            prev = t;
        }
        return cnt > 0 && sum > 0 ? 60000f / ((float) sum / cnt) : 0f;
    }

    private void startCalibration() {
        if (!running) toggleSensor();
        calibrating = true;
        calStart = System.currentTimeMillis();
        calSum = 0f; calMax = 0f; calN = 0;
        log("标定开始：保持手机静止 3 秒");
    }

    private void finishCalibration() {
        calibrating = false;
        float mean = calN > 0 ? calSum / calN : 0f;
        realThr = Math.max(1.2f, Math.max(mean * 4f, calMax * 0.5f));
        String r = String.format(Locale.US, "标定完成：本底 %.2f，阈值 %.2f m/s²", mean, realThr);
        log(r);
        Toast.makeText(this, r, Toast.LENGTH_LONG).show();
    }

    private void updateRunUi() {
        int n = Math.min(tsIdx, stepTs.length);
        float cadence = 0f;
        if (n >= 4) {
            // 取最近 8 个时间点算平均间隔
            long sum = 0;
            int cnt = 0;
            long prev = 0;
            int start = Math.max(0, tsIdx - 8);
            for (int i = start; i < tsIdx; i++) {
                long t = stepTs[i % stepTs.length];
                if (prev > 0) { sum += t - prev; cnt++; }
                prev = t;
            }
            if (cnt > 0 && sum > 0) cadence = 60000f / ((float) sum / cnt);
        }
        boolean real = cbReal != null && cbReal.isChecked();
        float strideM = real ? stride() : 0.85f;
        float dist = stepCount * strideM;
        String pace = "--";
        if (cadence > 30) {
            float speed = cadence / 60f * strideM;  // m/s
            if (speed > 0.5f) {
                float minPerKm = 1000f / speed / 60f;
                int mm = (int) minPerKm;
                int ss = Math.round((minPerKm - mm) * 60);
                if (ss == 60) { mm++; ss = 0; }
                pace = String.format(Locale.US, "%d'%02d\"/km", mm, ss);
            }
        }
        String kind = cadence < 1 ? "--" : (cadence >= 150 ? "跑" : "走");
        tvRun.setText(String.format(Locale.US,
                "步数 %d　步频 %.0f spm（%s）\n配速 %s　距离 %.0f m　步幅 %.2f m",
                stepCount, cadence, kind, pace, dist, strideM));
    }

    private void log(String line) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        CharSequence old = tvLog.getText();
        String cur = old == null ? "" : old.toString();
        // 能力块保留在顶部，日志追加在分隔线之后
        String sep = "\n\n———— 操作日志 ————\n";
        int i = cur.indexOf(sep);
        String head = i >= 0 ? cur.substring(0, i) : cur;
        StringBuilder tail = new StringBuilder(i >= 0 ? cur.substring(i + sep.length()) : "");
        tail.append(t).append("  ").append(line).append('\n');
        tvLog.setText(head + sep + tail);
    }

    // ================= UI 小工具 =================

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(top);
        return p;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private TextView title(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(ACC);
        t.setTextSize(22f);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView sub(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(DIM);
        t.setTextSize(12f);
        t.setPadding(0, dp(2), 0, 0);
        return t;
    }

    private TextView section(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(ACC);
        t.setTextSize(15f);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(20), 0, dp(4));
        return t;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(FG);
        t.setTextSize(13f);
        t.setPadding(0, dp(6), 0, 0);
        return t;
    }

    private TextView body(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(FG);
        t.setTextSize(15f);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        return t;
    }

    private LinearLayout card(TextView inner) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.setBackgroundColor(0xFF1E1E1E);
        box.addView(inner);
        LinearLayout.LayoutParams p = lp(8);
        box.setLayoutParams(p);
        return box;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(13f);
        b.setTextColor(0xFF101010);
        b.setBackgroundColor(ACC);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(4), dp(8), dp(4), dp(8));
        return b;
    }

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar s) { }
        @Override public void onStopTrackingTouch(SeekBar s) { }
    }
}
