package com.dsh.vibgen;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 接收测试器发来的模拟路线 */
public class SimReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.dsh.vibshake.SIM_LOOP";

    @Override public void onReceive(Context ctx, Intent it) {
        if (!ACTION.equals(it.getAction())) return;
        MainActivity.dispatchSimLoop(it.getStringExtra("json"), it.getIntExtra("len", 0));
    }
}
