package com.alarmbot.mobile.alarm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.alarmbot.mobile.R;
import com.alarmbot.mobile.settings.AppSettings;
import com.alarmbot.mobile.voice.VoiceCatalog;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;

public final class AlarmRingService extends Service {
    public static final String ACTION_START = "com.alarmbot.mobile.RING_START";
    public static final String ACTION_DISMISS = "com.alarmbot.mobile.RING_DISMISS";
    public static final String ACTION_STATUS = "com.alarmbot.mobile.RING_STATUS";
    public static final String EXTRA_STATUS_TEXT = "status_text";
    public static final String EXTRA_ALARM_LABEL = "alarm_label";
    public static final String EXTRA_ALARM_TIME = "alarm_time";

    private static final String CHANNEL_ID = "alarm_ring_channel_v3";
    private static final int NOTIFICATION_ID = 1001;
    private static final long NEXT_TRACK_DELAY_MS = 5_000L;
    private static final String TAG = "AlarmRingService";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private MediaPlayer mediaPlayer;
    private AssetFileDescriptor openAfd;
    private PowerManager.WakeLock wakeLock;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private int previousAlarmVolume = -1;
    private String alarmId;
    private AlarmItem alarmItem;
    private VoiceCatalog.VoicePack voicePack;
    private int currentSet;
    private int currentTrack;
    private boolean dismissed;
    private boolean waitingForNext;
    private boolean foregroundStarted;

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            // Must call startForeground quickly after startForegroundService, or the system kills the app.
            ensureForegroundStarted("알람");

            if (intent == null) {
                stopSafely();
                return START_NOT_STICKY;
            }

            String action = intent.getAction();
            if (ACTION_DISMISS.equals(action)) {
                dismissAndStop();
                return START_NOT_STICKY;
            }

            if (!ACTION_START.equals(action)) {
                stopSafely();
                return START_NOT_STICKY;
            }

