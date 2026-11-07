/*
 * ★ 本文件是从成品 APK 反编译回收的 —— 原源码在交接包中丢失。
 *   来源: apks/VibGen.apk   （jadx 1.5.6 反编译）
 *   已做处理: 清理反编译噪音注释；把 jadx 生成的合成 lambda 引用还原成源码级 lambda。
 *   语义未改动。取证与验证过程见 docs/recovery-notes.md。
 */
package com.dsh.vibgen;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.util.ArrayList;
import java.util.List;

public class Map3DView extends WebView {
    private final Handler h;
    private long lastPush;
    private final List<float[]> own;
    private boolean pending;
    private boolean ready;
    private final List<float[]> rem;

    public Map3DView(Context context) {
        super(context);
        this.own = new ArrayList();
        this.rem = new ArrayList();
        this.h = new Handler(Looper.getMainLooper());
        this.ready = false;
        this.pending = false;
        this.lastPush = 0L;
        getSettings().setJavaScriptEnabled(true);
        getSettings().setAllowFileAccess(true);
        getSettings().setAllowFileAccessFromFileURLs(true);
        getSettings().setAllowUniversalAccessFromFileURLs(true);
        getSettings().setDomStorageEnabled(true);
        setBackgroundColor(-16051680);
        setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView webView, String str) {
                Map3DView.this.ready = true;
                Map3DView.this.push();
            }
        });
        loadUrl("file:///android_asset/city.html");
    }

    private String json(List<float[]> list) {
        StringBuilder sb = new StringBuilder("[");
        synchronized (list) {
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                float[] fArr = list.get(i);
                sb.append('[');
                sb.append(round1(fArr[0]));
                sb.append(',');
                sb.append(round1(fArr[1]));
                sb.append(']');
            }
        }
        sb.append(']');
        return sb.toString();
    }

    public void push() {
        if (this.ready) {
            this.lastPush = System.currentTimeMillis();
            evaluateJavascript("setRoute(" + json(this.own) + "," + json(this.rem) + ");", null);
        }
    }

    private void pushMaybe() {
        if (System.currentTimeMillis() - this.lastPush >= 250) {
            push();
        } else {
            if (this.pending) {
                return;
            }
            this.pending = true;
            this.h.postDelayed(() -> {
                this.pending = false;
                push();
            }, 260L);
        }
    }

    private String round1(float f) {
        return String.valueOf(((double) Math.round(f * 10.0f)) / 10.0d);
    }

    private void trim(List<float[]> list) {
        while (list.size() > 4000) {
            list.remove(0);
        }
    }

    public void addOwn(float f, float f2) {
        this.own.add(new float[]{f, f2});
        trim(this.own);
        pushMaybe();
    }

    public void addRemote(float f, float f2) {
        this.rem.add(new float[]{f, f2});
        trim(this.rem);
        pushMaybe();
    }

    public void clearAll() {
        this.own.clear();
        this.rem.clear();
        push();
    }
}
