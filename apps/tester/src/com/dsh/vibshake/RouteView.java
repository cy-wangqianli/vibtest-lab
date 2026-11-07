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

public class RouteView extends View {
    private static final int MAX_POINTS = 900;
    private final Paint dotPaint;
    private final Paint gridPaint;
    private final ArrayList<Float> ownX;
    private final ArrayList<Float> ownY;
    private final Paint remPaint;
    private final ArrayList<Float> remX;
    private final ArrayList<Float> remY;
    private final Paint routePaint;
    private float scalePx;
    private final Paint textPaint;

    public RouteView(Context context) {
        super(context);
        this.ownX = new ArrayList<>();
        this.ownY = new ArrayList<>();
        this.remX = new ArrayList<>();
        this.remY = new ArrayList<>();
        this.scalePx = 3.0f;
        this.routePaint = new Paint(1);
        this.remPaint = new Paint(1);
        this.gridPaint = new Paint();
        this.textPaint = new Paint(1);
        this.dotPaint = new Paint(1);
        init();
    }

    public RouteView(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        this.ownX = new ArrayList<>();
        this.ownY = new ArrayList<>();
        this.remX = new ArrayList<>();
        this.remY = new ArrayList<>();
        this.scalePx = 3.0f;
        this.routePaint = new Paint(1);
        this.remPaint = new Paint(1);
        this.gridPaint = new Paint();
        this.textPaint = new Paint(1);
        this.dotPaint = new Paint(1);
        init();
    }

    private float[] bounds() {
        float f = Float.MAX_VALUE;
        float f2 = -3.4028235E38f;
        float f3 = -3.4028235E38f;
        int i = 0;
        boolean z = false;
        float f4 = Float.MAX_VALUE;
        while (i < 2) {
            ArrayList<Float> arrayList = i == 0 ? this.ownX : this.remX;
            ArrayList<Float> arrayList2 = i == 0 ? this.ownY : this.remY;
            int i2 = 0;
            while (i2 < arrayList.size()) {
                float fFloatValue = arrayList.get(i2).floatValue();
                float fFloatValue2 = arrayList2.get(i2).floatValue();
                if (fFloatValue < f) {
                    f = fFloatValue;
                }
                if (fFloatValue > f2) {
                    f2 = fFloatValue;
                }
                if (fFloatValue2 < f4) {
                    f4 = fFloatValue2;
                }
                if (fFloatValue2 > f3) {
                    f3 = fFloatValue2;
                }
                i2++;
                z = true;
            }
            i++;
        }
        if (!z) {
            return new float[]{-15.0f, 15.0f, -15.0f, 15.0f};
        }
        if (f2 - f < 20.0f) {
            float f5 = (f + f2) / 2.0f;
            f2 = f5 + 10.0f;
            f = f5 - 10.0f;
        }
        if (f3 - f4 < 20.0f) {
            float f6 = (f4 + f3) / 2.0f;
            f3 = f6 + 10.0f;
            f4 = f6 - 10.0f;
        }
        return new float[]{f, f2, f4, f3};
    }

    private Path buildPath(ArrayList<Float> arrayList, ArrayList<Float> arrayList2, int i, int i2, float f, float f2) {
        Path path = new Path();
        int size = arrayList.size();
        if (size == 0) {
            return path;
        }
        path.moveTo(sx(arrayList.get(0).floatValue(), i, f), sy(arrayList2.get(0).floatValue(), i2, f2));
        for (int i3 = 1; i3 < size; i3++) {
            float fSx = sx(arrayList.get(i3).floatValue(), i, f);
            float fSy = sy(arrayList2.get(i3).floatValue(), i2, f2);
            if (i3 < size - 1) {
                int i4 = i3 + 1;
                path.quadTo(fSx, fSy, (sx(arrayList.get(i4).floatValue(), i, f) + fSx) / 2.0f, (sy(arrayList2.get(i4).floatValue(), i2, f2) + fSy) / 2.0f);
            } else {
                path.lineTo(fSx, fSy);
            }
        }
        return path;
    }

