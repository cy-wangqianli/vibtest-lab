package com.dsh.vibgen;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.app.Dialog;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity {

    private static final int BG = 0xFF121212;
    private static final int FG = 0xFFE8E8E8;
    private static final int DIM = 0xFF9AA0A6;
    private static final int ACC = 0xFF7FD1AE;
    private static final int PANEL = 0xFF1E1E1E;
    private static final long LOOP_MS = 6000L;

    private static final int MODE_SINE = 0;
    private static final int MODE_PULSE = 1;
    private static final int MODE_SWEEP = 2;
    private static final int MODE_COUNT = 3;
    private static final int MODE_RUN = 4;
    private static final int PULSE_MS = 250;

    private Vibrator vib;
    private final Handler h = new Handler(Looper.getMainLooper());

    private TextView tvFreq, tvInfo, tvLog;
    private SeekBar sbFreq, sbAmp;
    private Button btnStart, bSine, bPulse, bSweep, bCount, bRun;
    private SeekBar sbCad;
    private CheckBox cbDrift, cbInterval, cbHill, cbLoop, cbRamp, cbMockStep;
    private CheckBox cbShort, cbJitter, cbDouble, cbRealism;
    private CheckBox cbSweep, cbSilence, cbRandAmp;
    private long runStart = 0L;

    private static MainActivity INSTANCE;
    private LeafletMapView rv;
    private MockLoc mockLoc;
    private Button btnMock;
    private EditText etLat, etLng;
    private Button btnFull;
    private boolean pickMode = false;
    private TextView tvCoord;
    private String lastLoopJson = null;
    private final boolean[] pickTag = {true};
    private float px = 0f, py = 0f, phead = (float) (Math.random() * Math.PI * 2);

    private final Runnable routeTick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            float speed = cadence() / 60f * 0.9f;      // m/s，按 0.9 m 步幅估
            float dt = 0.5f;
            phead += (float) ((Math.random() - 0.5) * 0.35);
            px += speed * dt * (float) Math.cos(phead);
            py += speed * dt * (float) Math.sin(phead);
            if (rv != null) {
                rv.addOwn(px, py);
                rv.setStats(String.format(Locale.US, "%.0f m", Math.hypot(px, py)),
                        String.format(Locale.US, "%.1f m/s", speed));
            }
            Intent it = new Intent("com.dsh.vibshake.ROUTE");
            it.setPackage("com.dsh.vibshake");
            it.putExtra("x", px);
            it.putExtra("y", py);
            sendBroadcast(it);
            Intent it2 = new Intent(it);
            it2.setPackage("com.dsh.vibshake2");
            sendBroadcast(it2);
            h.postDelayed(this, 500L);
        }
    };

    private void startRouteTick() {
        h.removeCallbacks(routeTick);
        h.postDelayed(routeTick, 500L);
    }
    private SeekBar sbCnt, sbGap;
    private TextView tvCnt;
    private int cntIndex = 0;
    private boolean usingService = false;
    private CheckBox cbBg, cbMock;
    private int mockIdx = 0;

    private final Runnable mockRunner = new Runnable() {
        @Override public void run() { mockStep(); }
    };

    private final Runnable pulseRunner = new Runnable() {
        @Override public void run() { pulseStep(); }
    };

    private int mode = MODE_SINE;
    private boolean running = false;

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (!running) return;
            play();
            h.postDelayed(this, LOOP_MS - 150L);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        INSTANCE = this;
        vib = resolveVibrator();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        root.addView(title("震动发生器"));
        root.addView(sub("按设定频率持续震动 · 可被「震摇测试」读到"));

        tvFreq = new TextView(this);
        tvFreq.setTextColor(ACC);
        tvFreq.setTextSize(38f);
        tvFreq.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvFreq.setPadding(0, dp(18), 0, 0);
        root.addView(tvFreq);

        tvInfo = label("");
        root.addView(tvInfo);

        root.addView(section("频率"));
        sbFreq = new SeekBar(this);
        sbFreq.setMax(78);            // 1.0 .. 40.0 Hz，步长 0.5
        sbFreq.setProgress(18);       // 10.0 Hz
        sbFreq.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                refreshFreqText();
                if (running) { play(); }
            }
        });
        root.addView(sbFreq);

        root.addView(section("强度"));
        SeekBar sbAmpLocal = new SeekBar(this);
        sbAmp = sbAmpLocal;
        sbAmp.setMax(254);
        sbAmp.setProgress(254);
        root.addView(sbAmp);

        root.addView(section("波形"));
        LinearLayout rowMode = new LinearLayout(this);
        rowMode.setOrientation(LinearLayout.HORIZONTAL);
        bSine = button("正弦");
        bPulse = button("脉冲");
        bSweep = button("扫频");
        bSine.setOnClickListener(v -> setMode(MODE_SINE));
        bPulse.setOnClickListener(v -> setMode(MODE_PULSE));
        bSweep.setOnClickListener(v -> setMode(MODE_SWEEP));
        rowMode.addView(bSine, weight());
        rowMode.addView(bPulse, weight());
        bCount = button("计数");
        rowMode.addView(bCount, weight());
        bRun = button("跑步");
        rowMode.addView(bRun, weight());
        root.addView(rowMode);

        bRun.setOnClickListener(v -> setMode(MODE_RUN));

        root.addView(section("跑步模拟"));
        sbCad = new SeekBar(this);
        sbCad.setMax(80);          // 120 .. 200 spm
        sbCad.setProgress(60);     // 180 spm
        sbCad.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar s2, int p2, boolean u) {
                refreshFreqText();
                if (running && mode == MODE_RUN) play();
            }
        });
        root.addView(sbCad);

        cbDrift = new CheckBox(this);
        cbDrift.setText("步频漂移 ±5%（像体力下降）");
        cbDrift.setTextColor(FG);
        root.addView(cbDrift);

        cbInterval = new CheckBox(this);
        cbInterval.setText("间歇：30 秒快 + 60 秒慢");
        cbInterval.setTextColor(FG);
        root.addView(cbInterval);

        cbHill = new CheckBox(this);
        cbHill.setText("起伏：幅度按 16 秒周期 ±25%");
        cbHill.setTextColor(FG);
        root.addView(cbHill);

        cbRamp = new CheckBox(this);
        cbRamp.setText("渐快：120 秒内步频 +15%");
        cbRamp.setTextColor(FG);
        root.addView(cbRamp);

        bCount.setOnClickListener(v -> setMode(MODE_COUNT));

        root.addView(section("计数模式"));
        tvCnt = label("");
        sbCnt = new SeekBar(this);
        sbCnt.setMax(49);
        sbCnt.setProgress(9);
        sbCnt.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar s2, int p2, boolean u) { refreshFreqText(); }
        });
        sbGap = new SeekBar(this);
        sbGap.setMax(16);
        sbGap.setProgress(3);
        sbGap.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar s2, int p2, boolean u) { refreshFreqText(); }
        });
        root.addView(tvCnt);
        root.addView(sbCnt);
        root.addView(sbGap);

        root.addView(section("运行"));
        btnStart = button("开始");
        btnStart.setTextSize(16f);
        btnStart.setPadding(dp(4), dp(14), dp(4), dp(14));
        btnStart.setOnClickListener(v -> toggle());
        root.addView(btnStart, lp(6));

        cbBg = new CheckBox(this);
        cbBg.setText("切到后台继续震（用另一页测频率时勾上）");
        cbBg.setTextColor(FG);
        cbBg.setChecked(false);
        root.addView(cbBg);

        cbMock = new CheckBox(this);
        cbMock.setText("模拟摇动：不震动，直接给「震摇测试」发事件");
        cbMock.setTextColor(0xFF9AA0A6);
        cbMock.setChecked(false);
        root.addView(cbMock);

        cbMockStep = new CheckBox(this);
        cbMockStep.setText("按「脚步」发：计入测试端的步数/步频（不勾则只算摇动）");
        cbMockStep.setTextColor(0xFF9AA0A6);
        cbMockStep.setChecked(true);
        root.addView(cbMockStep);

        cbShort = new CheckBox(this);
        cbShort.setText("难样本：短脉冲 15 ms / 40% 幅度");
        cbShort.setTextColor(0xFF9AA0A6);
        root.addView(cbShort);

        cbJitter = new CheckBox(this);
        cbJitter.setText("难样本：间隔抖动（0.55× / 1.9× 交替）");
        cbJitter.setTextColor(0xFF9AA0A6);
        root.addView(cbJitter);

        cbDouble = new CheckBox(this);
        cbDouble.setText("难样本：双峰（一次落点两个脉冲）");
        cbDouble.setTextColor(0xFF9AA0A6);
        root.addView(cbDouble);

        cbSweep = new CheckBox(this);
        cbSweep.setText("红队：扫参（自动遍历 脉宽×幅度）");
        cbSweep.setTextColor(0xFFFFB74D);
        root.addView(cbSweep);

        cbSilence = new CheckBox(this);
        cbSilence.setText("红队：每 8 次插一段 8 秒静默");
        cbSilence.setTextColor(0xFFFFB74D);
        root.addView(cbSilence);

        cbRandAmp = new CheckBox(this);
        cbRandAmp.setText("红队：幅度随机 10–100%");
        cbRandAmp.setTextColor(0xFFFFB74D);
        root.addView(cbRandAmp);

        cbRealism = new CheckBox(this);
        cbRealism.setText("红队：拟真抖动（速度/停顿/噪声，对抗评分）");
        cbRealism.setTextColor(0xFFFFB74D);
        root.addView(cbRealism);

        cbLoop = new CheckBox(this);
        cbLoop.setText("循环发送，直到我点停止");
        cbLoop.setTextColor(0xFF9AA0A6);
        root.addView(cbLoop);

        LinearLayout rowQuick = new LinearLayout(this);
        rowQuick.setOrientation(LinearLayout.HORIZONTAL);
        Button bPreview = button("响 2 秒");
        bPreview.setOnClickListener(v -> preview());
        Button bStop = button("停止");
        bStop.setOnClickListener(v -> stopAll("手动停止"));
        rowQuick.addView(bPreview, weight());
        rowQuick.addView(bStop, weight());
        root.addView(rowQuick);

        root.addView(section("路线地图（Leaflet）"));
        rv = new LeafletMapView(this);
        root.addView(rv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(280)));

        rv.setLoopListener((json, len) -> onLoopReady(json, len, false));
        tvCoord = label("坐标：--");
        root.addView(tvCoord);

        btnFull = button("全屏选点（带搜索）");
        btnFull.setOnClickListener(v -> openFullPicker());
        root.addView(btnFull, lp(6));

        Button bDev = button("打开开发者选项");
        bDev.setOnClickListener(v -> {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            } catch (Throwable t) {
                Toast.makeText(this, "无法打开设置", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(bDev, lp(6));

        btnMock = button("模拟定位：关");
        btnMock.setOnClickListener(v -> toggleMock());
        root.addView(btnMock, lp(6));

        LinearLayout rowTele = new LinearLayout(this);
        rowTele.setOrientation(LinearLayout.HORIZONTAL);
        etLat = new EditText(this);
        etLat.setHint("纬度 30.657");
        etLat.setTextSize(12f);
        etLat.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        etLng = new EditText(this);
        etLng.setHint("经度 104.066");
        etLng.setTextSize(12f);
        etLng.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        Button bTele = button("传送");
        bTele.setOnClickListener(v -> teleport());
        rowTele.addView(etLat, weight());
        rowTele.addView(etLng, weight());
        rowTele.addView(bTele, weight());
        root.addView(rowTele);

        LinearLayout rowMap2 = new LinearLayout(this);
        rowMap2.setOrientation(LinearLayout.HORIZONTAL);
        Button bPick = button("选点");
        bPick.setOnClickListener(v -> {
            pickMode = !pickMode;
            rv.setPicking(pickMode);
            bPick.setText(pickMode ? "选点中" : "选点");
        });
        Button bClose = button("闭合");
        bClose.setOnClickListener(v -> rv.closeLoop());
        Button bClear = button("清除");
        bClear.setOnClickListener(v -> rv.clearAll());
        rowMap2.addView(bPick, weight());
        rowMap2.addView(bClose, weight());
        rowMap2.addView(bClear, weight());
        root.addView(rowMap2);

        root.addView(section("说明"));
        TextView tip = new TextView(this);
        tip.setTextColor(DIM);
        tip.setTextSize(12f);
        tip.setLineSpacing(dp(3), 1f);
        tip.setText("· 马达分 ERM（转子）和 LRA（线性），LRA 才能跟住振幅波形\n"
                + "· ERM 在 30 Hz 以上基本糊成一声嗡响，属正常\n"
                + "· 频率越高手感越“麻”，低频（3–10 Hz）更像敲击\n"
                + "· 本页震动不会在后台继续，切走即停");
        root.addView(card(tip));

        root.addView(section("日志"));
        tvLog = new TextView(this);
        tvLog.setTextColor(DIM);
        tvLog.setTextSize(11f);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setTextIsSelectable(true);
        root.addView(card(tvLog));

        setContentView(scroll);
        refreshFreqText();
        setMode(MODE_SINE);
        dumpCaps();
    }

    // ---------------- 频率 / 波形 ----------------

    private float freq() { return (sbFreq.getProgress() + 2) / 2f; }   // 1.0 .. 40.0

    private int amp() { return sbAmp.getProgress() + 1; }

    private void refreshFreqText() {
        float f = freq();
        long period = Math.max(2L, Math.round(1000f / f));
        if (mode == MODE_RUN) {
            int spm = cadence();
            tvFreq.setText(String.format(Locale.US, "%d spm", spm));
            float spd = spm / 60f * 0.9f;                 // 按 0.9 m 步幅估
            float paceMin = spd > 0.1f ? 1000f / spd / 60f : 0f;
            String paceStr = paceMin > 0
                    ? String.format(Locale.US, "%d'%02d\"/km", (int) paceMin,
                        Math.round((paceMin - (int) paceMin) * 60)) : "--";
            tvInfo.setText(String.format(Locale.US,
                    "每步 %d ms · 预计 %.1f km/h（%s）· 强度 %d",
                    Math.round(60000f / spm), spd * 3.6f, paceStr, amp()));
            if (tvCnt != null) tvCnt.setText("跑步模拟：每步 30 ms 冲击 + 回弹，幅度带抖动");
            return;
        }
        if (mode == MODE_COUNT) {
            tvFreq.setText(String.format(Locale.US, "%d 次", countTotal()));
            tvInfo.setText(String.format(Locale.US,
                    "脉冲 %d ms · 间隔 %d ms · 强度 %d（滑块在上面）",
                    PULSE_MS, gapMs(), amp()));
            tvCnt.setText(String.format(Locale.US, "次数 %d（滑块1）· 间隔 %d ms（滑块2）",
                    countTotal(), gapMs()));
            return;
        }
        tvFreq.setText(String.format(Locale.US, "%.1f Hz", f));
        tvInfo.setText(String.format(Locale.US,
                "周期 %d ms · %s · 强度 %d", period,
                mode == MODE_SINE ? "正弦（50% 占空）"
                        : mode == MODE_PULSE ? "脉冲（12% 占空）" : "扫频 2→20 Hz", amp()));
    }

    private void setMode(int m) {
        mode = m;
        bSine.setBackgroundColor(m == MODE_SINE ? ACC : 0xFF2A2A2A);
        bSine.setTextColor(m == MODE_SINE ? 0xFF101010 : FG);
        bPulse.setBackgroundColor(m == MODE_PULSE ? ACC : 0xFF2A2A2A);
        bPulse.setTextColor(m == MODE_PULSE ? 0xFF101010 : FG);
        bSweep.setBackgroundColor(m == MODE_SWEEP ? ACC : 0xFF2A2A2A);
        bSweep.setTextColor(m == MODE_SWEEP ? 0xFF101010 : FG);
        if (bRun != null) {
            bRun.setBackgroundColor(m == MODE_RUN ? ACC : 0xFF2A2A2A);
            bRun.setTextColor(m == MODE_RUN ? 0xFF101010 : FG);
        }
        if (bCount != null) {
            bCount.setBackgroundColor(m == MODE_COUNT ? ACC : 0xFF2A2A2A);
            bCount.setTextColor(m == MODE_COUNT ? 0xFF101010 : FG);
        }
        refreshFreqText();
        if (running) play();
    }

    /** 真人跑步近似：每步一个短冲击 + 回弹，幅度逐 step 抖动 */
    private Object[] buildRun() {
        long el = runStart == 0 ? 0 : (System.currentTimeMillis() - runStart);
        int spm = cadence();
        if (cbDrift != null && cbDrift.isChecked()) {
            spm = Math.round(spm * (1f + 0.05f * (float) Math.sin(el / 40000.0 * 2 * Math.PI)));
        }
        if (cbInterval != null && cbInterval.isChecked()) {
            long c = el % 90000L;
            spm += (c < 30000L) ? 12 : -28;
        }
        if (cbRamp != null && cbRamp.isChecked()) {
            spm = Math.round(spm * (1f + 0.15f * Math.min(1f, el / 120000f)));
        }
        spm = Math.max(90, Math.min(230, spm));
        long period = Math.max(200L, Math.round(60000f / spm));
        long strike = Math.max(18L, Math.round(period * 0.16f));
        long rebound = Math.max(12L, Math.round(period * 0.12f));
        long rest = Math.max(20L, period - strike - rebound);
        int steps = (int) Math.max(1, LOOP_MS / period);
        float[] jitter = {0.96f, 1.0f, 0.91f, 1.0f, 0.94f, 0.98f, 0.90f, 1.0f};
        int base = (vib != null && vib.hasAmplitudeControl())
                ? Math.min(255, Math.max(1, amp())) : 200;
        if (cbHill != null && cbHill.isChecked()) {
            base = Math.max(20, Math.round(base * (1f + 0.25f * (float) Math.sin(el / 16000.0 * 2 * Math.PI))));
        }
        long[] t = new long[steps * 3 + 1];
        int[] a = new int[steps * 3 + 1];
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
        return new Object[]{t, a};
    }

    private Object[] build() {
        if (mode == MODE_RUN) return buildRun();
        if (mode == MODE_SWEEP) {
            // 2→20 Hz 在 6 秒内扫一遍，每档 1 个周期
            java.util.ArrayList<Long> t = new java.util.ArrayList<>();
            java.util.ArrayList<Integer> a = new java.util.ArrayList<>();
            t.add(0L);
            a.add(0);
            float f = 2f;
            long total = 0;
            while (total < LOOP_MS - 100) {
                long period = Math.max(3L, Math.round(1000f / f));
                long on = Math.max(1L, period / 2);
                t.add(on); a.add(amp());
                t.add(period - on); a.add(0);
                total += period;
                f += 0.08f;
                if (f > 20f) f = 20f;
            }
            long[] tt = new long[t.size()];
            int[] aa = new int[t.size()];
            for (int i = 0; i < tt.length; i++) { tt[i] = t.get(i); aa[i] = a.get(i); }
            return new Object[]{tt, aa};
        }

        long period = Math.max(2L, Math.round(1000f / freq()));
        long on = mode == MODE_SINE
                ? Math.max(1L, period / 2)
                : Math.max(1L, Math.round(period * 0.12f));
        long off = Math.max(1L, period - on);

        int cycles = (int) Math.max(1, LOOP_MS / (on + off));
        long[] tt = new long[cycles * 2 + 1];
        int[] aa = new int[cycles * 2 + 1];
        tt[0] = 0; aa[0] = 0;
        for (int i = 0; i < cycles; i++) {
            tt[i * 2 + 1] = on;  aa[i * 2 + 1] = amp();
            tt[i * 2 + 2] = off; aa[i * 2 + 2] = 0;
        }
        return new Object[]{tt, aa};
    }

    // ---------------- 播放 ----------------

    private void toggle() {
        if (running) { stopAll("手动停止"); return; }
        if (vib == null || !vib.hasVibrator()) { log("设备无振动器"); return; }
        if (cbMock != null && cbMock.isChecked()) { startMock(); return; }
        if (cbBg != null && cbBg.isChecked()) { startForegroundVibe(); return; }
        if (mode == MODE_RUN) runStart = System.currentTimeMillis();
        if (mode == MODE_COUNT) { startCount(); return; }
        running = true;
        startRouteTick();
        btnStart.setText("停止");
        btnStart.setBackgroundColor(0xFFB3261E);
        play();
        h.postDelayed(loop, LOOP_MS - 150L);
        log(String.format(Locale.US, "start %.1f Hz mode=%d amp=%d", freq(), mode, amp()));
    }

    private void startForegroundVibe() {
        running = true;
        startRouteTick();
        usingService = true;
        btnStart.setText("停止");
        btnStart.setBackgroundColor(0xFFB3261E);
        Intent it = new Intent(this, VibeService.class);
        it.putExtra(VibeService.EXTRA_MODE, mode);
        it.putExtra(VibeService.EXTRA_FREQ, freq());
        it.putExtra(VibeService.EXTRA_AMP, amp());
        it.putExtra(VibeService.EXTRA_COUNT, countTotal());
        it.putExtra(VibeService.EXTRA_GAP, gapMs());
        it.putExtra(VibeService.EXTRA_CADENCE, cadence());
        it.putExtra(VibeService.EXTRA_DRIFT, cbDrift.isChecked() ? 1 : 0);
        it.putExtra(VibeService.EXTRA_INTERVAL, cbInterval.isChecked() ? 1 : 0);
        it.putExtra(VibeService.EXTRA_HILL, cbHill.isChecked() ? 1 : 0);
        it.putExtra(VibeService.EXTRA_RAMP, cbRamp.isChecked() ? 1 : 0);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(it);
        else startService(it);
        log("前台服务已启动，可切到别的页面");
    }

    private void startMock() {
        running = true;
        startRouteTick();
        btnStart.setText("停止");
        btnStart.setBackgroundColor(0xFFB3261E);
        mockIdx = 0;
        log(String.format(Locale.US, "模拟模式 %d 次 · 间隔 %d ms", countTotal(), gapMs()));
        mockStep();
    }

    private void mockStep() {
        if (!running) return;
        if (mockIdx >= countTotal()) {
            if (cbLoop != null && cbLoop.isChecked()) {
                mockIdx = 0;
                log("循环一轮，继续");
            } else {
                stopAll("已发完 " + countTotal() + " 条，停止");
                Toast.makeText(this, "模拟发送完成", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        mockIdx++;
        String act = (cbMockStep != null && cbMockStep.isChecked())
                ? "com.dsh.vibshake.MOCK_STEP" : "com.dsh.vibshake.MOCK_SHAKE";
        Intent it = new Intent(act);
        it.setPackage("com.dsh.vibshake");
        it.putExtra("seq", mockIdx);
        sendBroadcast(it);
        Intent it2 = new Intent(it);
        it2.setPackage("com.dsh.vibshake2");
        sendBroadcast(it2);
        log("mock " + mockIdx + "/" + countTotal());
        h.postDelayed(mockRunner, gapMs());
    }

    private int cadence() { return 120 + sbCad.getProgress(); }

    private int countTotal() { return sbCnt.getProgress() + 1; }

    private int gapMs() { return 400 + sbGap.getProgress() * 100; }

    private void startCount() {
        running = true;
        startRouteTick();
        btnStart.setText("停止");
        btnStart.setBackgroundColor(0xFFB3261E);
        cntIndex = 0;
        log(String.format(Locale.US, "计数模式 %d 次 · 脉冲 %d ms · 间隔 %d ms",
                countTotal(), PULSE_MS, gapMs()));
        pulseStep();
    }

    private void pulseStep() {
        if (!running) return;
        if (cntIndex >= countTotal()) { stopAll("计数完成"); return; }
        cntIndex++;
        int base = vib.hasAmplitudeControl() ? amp() : VibrationEffect.DEFAULT_AMPLITUDE;
        int[] SWEEP_W = {5, 10, 15, 30, 60, 120, 200};
        int[] SWEEP_A = {15, 30, 50, 75, 100};
        int durMs = (cbShort != null && cbShort.isChecked()) ? 15 : PULSE_MS;
        int a = (cbShort != null && cbShort.isChecked()) ? Math.max(1, Math.round(base * 0.4f)) : base;
        if (cbSweep != null && cbSweep.isChecked()) {
            int k = cntIndex - 1;
            durMs = SWEEP_W[k % SWEEP_W.length];
            a = Math.max(1, base * SWEEP_A[(k / SWEEP_W.length) % SWEEP_A.length] / 100);
            log("scan w=" + durMs + "ms a=" + a);
        }
        if (cbRandAmp != null && cbRandAmp.isChecked()) {
            a = Math.max(1, Math.round(base * (0.1f + (float) Math.random() * 0.9f)));
        }
        vib.vibrate(VibrationEffect.createOneShot(durMs, a));
        if (cbDouble != null && cbDouble.isChecked()) {
            final int fd = durMs, fa = a;
            h.postDelayed(() -> {
                if (!running) return;
                vib.vibrate(VibrationEffect.createOneShot(fd, fa));
                log("double-peak");
            }, 45L);
        }
        log("pulse " + cntIndex + "/" + countTotal());
        int gap = gapMs();
        if (cbSilence != null && cbSilence.isChecked() && cntIndex % 8 == 0) {
            gap = 8000;
            log("静默 8 秒");
        }
        if (cbJitter != null && cbJitter.isChecked()) {
            gap = (cntIndex % 2 == 0) ? Math.round(gap * 0.55f) : Math.round(gap * 1.9f);
        }
        h.postDelayed(pulseRunner, gap);
    }

    private void play() {
        Object[] p = build();
        long[] t = (long[]) p[0];
        int[] a = (int[]) p[1];
        try {
            vib.vibrate(VibrationEffect.createWaveform(t, a, -1));
        } catch (Exception e) {
            long[] plain = t;
            vib.vibrate(VibrationEffect.createWaveform(plain, -1));
            log("振幅波形被拒，退回默认强度");
        }
    }

    private void preview() {
        if (vib == null || !vib.hasVibrator()) { log("设备无振动器"); return; }
        Object[] p = build();
        long[] t = (long[]) p[0];
        int[] a = (int[]) p[1];
        long sum = 0;
        for (long v : t) { sum += v; if (sum >= 2000) break; }
        // 截到 2 秒
        int n = 1;
        long acc = 0;
        while (n < t.length && acc + t[n] <= 2000) { acc += t[n]; n++; }
        long[] tt = new long[n];
        int[] aa = new int[n];
        System.arraycopy(t, 0, tt, 0, n);
        System.arraycopy(a, 0, aa, 0, n);
        vib.vibrate(VibrationEffect.createWaveform(tt, aa, -1));
        log(String.format(Locale.US, "preview 2s @ %.1f Hz", freq()));
    }

    private void stopAll(String why) {
        running = false;
        h.removeCallbacks(loop);
        h.removeCallbacks(pulseRunner);
        h.removeCallbacks(mockRunner);
        h.removeCallbacks(routeTick);
        try { stopService(new Intent(this, SimKeepAlive.class)); } catch (Throwable ignore) { }
        if (usingService) {
            usingService = false;
            Intent it = new Intent(this, VibeService.class);
            it.setAction(VibeService.ACTION_STOP);
            try { startService(it); } catch (Throwable ignore) { }
        }
        if (vib != null) vib.cancel();
        btnStart.setText("开始");
        btnStart.setBackgroundColor(ACC);
        log(why);
    }

    @Override protected void onPause() {
        super.onPause();
        boolean mockMode = cbMock != null && cbMock.isChecked();
        if (running && !mockMode && (cbBg == null || !cbBg.isChecked())) stopAll("切到后台，已停");
    }

    // ---------------- 杂项 ----------------

    private Vibrator resolveVibrator() {
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vm != null) return vm.getDefaultVibrator();
        }
        return (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
    }

    private void dumpCaps() {
        StringBuilder sb = new StringBuilder();
        sb.append("机型 ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("SDK ").append(Build.VERSION.SDK_INT).append('\n');
        if (vib != null) {
            sb.append("振动器 ").append(vib.hasVibrator() ? "✓" : "✗")
              .append("   振幅控制 ").append(vib.hasAmplitudeControl() ? "✓" : "✗");
            if (Build.VERSION.SDK_INT >= 31) sb.append("   id ").append(vib.getId());
        } else {
            sb.append("振动器 ✗");
        }
        tvLog.setText(sb.append('\n').toString());
    }

    private void log(String line) {
        CharSequence old = tvLog.getText();
        String cur = old == null ? "" : old.toString();
        if (cur.length() > 1200) cur = cur.substring(cur.length() - 1200);
        tvLog.setText(cur + line + '\n');
    }

    static void dispatchSimLoop(String json, int len) {
        final MainActivity a = INSTANCE;
        if (a == null || json == null) return;
        a.runOnUiThread(() -> a.onLoopReady(json, len, false));
    }

    private MockLoc ensureMock() {
        if (mockLoc == null) {
            mockLoc = new MockLoc(this);
            mockLoc.setStatus(txt -> log(txt));
            mockLoc.setPosListener((lat, lng) -> {
                if (rv != null) {
                    double[] g = GeoUtil.wgs2gcj(lat, lng);
                    rv.setHere(g[0], g[1]);
                    if (tvCoord != null) {
                        tvCoord.setText(String.format(Locale.US,
                                "推送 WGS %.5f,%.5f\n地图 GCJ %.5f,%.5f", lat, lng, g[0], g[1]));
                    }
                }
            });
        }
        return mockLoc;
    }

    private void onLoopReady(String json, int len, boolean fromDialog) {
        lastLoopJson = json;
        ensureMock();
        mockLoc.setLoop(GeoUtil.loopToWgs(json), len);
        rv.showLoop(json);
        log("闭环已载入 " + len + " m");
        try {
            org.json.JSONArray ja = new org.json.JSONArray(json);
            double gLat = ja.getJSONArray(0).getDouble(0);
            double gLng = ja.getJSONArray(0).getDouble(1);
            double[] w = GeoUtil.gcj2wgs(gLat, gLng);
            if (tvCoord != null) {
                tvCoord.setText(String.format(Locale.US,
                        "选点 GCJ %.5f,%.5f\n推送 WGS %.5f,%.5f", gLat, gLng, w[0], w[1]));
            }
        } catch (Throwable ignore) { }
        // 选完路线立即开始模拟运动，不用再点按钮
        try {
            ensureMock();
            if (!mockLoc.isRunning()) {
                if (mockLoc.start()) {
                    if (btnMock != null) btnMock.setText("模拟定位：开");
                    log("已沿路线开始模拟运动");
                    try {
                        startForegroundService(new Intent(this, SimKeepAlive.class));
                    } catch (Throwable ignore) { }
                }
            }
        } catch (Throwable t) {
            log("自动开始失败：" + t.getMessage());
        }
        // 发给测试端做只读展示
        try {
            Intent it = new Intent("com.dsh.vibshake.LOOP");
            it.putExtra("json", json);
            it.setPackage("com.dsh.vibshake");
            sendBroadcast(it);
            Intent it2 = new Intent(it);
            it2.setPackage("com.dsh.vibshake2");
            sendBroadcast(it2);
        } catch (Throwable ignore) { }
    }

    private void openFullPicker() {
        Dialog dlg = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout box = new FrameLayout(this);

        LeafletMapView pick = new LeafletMapView(this);
        pick.setSearchEnabled(true);
        pick.setPicking(true);
        pick.setLoopListener((json, len) -> onLoopReady(json, len, true));
        box.addView(pick, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xE60F1214);
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));

        Button bPick = button("选点中");
        bPick.setOnClickListener(v -> {
            pick.setPicking(!pickTag[0]);
            pickTag[0] = !pickTag[0];
            bPick.setText(pickTag[0] ? "选点中" : "选点");
        });
        Button bClose = button("闭合");
        bClose.setOnClickListener(v -> pick.closeLoop());
        Button bClear = button("清除");
        bClear.setOnClickListener(v -> pick.clearAll());
        Button bDone = button("完成");
        bDone.setOnClickListener(v -> {
            dlg.dismiss();
            if (lastLoopJson != null && rv != null) {
                final String js = lastLoopJson;
                rv.postDelayed(() -> rv.showLoop(js), 250L);
            }
        });

        bar.addView(bPick, weight());
        bar.addView(bClose, weight());
        bar.addView(bClear, weight());
        bar.addView(bDone, weight());

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = android.view.Gravity.BOTTOM;
        blp.setMargins(dp(10), 0, dp(10), dp(22));
        box.addView(bar, blp);

        dlg.setContentView(box);
        dlg.show();
    }

    private void teleport() {
        try {
            double lat = Double.parseDouble(etLat.getText().toString().trim());
            double lng = Double.parseDouble(etLng.getText().toString().trim());
            ensureMock();
            mockLoc.startFixed(lat, lng);
            double[] g = GeoUtil.wgs2gcj(lat, lng);
            if (rv != null) rv.setHere(g[0], g[1]);
            if (btnMock != null) btnMock.setText("模拟定位：开");
            log(String.format(Locale.US, "传送到 %.5f, %.5f", lat, lng));
        } catch (Throwable t) {
            Toast.makeText(this, "经纬度格式不对", Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleMock() {
        ensureMock();
        if (mockLoc.isRunning()) {
            mockLoc.stop();
            if (btnMock != null) btnMock.setText("模拟定位：关");
            return;
        }
        if (mockLoc.start() && btnMock != null) btnMock.setText("模拟定位：开");
    }

    private void openShadow() {
        try {
            Intent it = getPackageManager().getLaunchIntentForPackage("com.shadowloc.app");
            if (it != null) {
                startActivity(it);
                log("已打开影位");
            } else {
                Toast.makeText(this, "未安装「影位 · 模拟定位」", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Toast.makeText(this, "打开失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

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
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView sub(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(DIM);
        t.setTextSize(12f);
        return t;
    }

    private TextView section(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(ACC);
        t.setTextSize(15f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(20), 0, dp(4));
        return t;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(FG);
        t.setTextSize(13f);
        t.setPadding(0, dp(8), 0, 0);
        return t;
    }

    private LinearLayout card(TextView inner) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.setBackgroundColor(PANEL);
        box.addView(inner);
        box.setLayoutParams(lp(8));
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
