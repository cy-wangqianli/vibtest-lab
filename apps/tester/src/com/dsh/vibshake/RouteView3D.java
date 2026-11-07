/*
 * ★ 本文件是从成品 APK 反编译回收的 —— 原源码在交接包中丢失。
 *   来源: apks/VibShakeTest2.apk   （jadx 1.5.6 反编译）
 *   已做处理: 清理反编译噪音注释；把 jadx 生成的合成 lambda 引用还原成源码级 lambda。
 *   语义未改动。取证与验证过程见 docs/recovery-notes.md。
 */
package com.dsh.vibshake;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import java.util.ArrayList;

public class RouteView3D extends View {
    private static final float LIFT = 6.0f;
    private static final int MAX = 900;
    private final Paint dot;
    private final Paint grid;
    private final Paint gridMajor;
    private final ArrayList<Float> ownX;
    private final ArrayList<Float> ownY;
    private float pCx;
    private float pCy;
    private float pScale;
    private final Paint rem;
    private final ArrayList<Float> remX;
    private final ArrayList<Float> remY;
    private final Paint route;
    private final Paint stick;
    private final Paint text;

    public RouteView3D(Context context) {
        super(context);
        this.ownX = new ArrayList<>();
        this.ownY = new ArrayList<>();
        this.remX = new ArrayList<>();
        this.remY = new ArrayList<>();
        this.pScale = 3.0f;
        this.pCx = 0.0f;
        this.pCy = 0.0f;
        this.grid = new Paint();
        this.gridMajor = new Paint();
        this.route = new Paint(1);
        this.rem = new Paint(1);
        this.stick = new Paint(1);
        this.text = new Paint(1);
        this.dot = new Paint(1);
        init();
    }

    public RouteView3D(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        this.ownX = new ArrayList<>();
        this.ownY = new ArrayList<>();
        this.remX = new ArrayList<>();
        this.remY = new ArrayList<>();
        this.pScale = 3.0f;
        this.pCx = 0.0f;
        this.pCy = 0.0f;
        this.grid = new Paint();
        this.gridMajor = new Paint();
        this.route = new Paint(1);
        this.rem = new Paint(1);
        this.stick = new Paint(1);
        this.text = new Paint(1);
        this.dot = new Paint(1);
        init();
    }

    private float[] bounds() {
        float fMin = Float.MAX_VALUE;
        float fMax = -3.4028235E38f;
        float fMax2 = -3.4028235E38f;
        int i = 0;
        boolean z = false;
        float fMin2 = Float.MAX_VALUE;
        while (i < 2) {
            ArrayList<Float> arrayList = i == 0 ? this.ownX : this.remX;
            ArrayList<Float> arrayList2 = i == 0 ? this.ownY : this.remY;
            int i2 = 0;
            while (i2 < arrayList.size()) {
                fMin = Math.min(fMin, arrayList.get(i2).floatValue());
                fMax = Math.max(fMax, arrayList.get(i2).floatValue());
                fMin2 = Math.min(fMin2, arrayList2.get(i2).floatValue());
                fMax2 = Math.max(fMax2, arrayList2.get(i2).floatValue());
                i2++;
                z = true;
            }
            i++;
        }
        if (!z) {
            fMin = -20.0f;
            fMin2 = -20.0f;
            fMax = 20.0f;
            fMax2 = 20.0f;
        }
        if (fMax - fMin < 20.0f) {
            float f = (fMin + fMax) / 2.0f;
            fMax = f + 10.0f;
            fMin = f - 10.0f;
        }
        if (fMax2 - fMin2 < 20.0f) {
            float f2 = (fMin2 + fMax2) / 2.0f;
            fMax2 = f2 + 10.0f;
            fMin2 = f2 - 10.0f;
        }
        return new float[]{fMin, fMax, fMin2, fMax2};
    }

    private float dp(float f) {
        return f * getResources().getDisplayMetrics().density;
    }

    private void drawGrid(Canvas canvas, int i, int i2) {
        int iMax = (int) Math.max(60.0f, (Math.max(i, i2) / Math.max(0.01f, this.pScale)) * 0.8f);
        int i3 = -iMax;
        for (int i4 = i3; i4 <= iMax; i4 += 20) {
            float f = i3;
            float f2 = i4;
            float[] fArrProj = proj(f, f2, 0.0f, i, i2);
            float f3 = iMax;
            float[] fArrProj2 = proj(f3, f2, 0.0f, i, i2);
            float f4 = fArrProj[0];
            float f5 = fArrProj[1];
            int i5 = i4 % 100;
            canvas.drawLine(f4, f5, fArrProj2[0], fArrProj2[1], i5 == 0 ? this.gridMajor : this.grid);
            float[] fArrProj3 = proj(f2, f, 0.0f, i, i2);
            float[] fArrProj4 = proj(f2, f3, 0.0f, i, i2);
            canvas.drawLine(fArrProj3[0], fArrProj3[1], fArrProj4[0], fArrProj4[1], i5 == 0 ? this.gridMajor : this.grid);
        }
    }

