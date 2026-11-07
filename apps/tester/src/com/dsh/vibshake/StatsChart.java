package com.dsh.vibshake;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简配速曲线：横轴时间、纵轴速度（km/h），越靠上越快。
 */
public class StatsChart extends View {

    private final List<float[]> data = new ArrayList<>();   // {秒, km/h}
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    public StatsChart(Context c) { super(c); init(); }
    public StatsChart(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(dp(2.2f));
        line.setColor(0xFF7FD1AE);

        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0x227FD1AE);

        grid.setColor(0xFF232A33);
        grid.setStrokeWidth(1f);

        text.setColor(0xFF7D8A9B);
        text.setTextSize(dp(9));
        setBackgroundColor(0xFF141A20);
    }

    public void clear() { data.clear(); postInvalidate(); }

    public void add(float sec, float kmh) {
        data.add(new float[]{sec, kmh});
        while (data.size() > 900) data.remove(0);
        postInvalidate();
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        float padL = dp(4), padR = dp(4), padT = dp(6), padB = dp(6);

        // 网格
        for (int i = 0; i <= 4; i++) {
            float y = padT + (h - padT - padB) * i / 4f;
            c.drawLine(padL, y, w - padR, y, grid);
        }
        if (data.size() < 2) {
            c.drawText("等待数据…", padL + dp(4), h / 2f, text);
            return;
        }

        float maxKmh = 1f, maxT = 1f;
        for (float[] p : data) {
            if (p[1] > maxKmh) maxKmh = p[1];
            if (p[0] > maxT) maxT = p[0];
        }
        maxKmh = (float) Math.ceil(maxKmh / 2f) * 2f;

        float plotW = w - padL - padR;
        float plotH = h - padT - padB;

        android.graphics.Path path = new android.graphics.Path();
        android.graphics.Path area = new android.graphics.Path();
        boolean started = false;
        for (float[] p : data) {
            float x = padL + plotW * (p[0] / maxT);
            float y = padT + plotH * (1f - Math.min(1f, p[1] / maxKmh));
            if (!started) { path.moveTo(x, y); area.moveTo(x, h - padB); area.lineTo(x, y); started = true; }
            else { path.lineTo(x, y); area.lineTo(x, y); }
        }
        area.lineTo(padL + plotW, h - padB);
        area.close();
        c.drawPath(area, fill);
        c.drawPath(path, line);

        c.drawText(String.format(java.util.Locale.US, "%.0f", maxKmh) + " km/h", padL + dp(3), padT + dp(9), text);
        c.drawText(String.format(java.util.Locale.US, "%.0f s", maxT), w - padR - dp(30), h - dp(3), text);
    }
}
