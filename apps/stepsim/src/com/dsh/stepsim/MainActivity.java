package com.dsh.stepsim;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

/**
 * 独立的步数模拟器：只做一件事 —— 按设定步频，向「震摇测试」发 MOCK_STEP 广播。
 * 不震动、不读传感器、不需要前台服务。
 */
public class MainActivity extends Activity {

    private static final String ACTION = "com.dsh.vibshake.MOCK_STEP";
    private static final String TARGET = "com.dsh.vibshake";
    private static final int BG = 0xFF121212;
    private static final int FG = 0xFFE8E8E8;
    private static final int DIM = 0xFF9AA0A6;
    private static final int ACC = 0xFF7FD1AE;

    private final Handler h = new Handler(Looper.getMainLooper());

    private SeekBar sbSpm, sbDur;
    private TextView tvStat, tvLog, tvSpm, tvDur;
    private Button btn;

    private boolean running = false;
    private int sent = 0;
    private long startAt = 0L;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long el = System.currentTimeMillis() - startAt;
            if (el > durationMs()) { stop("到达设定时长，自动停止"); return; }

            sent++;
            Intent it = new Intent(ACTION);
            it.setPackage(TARGET);
            it.putExtra("seq", sent);
            sendBroadcast(it);
            updateStat(el);
            h.postDelayed(this, intervalMs());
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView t = new TextView(this);
        t.setText("步数模拟器");
        t.setTextColor(ACC);
        t.setTextSize(22f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(t);

        TextView sub = new TextView(this);
        sub.setText("向「震摇测试」发送模拟脚步事件（不震动）");
        sub.setTextColor(DIM);
        sub.setTextSize(12f);
        root.addView(sub);

        tvStat = new TextView(this);
        tvStat.setTextColor(ACC);
        tvStat.setTextSize(30f);
        tvStat.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvStat.setPadding(0, dp(18), 0, 0);
        tvStat.setText("0 步");
        root.addView(tvStat);

        tvSpm = label("步频 180 spm · 每步 333 ms");
        root.addView(tvSpm);
        sbSpm = new SeekBar(this);
        sbSpm.setMax(180);        // 60 .. 240
        sbSpm.setProgress(120);   // 180
        sbSpm.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { refreshLabels(); }
        });
        root.addView(sbSpm);

        tvDur = label("时长 60 秒");
        root.addView(tvDur);
        sbDur = new SeekBar(this);
        sbDur.setMax(59);         // 10 .. 600 秒
        sbDur.setProgress(5);     // 60 s
        sbDur.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { refreshLabels(); }
        });
        root.addView(sbDur);

        btn = new Button(this);
        btn.setText("开始模拟");
        btn.setAllCaps(false);
        btn.setTextSize(16f);
        btn.setTextColor(0xFF101010);
        btn.setBackgroundColor(ACC);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(4), dp(14), dp(4), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        btn.setOnClickListener(v -> {
            if (running) stop("手动停止"); else start();
        });
        root.addView(btn, lp);

        TextView tip = new TextView(this);
        tip.setTextColor(DIM);
        tip.setTextSize(12f);
        tip.setPadding(0, dp(14), 0, 0);
        tip.setText("前置条件：\n"
                + "1. 已安装「震摇测试」，并且停在它的界面上\n"
                + "2. 测试端不需要点「开始监听」（模拟事件不走传感器）\n"
                + "3. 本 App 在后台时广播仍会继续发");
        root.addView(tip);

        TextView logTitle = new TextView(this);
        logTitle.setText("日志");
        logTitle.setTextColor(ACC);
        logTitle.setTextSize(15f);
        logTitle.setTypeface(Typeface.DEFAULT_BOLD);
        logTitle.setPadding(0, dp(20), 0, dp(4));
        root.addView(logTitle);

        tvLog = new TextView(this);
        tvLog.setTextColor(DIM);
        tvLog.setTextSize(11f);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setTextIsSelectable(true);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.setBackgroundColor(0xFF1E1E1E);
        box.addView(tvLog);
        root.addView(box);

        setContentView(scroll);
        refreshLabels();
    }

    private int spm() { return 60 + sbSpm.getProgress(); }
    private long durationMs() { return (10L + sbDur.getProgress() * 10L) * 1000L; }
    private long intervalMs() { return Math.max(150L, Math.round(60000f / spm())); }

    private void refreshLabels() {
        tvSpm.setText(String.format(Locale.US, "步频 %d spm · 每步 %d ms", spm(), intervalMs()));
        tvDur.setText(String.format(Locale.US, "时长 %d 秒 · 预计 %d 步",
                durationMs() / 1000, (int) (durationMs() / intervalMs())));
    }

    private void start() {
        running = true;
        sent = 0;
        startAt = System.currentTimeMillis();
        btn.setText("停止");
        btn.setBackgroundColor(0xFFB3261E);
        log(String.format(Locale.US, "开始：%d spm，%d 秒", spm(), durationMs() / 1000));
        tick.run();
    }

    private void stop(String why) {
        running = false;
        h.removeCallbacks(tick);
        btn.setText("开始模拟");
        btn.setBackgroundColor(ACC);
        log(why + "，共 " + sent + " 步");
        updateStat(System.currentTimeMillis() - startAt);
    }

    private void updateStat(long el) {
        long sec = Math.max(0, el / 1000);
        tvStat.setText(String.format(Locale.US, "%d 步", sent));
        if (sec > 0) {
            tvStat.append(String.format(Locale.US, "\n%02d:%02d · %.0f spm",
                    sec / 60, sec % 60, sent * 60f / sec));
        }
    }

    private void log(String line) {
        CharSequence old = tvLog.getText();
        String cur = old == null ? "" : old.toString();
        if (cur.length() > 1500) cur = cur.substring(cur.length() - 1500);
        tvLog.setText(cur + line + '\n');
    }

    @Override protected void onPause() {
        super.onPause();
        if (running) stop("切到后台（本页不后台运行）");
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(FG);
        t.setTextSize(13f);
        t.setPadding(0, dp(14), 0, 0);
        return t;
    }

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar s) { }
        @Override public void onStopTrackingTouch(SeekBar s) { }
    }
}
