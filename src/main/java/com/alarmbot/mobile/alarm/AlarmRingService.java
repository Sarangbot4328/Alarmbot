package com.alarmbot.mobile.alarm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
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

    private static final String CHANNEL_ID = "alarm_ring_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final long NEXT_TRACK_DELAY_MS = 60_000L;
    private static final String TAG = "AlarmRingService";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private MediaPlayer mediaPlayer;
    private PowerManager.WakeLock wakeLock;
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

        startInForeground();
        acquireWakeLock();
        launchRingActivity();
        playCurrentTrack();

        // Daily alarms: schedule the next occurrence while this one is ringing.
        AlarmScheduler.rescheduleAfterRing(this, alarmItem);
        return START_STICKY;
    }

    private void startInForeground() {
        Intent fullScreen = new Intent(this, AlarmRingActivity.class);
        fullScreen.putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId);
        fullScreen.putExtra(EXTRA_ALARM_TIME, alarmItem.formattedTime());
        fullScreen.putExtra(EXTRA_ALARM_LABEL, alarmItem.label);
        fullScreen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        PendingIntent fullScreenPi = PendingIntent.getActivity(
                this,
                alarmId.hashCode(),
                fullScreen,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

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
                .setOngoing(true)
                .setFullScreenIntent(fullScreenPi, true)
                .setContentIntent(fullScreenPi)
                .addAction(0, getString(R.string.dismiss_alarm), dismissPi)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void launchRingActivity() {
        Intent activity = new Intent(this, AlarmRingActivity.class);
        activity.putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId);
        activity.putExtra(EXTRA_ALARM_TIME, alarmItem.formattedTime());
        activity.putExtra(EXTRA_ALARM_LABEL, alarmItem.label);
        activity.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(activity);
        broadcastStatus();
    }

    private void playCurrentTrack() {
        if (dismissed) return;
        waitingForNext = false;
        releasePlayer();

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
            mediaPlayer.setOnCompletionListener(mp -> onTrackCompleted());
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error what=" + what + " extra=" + extra);
                scheduleNextAfterDelay();
                return true;
            });
            mediaPlayer.prepare();
            mediaPlayer.start();
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
            status = "세트 " + currentSet + " · " + currentTrack + "번 대기 중 (1분 후)";
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
        releaseWakeLock();
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
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    @Override
    public void onDestroy() {
        dismissed = true;
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
