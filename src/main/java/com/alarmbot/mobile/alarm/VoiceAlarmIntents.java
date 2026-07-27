package com.alarmbot.mobile.alarm;

import android.content.Intent;
import android.provider.AlarmClock;

import java.util.ArrayList;
import java.util.Calendar;

/**
 * Parses {@link AlarmClock#ACTION_SET_ALARM} extras from Gemini / Google Assistant.
 */
public final class VoiceAlarmIntents {
    private VoiceAlarmIntents() {
    }

    public static boolean hasTime(Intent intent) {
        return intent != null && intent.hasExtra(AlarmClock.EXTRA_HOUR);
    }

    public static int hour(Intent intent) {
        return intent.getIntExtra(AlarmClock.EXTRA_HOUR, 0);
    }

    public static int minute(Intent intent) {
        return intent.getIntExtra(AlarmClock.EXTRA_MINUTES, 0);
    }

    public static String message(Intent intent) {
        String msg = intent.getStringExtra(AlarmClock.EXTRA_MESSAGE);
        return msg == null ? "" : msg.trim();
    }

    public static boolean skipUi(Intent intent) {
        return intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false);
    }

    /**
     * No DAYS → once only (e.g. "내일 아침 7시", "3시간 뒤").
     * With DAYS → repeating (e.g. "매일", "매주 수요일").
     */
    public static int daysMask(Intent intent) {
        ArrayList<Integer> days = intent.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS);
        if (days == null || days.isEmpty()) {
            return AlarmItem.MASK_NONE;
        }
        int mask = 0;
        for (Integer day : days) {
            if (day == null) continue;
            if (day >= Calendar.SUNDAY && day <= Calendar.SATURDAY) {
                mask |= AlarmItem.bit(day);
            }
        }
        return mask;
    }

    public static AlarmItem createFromSetAlarmIntent(Intent intent) {
        int hour = hour(intent);
        int minute = minute(intent);
        if (hour < 0 || hour > 23) hour = 0;
        if (minute < 0 || minute > 59) minute = 0;
        return AlarmItem.create(hour, minute, message(intent), daysMask(intent));
    }
}