            alarmId = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID);
            alarmItem = AlarmStore.getById(this, alarmId);
            if (alarmItem == null) {
                stopSafely();
                return START_NOT_STICKY;
            }

            dismissed = false;
            waitingForNext = false;
            voicePack = VoiceCatalog.get(AppSettings.getVoiceId(this));
            currentSet = randomSet();
            currentTrack = 1;

            ensureForegroundStarted(alarmItem.formattedTime());
            forceMaxAlarmVolume();
            acquireWakeLock();
            launchRingActivityWithRetry();
            playCurrentTrack();
            AlarmScheduler.rescheduleAfterRing(this, alarmItem);
            return START_STICKY;
        } catch (Throwable t) {
            Log.e(TAG, "onStartCommand failed", t);
            try {
                ensureForegroundStarted("알람");
            } catch (Throwable ignored) {
            }
            stopSafely();
            return START_NOT_STICKY;
        }
    }

    private void ensureForegroundStarted(String contentText) {
        Notification notification = buildNotification(contentText);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        foregroundStarted = true;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, notification);
    }

    private Notification buildNotification(String contentText) {
        PendingIntent fullScreenPi = ringActivityPendingIntent();
        PendingIntent dismissPi = PendingIntent.getService(
                this,
                2,
                new Intent(this, AlarmRingService.class).setAction(ACTION_DISMISS),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String title = getString(R.string.alarm_ringing);
        String text = contentText == null ? title : contentText;
        if (alarmItem != null && !alarmItem.label.isEmpty()) {
            text = alarmItem.formattedTime() + " · " + alarmItem.label;
        } else if (alarmItem != null) {
            text = alarmItem.formattedTime();
        }

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setFullScreenIntent(fullScreenPi, true)
                .setContentIntent(fullScreenPi)
                .addAction(0, getString(R.string.dismiss_alarm), dismissPi)
                .build();
    }

    private PendingIntent ringActivityPendingIntent() {
        Intent fullScreen = ringActivityIntent();
        int requestCode = alarmId != null ? alarmId.hashCode() : 1;
        return PendingIntent.getActivity(
                this,
                requestCode,
                fullScreen,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private Intent ringActivityIntent() {
        Intent activity = new Intent(this, AlarmRingActivity.class);
        if (alarmId != null) activity.putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId);
        if (alarmItem != null) {
            activity.putExtra(EXTRA_ALARM_TIME, alarmItem.formattedTime());
            activity.putExtra(EXTRA_ALARM_LABEL, alarmItem.label);
        }
        activity.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        );
        return activity;
    }

    private void launchRingActivityWithRetry() {
        launchRingActivity();
        handler.postDelayed(this::launchRingActivity, 500);
        handler.postDelayed(this::launchRingActivity, 1500);
    }

    private void launchRingActivity() {
        if (dismissed) return;
        try {
            startActivity(ringActivityIntent());
        } catch (Exception e) {
            Log.w(TAG, "startActivity for ring UI failed", e);
        }
        broadcastStatus();
    }

    private void forceMaxAlarmVolume() {
        if (audioManager == null) return;
        try {
            if (previousAlarmVolume < 0) {
                previousAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM);
            }
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            if (max > 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, max, 0);
            }
            requestAlarmAudioFocus();
        } catch (Exception e) {
            Log.w(TAG, "Failed to force max alarm volume", e);
        }
    }

    private void requestAlarmAudioFocus() {
        if (audioManager == null) return;
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(attrs)
                        .setOnAudioFocusChangeListener(focusChange -> { })
                        .build();
                audioManager.requestAudioFocus(focusRequest);
            } else {
                audioManager.requestAudioFocus(
                        focusChange -> { },
                        AudioManager.STREAM_ALARM,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                );
            }
        } catch (Exception e) {
            Log.w(TAG, "audio focus failed", e);
        }
    }

    private void restoreAlarmVolume() {
        if (audioManager == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest);
                focusRequest = null;
            }
            if (previousAlarmVolume >= 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, previousAlarmVolume, 0);
                previousAlarmVolume = -1;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to restore alarm volume", e);
        }
    }

    private void playCurrentTrack() {
        if (dismissed || voicePack == null) return;
        waitingForNext = false;
        releasePlayer();
        forceMaxAlarmVolume();

        String path = voicePack.trackAssetPath(currentSet, currentTrack);
        try {
            openAfd = getAssets().openFd(path);
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mediaPlayer.setDataSource(openAfd.getFileDescriptor(), openAfd.getStartOffset(), openAfd.getLength());
            mediaPlayer.setVolume(1f, 1f);
            mediaPlayer.setOnCompletionListener(mp -> onTrackCompleted());
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error what=" + what + " extra=" + extra);
                scheduleNextAfterDelay();
                return true;
            });
            mediaPlayer.prepare();
            mediaPlayer.start();
            launchRingActivity();
            broadcastStatus();
        } catch (Exception e) {
            Log.e(TAG, "Failed to play " + path, e);
            releasePlayer();
            scheduleNextAfterDelay();
        }
    }

    private void onTrackCompleted() {
        if (dismissed) return;
        scheduleNextAfterDelay();
    }

    private void scheduleNextAfterDelay() {
        if (dismissed) return;
        waitingForNext = true;
        advanceTrackPointer();
        broadcastStatus();
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(() -> {
            if (!dismissed) playCurrentTrack();
        }, NEXT_TRACK_DELAY_MS);
        handler.postDelayed(this::launchRingActivity, 500);
    }

    private void advanceTrackPointer() {
        if (voicePack != null && currentTrack < voicePack.trackCount) {
            currentTrack += 1;
            return;
        }
        currentSet = randomSet();
        currentTrack = 1;
    }

    private int randomSet() {
        int count = voicePack != null ? Math.max(1, voicePack.setCount) : 1;
        return 1 + ThreadLocalRandom.current().nextInt(count);
    }

    private void broadcastStatus() {
        if (alarmItem == null) return;
        String status;
        if (waitingForNext) {
            status = "세트 " + currentSet + " · " + currentTrack + "번 대기 중 (5초 후)";
        } else {
            status = "세트 " + currentSet + " · " + currentTrack + "번 재생 중";
        }
        Intent statusIntent = new Intent(ACTION_STATUS);
        statusIntent.setPackage(getPackageName());
        statusIntent.putExtra(EXTRA_STATUS_TEXT, status);
        statusIntent.putExtra(EXTRA_ALARM_TIME, alarmItem.formattedTime());
        statusIntent.putExtra(EXTRA_ALARM_LABEL, alarmItem.label);
        sendBroadcast(statusIntent);
    }

    private void dismissAndStop() {
        dismissed = true;
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        restoreAlarmVolume();
        releaseWakeLock();
        stopSafely();
    }

    private void stopSafely() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(NOTIFICATION_ID);
            if (foregroundStarted) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                foregroundStarted = false;
            }
        } catch (Exception ignored) {
        }
        stopSelf();
    }

    private void releasePlayer() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.setOnCompletionListener(null);
                mediaPlayer.setOnErrorListener(null);
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
            } catch (Exception ignored) {
            }
            try {
                mediaPlayer.release();
            } catch (Exception ignored) {
            }
            mediaPlayer = null;
        }
        if (openAfd != null) {
            try {
                openAfd.close();
            } catch (IOException ignored) {
            }
            openAfd = null;
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "alarmbot:ring");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) {
                wakeLock.acquire(6 * 60 * 60 * 1000L);
            }
        } catch (Exception e) {
            Log.w(TAG, "wake lock failed", e);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {
        }
        wakeLock = null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.alarm_channel_name),
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription(getString(R.string.alarm_ringing));
        channel.setBypassDnd(true);
        channel.enableVibration(true);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        channel.setSound(null, null);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    @Override
    public void onDestroy() {
        dismissed = true;
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        restoreAlarmVolume();
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
