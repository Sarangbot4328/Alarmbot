package com.alarmbot.mobile.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String alarmId = intent != null ? intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID) : null;
        if (alarmId == null || alarmId.isEmpty()) return;

        AlarmItem item = AlarmStore.getById(context, alarmId);
        if (item == null || !item.enabled) return;

        Intent service = new Intent(context, AlarmRingService.class);
        service.setAction(AlarmRingService.ACTION_START);
        service.putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId);
        context.startForegroundService(service);
    }
}
