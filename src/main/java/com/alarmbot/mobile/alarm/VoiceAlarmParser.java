package com.alarmbot.mobile.alarm;

import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses simple Korean voice commands into alarm time/repeat.
 */
public final class VoiceAlarmParser {
    private static final Pattern RELATIVE_HOURS = Pattern.compile("(\\d+)\\s*시간\\s*(뒤|후)");
    private static final Pattern RELATIVE_MINUTES = Pattern.compile("(\\d+)\\s*분\\s*(뒤|후)");
    private static final Pattern TIME_HM = Pattern.compile(
            "(오전|오후|아침|저녁|밤)?\\s*(\\d{1,2})\\s*시\\s*(\\d{1,2})?\\s*분?");
    private static final Pattern TIME_COLON = Pattern.compile("(\\d{1,2})\\s*[:：]\\s*(\\d{1,2})");

    private VoiceAlarmParser() {
    }

    public static final class Result {
        public final int hour;
        public final int minute;
        public final int daysMask;
        public final String label;

        public Result(int hour, int minute, int daysMask, String label) {
            this.hour = hour;
            this.minute = minute;
            this.daysMask = daysMask;
            this.label = label;
        }
    }

    public static Result parse(String raw) {
        if (raw == null) return null;
        String original = raw.trim();
        String text = original.toLowerCase(Locale.ROOT).replace(" ", "");
        if (text.isEmpty()) return null;

        int daysMask = parseDays(text);
        int[] time = parseRelative(text);
        if (time == null) time = parseAbsolute(text);
        if (time == null) return null;

        return new Result(time[0], time[1], daysMask, original);
    }

    private static int parseDays(String text) {
        if (text.contains("매일")) return AlarmItem.MASK_EVERY_DAY;
        if (text.contains("주중") || text.contains("평일")) return AlarmItem.MASK_WEEKDAYS;
        if (text.contains("주말")) return AlarmItem.MASK_WEEKEND;

        int mask = 0;
        if (text.contains("월요일") || text.contains("매주월")) mask |= AlarmItem.bit(Calendar.MONDAY);
        if (text.contains("화요일") || text.contains("매주화")) mask |= AlarmItem.bit(Calendar.TUESDAY);
        if (text.contains("수요일") || text.contains("매주수")) mask |= AlarmItem.bit(Calendar.WEDNESDAY);
        if (text.contains("목요일") || text.contains("매주목")) mask |= AlarmItem.bit(Calendar.THURSDAY);
        if (text.contains("금요일") || text.contains("매주금")) mask |= AlarmItem.bit(Calendar.FRIDAY);
        if (text.contains("토요일") || text.contains("매주토")) mask |= AlarmItem.bit(Calendar.SATURDAY);
        if (text.contains("일요일") || text.contains("매주일")) mask |= AlarmItem.bit(Calendar.SUNDAY);
        return mask;
    }

    private static int[] parseRelative(String text) {
        Calendar cal = Calendar.getInstance();
        Matcher mh = RELATIVE_HOURS.matcher(text);
        Matcher mm = RELATIVE_MINUTES.matcher(text);
        boolean matched = false;
        if (mh.find()) {
            cal.add(Calendar.HOUR_OF_DAY, Integer.parseInt(mh.group(1)));
            matched = true;
        }
        if (mm.find()) {
            cal.add(Calendar.MINUTE, Integer.parseInt(mm.group(1)));
            matched = true;
        }
        if (!matched) return null;
        return new int[]{cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)};
    }

    private static int[] parseAbsolute(String text) {
        Matcher colon = TIME_COLON.matcher(text);
        if (colon.find()) {
            int hour = Integer.parseInt(colon.group(1));
            int minute = Integer.parseInt(colon.group(2));
            hour = applyPeriod(text, hour);
            return valid(hour, minute);
        }

        Matcher m = TIME_HM.matcher(text);
        if (!m.find()) return null;
        String period = m.group(1);
        int hour = Integer.parseInt(m.group(2));
        int minute = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;

        if (period != null) {
            hour = applyPeriodToken(period, hour);
        } else {
            hour = applyPeriod(text, hour);
        }
        return valid(hour, minute);
    }

    private static int applyPeriod(String text, int hour) {
        if (text.contains("오후") || text.contains("저녁") || text.contains("밤")) {
            if (hour < 12) hour += 12;
        } else if (text.contains("오전") || text.contains("아침")) {
            if (hour == 12) hour = 0;
        }
        return hour;
    }

    private static int applyPeriodToken(String period, int hour) {
        if ("오후".equals(period) || "저녁".equals(period) || "밤".equals(period)) {
            if (hour < 12) hour += 12;
        } else if ("오전".equals(period) || "아침".equals(period)) {
            if (hour == 12) hour = 0;
        }
        return hour;
    }

    private static int[] valid(int hour, int minute) {
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return null;
        return new int[]{hour, minute};
    }
}
