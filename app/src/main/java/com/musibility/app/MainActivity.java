package com.musibility.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationListener;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int AUDIO_PERMISSION = 41;
    private static final int LOCATION_PERMISSION = 42;
    private final ExecutorService worker = Executors.newFixedThreadPool(3);
    private final Handler mainHandler = new Handler();
    private final ArrayList<Song> queue = new ArrayList<>();
    private LinearLayout root;
    private LinearLayout content;
    private Song currentSong;
    private boolean shuffle;
    private boolean currentQueueIsPlaylist;
    private Song pendingLocationSong;
    private String currentScreen = "discover";
    private TextView playerPlayButton;
    private TextView playerTime;
    private SeekBar playerSeekBar;
    private TextView lyricsText;
    private int screenGeneration;
    private List<String> lyricLines = new ArrayList<>();
    private List<Long> lyricTimes = new ArrayList<>();
    private final Runnable lyricsTicker = new Runnable() {
        @Override public void run() {
            if (lyricsText != null && currentSong != null && !lyricLines.isEmpty()) {
                int position = getSharedPreferences("musibility_widget", MODE_PRIVATE).getInt("position", 0);
                int active = 0;
                for (int i = 0; i < lyricTimes.size(); i++) if (lyricTimes.get(i) <= position) active = i;
                SpannableStringBuilder text = new SpannableStringBuilder();
                for (int i = 0; i < lyricLines.size(); i++) {
                    int start = text.length();
                    if (i == active) text.append("▶  ");
                    text.append(lyricLines.get(i)).append('\n');
                    if (i == active) {
                        text.setSpan(new ForegroundColorSpan(Color.rgb(215, 250, 0)), start,
                                text.length() - 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        text.setSpan(new StyleSpan(Typeface.BOLD), start, text.length() - 1,
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
                lyricsText.setText(text.toString());
            }
            mainHandler.postDelayed(this, 300);
        }
    };
    private final BroadcastReceiver playbackReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            Object stateSong = intent.getSerializableExtra("song");
            if (stateSong instanceof Song && (currentSong == null || currentSong.id != ((Song) stateSong).id)) {
                currentSong = (Song) stateSong;
                if ("player".equals(currentScreen)) showPlayer();
            }
            boolean playing = intent.getBooleanExtra("playing", false);
            if (playerPlayButton != null) playerPlayButton.setText(playing ? "Ⅱ" : "▶");
            if (playerSeekBar != null) {
                int duration = intent.getIntExtra("duration", 0);
                int position = intent.getIntExtra("position", 0);
                playerSeekBar.setMax(Math.max(duration, 1));
                playerSeekBar.setProgress(Math.min(position, Math.max(duration, 1)));
            }
            if (playerTime != null) {
                playerTime.setText(formatTime(intent.getIntExtra("position", 0) / 1000)
                        + " / " + formatTime(intent.getIntExtra("duration", 0) / 1000));
            }
        }
    };

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(1, 1, 1));
        getWindow().setNavigationBarColor(Color.rgb(1, 1, 1));
        IntentFilter filter = new IntentFilter(PlaybackService.ACTION_STATE);
        ContextCompat.registerReceiver(this, playbackReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        showDiscover();
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(playbackReceiver); } catch (IllegalArgumentException ignored) { }
        worker.shutdown();
        mainHandler.removeCallbacks(lyricsTicker);
        super.onDestroy();
    }

    private void createShell(String title) {
        screenGeneration++;
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(1, 1, 1));
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(27);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setPadding(dp(20), dp(16), dp(16), dp(12));
        root.addView(heading);
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setPadding(dp(16), dp(6), dp(16), dp(20));
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setBackgroundColor(Color.rgb(18, 18, 18));
        String[][] tabs = {{"Khám phá", "discover"}, {"Playlist", "playlists"},
                {"Nhận diện", "identify"}, {"Bản đồ", "map"}, {"Đang phát", "player"}};
        for (String[] tab : tabs) {
            TextView item = new TextView(this);
            item.setText(tab[0]);
            item.setTextSize(11);
            item.setGravity(Gravity.CENTER);
            item.setTextColor(tab[1].equals(currentScreen) ? Color.rgb(215, 250, 0) : Color.LTGRAY);
            item.setPadding(dp(3), dp(13), dp(3), dp(13));
            item.setOnClickListener(v -> navigate(tab[1]));
            nav.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
        }
        root.addView(nav);
        setContentView(root);
    }

    private void navigate(String page) {
        if ("discover".equals(page)) showDiscover();
        else if ("playlists".equals(page)) showPlaylists();
        else if ("identify".equals(page)) showIdentify();
        else if ("map".equals(page)) openMap();
        else showPlayer();
    }

    private void showDiscover() {
        currentScreen = "discover";
        createShell("Musibility");
        addLabel(content, "Tìm nhạc, nghệ sĩ, playlist", 15, Color.LTGRAY);
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Tên bài hát hoặc nghệ sĩ");
        search.setHintTextColor(Color.GRAY);
        search.setTextColor(Color.WHITE);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        content.addView(search, matchWrap());
        LinearLayout actions = new LinearLayout(this);
        Button tracks = button("Tìm bài hát");
        Button artist = button("Tìm nghệ sĩ");
        Button playlists = button("Playlist nghệ sĩ");
        actions.addView(tracks, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(artist, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(playlists, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(actions);
        tracks.setOnClickListener(v -> searchTracks(search.getText().toString()));
        artist.setOnClickListener(v -> searchArtists(search.getText().toString()));
        playlists.setOnClickListener(v -> searchPlaylists(search.getText().toString()));
        Button chart = button("🔥  Những bài được nghe nhiều");
        content.addView(chart, matchWrap());
        chart.setOnClickListener(v -> loadChart());
        addLabel(content, "Đề xuất từ lịch sử nghe", 20, Color.WHITE);
        List<Song> recent = MusicData.recentSongs(this);
        if (recent.isEmpty()) {
            addLabel(content, "Phát một vài bài để nhận gợi ý tương tự.", 14, Color.GRAY);
        } else {
            Song latest = recent.get(0);
            runAsync(() -> {
                List<Song> similar = MusicCatalog.similar(latest);
                similar.removeIf(song -> song.id == latest.id);
                return similar;
            }, list -> {
                addLabel(content, "Vì bạn đã nghe " + latest.artist, 15, Color.rgb(215, 250, 0));
                addSongs(list);
            });
        }
        loadChart();
    }

    private void loadChart() {
        runAsync(MusicCatalog::trending, this::addSongs);
    }

    private void searchTracks(String query) {
        if (query.trim().isEmpty()) return;
        screenGeneration++;
        content.removeAllViews();
        Button back = button("← Quay lại tìm kiếm");
        content.addView(back, matchWrap());
        back.setOnClickListener(v -> showDiscover());
        addLabel(content, "Kết quả bài hát", 20, Color.WHITE);
        runAsync(() -> MusicCatalog.searchTracks(query), this::addSongs);
    }

    private void searchArtists(String query) {
        if (query.trim().isEmpty()) return;
        screenGeneration++;
        content.removeAllViews();
        Button back = button("← Quay lại tìm kiếm");
        content.addView(back, matchWrap());
        back.setOnClickListener(v -> showDiscover());
        addLabel(content, "Nghệ sĩ phù hợp", 20, Color.WHITE);
        runAsync(() -> MusicCatalog.searchArtists(query), artists -> {
            for (JSONObject artist : artists) {
                String artistId = artist.optString("_sourceId", artist.optString("id"));
                String provider = artist.optString("_provider", "Audius");
                String artistName = artist.optString("name");
                if (artistId.isEmpty()) continue;
                Button result = button("♪  " + artistName + "  ·  Xem bài hát");
                content.addView(result, matchWrap());
                result.setOnClickListener(v -> showArtist(artistName, artistId, provider));
            }
            if (artists.isEmpty()) addLabel(content, "Không tìm thấy nghệ sĩ.", 14, Color.GRAY);
        });
    }

    private void searchPlaylists(String query) {
        if (query.trim().isEmpty()) return;
        screenGeneration++;
        content.removeAllViews();
        Button back = button("← Quay lại tìm kiếm");
        content.addView(back, matchWrap());
        back.setOnClickListener(v -> showDiscover());
        addLabel(content, "Playlist và album", 20, Color.WHITE);
        runAsync(() -> MusicCatalog.searchPlaylists(query), albums -> {
            for (JSONObject album : albums) {
                String name = album.optString("playlist_name",
                        album.optString("name", album.optString("title")));
                JSONObject owner = album.optJSONObject("user");
                String ownerName = owner == null ? album.optString("artist_name") : owner.optString("name");
                Button item = button("♫  " + name + (ownerName.isEmpty() ? "" : "  ·  " + ownerName));
                content.addView(item, matchWrap());
                item.setOnClickListener(v -> runAsync(() -> MusicCatalog.playlistTracks(
                                album.optString("_sourceId", album.optString("id")),
                                album.optString("_provider", "Audius"), name), this::addPlaylistSongs));
            }
            if (albums.isEmpty()) addLabel(content, "Không tìm thấy playlist.", 14, Color.GRAY);
        });
    }

    private void showArtist(String name, String artistId, String provider) {
        currentScreen = "discover";
        createShell(name);
        addLabel(content, "Bài hát nổi bật của nghệ sĩ", 16, Color.LTGRAY);
        runAsync(() -> MusicCatalog.artistTracks(name, artistId, provider), this::addSongs);
    }

    private void addSongs(List<Song> songs) {
        renderSongs(songs, false);
    }

    private void addPlaylistSongs(List<Song> songs) {
        renderSongs(songs, true);
    }

    private void renderSongs(List<Song> songs, boolean playlistQueue) {
        if (songs == null || songs.isEmpty()) {
            addLabel(content, "Không tìm thấy bài hát.", 14, Color.GRAY);
            return;
        }
        for (int i = 0; i < songs.size(); i++) {
            Song song = songs.get(i);
            final int songIndex = i;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(5), 0, dp(5));
            ImageView cover = new ImageView(this);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            row.addView(cover, new LinearLayout.LayoutParams(dp(54), dp(54)));
            loadImage(song.coverUrl, cover);
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(dp(12), 0, dp(4), 0);
            TextView title = addLabel(labels, song.title, 15, Color.WHITE);
            title.setMaxLines(1);
            TextView subtitle = addLabel(labels, song.artist + " · " + song.album
                    + " · " + song.provider, 12, Color.LTGRAY);
            subtitle.setMaxLines(1);
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            TextView play = new TextView(this);
            play.setText("▶");
            play.setTextSize(18);
            play.setTextColor(Color.rgb(215, 250, 0));
            row.addView(play);
            row.setOnClickListener(v -> startQueue(songs, songIndex, playlistQueue));
            content.addView(row, matchWrap());
            View divider = new View(this);
            divider.setBackgroundColor(Color.rgb(45, 45, 45));
            content.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        }
    }

    private void startQueue(List<Song> songs, int index, boolean playlistQueue) {
        if (songs.isEmpty()) return;
        queue.clear();
        queue.addAll(songs);
        currentQueueIsPlaylist = playlistQueue;
        currentSong = queue.get(index);
        MusicData.recordPlay(this, currentSong, getLastLocation());
        Intent intent = new Intent(this, PlaybackService.class).setAction(PlaybackService.ACTION_PLAY);
        intent.putExtra(PlaybackService.EXTRA_QUEUE, new ArrayList<>(queue));
        intent.putExtra(PlaybackService.EXTRA_INDEX, index);
        intent.putExtra(PlaybackService.EXTRA_SHUFFLE, shuffle);
        startPlaybackService(intent);
        showPlayer();
    }

    private void startPlaybackService(Intent intent) {
        if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(this, intent);
        else startService(intent);
    }

    private void showPlayer() {
        currentScreen = "player";
        createShell("Đang phát");
        if (currentSong == null) {
            addLabel(content, "Chọn một bài hát trong Khám phá để bắt đầu.", 16, Color.LTGRAY);
            return;
        }
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        content.addView(cover, new LinearLayout.LayoutParams(dp(270), dp(270)));
        loadImage(currentSong.coverUrl, cover);
        TextView title = addLabel(content, currentSong.title, 23, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(18), 0, dp(4));
        title.setTypeface(null, Typeface.BOLD);
        TextView artist = addLabel(content, currentSong.artist, 16, Color.rgb(215, 250, 0));
        artist.setGravity(Gravity.CENTER);
        artist.setOnClickListener(v -> showArtist(currentSong.artist,
                currentSong.artistSourceId, currentSong.provider));
        TextView album = addLabel(content, currentSong.album, 13, Color.GRAY);
        album.setGravity(Gravity.CENTER);
        if (!currentSong.licenseUrl.isEmpty()) {
            TextView license = addLabel(content, "Nhạc Creative Commons · Xem giấy phép", 12, Color.LTGRAY);
            license.setGravity(Gravity.CENTER);
            license.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(currentSong.licenseUrl)));
                } catch (android.content.ActivityNotFoundException e) {
                    showError(e);
                }
            });
        }
        playerSeekBar = new SeekBar(this);
        playerSeekBar.setMax(Math.max(1, currentSong.durationSeconds * 1000));
        content.addView(playerSeekBar, matchWrap());
        playerTime = addLabel(content, "0:00 / " + formatTime(currentSong.durationSeconds), 12, Color.LTGRAY);
        playerTime.setGravity(Gravity.CENTER);
        playerSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private boolean fromUser;
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) { fromUser = user; }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (fromUser) {
                    Intent seek = new Intent(MainActivity.this, PlaybackService.class).setAction(PlaybackService.ACTION_SEEK);
                    seek.putExtra("position", bar.getProgress());
                    startPlaybackService(seek);
                }
            }
        });
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        Button previous = button("|◀");
        playerPlayButton = button("▶");
        Button next = button("▶|");
        controls.addView(previous); controls.addView(playerPlayButton); controls.addView(next);
        content.addView(controls);
        previous.setOnClickListener(v -> sendPlaybackAction(PlaybackService.ACTION_PREVIOUS));
        next.setOnClickListener(v -> sendPlaybackAction(PlaybackService.ACTION_NEXT));
        playerPlayButton.setOnClickListener(v -> sendPlaybackAction(PlaybackService.ACTION_TOGGLE));
        LinearLayout options = new LinearLayout(this);
        options.setGravity(Gravity.CENTER);
        Button add = button("＋ Playlist");
        Button share = button("Chia sẻ");
        Button lyrics = button("Lời bài hát");
        options.addView(add); options.addView(share); options.addView(lyrics);
        content.addView(options);
        add.setOnClickListener(v -> choosePlaylist());
        share.setOnClickListener(v -> shareSong());
        lyrics.setOnClickListener(v -> showLyrics());
        if (currentQueueIsPlaylist) {
            Button shuffleButton = button(shuffle ? "🔀 Đang bật tráo bài" : "🔀 Tráo bài trong playlist");
            content.addView(shuffleButton);
            shuffleButton.setOnClickListener(v -> {
                shuffle = !shuffle;
                Intent intent = new Intent(this, PlaybackService.class).setAction(PlaybackService.ACTION_SET_SHUFFLE);
                intent.putExtra(PlaybackService.EXTRA_SHUFFLE, shuffle);
                startPlaybackService(intent);
                showPlayer();
            });
        }
        worker.execute(() -> {
            android.content.SharedPreferences state = getSharedPreferences("musibility_widget", MODE_PRIVATE);
            boolean playing = state.getBoolean("playing", false);
            mainHandler.post(() -> {
                if (playerPlayButton != null) playerPlayButton.setText(playing ? "Ⅱ" : "▶");
            });
        });
    }

    private void sendPlaybackAction(String action) {
        startPlaybackService(new Intent(this, PlaybackService.class).setAction(action));
    }

    private void choosePlaylist() {
        List<String> names = MusicData.playlistNames(this);
        if (names.isEmpty()) {
            Toast.makeText(this, "Hãy tạo playlist trước.", Toast.LENGTH_SHORT).show();
            showPlaylists();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Thêm vào playlist")
                .setItems(names.toArray(new String[0]), (dialog, which) -> {
                    try {
                        MusicData.addToPlaylist(this, names.get(which), currentSong);
                        Toast.makeText(this, "Đã thêm vào " + names.get(which), Toast.LENGTH_SHORT).show();
                    } catch (Exception e) { showError(e); }
                }).show();
    }

    private void shareSong() {
        if (currentSong == null) return;
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, currentSong.title + " — " + currentSong.artist
                + (currentSong.link.isEmpty() ? "" : "\n" + currentSong.link));
        startActivity(Intent.createChooser(share, "Chia sẻ bài hát"));
    }

    private void showLyrics() {
        if (currentSong == null) return;
        currentScreen = "player";
        createShell("Lời bài hát");
        lyricsText = addLabel(content, "Đang tìm lời bài hát…", 18, Color.WHITE);
        lyricsText.setGravity(Gravity.CENTER);
        lyricsText.setPadding(dp(8), dp(18), dp(8), dp(18));
        worker.execute(() -> {
            try {
                String url = "https://lrclib.net/api/get?artist_name="
                        + URLEncoder.encode(currentSong.artist, StandardCharsets.UTF_8.name())
                        + "&track_name=" + URLEncoder.encode(currentSong.title, StandardCharsets.UTF_8.name())
                        + "&duration=" + currentSong.durationSeconds;
                java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                String response;
                try (java.io.InputStream in = connection.getInputStream();
                     java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                    byte[] data = new byte[4096]; int count;
                    while ((count = in.read(data)) != -1) out.write(data, 0, count);
                    response = out.toString(StandardCharsets.UTF_8.name());
                } finally { connection.disconnect(); }
                JSONObject json = new JSONObject(response);
                String synced = json.optString("syncedLyrics", "");
                String plain = json.optString("plainLyrics", "");
                List<String> lines = new ArrayList<>();
                List<Long> times = new ArrayList<>();
                if (!synced.isEmpty()) {
                    java.util.regex.Matcher matcher = java.util.regex.Pattern
                            .compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)\\](.*)").matcher(synced);
                    while (matcher.find()) {
                        lines.add(matcher.group(3).trim());
                        times.add((long) (Double.parseDouble(matcher.group(1)) * 60000
                                + Double.parseDouble(matcher.group(2)) * 1000));
                    }
                } else if (!plain.isEmpty()) {
                    for (String line : plain.split("\\r?\\n")) { lines.add(line); times.add(0L); }
                }
                mainHandler.post(() -> {
                    if (lines.isEmpty()) lyricsText.setText("Chưa tìm thấy lời bài hát phù hợp.");
                    else {
                        lyricLines = lines; lyricTimes = times;
                        lyricsText.setText(String.join("\n", lines));
                        if (!synced.isEmpty()) mainHandler.post(lyricsTicker);
                        else addLabel(content, "Lời bài hát chưa có dấu thời gian.", 12, Color.GRAY);
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> lyricsText.setText("Không tải được lời bài hát. Vui lòng thử lại."));
            }
        });
    }

    private void showPlaylists() {
        currentScreen = "playlists";
        createShell("Playlist của tôi");
        Button create = button("＋  Tạo playlist mới");
        content.addView(create, matchWrap());
        create.setOnClickListener(v -> {
            EditText input = new EditText(this);
            input.setHint("Tên playlist");
            new AlertDialog.Builder(this).setTitle("Tạo playlist").setView(input)
                    .setPositiveButton("Tạo", (dialog, which) -> {
                        String name = input.getText().toString().trim();
                        if (name.isEmpty()) return;
                        try { MusicData.createPlaylist(this, name); showPlaylists(); }
                        catch (Exception e) { showError(e); }
                    }).setNegativeButton("Hủy", null).show();
        });
        List<String> names = MusicData.playlistNames(this);
        if (names.isEmpty()) addLabel(content, "Playlist cá nhân được lưu trên thiết bị.", 14, Color.GRAY);
        for (String name : names) {
            Button playlist = button("♫  " + name + "  ·  " + MusicData.playlistSongs(this, name).size() + " bài");
            content.addView(playlist, matchWrap());
            playlist.setOnClickListener(v -> {
                List<Song> songs = MusicData.playlistSongs(this, name);
                content.removeAllViews();
                addLabel(content, name, 20, Color.WHITE);
                if (songs.isEmpty()) addLabel(content, "Playlist đang trống. Thêm bài từ màn hình phát nhạc.", 14, Color.GRAY);
                else addPlaylistSongs(songs);
            });
        }
    }

    private void showIdentify() {
        currentScreen = "identify";
        createShell("Nhận diện bài hát");
        TextView icon = addLabel(content, "♫", 76, Color.rgb(215, 250, 0));
        icon.setGravity(Gravity.CENTER);
        Button identify = button("Nhấn để nghe");
        content.addView(identify, matchWrap());
        addLabel(content, "Musibility dùng microphone để nghe âm thanh xung quanh và tra cứu bằng ACRCloud. Ứng dụng không đọc âm thanh nội bộ từ loa; hãy cấp quyền microphone khi được hỏi.", 14, Color.LTGRAY);
        identify.setOnClickListener(v -> requestAndIdentify());
    }

    private void requestAndIdentify() {
        if (Build.VERSION.SDK_INT >= 23 && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION);
            return;
        }
        createShell("Đang nghe…");
        addLabel(content, "Giữ điện thoại gần nguồn nhạc trong 8 giây.", 16, Color.WHITE);
        ProgressBar progress = new ProgressBar(this);
        content.addView(progress);
        worker.execute(() -> {
            try {
                AcrCloudRecognizer.Result result = AcrCloudRecognizer.listenAndIdentify();
                mainHandler.post(() -> {
                    createShell("Đã nhận diện");
                    addLabel(content, result.title, 23, Color.WHITE);
                    addLabel(content, result.artist, 17, Color.rgb(215, 250, 0));
                    EditText query = new EditText(this);
                    query.setText(result.title + " " + result.artist);
                    content.addView(query);
                    Button find = button("Tìm và nghe bản nhạc này");
                    content.addView(find);
                    find.setOnClickListener(v -> searchRecognized(result.title));
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    createShell("Nhận diện bài hát");
                    addLabel(content, "Nhận diện thất bại: " + e.getMessage(), 15, Color.LTGRAY);
                    Button retry = button("Thử lại");
                    content.addView(retry);
                    retry.setOnClickListener(v -> requestAndIdentify());
                });
            }
        });
    }

    private void searchRecognized(String title) {
        currentScreen = "discover";
        createShell("Kết quả nhận diện");
        runAsync(() -> MusicCatalog.searchTracks(title), this::addSongs);
    }

    private void openMap() {
        if (BuildConfig.GOOGLE_MAPS_KEY.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Cần Google Maps API key")
                    .setMessage("Thêm google.maps.key=YOUR_KEY vào local.properties và bật Maps SDK for Android trong Google Cloud Console.")
                    .setPositiveButton("Mở bản đồ", (d, w) -> startActivity(new Intent(this, MapActivity.class)))
                    .setNegativeButton("Đóng", null).show();
        } else startActivity(new Intent(this, MapActivity.class));
    }

    private Location getLastLocation() {
        if (Build.VERSION.SDK_INT >= 23 && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (currentScreen.equals("player") || currentScreen.equals("discover")) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION);
            }
            return null;
        }
        try {
            LocationManager manager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            boolean finePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            String provider = finePermission ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;
            Location known = manager.getLastKnownLocation(provider);
            if (known == null) requestOneLocation(manager, currentSong);
            return known;
        } catch (SecurityException e) {
            return null;
        }
    }

    private void requestOneLocation(LocationManager manager, Song song) {
        if (song == null) return;
        pendingLocationSong = song;
        LocationListener listener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (pendingLocationSong == song) {
                    pendingLocationSong = null;
                    MusicData.recordLocation(MainActivity.this, song, location);
                }
                try { manager.removeUpdates(this); } catch (SecurityException ignored) { }
            }
            @Override public void onProviderEnabled(String provider) { }
            @Override public void onProviderDisabled(String provider) { }
            @Override public void onStatusChanged(String provider, int status, android.os.Bundle extras) { }
        };
        try {
            boolean finePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            String network = LocationManager.NETWORK_PROVIDER;
            String provider = manager.isProviderEnabled(network) ? network
                    : finePermission ? LocationManager.GPS_PROVIDER : network;
            if (!manager.isProviderEnabled(provider)) return;
            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper());
        } catch (Exception e) {
            android.util.Log.w("Musibility", "Could not request one-time location", e);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == AUDIO_PERMISSION && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) requestAndIdentify();
        if (requestCode == LOCATION_PERMISSION && currentSong != null
                && (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)) {
            MusicData.recordLocation(this, currentSong, getLastLocation());
        }
    }

    private void loadImage(String url, ImageView target) {
        if (url == null || url.isEmpty()) return;
        worker.execute(() -> {
            try {
                java.net.URLConnection connection = new URL(url).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                android.graphics.Bitmap bitmap = BitmapFactory.decodeStream(connection.getInputStream());
                if (bitmap != null) mainHandler.post(() -> target.setImageBitmap(bitmap));
            } catch (Exception e) { android.util.Log.w("Musibility", "Could not load album art", e); }
        });
    }

    private interface ResultCallback<T> { void onResult(T result); }
    private interface Work<T> { T run() throws Exception; }
    private <T> void runAsync(Work<T> task, ResultCallback<T> callback) {
        int generation = screenGeneration;
        worker.execute(() -> {
            try {
                T result = task.run();
                mainHandler.post(() -> {
                    if (generation == screenGeneration) callback.onResult(result);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    Toast.makeText(this, "Không thể tải dữ liệu: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showError(Exception error) {
        Toast.makeText(this, error.getMessage() == null ? "Có lỗi xảy ra." : error.getMessage(), Toast.LENGTH_LONG).show();
    }

    private TextView addLabel(LinearLayout parent, String text, int size, int color) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(color);
        label.setPadding(dp(4), dp(8), dp(4), dp(8));
        parent.addView(label, matchWrap());
        return label;
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.rgb(1, 1, 1));
        button.setAllCaps(false);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(215, 250, 0)));
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    private String formatTime(int seconds) {
        return String.format(java.util.Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60);
    }
}
