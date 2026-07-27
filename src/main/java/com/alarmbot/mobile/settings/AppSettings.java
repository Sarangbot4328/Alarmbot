package com.alarmbot.mobile.settings;

import android.content.Context;
import android.content.SharedPreferences;

import com.alarmbot.mobile.voice.VoiceCatalog;

public final class AppSettings {
    private static final String PREFS = "alarmbot_settings";
    private static final String KEY_VOICE_ID = "voice_id";

    private AppSettings() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getVoiceId(Context context) {
        return prefs(context).getString(KEY_VOICE_ID, VoiceCatalog.VOICE_IU_DAEGUN);
    }

    public static void setVoiceId(Context context, String voiceId) {
        prefs(context).edit().putString(KEY_VOICE_ID, voiceId).apply();
    }
}
