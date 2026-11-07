/*
 * ★ 本文件是从成品 APK 反编译回收的 —— 原源码在交接包中丢失。
 *   来源: apks/VibShakeTest2.apk   （jadx 1.5.6 反编译）
 *   已做处理: 清理反编译噪音注释；把 jadx 生成的合成 lambda 引用还原成源码级 lambda。
 *   语义未改动。取证与验证过程见 docs/recovery-notes.md。
 */
package com.dsh.vibshake;

import android.content.Context;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class City2DView extends WebView {
    private Listener listener;
    private boolean ready;

    public interface Listener {
        void onConsole(String str);

        void onReady();
    }

    public City2DView(Context context) {
        super(context);
        this.ready = false;
        getSettings().setJavaScriptEnabled(true);
        getSettings().setAllowFileAccess(true);
        getSettings().setAllowFileAccessFromFileURLs(true);
        getSettings().setAllowUniversalAccessFromFileURLs(true);
        getSettings().setDomStorageEnabled(true);
        setBackgroundColor(-16052200);
        setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView webView, String str) {
                City2DView.this.ready = true;
                if (City2DView.this.listener != null) {
                    City2DView.this.listener.onReady();
                }
            }
        });
        setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                if (City2DView.this.listener == null) {
                    return true;
                }
                City2DView.this.listener.onConsole(consoleMessage.messageLevel() + ": " + consoleMessage.message() + " @" + consoleMessage.lineNumber());
                return true;
            }
        });
        loadUrl("file:///android_asset/city2d.html");
    }

    private void js(String str) {
        if (this.ready) {
            evaluateJavascript(str, null);
        }
    }

    public void addOwn(float f, float f2) {
        js("addLive(" + f + "," + f2 + ",false)");
    }

    public void addRemote(float f, float f2) {
        js("addLive(" + f + "," + f2 + ",true)");
    }

    public void clearAll() {
        js("clearAll()");
    }

    public void closeLoop() {
        js("closeLoop()");
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setPicking(boolean z) {
        StringBuilder sb = new StringBuilder("setPicking(");
        sb.append(z ? "true" : "false");
        sb.append(")");
        js(sb.toString());
    }

    public void step(float f) {
        js("stepLoop(" + f + ")");
    }
}
