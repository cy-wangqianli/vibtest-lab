package com.dsh.vibshake;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** 每公里配速柱状图：柱越矮配速越快 */
public class SplitChart extends View {

    private final List<float[]> splits = new ArrayList<>();   // {km, paceMinPerKm}
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barFast = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint();

    public SplitChart(Context c) { super(c); init(); }
    public SplitChart(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        bar.setColor(0xFF7FD1AE);
        barFast.setColor(0xFFFFB74D);
        text.setColor(0xFF8A8F94);
        text.setTextSize(dp(9));
        grid.setColor(0xFF232A33);
        setBackgroundColor(0xFF141A20);
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    public void clear() { splits.clear(); postInvalidate(); }

    public void add(float km, float paceMin) {
        splits.add(new float[]{km, paceMin});
        postInvalidate();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        c.drawLine(0, h - dp(2), w, h - dp(2), grid);
        if (splits.isEmpty()) {
            c.drawText("每公里配速：暂无分段", dp(6), h / 2f, text);
            return;
        }
        float maxPace = 1f, minPace = 999f;
        for (float[] s : splits) {
            maxPace = Math.max(maxPace, s[1]);
            minPace = Math.min(minPace, s[1]);
        }
        float best = minPace;
        float padL = dp(4), padR = dp(4), padB = dp(14), padT = dp(6);
        float plotH = h - padB - padT;
        float slot = (w - padL - padR) / Math.max(1, splits.size());
        for (int i = 0; i < splits.size(); i++) {
            float[] s = splits.get(i);
            float norm = maxPace > minPace ? (maxPace - s[1]) / (maxPace - minPace) : 0.5f;
            float bh = dp(6) + plotH * (0.35f + 0.65f * norm);
            float x = padL + slot * i + slot * 0.18f;
            float bw = slot * 0.64f;
            c.drawRect(x, h - padB - bh, x + bw, h - padB, s[1] == best ? barFast : bar);
            c.drawText(((int) s[0]) + "km", x, h - dp(2), text);
        }
        c.drawText(String.format(java.util.Locale.US, "最快 %.1f'  最慢 %.1f'", minPace, maxPace),
                padL, padT + dp(8), text);
    }
}
