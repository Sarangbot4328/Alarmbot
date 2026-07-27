package com.alarmbot.mobile.alarm;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.alarmbot.mobile.R;
import com.alarmbot.mobile.settings.AppSettings;
import com.alarmbot.mobile.ui.SystemBarInsets;
import com.alarmbot.mobile.voice.VoiceCatalog;

import java.util.Calendar;

public final class AlarmEditActivity extends AppCompatActivity {
    public static final String EXTRA_ALARM_ID = "edit_alarm_id";

    private TimePicker timePicker;
    private EditText labelInput;
    private TextView repeatSummary;
    private TextView[] dayViews;
    private TextView presetOnce;
    private TextView presetEveryday;
    private TextView presetWeekdays;
    private TextView presetWeekend;
    private Button deleteButton;

    private String editingId;
    private int daysMask = AlarmItem.MASK_NONE;
    private boolean existingEnabled = true;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_alarm_edit);
        SystemBarInsets.apply(this, findViewById(R.id.edit_root), true);

        timePicker = findViewById(R.id.time_picker);
        timePicker.setIs24HourView(true);
        labelInput = findViewById(R.id.alarm_label);
        repeatSummary = findViewById(R.id.repeat_summary);
        deleteButton = findViewById(R.id.btn_delete);
        TextView title = findViewById(R.id.edit_title);

        dayViews = new TextView[]{
                findViewById(R.id.day_mon),
                findViewById(R.id.day_tue),
                findViewById(R.id.day_wed),
                findViewById(R.id.day_thu),
                findViewById(R.id.day_fri),
                findViewById(R.id.day_sat),
                findViewById(R.id.day_sun)
        };
        presetOnce = findViewById(R.id.preset_once);
        presetEveryday = findViewById(R.id.preset_everyday);
        presetWeekdays = findViewById(R.id.preset_weekdays);
        presetWeekend = findViewById(R.id.preset_weekend);

        for (int i = 0; i < dayViews.length; i++) {
            final int calendarDay = AlarmItem.DAY_ORDER[i];
            dayViews[i].setOnClickListener(v -> {
                boolean next = !((daysMask & AlarmItem.bit(calendarDay)) != 0);
                if (next) daysMask |= AlarmItem.bit(calendarDay);
                else daysMask &= ~AlarmItem.bit(calendarDay);
                refreshDayUi();
            });
        }

        presetOnce.setOnClickListener(v -> {
            daysMask = AlarmItem.MASK_NONE;
            refreshDayUi();
        });
        presetEveryday.setOnClickListener(v -> {
            daysMask = AlarmItem.MASK_EVERY_DAY;
            refreshDayUi();
        });
        presetWeekdays.setOnClickListener(v -> {
            daysMask = AlarmItem.MASK_WEEKDAYS;
            refreshDayUi();
        });
        presetWeekend.setOnClickListener(v -> {
            daysMask = AlarmItem.MASK_WEEKEND;
            refreshDayUi();
        });

        findViewById(R.id.btn_cancel).setOnClickListener(v -> finish());
        findViewById(R.id.btn_save).setOnClickListener(v -> save());
        deleteButton.setOnClickListener(v -> confirmDelete());

        editingId = getIntent().getStringExtra(EXTRA_ALARM_ID);
        if (editingId != null) {
            AlarmItem existing = AlarmStore.getById(this, editingId);
            if (existing == null) {
                finish();
                return;
            }
            title.setText(R.string.edit_alarm);
            deleteButton.setVisibility(View.VISIBLE);
            timePicker.setHour(existing.hour);
            timePicker.setMinute(existing.minute);
            labelInput.setText(existing.label);
            daysMask = existing.daysMask;
            existingEnabled = existing.enabled;
        } else {
            title.setText(R.string.add_alarm);
            Calendar now = Calendar.getInstance();
            timePicker.setHour(now.get(Calendar.HOUR_OF_DAY));
            timePicker.setMinute(now.get(Calendar.MINUTE));
            daysMask = AlarmItem.MASK_NONE;
            deleteButton.setVisibility(View.GONE);
        }
        refreshDayUi();
    }

    private void refreshDayUi() {
        int selectedText = ContextCompat.getColor(this, android.R.color.white);
        int idleText = ContextCompat.getColor(this, R.color.text_primary);
        for (int i = 0; i < dayViews.length; i++) {
            boolean on = (daysMask & AlarmItem.bit(AlarmItem.DAY_ORDER[i])) != 0;
            dayViews[i].setSelected(on);
            dayViews[i].setTextColor(on ? selectedText : idleText);
        }

        presetOnce.setSelected(daysMask == AlarmItem.MASK_NONE);
        presetEveryday.setSelected(daysMask == AlarmItem.MASK_EVERY_DAY);
        presetWeekdays.setSelected(daysMask == AlarmItem.MASK_WEEKDAYS);
        presetWeekend.setSelected(daysMask == AlarmItem.MASK_WEEKEND);

        presetOnce.setTextColor(presetOnce.isSelected() ? selectedText : idleText);
        presetEveryday.setTextColor(presetEveryday.isSelected() ? selectedText : idleText);
        presetWeekdays.setTextColor(presetWeekdays.isSelected() ? selectedText : idleText);
        presetWeekend.setTextColor(presetWeekend.isSelected() ? selectedText : idleText);

        AlarmItem temp = new AlarmItem("tmp", 0, 0, "", true, daysMask);
        repeatSummary.setText(temp.repeatLabel(this));
    }

    private void save() {
        int hour = timePicker.getHour();
        int minute = timePicker.getMinute();
        String label = labelInput.getText() != null ? labelInput.getText().toString().trim() : "";

        AlarmItem item;
        if (editingId != null) {
            item = new AlarmItem(editingId, hour, minute, label, existingEnabled, daysMask);
        } else {
            item = AlarmItem.create(hour, minute, label, daysMask);
        }
        AlarmStore.upsert(this, item);
        if (item.enabled) AlarmScheduler.schedule(this, item);
        else AlarmScheduler.cancel(this, item.id);

        String voice = VoiceCatalog.get(AppSettings.getVoiceId(this)).displayName;
        Toast.makeText(this, item.formattedTime() + " · " + item.repeatLabel(this) + " · " + voice,
                Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    private void confirmDelete() {
        if (editingId == null) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete)
                .setMessage("이 알람을 삭제할까요?")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    AlarmScheduler.cancel(this, editingId);
                    AlarmStore.delete(this, editingId);
                    setResult(RESULT_OK);
                    finish();
                })
                .show();
    }
}
