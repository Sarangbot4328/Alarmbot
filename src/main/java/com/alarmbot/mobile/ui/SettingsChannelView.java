package com.alarmbot.mobile.ui;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.widget.Button;
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

import java.util.Calendar;

public final class SettingsChannelView extends FrameLayout {
    private static final String[] CLOCK_PACKAGES = {
            "com.sec.android.app.clockpackage",
            "com.google.android.deskclock",
            "com.android.deskclock"
    };

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
        Button openClock = findViewById(R.id.btn_open_clock_settings);
        Button testChooser = findViewById(R.id.btn_test_chooser);

        voiceGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (refreshing) return;
            if (checkedId == R.id.voice_iu) {
                AppSettings.setVoiceId(activity, VoiceCatalog.VOICE_IU_DAEGUN);
                Toast.makeText(activity, VoiceCatalog.DISPLAY_IU_DAEGUN + " 선택됨", Toast.LENGTH_SHORT).show();
            }
        });

        openClock.setOnClickListener(v -> openClockAppSettings());
        testChooser.setOnClickListener(v -> testAlarmAppChooser());
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

    private void openClockAppSettings() {
        PackageManager pm = activity.getPackageManager();
        for (String pkg : CLOCK_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0);
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + pkg));
                activity.startActivity(intent);
                Toast.makeText(activity, "기본으로 설정 지우기 또는 사용 중지를 눌러 주세요", Toast.LENGTH_LONG).show();
                return;
            } catch (Exception ignored) {
            }
        }
        Toast.makeText(activity, "시계 앱을 찾지 못했습니다. 설정 > 앱 > 시계에서 직접 열어 주세요", Toast.LENGTH_LONG).show();
    }

    private void testAlarmAppChooser() {
        Calendar now = Calendar.getInstance();
        Intent intent = new Intent(AlarmClock.ACTION_SET_ALARM);
        intent.putExtra(AlarmClock.EXTRA_HOUR, now.get(Calendar.HOUR_OF_DAY));
        intent.putExtra(AlarmClock.EXTRA_MINUTES, (now.get(Calendar.MINUTE) + 2) % 60);
        intent.putExtra(AlarmClock.EXTRA_MESSAGE, "알람봇 연결 테스트");
        intent.putExtra(AlarmClock.EXTRA_SKIP_UI, false);
        try {
            activity.startActivity(Intent.createChooser(intent, "알람을 넣을 앱 선택"));
        } catch (Exception e) {
            Toast.makeText(activity, "알람 앱 선택 창을 열 수 없습니다", Toast.LENGTH_SHORT).show();
        }
    }
}
