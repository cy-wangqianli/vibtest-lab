package com.dsh.vibshake;

import android.annotation.SuppressLint;
import android.content.Context;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.webkit.WebViewClient;

/**
 * 街区地图视图（高德瓦片 + Leaflet），并提供闭环回传给原生，
 * 供 MockLoc 把闭环变成模拟定位轨迹。
 */
public class LeafletMapView extends WebView {

    public interface Listener { void onConsole(String msg); void onReady(); }
    public interface LoopListener { void onLoop(String json, int lengthMeters); }

    private static final double BASE_LAT = 30.657;
    private static final double BASE_LNG = 104.066;

    private boolean ready = false;
    private Listener listener;
    private LoopListener loopListener;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public LeafletMapView(Context c) {
        super(c);
        getSettings().setJavaScriptEnabled(true);
        getSettings().setAllowFileAccess(true);
        getSettings().setAllowFileAccessFromFileURLs(true);
        getSettings().setAllowUniversalAccessFromFileURLs(true);
        getSettings().setDomStorageEnabled(true);
        setBackgroundColor(0xFF0B0F14);
        getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
        getSettings().setDatabaseEnabled(true);

        addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void setLoop(String json, int lengthMeters) {
                if (loopListener != null) loopListener.onLoop(json, lengthMeters);
            }
        }, "AndroidBridge");

        setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                ready = true;
                if (listener != null) listener.onReady();
            }
        });
        setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage m) {
                if (listener != null) {
                    listener.onConsole(m.messageLevel() + ": " + m.message() + " @" + m.lineNumber());
                }
                return true;
            }
        });
        loadUrl("file:///android_asset/mapstreet.html");
    }

    public void setListener(Listener l) { this.listener = l; }
    public void setLoopListener(LoopListener l) { this.loopListener = l; }

    public void setSearchEnabled(boolean on) { js("setSearch(" + (on ? "true" : "false") + ")"); }

    public void showLoop(String json) {
        if (json == null) return;
        String esc = json.replace("\\", "\\\\").replace("'", "\\'");
        js("showLoop('" + esc + "')");
    }

    public void resetView() { js("resetView()"); }

    public void setStats(String dist, String row) {
        String d = dist == null ? "" : dist.replace("'", "");
        String r = row == null ? "" : row.replace("'", "");
        js("setStats('" + d + "','" + r + "')");
    }

    public void setHere(double lat, double lng) { js("setHere(" + lat + "," + lng + ")"); }

    public void setPicking(boolean on) { js("setPicking(" + (on ? "true" : "false") + ")"); }
    public void closeLoop() { js("closeLoop()"); }
    public void step(float meters) { js("stepLoop(" + meters + ")"); }
    public void clearAll() { js("clearAll()"); }

    /** 直接用经纬度画真人轨迹 */
    public void addTrackLatLng(double lat, double lng) {
        js("addLive(" + lat + "," + lng + ",false)");
    }

    public void addOwn(float x, float y) { addLive(x, y, false); }
    public void addRemote(float x, float y) { addLive(x, y, true); }

    private void addLive(float x, float y, boolean remote) {
        double lat = BASE_LAT + y / 111320.0;
        double lng = BASE_LNG + x / (111320.0 * Math.cos(Math.toRadians(BASE_LAT)));
        js("addLive(" + lat + "," + lng + "," + (remote ? "true" : "false") + ")");
    }

    private void js(String code) {
        if (!ready) return;
        evaluateJavascript(code, null);
    }
}
