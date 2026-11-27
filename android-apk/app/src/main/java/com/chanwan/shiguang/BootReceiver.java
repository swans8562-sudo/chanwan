package com.chanwan.shiguang;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) return;
        try {
            Intent svc = new Intent(context, ChanwanPushService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Throwable ignored) {
            // Prevents ForegroundServiceStartNotAllowedException crash on strict OEM / Android 15+ boot policies
        }
    }
}
