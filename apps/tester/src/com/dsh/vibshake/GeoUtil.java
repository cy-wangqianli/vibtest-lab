package com.dsh.vibshake;

import org.json.JSONArray;

/**
 * 国内坐标系转换：WGS-84（GPS/系统）↔ GCJ-02（高德/腾讯瓦片）。
 * 不做这个转换，地图上的点和系统定位会相差 300–600 米。
 */
public final class GeoUtil {

    private static final double A = 6378245.0;
    private static final double EE = 0.00669342162296594323;

    private GeoUtil() { }

    public static double[] wgs2gcj(double lat, double lng) {
        if (outOfChina(lat, lng)) return new double[]{lat, lng};
        double dLat = transformLat(lng - 105.0, lat - 35.0);
        double dLng = transformLng(lng - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI);
        dLng = (dLng * 180.0) / (A / sqrtMagic * Math.cos(radLat) * Math.PI);
        return new double[]{lat + dLat, lng + dLng};
    }

    public static double[] gcj2wgs(double lat, double lng) {
        if (outOfChina(lat, lng)) return new double[]{lat, lng};
        double[] g = wgs2gcj(lat, lng);
        double dLat = g[0] - lat;
        double dLng = g[1] - lng;
        return new double[]{lat - dLat, lng - dLng};
    }

    /** 把地图上（GCJ-02）的闭环 JSON 转成 WGS-84 的 JSON */
    public static String loopToWgs(String gcjJson) {
        try {
            JSONArray src = new JSONArray(gcjJson);
            JSONArray out = new JSONArray();
            for (int i = 0; i < src.length(); i++) {
                JSONArray p = src.getJSONArray(i);
                double[] w = gcj2wgs(p.getDouble(0), p.getDouble(1));
                out.put(new JSONArray().put(w[0]).put(w[1]));
            }
            return out.toString();
        } catch (Throwable t) {
            return gcjJson;
        }
    }

    private static boolean outOfChina(double lat, double lng) {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y
                + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLng(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
    }
}
