package com.alarmbot.mobile.alarm;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.alarmbot.mobile.R;

public final class AlarmRingActivity extends AppCompatActivity {
    private TextView ringStatus;
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String status = intent.getStringExtra(AlarmRingService.EXTRA_STATUS_TEXT);
            if (status != null) ringStatus.setText(status);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setupAlarmWindow();
        setContentView(R.layout.activity_alarm_ring);

        TextView ringTime = findViewById(R.id.ring_time);
        TextView ringLabel = findViewById(R.id.ring_label);
        ringStatus = findViewById(R.id.ring_status);
        Button dismiss = findViewById(R.id.btn_dismiss);

        applyExtras(getIntent());

        dismiss.setOnClickListener(v -> {
            Intent stop = new Intent(this, AlarmRingService.class);
            stop.setAction(AlarmRingService.ACTION_DISMISS);
            startService(stop);
            finishAndRemoveTask();
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        setupAlarmWindow();
        applyExtras(intent);
    }

    private void applyExtras(Intent intent) {
        if (intent == null) return;
        TextView ringTime = findViewById(R.id.ring_time);
        TextView ringLabel = findViewById(R.id.ring_label);
        if (ringTime == null || ringLabel == null) return;
        String time = intent.getStringExtra(AlarmRingService.EXTRA_ALARM_TIME);
        String label = intent.getStringExtra(AlarmRingService.EXTRA_ALARM_LABEL);
        ringTime.setText(time != null ? time : "");
        if (label != null && !label.isEmpty()) {
            ringLabel.setText(label);
        } else {
            ringLabel.setText(R.string.alarm_ringing);
        }
    }

    private void setupAlarmWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            );
        }
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
        );

        KeyguardManager keyguard = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguard != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            keyguard.requestDismissKeyguard(this, null);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(AlarmRingService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
    }

    @Override
    protected void onStop() {
        try {
            unregisterReceiver(statusReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        super.onStop();
    }
}
