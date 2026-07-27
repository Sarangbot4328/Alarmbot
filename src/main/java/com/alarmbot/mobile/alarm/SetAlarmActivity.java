package com.alarmbot.mobile.alarm;

import android.content.Intent;
import android.os.Bundle;
import android.provider.AlarmClock;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.alarmbot.mobile.MainActivity;
import com.alarmbot.mobile.R;
import com.alarmbot.mobile.settings.AppSettings;
import com.alarmbot.mobile.voice.VoiceCatalog;

/**
 * Handles Gemini / Assistant {@link AlarmClock#ACTION_SET_ALARM} and SHOW_ALARMS.
 */
public final class SetAlarmActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        Intent intent = getIntent();
        String action = intent != null ? intent.getAction() : null;

        if (AlarmClock.ACTION_SHOW_ALARMS.equals(action)) {
            openMain();
            finish();
            return;
        }

        if (!AlarmClock.ACTION_SET_ALARM.equals(action)) {
            finish();
            return;
        }

        if (!VoiceAlarmIntents.hasTime(intent)) {
            // 시간 정보가 없으면 편집 화면으로
            startActivity(new Intent(this, AlarmEditActivity.class));
            finish();
            return;
        }

        AlarmItem item = VoiceAlarmIntents.createFromSetAlarmIntent(intent);
        AlarmStore.upsert(this, item);
        AlarmScheduler.schedule(this, item);

        String voice = VoiceCatalog.get(AppSettings.getVoiceId(this)).displayName;
        String text = item.formattedTime() + " · " + item.repeatLabel(this)
                + " · " + voice;
        Toast.makeText(this, getString(R.string.voice_alarm_set, text), Toast.LENGTH_LONG).show();

        if (!VoiceAlarmIntents.skipUi(intent)) {
            openMain();
        }
        finish();
    }

    private void openMain() {
        Intent main = new Intent(this, MainActivity.class);
        main.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(main);
    }
}
