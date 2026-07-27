package com.alarmbot.mobile.ui;

import android.view.LayoutInflater;
import android.widget.FrameLayout;

import com.alarmbot.mobile.MainActivity;
import com.alarmbot.mobile.R;

public final class TodoChannelView extends FrameLayout {
    public TodoChannelView(MainActivity activity) {
        super(activity);
        LayoutInflater.from(activity).inflate(R.layout.channel_todo, this, true);
    }
}
