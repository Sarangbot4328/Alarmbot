package com.alarmbot.mobile.alarm;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
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

    private static final String CHANNEL_ID = "alarm_ring_channel_v2";
    private static final int NOTIFICATION_ID = 1001;
    private static final long NEXT_TRACK_DELAY_MS = 5_000L;
    private static final String TAG = "AlarmRingService";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private MediaPlayer mediaPlayer;
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

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_DISMISS.equals(action)) {
            dismissAndStop();
            return START_NOT_STICKY;
        }

        if (!ACTION_START.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        alarmId = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID);
        alarmItem = AlarmStore.getById(this, alarmId);
        if (alarmItem == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        dismissed = false;
        waitingForNext = false;
        voicePack = VoiceCatalog.get(AppSettings.getVoiceId(this));
        currentSet = randomSet();
        currentTrack = 1;

        forceMaxAlarmVolume();
        startInForeground();
        acquireWakeLock();
        launchRingActivityWithRetry();
        playCurrentTrack();

        AlarmScheduler.rescheduleAfterRing(this, alarmItem);
        return START_STICKY;
    }

    private void startInForeground() {
        PendingIntent fullScreenPi = ringActivityPendingIntent();

        Intent dismissIntent = new Intent(this, AlarmRingService.class);
        dismissIntent.setAction(ACTION_DISMISS);
        PendingIntent dismissPi = PendingIntent.getService(
                this,
                2,
                dismissIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle(getString(R.string.alarm_ringing))
                .setContentText(alarmItem.formattedTime()
                        + (alarmItem.label.isEmpty() ? "" : " · " + alarmItem.label))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setFullScreenIntent(fullScreenPi, true)
                .setContentIntent(fullScreenPi)
                .addAction(0, getString(R.string.dismiss_alarm), dismissPi)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        // Re-post so full-screen intent fires even if FGS start alone was insufficient.
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, notification);
    }

    private PendingIntent ringActivityPendingIntent() {
        Intent fullScreen = ringActivityIntent();
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        if (Build.VERSION.SDK_INT >= 34) {
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            return PendingIntent.getActivity(
                    this,
                    alarmId.hashCode(),
                    fullScreen,
                    flags,
                    options.toBundle()
            );
        }
        return PendingIntent.getActivity(this, alarmId.hashCode(), fullScreen, flags);
    }

    private Intent ringActivityIntent() {
        Intent activity = new Intent(this, AlarmRingActivity.class);
        activity.putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId);
        activity.putExtra(EXTRA_ALARM_TIME, alarmItem.formattedTime());
        activity.putExtra(EXTRA_ALARM_LABEL, alarmItem.label);
        activity.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_NO_USER_ACTION
        );
        return activity;
    }

    private void launchRingActivityWithRetry() {
        launchRingActivity();
        // Background start can be blocked once; retry a few times while ringing.
        handler.postDelayed(this::launchRingActivity, 400);
        handler.postDelayed(this::launchRingActivity, 1200);
        handler.postDelayed(this::launchRingActivity, 2500);
    }

    private void launchRingActivity() {
        if (dismissed || alarmItem == null) return;
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
            previousAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM);
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, max, 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (audioManager.isStreamMute(AudioManager.STREAM_ALARM)) {
                    audioManager.adjustStreamVolume(
                            AudioManager.STREAM_ALARM, AudioManager.ADJUST_UNMUTE, 0);
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, max, 0);
                }
            }
            requestAlarmAudioFocus();
        } catch (Exception e) {
            Log.w(TAG, "Failed to force max alarm volume", e);
        }
    }

    private void requestAlarmAudioFocus() {
        if (audioManager == null) return;
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(focusChange -> { })
                    .build();
            audioManager.requestAudioFocus(focusRequest);
        } else {
            audioManager.requestAudioFocus(
                    focusChange -> { },
                    AudioManager.STREAM_ALARM,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            );
        }
    }

    private void restoreAlarmVolume() {
        if (audioManager == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest);
                focusRequest = null;
            } else {
                audioManager.abandonAudioFocus(focusChange -> { });
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
        if (dismissed) return;
        waitingForNext = false;
        releasePlayer();
        forceMaxAlarmVolume();

        String path = voicePack.trackAssetPath(currentSet, currentTrack);
        android.content.res.AssetFileDescriptor afd = null;
        try {
            afd = getAssets().openFd(path);
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
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
        } catch (IOException e) {
            Log.e(TAG, "Failed to play " + path, e);
            scheduleNextAfterDelay();
        } finally {
            if (afd != null) {
                try {
                    afd.close();
                } catch (IOException ignored) {
                }
            }
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
        // Keep trying to bring dismiss UI forward while waiting.
        handler.postDelayed(this::launchRingActivity, 500);
    }

    private void advanceTrackPointer() {
        if (currentTrack < voicePack.trackCount) {
            currentTrack += 1;
            return;
        }
        currentSet = randomSet();
        currentTrack = 1;
    }

    private int randomSet() {
        return 1 + ThreadLocalRandom.current().nextInt(voicePack.setCount);
    }

    private void broadcastStatus() {
        String status;
        if (waitingForNext) {
            status = "세트 " + currentSet + " · " + currentTrack + "번 대기 중 (5초 후)";
        } else {
            status = "세트 " + currentSet + " · " + currentTrack + "번 재생 중";
        }
        Intent statusIntent = new Intent(ACTION_STATUS);
        statusIntent.setPackage(getPackageName());
        statusIntent.putExtra(EXTRA_STATUS_TEXT, status);
        statusIntent.putExtra(EXTRA_ALARM_TIME, alarmItem != null ? alarmItem.formattedTime() : "");
        statusIntent.putExtra(EXTRA_ALARM_LABEL, alarmItem != null ? alarmItem.label : "");
        sendBroadcast(statusIntent);
    }

    private void dismissAndStop() {
        dismissed = true;
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        restoreAlarmVolume();
        releaseWakeLock();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void releasePlayer() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
            } catch (IllegalStateException ignored) {
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "alarmbot:ring");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(6 * 60 * 60 * 1000L);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
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
