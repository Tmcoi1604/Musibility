package com.musibility.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.RemoteViews;

import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MusicWidgetProvider extends AppWidgetProvider {
    private static final ExecutorService IMAGE_WORKER = Executors.newSingleThreadExecutor();
    private static volatile Bitmap cachedCover;
    private static volatile String cachedCoverUrl = "";

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        String serviceAction = null;
        if ("com.musibility.app.WIDGET_PLAY_PAUSE".equals(action)) serviceAction = PlaybackService.ACTION_TOGGLE;
        if ("com.musibility.app.WIDGET_NEXT".equals(action)) serviceAction = PlaybackService.ACTION_NEXT;
        if ("com.musibility.app.WIDGET_PREVIOUS".equals(action)) serviceAction = PlaybackService.ACTION_PREVIOUS;
        if (serviceAction != null) {
            Intent service = new Intent(context, PlaybackService.class).setAction(serviceAction);
            if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
            else context.startService(service);
            return;
        }
        if (AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action)) {
            int[] ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS);
            if (ids == null) refresh(context);
            else onUpdate(context, AppWidgetManager.getInstance(context), ids);
            return;
        }
        super.onReceive(context, intent);
    }

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        clearArtworkIfNeeded(context);
        for (int id : ids) update(context, manager, id, cachedCover);
        loadCover(context, manager);
    }

    private static void update(Context context, AppWidgetManager manager, int id, Bitmap cover) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.music_widget);
        android.content.SharedPreferences state = context.getSharedPreferences("musibility_widget", Context.MODE_PRIVATE);
        views.setTextViewText(R.id.widget_artist, state.getString("artist", "Chạm để khám phá âm nhạc"));
        int duration = state.getInt("duration", 0);
        int position = state.getInt("position", 0);
        views.setProgressBar(R.id.widget_progress, 1000,
                duration <= 0 ? 0 : Math.min(1000, (int) ((long) position * 1000 / duration)), false);
        views.setImageViewResource(R.id.widget_play, state.getBoolean("playing", false)
                ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        if (cover != null) views.setImageViewBitmap(R.id.widget_cover, cover);
        setAction(context, views, R.id.widget_previous, "com.musibility.app.WIDGET_PREVIOUS", 12);
        setAction(context, views, R.id.widget_play, "com.musibility.app.WIDGET_PLAY_PAUSE", 13);
        setAction(context, views, R.id.widget_next, "com.musibility.app.WIDGET_NEXT", 14);
        manager.updateAppWidget(id, views);
    }

    private static void setAction(Context context, RemoteViews views, int viewId, String action, int requestCode) {
        Intent intent = new Intent(context, MusicWidgetProvider.class).setAction(action);
        PendingIntent pending = PendingIntent.getBroadcast(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(viewId, pending);
    }

    static void refresh(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, MusicWidgetProvider.class));
        clearArtworkIfNeeded(context);
        for (int id : ids) update(context, manager, id, cachedCover);
        loadCover(context, manager);
    }

    private static void clearArtworkIfNeeded(Context context) {
        String url = context.getSharedPreferences("musibility_widget", Context.MODE_PRIVATE)
                .getString("cover", "");
        if (url.isEmpty()) {
            cachedCover = null;
            cachedCoverUrl = "";
        }
    }

    private static void loadCover(Context context, AppWidgetManager manager) {
        String url = context.getSharedPreferences("musibility_widget", Context.MODE_PRIVATE).getString("cover", "");
        if (url.isEmpty() || url.equals(cachedCoverUrl)) return;
        cachedCoverUrl = url;
        IMAGE_WORKER.execute(() -> {
            try {
                java.net.URLConnection connection = new URL(url).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                Bitmap bitmap = BitmapFactory.decodeStream(connection.getInputStream());
                if (bitmap == null) return;
                cachedCover = bitmap;
                int[] ids = manager.getAppWidgetIds(new ComponentName(context, MusicWidgetProvider.class));
                for (int id : ids) update(context, manager, id, bitmap);
            } catch (Exception e) {
                cachedCoverUrl = "";
                android.util.Log.w("Musibility", "Could not load widget cover", e);
            }
        });
    }
}
