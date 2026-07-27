package com.alarmbot.mobile.alarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;
import java.util.List;

public final class AlarmScheduler {
    public static final String EXTRA_ALARM_ID = "alarm_id";

    private AlarmScheduler() {
    }

    public static void scheduleAll(Context context) {
        List<AlarmItem> items = AlarmStore.getAll(context);
        for (AlarmItem item : items) {
            if (item.enabled) schedule(context, item);
            else cancel(context, item.id);
        }
    }

    public static void schedule(Context context, AlarmItem item) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;

        PendingIntent pi = pendingIntent(context, item.id);
        long triggerAt = nextTriggerMillis(item);
        if (triggerAt <= 0) {
            cancel(context, item.id);
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        } else {
            manager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        }
    }

    public static void cancel(Context context, String alarmId) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;
        manager.cancel(pendingIntent(context, alarmId));
    }

    /**
     * After an alarm rings: one-shot alarms are disabled; repeating alarms get the next fire time.
     */
    public static void rescheduleAfterRing(Context context, AlarmItem item) {
        if (item == null) return;
        if (item.isOnce()) {
            item.enabled = false;
            AlarmStore.upsert(context, item);
            cancel(context, item.id);
            return;
        }
        if (item.enabled) {
            schedule(context, item);
        }
    }

    private static PendingIntent pendingIntent(Context context, String alarmId) {
        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.setAction("com.alarmbot.mobile.ALARM_FIRE");
        intent.putExtra(EXTRA_ALARM_ID, alarmId);
        int requestCode = alarmId.hashCode();
        return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    public static long nextTriggerMillis(AlarmItem item) {
        Calendar now = Calendar.getInstance();
        if (item.isOnce()) {
            Calendar calendar = nextTimeTodayOrTomorrow(item.hour, item.minute, now);
            return calendar.getTimeInMillis();
        }

        for (int addDays = 0; addDays <= 7; addDays++) {
            Calendar candidate = (Calendar) now.clone();
            candidate.set(Calendar.SECOND, 0);
            candidate.set(Calendar.MILLISECOND, 0);
            candidate.set(Calendar.HOUR_OF_DAY, item.hour);
            candidate.set(Calendar.MINUTE, item.minute);
            candidate.add(Calendar.DAY_OF_YEAR, addDays);
            if (!item.hasDay(candidate.get(Calendar.DAY_OF_WEEK))) continue;
            if (candidate.getTimeInMillis() > now.getTimeInMillis()) {
                return candidate.getTimeInMillis();
            }
        }
        return -1;
    }

    private static Calendar nextTimeTodayOrTomorrow(int hour, int minute, Calendar now) {
        Calendar calendar = (Calendar) now.clone();
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        if (calendar.getTimeInMillis() <= now.getTimeInMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        }
        return calendar;
    }
}
