package com.musibility.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlaybackService extends Service {
    public static final String ACTION_PLAY = "com.musibility.app.PLAY";
    public static final String ACTION_TOGGLE = "com.musibility.app.TOGGLE";
    public static final String ACTION_NEXT = "com.musibility.app.NEXT";
    public static final String ACTION_PREVIOUS = "com.musibility.app.PREVIOUS";
    public static final String ACTION_SEEK = "com.musibility.app.SEEK";
    public static final String ACTION_SET_SHUFFLE = "com.musibility.app.SET_SHUFFLE";
    public static final String ACTION_STATE = "com.musibility.app.PLAYBACK_STATE";
    public static final String EXTRA_QUEUE = "queue";
    public static final String EXTRA_INDEX = "index";
    public static final String EXTRA_SHUFFLE = "shuffle";

    private static final String CHANNEL_ID = "musibility_playback";
    private final ExecutorService fallbackWorker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler();
    private final ArrayList<Song> queue = new ArrayList<>();
    private MediaPlayer player;
    private int currentIndex = -1;
    private boolean shuffle;
    private boolean preparing;
    private boolean fallbackAttempted;
    private boolean fallbackInProgress;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            publishState();
            if (player != null && player.isPlaying()) handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        Song activeSong = currentIndex >= 0 && currentIndex < queue.size() ? queue.get(currentIndex) : null;
        startForeground(1, activeSong == null ? notification("Musibility", "Đang chuẩn bị phát nhạc")
                : notification(activeSong.title, activeSong.artist));
        String action = intent.getAction();
        if (ACTION_PLAY.equals(action)) {
            Object value = intent.getSerializableExtra(EXTRA_QUEUE);
            if (value instanceof ArrayList<?>) {
                queue.clear();
                for (Object item : (ArrayList<?>) value) if (item instanceof Song) queue.add((Song) item);
            }
            currentIndex = intent.getIntExtra(EXTRA_INDEX, 0);
            shuffle = intent.getBooleanExtra(EXTRA_SHUFFLE, false);
            fallbackAttempted = false;
            fallbackInProgress = false;
            playCurrent();
        } else if (ACTION_TOGGLE.equals(action)) {
            if (player == null && currentIndex < 0) {
                stopForeground(true);
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            if (player != null && player.isPlaying()) player.pause();
            else if (player != null && !preparing) player.start();
            else if (player == null && currentIndex >= 0) playCurrent();
            publishState();
        } else if (ACTION_NEXT.equals(action)) {
            playNext(true);
        } else if (ACTION_PREVIOUS.equals(action)) {
            playNext(false);
        } else if (ACTION_SEEK.equals(action) && player != null && !preparing) {
            player.seekTo(Math.max(0, intent.getIntExtra("position", 0)));
            publishState();
        } else if (ACTION_SET_SHUFFLE.equals(action)) {
            shuffle = intent.getBooleanExtra(EXTRA_SHUFFLE, false);
        }
        return START_STICKY;
    }

    private void playCurrent() {
        if (currentIndex < 0 || currentIndex >= queue.size()) {
            publishState();
            return;
        }
        Song song = queue.get(currentIndex);
        if (song.audioUrl.isEmpty()) {
            publishState();
            return;
        }
        releasePlayer();
        preparing = true;
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            Uri source = Uri.parse(song.audioUrl);
            if ("content".equals(source.getScheme())) player.setDataSource(this, source);
            else player.setDataSource(song.audioUrl);
            player.setOnPreparedListener(mp -> {
                preparing = false;
                mp.start();
                updateNotification();
                publishState();
                handler.removeCallbacks(ticker);
                handler.post(ticker);
            });
            player.setOnCompletionListener(mp -> playNext(true));
            player.setOnErrorListener((mp, what, extra) -> {
                preparing = false;
                Log.e("Musibility", "Playback error: " + what + "/" + extra);
                if ("Audius".equals(song.provider) && !fallbackAttempted) tryJamendoFallback(song);
                else playNext(true);
                return true;
            });
            player.prepareAsync();
            publishState();
        } catch (Exception e) {
            preparing = false;
            Log.e("Musibility", "Could not start playback", e);
            if ("Audius".equals(song.provider) && !fallbackAttempted) tryJamendoFallback(song);
            else publishState();
        }
    }

    private void tryJamendoFallback(Song failedSong) {
        if (fallbackAttempted || fallbackInProgress) return;
        fallbackAttempted = true;
        fallbackInProgress = true;
        int failedIndex = currentIndex;
        fallbackWorker.execute(() -> {
            Song fallback = null;
            Exception failure = null;
            try {
                java.util.List<Song> candidates = JamendoApi.searchTracks(failedSong.title + " " + failedSong.artist);
                String target = normalize(failedSong.title);
                for (Song candidate : candidates) {
                    if (normalize(candidate.title).equals(target)) {
                        fallback = candidate;
                        break;
                    }
                }
                if (fallback == null && !candidates.isEmpty()) fallback = candidates.get(0);
                if (fallback == null) failure = new java.io.IOException("Không tìm thấy bản thay thế trên Jamendo.");
            } catch (Exception e) {
                failure = e;
            }
            Song selectedFallback = fallback;
            Exception fallbackFailure = failure;
            handler.post(() -> {
                fallbackInProgress = false;
                if (currentIndex != failedIndex || queue.isEmpty() || queue.get(currentIndex) != failedSong) return;
                if (selectedFallback != null) {
                    Log.i("Musibility", "Audius playback failed; switching to Jamendo fallback");
                    queue.set(currentIndex, selectedFallback);
                    playCurrent();
                } else {
                    Log.w("Musibility", "Jamendo fallback unavailable", fallbackFailure);
                    playNext(true);
                }
            });
        });
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ").trim();
    }

    private void playNext(boolean next) {
        if (queue.isEmpty()) return;
        if (shuffle && queue.size() > 1 && next) {
            int old = currentIndex;
            do { currentIndex = (int) (Math.random() * queue.size()); } while (currentIndex == old);
        } else {
            currentIndex += next ? 1 : -1;
            if (currentIndex >= queue.size()) currentIndex = 0;
            if (currentIndex < 0) currentIndex = queue.size() - 1;
        }
        fallbackAttempted = false;
        fallbackInProgress = false;
        playCurrent();
    }

    private void releasePlayer() {
        handler.removeCallbacks(ticker);
        if (player != null) {
            try { player.reset(); player.release(); } catch (Exception e) {
                Log.w("Musibility", "Could not release player", e);
            }
            player = null;
        }
    }

    private void publishState() {
        Song song = currentIndex >= 0 && currentIndex < queue.size() ? queue.get(currentIndex) : null;
        boolean isPlaying = player != null && player.isPlaying();
        int position = 0;
        int duration = 0;
        if (player != null) {
            try { position = player.getCurrentPosition(); duration = player.getDuration(); }
            catch (IllegalStateException ignored) { }
        }
        getSharedPreferences("musibility_widget", MODE_PRIVATE).edit()
                .putString("title", song == null ? "Musibility" : song.title)
                .putString("artist", song == null ? "Chạm để khám phá âm nhạc" : song.artist)
                .putString("cover", song == null ? "" : song.coverUrl)
                .putBoolean("playing", isPlaying)
                .putInt("position", position)
                .putInt("duration", duration)
                .apply();
        Intent state = new Intent(ACTION_STATE).setPackage(getPackageName());
        state.putExtra("title", song == null ? "" : song.title);
        state.putExtra("artist", song == null ? "" : song.artist);
        state.putExtra("song", song);
        state.putExtra("playing", isPlaying);
        state.putExtra("position", position);
        state.putExtra("duration", duration);
        sendBroadcast(state);
        MusicWidgetProvider.refresh(this);
        if (song != null) updateNotification();
    }

    private void updateNotification() {
        Song song = currentIndex >= 0 && currentIndex < queue.size() ? queue.get(currentIndex) : null;
        if (song != null) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.notify(1, notification(song.title, song.artist));
        }
    }

    private Notification notification(String title, String artist) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent toggle = new Intent(this, PlaybackService.class).setAction(ACTION_TOGGLE);
        PendingIntent playPause = PendingIntent.getService(this, 1, toggle,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(artist)
                .setContentIntent(content)
                .addAction(android.R.drawable.ic_media_pause, "Phát/Tạm dừng", playPause)
                .setOngoing(player != null && player.isPlaying())
                .setOnlyAlertOnce(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Phát nhạc",
                    NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        releasePlayer();
        fallbackWorker.shutdownNow();
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
