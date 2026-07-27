package com.alarmbot.mobile.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public final class AlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "AlarmReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String alarmId = intent != null ? intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID) : null;
        if (alarmId == null || alarmId.isEmpty()) return;

        AlarmItem item = AlarmStore.getById(context, alarmId);
        if (item == null || !item.enabled) return;

        try {
            Intent service = new Intent(context, AlarmRingService.class);
            service.setAction(AlarmRingService.ACTION_START);
            service.putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start AlarmRingService", e);
        }
    }
}
