package com.alarmbot.mobile.ui;

import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.alarmbot.mobile.BuildConfig;
import com.alarmbot.mobile.MainActivity;
import com.alarmbot.mobile.R;
import com.alarmbot.mobile.settings.AppSettings;
import com.alarmbot.mobile.voice.VoiceCatalog;

public final class SettingsChannelView extends FrameLayout {
    private final MainActivity activity;
    private final RadioGroup voiceGroup;
    private final TextView version;
    private boolean refreshing;

    public SettingsChannelView(MainActivity activity) {
        super(activity);
        this.activity = activity;
        LayoutInflater.from(activity).inflate(R.layout.channel_settings, this, true);
        voiceGroup = findViewById(R.id.voice_group);
        version = findViewById(R.id.app_version);

        voiceGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (refreshing) return;
            if (checkedId == R.id.voice_iu) {
                AppSettings.setVoiceId(activity, VoiceCatalog.VOICE_IU_DAEGUN);
                Toast.makeText(activity, VoiceCatalog.DISPLAY_IU_DAEGUN + " 선택됨", Toast.LENGTH_SHORT).show();
            }
        });
        refresh();
    }

    public void refresh() {
        refreshing = true;
        String voiceId = AppSettings.getVoiceId(activity);
        RadioButton iu = findViewById(R.id.voice_iu);
        iu.setChecked(VoiceCatalog.VOICE_IU_DAEGUN.equals(voiceId));
        version.setText("버전 " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
        refreshing = false;
    }
}
