package com.alarmbot.mobile.ui;

import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alarmbot.mobile.MainActivity;
import com.alarmbot.mobile.R;
import com.alarmbot.mobile.alarm.AlarmEditActivity;
import com.alarmbot.mobile.alarm.AlarmItem;
import com.alarmbot.mobile.alarm.AlarmScheduler;
import com.alarmbot.mobile.alarm.AlarmStore;

import java.util.ArrayList;
import java.util.List;

public final class AlarmChannelView extends FrameLayout {
    private final MainActivity activity;
    private final TextView title;
    private final Button switchMode;
    private final LinearLayout alarmPanel;
    private final TextView timerPanel;
    private final TextView emptyView;
    private final AlarmAdapter adapter;
    private boolean timerMode;

    public AlarmChannelView(MainActivity activity) {
        super(activity);
        this.activity = activity;
        LayoutInflater.from(activity).inflate(R.layout.channel_alarm, this, true);
        title = findViewById(R.id.alarm_title);
        switchMode = findViewById(R.id.btn_switch_mode);
        alarmPanel = findViewById(R.id.alarm_mode_panel);
        timerPanel = findViewById(R.id.timer_mode_panel);
        emptyView = findViewById(R.id.empty_alarms);
        RecyclerView list = findViewById(R.id.alarm_list);
        Button add = findViewById(R.id.btn_add_alarm);

        adapter = new AlarmAdapter();
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setAdapter(adapter);

        add.setOnClickListener(v -> openEditor(null));
        switchMode.setOnClickListener(v -> toggleMode());
        refresh();
    }

    public void refresh() {
        List<AlarmItem> items = AlarmStore.getAll(activity);
        adapter.submit(items);
        emptyView.setVisibility(items.isEmpty() ? VISIBLE : GONE);
    }

    private void toggleMode() {
        timerMode = !timerMode;
        alarmPanel.setVisibility(timerMode ? GONE : VISIBLE);
        timerPanel.setVisibility(timerMode ? VISIBLE : GONE);
        title.setText(timerMode ? "타이머" : activity.getString(R.string.tab_alarm));
        switchMode.setText(timerMode ? R.string.switch_to_alarm : R.string.switch_to_timer);
    }

    private void openEditor(String alarmId) {
        Intent intent = new Intent(activity, AlarmEditActivity.class);
        if (alarmId != null) intent.putExtra(AlarmEditActivity.EXTRA_ALARM_ID, alarmId);
        activity.startActivity(intent);
    }

    private void toggleEnabled(AlarmItem item, boolean enabled) {
        item.enabled = enabled;
        AlarmStore.upsert(activity, item);
        if (enabled) AlarmScheduler.schedule(activity, item);
        else AlarmScheduler.cancel(activity, item.id);
        refresh();
    }

    private void confirmDelete(AlarmItem item) {
        new AlertDialog.Builder(activity)
                .setTitle(item.formattedTime())
                .setMessage("이 알람을 삭제할까요?")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    AlarmScheduler.cancel(activity, item.id);
                    AlarmStore.delete(activity, item.id);
                    refresh();
                })
                .show();
    }

    private final class AlarmAdapter extends RecyclerView.Adapter<AlarmAdapter.Holder> {
        private final List<AlarmItem> items = new ArrayList<>();

        void submit(List<AlarmItem> next) {
            items.clear();
            items.addAll(next);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.row_alarm, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            AlarmItem item = items.get(position);
            holder.time.setText(item.formattedTime());
            holder.meta.setText(item.listMeta(activity));
            holder.enabled.setOnCheckedChangeListener(null);
            holder.enabled.setChecked(item.enabled);
            holder.enabled.setOnCheckedChangeListener((buttonView, isChecked) -> toggleEnabled(item, isChecked));
            holder.itemView.setOnClickListener(v -> openEditor(item.id));
            holder.itemView.setOnLongClickListener(v -> {
                confirmDelete(item);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final TextView time;
            final TextView meta;
            final SwitchCompat enabled;

            Holder(@NonNull View itemView) {
                super(itemView);
                time = itemView.findViewById(R.id.alarm_time);
                meta = itemView.findViewById(R.id.alarm_meta);
                enabled = itemView.findViewById(R.id.alarm_enabled);
            }
        }
    }
}