    private void drawTrack(Canvas canvas, ArrayList<Float> arrayList, ArrayList<Float> arrayList2, Paint paint, int i, int i2, float f) {
        int size = arrayList.size();
        if (size < 2) {
            return;
        }
        Path path = new Path();
        float[] fArrProj = proj(arrayList.get(0).floatValue(), arrayList2.get(0).floatValue(), f, i, i2);
        path.moveTo(fArrProj[0], fArrProj[1]);
        for (int i3 = 1; i3 < size; i3++) {
            float[] fArrProj2 = proj(arrayList.get(i3).floatValue(), arrayList2.get(i3).floatValue(), f, i, i2);
            if (i3 < size - 1) {
                int i4 = i3 + 1;
                float[] fArrProj3 = proj(arrayList.get(i4).floatValue(), arrayList2.get(i4).floatValue(), f, i, i2);
                float f2 = fArrProj2[0];
                float f3 = fArrProj2[1];
                path.quadTo(f2, f3, (fArrProj3[0] + f2) / 2.0f, (fArrProj3[1] + f3) / 2.0f);
            } else {
                path.lineTo(fArrProj2[0], fArrProj2[1]);
            }
        }
        canvas.drawPath(path, paint);
    }

    private void init() {
        this.grid.setColor(-14539480);
        this.grid.setStrokeWidth(1.0f);
        this.gridMajor.setColor(-13749448);
        this.gridMajor.setStrokeWidth(1.4f);
        this.route.setStyle(Paint.Style.STROKE);
        this.route.setStrokeWidth(dp(3.2f));
        this.route.setColor(-8400466);
        this.route.setStrokeCap(Paint.Cap.ROUND);
        this.route.setStrokeJoin(Paint.Join.ROUND);
        this.rem.setStyle(Paint.Style.STROKE);
        this.rem.setStrokeWidth(dp(2.6f));
        this.rem.setColor(-18611);
        this.rem.setStrokeCap(Paint.Cap.ROUND);
        this.stick.setColor(1429109343);
        this.stick.setStrokeWidth(dp(1.0f));
        this.text.setColor(-7696492);
        this.text.setTextSize(dp(10.0f));
        this.dot.setColor(-1184275);
        this.dot.setStyle(Paint.Style.FILL);
        setBackgroundColor(-15855341);
    }

    private float[] proj(float f, float f2, float f3, int i, int i2) {
        float f4 = this.pCx;
        float f5 = this.pCy;
        float f6 = ((f - f4) * 0.866f) - ((f2 - f5) * 0.866f);
        float f7 = (((f - f4) * 0.5f) + ((f2 - f5) * 0.5f)) - f3;
        float f8 = i / 2.0f;
        float f9 = this.pScale;
        return new float[]{f8 + (f6 * f9), (i2 * 0.62f) + (f7 * f9)};
    }

    private void trim(ArrayList<Float> arrayList, ArrayList<Float> arrayList2) {
        while (arrayList.size() > MAX) {
            arrayList.remove(0);
            arrayList2.remove(0);
        }
    }

    public synchronized void addOwn(float f, float f2) {
        this.ownX.add(Float.valueOf(f));
        this.ownY.add(Float.valueOf(f2));
        trim(this.ownX, this.ownY);
        postInvalidate();
    }

    public synchronized void addRemote(float f, float f2) {
        this.remX.add(Float.valueOf(f));
        this.remY.add(Float.valueOf(f2));
        trim(this.remX, this.remY);
        postInvalidate();
    }

    public synchronized void clearAll() {
        this.ownX.clear();
        this.ownY.clear();
        this.remX.clear();
        this.remY.clear();
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        float[] fArrBounds = bounds();
        float fMax = (Math.max(fArrBounds[1] - fArrBounds[0], fArrBounds[3] - fArrBounds[2]) * 1.5f) + 20.0f;
        this.pCx = (fArrBounds[0] + fArrBounds[1]) / 2.0f;
        this.pCy = (fArrBounds[2] + fArrBounds[3]) / 2.0f;
        this.pScale = Math.min(width, height) / fMax;
        drawGrid(canvas, width, height);
        for (int i = 0; i < this.ownX.size(); i += 6) {
            float[] fArrProj = proj(this.ownX.get(i).floatValue(), this.ownY.get(i).floatValue(), LIFT, width, height);
            float[] fArrProj2 = proj(this.ownX.get(i).floatValue(), this.ownY.get(i).floatValue(), 0.0f, width, height);
            canvas.drawLine(fArrProj[0], fArrProj[1], fArrProj2[0], fArrProj2[1], this.stick);
        }
        drawTrack(canvas, this.remX, this.remY, this.rem, width, height, 0.0f);
        drawTrack(canvas, this.ownX, this.ownY, this.route, width, height, LIFT);
        if (!this.ownX.isEmpty()) {
            float[] fArrProj3 = proj(this.ownX.get(0).floatValue(), this.ownY.get(0).floatValue(), LIFT, width, height);
            canvas.drawCircle(fArrProj3[0], fArrProj3[1], dp(3.5f), this.dot);
            int size = this.ownX.size() - 1;
            float[] fArrProj4 = proj(this.ownX.get(size).floatValue(), this.ownY.get(size).floatValue(), LIFT, width, height);
            canvas.drawCircle(fArrProj4[0], fArrProj4[1], dp(5.0f), this.route);
        }
        if (this.ownX.isEmpty() && this.remX.isEmpty()) {
            canvas.drawText("暂无轨迹", (width / 2.0f) - dp(24.0f), height / 2.0f, this.text);
        }
        canvas.drawText("等轴测 · 一格 20 m · 绿=本机 橙=接收", dp(8.0f), height - dp(8.0f), this.text);
    }
}
