package com.dsh.vibshake;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 接收「模拟摇动」广播：发生器发一条，测试端计一次。
 * 只用于验证计数/去抖逻辑，不经过传感器。广播必须带包名（setPackage）。
 */
public class MockShakeReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.dsh.vibshake.MOCK_SHAKE";

    @Override public void onReceive(Context ctx, Intent it) {
        int seq = it.getIntExtra("seq", 0);
        if ("com.dsh.vibshake.LOOP".equals(it.getAction())) {
            MainActivity.dispatchLoop(it.getStringExtra("json"));
            return;
        }
        if ("com.dsh.vibshake.GEO".equals(it.getAction())) {
            MainActivity.dispatchGeo(it.getDoubleExtra("lat", 0), it.getDoubleExtra("lng", 0));
            return;
        }
        if ("com.dsh.vibshake.ROUTE".equals(it.getAction())) {
            MainActivity.dispatchRoute(it.getFloatExtra("x", 0f), it.getFloatExtra("y", 0f));
            return;
        }
        if ("com.dsh.vibshake.MOCK_STEP".equals(it.getAction())) {
            MainActivity.dispatchMockStep(seq);
        } else {
            MainActivity.dispatchMock(seq);
        }
    }
}