    private float dp(float f) {
        return f * getResources().getDisplayMetrics().density;
    }

    private void init() {
        this.routePaint.setStyle(Paint.Style.STROKE);
        this.routePaint.setStrokeWidth(dp(3.0f));
        this.routePaint.setColor(-8400466);
        this.routePaint.setStrokeCap(Paint.Cap.ROUND);
        this.routePaint.setStrokeJoin(Paint.Join.ROUND);
        this.remPaint.setStyle(Paint.Style.STROKE);
        this.remPaint.setStrokeWidth(dp(2.5f));
        this.remPaint.setColor(-18611);
        this.remPaint.setStrokeCap(Paint.Cap.ROUND);
        this.remPaint.setStrokeJoin(Paint.Join.ROUND);
        this.gridPaint.setColor(-14408668);
        this.gridPaint.setStrokeWidth(1.0f);
        this.textPaint.setColor(-7696492);
        this.textPaint.setTextSize(dp(10.0f));
        this.dotPaint.setStyle(Paint.Style.FILL);
        this.dotPaint.setColor(-1184275);
        setBackgroundColor(-15592426);
    }

    private float sx(float f, int i, float f2) {
        return (i / 2.0f) + ((f - f2) * this.scalePx);
    }

    private float sy(float f, int i, float f2) {
        return (i / 2.0f) - ((f - f2) * this.scalePx);
    }

    private void trim(ArrayList<Float> arrayList, ArrayList<Float> arrayList2) {
        while (arrayList.size() > MAX_POINTS) {
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
        float f;
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        float[] fArrBounds = bounds();
        float f2 = width;
        float fDp = dp(18.0f) * 2.0f;
        float f3 = height;
        float fMin = Math.min((f2 - fDp) / Math.max(1.0f, fArrBounds[1] - fArrBounds[0]), (f3 - fDp) / Math.max(1.0f, fArrBounds[3] - fArrBounds[2]));
        this.scalePx = fMin;
        float f4 = (fArrBounds[0] + fArrBounds[1]) / 2.0f;
        float f5 = (fArrBounds[2] + fArrBounds[3]) / 2.0f;
        float f6 = fMin * 25.0f;
        if (f6 > dp(14.0f)) {
            float f7 = ((f2 / 2.0f) - (this.scalePx * f4)) % f6;
            while (f7 < f2) {
                canvas.drawLine(f7, 0.0f, f7, f3, this.gridPaint);
                f7 += f6;
                f4 = f4;
            }
            f = f4;
            for (float f8 = ((f3 / 2.0f) + (this.scalePx * f5)) % f6; f8 < f3; f8 += f6) {
                canvas.drawLine(0.0f, f8, f2, f8, this.gridPaint);
            }
            canvas.drawText("25 m / 格", dp(8.0f), f3 - dp(8.0f), this.textPaint);
        } else {
            f = f4;
        }
        float f9 = f;
        canvas.drawPath(buildPath(this.remX, this.remY, width, height, f9, f5), this.remPaint);
        canvas.drawPath(buildPath(this.ownX, this.ownY, width, height, f9, f5), this.routePaint);
        if (!this.ownX.isEmpty()) {
            float f10 = f;
            canvas.drawCircle(sx(this.ownX.get(0).floatValue(), width, f10), sy(this.ownY.get(0).floatValue(), height, f5), dp(4.0f), this.dotPaint);
            int size = this.ownX.size() - 1;
            canvas.drawCircle(sx(this.ownX.get(size).floatValue(), width, f10), sy(this.ownY.get(size).floatValue(), height, f5), dp(5.0f), this.routePaint);
        }
        if (this.ownX.isEmpty() && this.remX.isEmpty()) {
            canvas.drawText("暂无轨迹", (f2 / 2.0f) - dp(22.0f), f3 / 2.0f, this.textPaint);
        }
    }
}
