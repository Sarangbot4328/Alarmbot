package com.alarmbot.mobile;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.alarmbot.mobile.alarm.AlarmScheduler;
import com.alarmbot.mobile.ui.AlarmChannelView;
import com.alarmbot.mobile.ui.SettingsChannelView;
import com.alarmbot.mobile.ui.SystemBarInsets;
import com.alarmbot.mobile.ui.TodoChannelView;

import java.util.ArrayList;
import java.util.Locale;

public final class MainActivity extends AppCompatActivity {
    private FrameLayout content;
    private Button alarmButton;
    private Button todoButton;
    private Button settingsButton;
    private AlarmChannelView alarmView;
    private TodoChannelView todoView;
    private SettingsChannelView settingsView;
    private int selectedChannel = 0;

    private final ActivityResultLauncher<Intent> speechLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                ArrayList<String> texts = result.getData().getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (texts == null || texts.isEmpty()) {
                    Toast.makeText(this, R.string.voice_parse_fail, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (alarmView != null) alarmView.onVoiceResult(texts.get(0));
            });

    private final ActivityResultLauncher<String> micPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) launchSpeechRecognizer();
                else Toast.makeText(this, R.string.voice_need_mic, Toast.LENGTH_LONG).show();
            });

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        SystemBarInsets.apply(this, findViewById(R.id.main_root), true);

        content = findViewById(R.id.content);
        alarmButton = findViewById(R.id.nav_alarm);
        todoButton = findViewById(R.id.nav_todo);
        settingsButton = findViewById(R.id.nav_settings);

        alarmView = new AlarmChannelView(this);
        todoView = new TodoChannelView(this);
        settingsView = new SettingsChannelView(this);

        alarmButton.setOnClickListener(v -> showAlarm());
        todoButton.setOnClickListener(v -> showTodo());
        settingsButton.setOnClickListener(v -> showSettings());

        showAlarm();
        requestNeededPermissions();
        ensureFullScreenIntentPermission();
        AlarmScheduler.scheduleAll(this);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (selectedChannel != 0) {
                    showAlarm();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    public void startVoiceAlarm() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }
        launchSpeechRecognizer();
    }

    private void launchSpeechRecognizer() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREAN);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_listening));
        try {
            speechLauncher.launch(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "이 폰에 음성 인식이 없습니다", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (alarmView != null) alarmView.refresh();
        ensureExactAlarmPermission();
    }

    private void showAlarm() {
        selectedChannel = 0;
        alarmView.refresh();
        swap(alarmView);
        tintNavigation();
    }

    private void showTodo() {
        selectedChannel = 1;
        swap(todoView);
        tintNavigation();
    }

    private void showSettings() {
        selectedChannel = 2;
        settingsView.refresh();
        swap(settingsView);
        tintNavigation();
    }

    private void swap(android.view.View view) {
        if (view.getParent() == content) return;
        content.removeAllViews();
        content.addView(view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void tintNavigation() {
        int active = ContextCompat.getColor(this, R.color.green_dark);
        int idle = ContextCompat.getColor(this, R.color.text_secondary);
        alarmButton.setTextColor(selectedChannel == 0 ? active : idle);
        todoButton.setTextColor(selectedChannel == 1 ? active : idle);
        settingsButton.setTextColor(selectedChannel == 2 ? active : idle);
    }

    private void requestNeededPermissions() {
        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    private void ensureExactAlarmPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am == null || am.canScheduleExactAlarms()) return;
        Toast.makeText(this, "정확한 알람 권한을 허용해 주세요", Toast.LENGTH_LONG).show();
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    private void ensureFullScreenIntentPermission() {
        if (Build.VERSION.SDK_INT < 34) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null || nm.canUseFullScreenIntent()) return;
        Toast.makeText(this, "알람 전체 화면 권한을 허용해 주세요", Toast.LENGTH_LONG).show();
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            try {
                Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                startActivity(intent);
            } catch (Exception ignored) {
            }
        }
    }
}
