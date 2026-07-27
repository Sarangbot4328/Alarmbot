package com.alarmbot.mobile.alarm;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AlarmStore {
    private static final String PREFS = "alarmbot_alarms";
    private static final String KEY_ALARMS = "alarms";

    private AlarmStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static List<AlarmItem> getAll(Context context) {
        String raw = prefs(context).getString(KEY_ALARMS, "[]");
        List<AlarmItem> items = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                items.add(AlarmItem.fromJson(array.getJSONObject(i)));
            }
        } catch (JSONException ignored) {
        }
        Collections.sort(items, (a, b) -> {
            int ah = a.hour * 60 + a.minute;
            int bh = b.hour * 60 + b.minute;
            return Integer.compare(ah, bh);
        });
        return items;
    }

    public static AlarmItem getById(Context context, String id) {
        for (AlarmItem item : getAll(context)) {
            if (item.id.equals(id)) return item;
        }
        return null;
    }

    public static void upsert(Context context, AlarmItem item) {
        List<AlarmItem> items = getAll(context);
        boolean replaced = false;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(item.id)) {
                items.set(i, item);
                replaced = true;
                break;
            }
        }
        if (!replaced) items.add(item);
        saveAll(context, items);
    }

    public static void delete(Context context, String id) {
        List<AlarmItem> items = getAll(context);
        items.removeIf(item -> item.id.equals(id));
        saveAll(context, items);
    }

    private static void saveAll(Context context, List<AlarmItem> items) {
        JSONArray array = new JSONArray();
        for (AlarmItem item : items) {
            try {
                array.put(item.toJson());
            } catch (JSONException ignored) {
            }
        }
        prefs(context).edit().putString(KEY_ALARMS, array.toString()).apply();
    }
}
