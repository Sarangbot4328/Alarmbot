package com.alarmbot.mobile.alarm;

import android.content.Context;

import com.alarmbot.mobile.R;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class AlarmItem {
    public static final int MASK_NONE = 0;
    public static final int MASK_WEEKDAYS =
            bit(Calendar.MONDAY) | bit(Calendar.TUESDAY) | bit(Calendar.WEDNESDAY)
                    | bit(Calendar.THURSDAY) | bit(Calendar.FRIDAY);
    public static final int MASK_WEEKEND = bit(Calendar.SATURDAY) | bit(Calendar.SUNDAY);
    public static final int MASK_EVERY_DAY =
            MASK_WEEKDAYS | MASK_WEEKEND;

    /** Display order: Mon..Sun */
    public static final int[] DAY_ORDER = {
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
            Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
    };

    public final String id;
    public int hour;
    public int minute;
    public String label;
    public boolean enabled;
    /** Bitmask using Calendar.SUNDAY..SATURDAY via {@link #bit(int)}. 0 = once only. */
    public int daysMask;

    public AlarmItem(String id, int hour, int minute, String label, boolean enabled, int daysMask) {
        this.id = id;
        this.hour = hour;
        this.minute = minute;
        this.label = label == null ? "" : label;
        this.enabled = enabled;
        this.daysMask = daysMask;
    }

    public static AlarmItem create(int hour, int minute, String label, int daysMask) {
        return new AlarmItem(UUID.randomUUID().toString(), hour, minute, label, true, daysMask);
    }

    public static int bit(int calendarDay) {
        return 1 << (calendarDay - 1);
    }

    public boolean isOnce() {
        return daysMask == MASK_NONE;
    }

    public boolean hasDay(int calendarDay) {
        return (daysMask & bit(calendarDay)) != 0;
    }

    public void setDay(int calendarDay, boolean on) {
        if (on) daysMask |= bit(calendarDay);
        else daysMask &= ~bit(calendarDay);
    }

    public String formattedTime() {
        return String.format(Locale.getDefault(), "%02d:%02d", hour, minute);
    }

    public String repeatLabel(Context context) {
        if (isOnce()) {
            Calendar now = Calendar.getInstance();
            Calendar fire = Calendar.getInstance();
            fire.set(Calendar.SECOND, 0);
            fire.set(Calendar.MILLISECOND, 0);
            fire.set(Calendar.HOUR_OF_DAY, hour);
            fire.set(Calendar.MINUTE, minute);
            if (fire.getTimeInMillis() <= now.getTimeInMillis()) {
                fire.add(Calendar.DAY_OF_YEAR, 1);
            }
            boolean today = fire.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                    && fire.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR);
            return context.getString(R.string.once) + (today ? " · 오늘" : " · 내일");
        }
        if (daysMask == MASK_EVERY_DAY) return context.getString(R.string.every_day);
        if (daysMask == MASK_WEEKDAYS) return context.getString(R.string.weekdays);
        if (daysMask == MASK_WEEKEND) return context.getString(R.string.weekend);

        List<String> parts = new ArrayList<>();
        int[] labels = {
                R.string.day_mon, R.string.day_tue, R.string.day_wed, R.string.day_thu,
                R.string.day_fri, R.string.day_sat, R.string.day_sun
        };
        for (int i = 0; i < DAY_ORDER.length; i++) {
            if (hasDay(DAY_ORDER[i])) parts.add(context.getString(labels[i]));
        }
        return String.join(" ", parts);
    }

    public String listMeta(Context context) {
        String repeat = repeatLabel(context);
        if (label.isEmpty()) return repeat;
        return repeat + " · " + label;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("hour", hour);
        json.put("minute", minute);
        json.put("label", label);
        json.put("enabled", enabled);
        json.put("daysMask", daysMask);
        return json;
    }

    public static AlarmItem fromJson(JSONObject json) throws JSONException {
        int daysMask = json.has("daysMask")
                ? json.getInt("daysMask")
                : MASK_EVERY_DAY; // old alarms without field → treat as daily
        return new AlarmItem(
                json.getString("id"),
                json.getInt("hour"),
                json.getInt("minute"),
                json.optString("label", ""),
                json.optBoolean("enabled", true),
                daysMask
        );
    }
}
