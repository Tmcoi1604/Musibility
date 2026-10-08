package com.musibility.app;

import android.Manifest;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Environment;
import android.media.MediaMetadataRetriever;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
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
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
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
    private static final int AUDIO_LIBRARY_PERMISSION = 43;
    private final ExecutorService worker = Executors.newFixedThreadPool(3);
    private final Handler mainHandler = new Handler();
    private final ArrayList<Song> queue = new ArrayList<>();
    private LinearLayout root;
    private LinearLayout content;
    private Song currentSong;
    private ImageView playerCoverImage;
    private boolean shuffle;
    private boolean currentQueueIsPlaylist;
    private String currentScreen = "discover";
    private ImageButton playerPlayButton;
    private ImageButton miniPlayButton;
    private ImageView miniCoverImage;
    private TextView miniTitle;
    private TextView miniArtist;
    private TextView playerTime;
    private SeekBar playerSeekBar;
    private TextView lyricsText;
    private OnBackPressedCallback backToDiscoverCallback;
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
                else updateMiniPlayerSong();
            }
            boolean playing = intent.getBooleanExtra("playing", false);
            updateMiniPlayerPlayback(playing);
            if (playerPlayButton != null) {
                playerPlayButton.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
                playerPlayButton.setContentDescription(playing ? "Tạm dừng" : "Phát");
            }
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
            String error = intent.getStringExtra("error");
            if (error != null && !error.isEmpty()) {
                Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
            }
        }
    };

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MusicData.clearListeningLocations(this);
        getWindow().setStatusBarColor(Color.rgb(1, 1, 1));
        getWindow().setNavigationBarColor(Color.rgb(1, 1, 1));
        IntentFilter filter = new IntentFilter(PlaybackService.ACTION_STATE);
        ContextCompat.registerReceiver(this, playbackReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        backToDiscoverCallback = new OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() {
                showDiscover();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backToDiscoverCallback);
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
        playerCoverImage = null;
        miniPlayButton = null;
        miniCoverImage = null;
        miniTitle = null;
        miniArtist = null;
        getWindow().setStatusBarColor(Color.rgb(1, 1, 1));
        if (backToDiscoverCallback != null) backToDiscoverCallback.setEnabled(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(1, 1, 1));
        boolean fullPlayer = "player".equals(currentScreen);
        LinearLayout headingRow = new LinearLayout(this);
        headingRow.setGravity(Gravity.CENTER_VERTICAL);
        if (fullPlayer) {
            ImageButton back = iconButton(R.drawable.ic_back, "Thu nhỏ trình phát");
            headingRow.addView(back);
            back.setOnClickListener(v -> showDiscover());
        }
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(27);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setPadding(fullPlayer ? dp(4) : dp(20), dp(16), dp(16), dp(12));
        headingRow.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(headingRow);
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setPadding(dp(16), dp(6), dp(16), dp(20));
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (!fullPlayer && currentSong != null) addMiniPlayer();
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setBackgroundColor(Color.rgb(18, 18, 18));
        String[][] tabs = {{"Khám phá", "discover"}, {"Playlist", "playlists"},
                {"Tải về", "downloads"}, {"Nhận diện", "identify"},
                {"Đang phát", "player"}};
        int[] tabIcons = {R.drawable.ic_home, R.drawable.ic_playlist, R.drawable.ic_downloads,
                R.drawable.ic_mic, R.drawable.ic_play};
        for (int i = 0; i < tabs.length; i++) {
            String[] tab = tabs[i];
            int tint = tab[1].equals(currentScreen) ? Color.rgb(215, 250, 0) : Color.LTGRAY;
            LinearLayout item = new LinearLayout(this);
            item.setGravity(Gravity.CENTER);
            item.setOrientation(LinearLayout.VERTICAL);
            ImageView icon = new ImageView(this);
            icon.setImageResource(tabIcons[i]);
            icon.setColorFilter(tint);
            item.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
            TextView label = new TextView(this);
            label.setText(tab[0]);
            label.setTextSize(9);
            label.setGravity(Gravity.CENTER);
            label.setTextColor(tint);
            label.setMaxLines(1);
            item.addView(label);
            item.setContentDescription(tab[0]);
            item.setPadding(dp(2), dp(6), dp(2), dp(5));
            item.setOnClickListener(v -> navigate(tab[1]));
            nav.addView(item, new LinearLayout.LayoutParams(0, dp(58), 1));
        }
        root.addView(nav);
        setContentView(root);
    }

    private void addMiniPlayer() {
        LinearLayout miniPlayer = new LinearLayout(this);
        miniPlayer.setGravity(Gravity.CENTER_VERTICAL);
        miniPlayer.setPadding(dp(8), dp(6), dp(8), dp(6));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(46, 27, 59));
        background.setCornerRadius(dp(8));
        miniPlayer.setBackground(background);
        LinearLayout.LayoutParams playerParams = new LinearLayout.LayoutParams(-1, dp(56));
        playerParams.setMargins(dp(8), dp(4), dp(8), dp(4));

        miniCoverImage = new ImageView(this);
        miniCoverImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        miniPlayer.addView(miniCoverImage, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout songInfo = new LinearLayout(this);
        songInfo.setOrientation(LinearLayout.VERTICAL);
        songInfo.setGravity(Gravity.CENTER_VERTICAL);
        songInfo.setPadding(dp(10), 0, dp(4), 0);
        miniTitle = new TextView(this);
        miniTitle.setTextColor(Color.WHITE);
        miniTitle.setTextSize(14);
        miniTitle.setTypeface(null, Typeface.BOLD);
        miniTitle.setMaxLines(1);
        songInfo.addView(miniTitle);
        miniArtist = new TextView(this);
        miniArtist.setTextColor(Color.LTGRAY);
        miniArtist.setTextSize(12);
        miniArtist.setMaxLines(1);
        songInfo.addView(miniArtist);
        miniPlayer.addView(songInfo, new LinearLayout.LayoutParams(0, -1, 1));
        ImageButton addToPlaylist = iconButton(R.drawable.ic_add_playlist, "Thêm vào playlist");
        addToPlaylist.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(48)));
        miniPlayer.addView(addToPlaylist);
        miniPlayButton = iconButton(R.drawable.ic_play, "Phát");
        miniPlayButton.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(48)));
        miniPlayer.addView(miniPlayButton);
        miniPlayer.setOnClickListener(v -> showPlayer());
        addToPlaylist.setOnClickListener(v -> choosePlaylist());
        miniPlayButton.setOnClickListener(v -> sendPlaybackAction(PlaybackService.ACTION_TOGGLE));
        root.addView(miniPlayer, playerParams);
        updateMiniPlayerSong();
        updateMiniPlayerPlayback(getSharedPreferences("musibility_widget", MODE_PRIVATE)
                .getBoolean("playing", false));
    }

    private void updateMiniPlayerSong() {
        if (currentSong == null) return;
        if (miniTitle != null) miniTitle.setText(currentSong.title);
        if (miniArtist != null) miniArtist.setText(currentSong.artist);
        if (miniCoverImage != null) loadImage(currentSong.coverUrl, miniCoverImage);
    }

    private void updateMiniPlayerPlayback(boolean playing) {
        if (miniPlayButton == null) return;
        miniPlayButton.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        miniPlayButton.setContentDescription(playing ? "Tạm dừng" : "Phát");
    }

    private void navigate(String page) {
        if ("discover".equals(page)) showDiscover();
        else if ("playlists".equals(page)) showPlaylists();
        else if ("downloads".equals(page)) showDownloads();
        else if ("identify".equals(page)) showIdentify();
        else showPlayer();
    }

    private void showDiscover() {
        currentScreen = "discover";
        createShell("Khám phá");
        if (backToDiscoverCallback != null) backToDiscoverCallback.setEnabled(false);
        LinearLayout searchCard = discoverPanel(new int[]{Color.rgb(43, 55, 96), Color.rgb(42, 32, 72)});
        TextView heading = addLabel(searchCard, "Âm nhạc của bạn", 23, Color.WHITE);
        heading.setTypeface(null, Typeface.BOLD);
        addLabel(searchCard, "Tìm bài hát, nghệ sĩ hoặc playlist", 14, Color.rgb(220, 220, 235));
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Bạn muốn nghe gì?");
        search.setHintTextColor(Color.GRAY);
        search.setTextColor(Color.rgb(20, 20, 30));
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setPadding(dp(14), 0, dp(10), 0);
        search.setBackground(searchFieldBackground());
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.addView(search, new LinearLayout.LayoutParams(0, dp(50), 1));
        ImageButton searchButton = iconButton(R.drawable.ic_search, "Tìm kiếm");
        searchButton.setBackground(searchFieldBackground());
        LinearLayout.LayoutParams searchButtonParams = new LinearLayout.LayoutParams(dp(50), dp(50));
        searchButtonParams.setMargins(dp(8), 0, 0, 0);
        searchRow.addView(searchButton, searchButtonParams);
        searchCard.addView(searchRow, matchWrap());
        content.addView(searchCard, matchWrap());
        View.OnClickListener submitSearch = v -> searchAll(search.getText().toString());
        searchButton.setOnClickListener(submitSearch);
        search.setOnEditorActionListener((view, actionId, event) -> {
            boolean enterPressed = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_DONE || enterPressed) {
                ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                        .hideSoftInputFromWindow(search.getWindowToken(), 0);
                submitSearch.onClick(search);
                return true;
            }
            return false;
        });

        TextView shortcutsHeading = addLabel(content, "Truy cập nhanh", 18, Color.WHITE);
        shortcutsHeading.setTypeface(null, Typeface.BOLD);
        LinearLayout shortcuts = new LinearLayout(this);
        shortcuts.setOrientation(LinearLayout.HORIZONTAL);
        shortcuts.addView(discoverShortcut("Bảng xếp hạng", "♫",
                new int[]{Color.rgb(132, 62, 103), Color.rgb(84, 44, 94)}, this::loadChart));
        shortcuts.addView(discoverShortcut("Nhạc đã tải", "↓",
                new int[]{Color.rgb(42, 104, 103), Color.rgb(29, 76, 91)}, this::showDownloads));
        shortcuts.addView(discoverShortcut("Playlist", "≡",
                new int[]{Color.rgb(159, 104, 49), Color.rgb(115, 73, 43)}, this::showPlaylists));
        content.addView(shortcuts, matchWrap());

        LinearLayout chartPanel = discoverPanel(new int[]{Color.rgb(35, 61, 77), Color.rgb(31, 40, 65)});
        TextView chartHeading = addLabel(chartPanel, "Đang thịnh hành", 19, Color.WHITE);
        chartHeading.setTypeface(null, Typeface.BOLD);
        addLabel(chartPanel, "Những bài được nghe nhiều", 13, Color.rgb(194, 210, 220));
        LinearLayout chartCards = new LinearLayout(this);
        chartCards.setOrientation(LinearLayout.HORIZONTAL);
        chartPanel.addView(chartCards, matchWrap());
        content.addView(chartPanel, matchWrap());

        LinearLayout recommendations = discoverPanel(new int[]{Color.rgb(70, 45, 84), Color.rgb(45, 38, 76)});
        TextView recommendationTitle = addLabel(recommendations, "Đề xuất cho bạn", 19, Color.WHITE);
        recommendationTitle.setTypeface(null, Typeface.BOLD);
        List<Song> recent = MusicData.recentSongs(this);
        if (recent.isEmpty()) {
            addLabel(recommendations, "Nghe một vài bài để nhận gợi ý phù hợp với gu của bạn.",
                    14, Color.rgb(218, 206, 228));
        } else {
            Song latest = recent.get(0);
            addLabel(recommendations, "Dựa trên " + latest.artist, 13, Color.rgb(218, 206, 228));
            LinearLayout recommendationCards = new LinearLayout(this);
            recommendationCards.setOrientation(LinearLayout.HORIZONTAL);
            recommendations.addView(recommendationCards, matchWrap());
            runAsync(() -> {
                List<Song> similar = MusicCatalog.similar(latest);
                similar.removeIf(song -> song.id == latest.id);
                return similar;
            }, list -> renderDiscoverSongs(recommendationCards, list));
        }
        content.addView(recommendations, matchWrap());
        runAsync(MusicCatalog::trending, songs -> renderDiscoverSongs(chartCards, songs));
    }

    private LinearLayout discoverPanel(int[] colors) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(12), dp(14), dp(14));
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        background.setCornerRadius(dp(20));
        panel.setBackground(background);
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, 0, 0, dp(16));
        panel.setLayoutParams(params);
        return panel;
    }

    private GradientDrawable searchFieldBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(14));
        return background;
    }

    private View discoverShortcut(String title, String symbol, int[] colors, Runnable action) {
        LinearLayout shortcut = new LinearLayout(this);
        shortcut.setOrientation(LinearLayout.VERTICAL);
        shortcut.setGravity(Gravity.CENTER_VERTICAL);
        shortcut.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        background.setCornerRadius(dp(16));
        shortcut.setBackground(background);
        TextView icon = new TextView(this);
        icon.setText(symbol);
        icon.setTextColor(Color.WHITE);
        icon.setTextSize(22);
        shortcut.addView(icon);
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(Color.WHITE);
        label.setTextSize(12);
        label.setTypeface(null, Typeface.BOLD);
        label.setMaxLines(1);
        shortcut.addView(label);
        shortcut.setContentDescription(title);
        shortcut.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(82), 1);
        params.setMargins(dp(3), 0, dp(7), 0);
        shortcut.setLayoutParams(params);
        return shortcut;
    }

    private void renderDiscoverSongs(LinearLayout container, List<Song> songs) {
        container.removeAllViews();
        if (songs == null || songs.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Chưa có bài phù hợp lúc này.");
            empty.setTextColor(Color.LTGRAY);
            empty.setTextSize(13);
            container.addView(empty);
            return;
        }
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        int[][] palette = {
                {Color.rgb(65, 69, 116), Color.rgb(47, 48, 86)},
                {Color.rgb(98, 58, 104), Color.rgb(66, 44, 81)},
                {Color.rgb(45, 94, 100), Color.rgb(36, 67, 83)},
                {Color.rgb(123, 82, 54), Color.rgb(82, 57, 50)}
        };
        for (int i = 0; i < Math.min(songs.size(), 12); i++) {
            Song song = songs.get(i);
            int songIndex = i;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(8), dp(8), dp(8), dp(10));
            GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    palette[i % palette.length]);
            background.setCornerRadius(dp(16));
            card.setBackground(background);
            ImageView cover = new ImageView(this);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackgroundColor(Color.rgb(51, 51, 65));
            card.addView(cover, new LinearLayout.LayoutParams(dp(126), dp(126)));
            loadImage(song.coverUrl, cover);
            TextView title = new TextView(this);
            title.setText(song.title);
            title.setTextColor(Color.WHITE);
            title.setTextSize(13);
            title.setTypeface(null, Typeface.BOLD);
            title.setMaxLines(1);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setPadding(0, dp(7), 0, dp(2));
            card.addView(title);
            TextView artist = new TextView(this);
            artist.setText(song.artist);
            artist.setTextColor(Color.rgb(210, 210, 220));
            artist.setTextSize(11);
            artist.setMaxLines(1);
            artist.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(artist);
            card.setContentDescription(song.title + " — " + song.artist);
            card.setOnClickListener(v -> startQueue(songs, songIndex, false));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            params.setMargins(0, 0, dp(10), 0);
            cards.addView(card, params);
        }
        scroll.addView(cards);
        container.addView(scroll, matchWrap());
    }

    private void loadChart() {
        currentScreen = "discover";
        createShell("Bảng xếp hạng");
        addLabel(content, "Những bài đang thịnh hành", 16, Color.LTGRAY);
        runAsync(MusicCatalog::trending, this::addSongs);
    }

    private void searchAll(String query) {
        if (query.trim().isEmpty()) return;
        screenGeneration++;
        if (backToDiscoverCallback != null) backToDiscoverCallback.setEnabled(true);
        content.removeAllViews();
        Button back = button("← Khám phá");
        content.addView(back, matchWrap());
        back.setOnClickListener(v -> showDiscover());
        addLabel(content, "Đang tìm bài hát, nghệ sĩ và playlist…", 14, Color.LTGRAY);
        runAsync(() -> {
            SearchResults results = new SearchResults();
            try { results.songs = MusicCatalog.searchTracks(query); }
            catch (Exception e) { results.songError = e.getMessage(); }
            try { results.artists = MusicCatalog.searchArtists(query); }
            catch (Exception e) { results.artistError = e.getMessage(); }
            try { results.playlists = MusicCatalog.searchPlaylists(query); }
            catch (Exception e) { results.playlistError = e.getMessage(); }
            return results;
        }, results -> renderSearchResults(query, results));
    }

    private void renderSearchResults(String query, SearchResults results) {
        content.removeAllViews();
        Button back = button("← Khám phá");
        content.addView(back, matchWrap());
        back.setOnClickListener(v -> showDiscover());

        addLabel(content, "Bài hát", 20, Color.WHITE);
        if (results.songs != null && !results.songs.isEmpty()) renderSongs(results.songs, false);
        else addLabel(content, results.songError == null ? "Không tìm thấy bài hát."
                : "Không tải được bài hát: " + results.songError, 14, Color.GRAY);

        addLabel(content, "Nghệ sĩ", 20, Color.WHITE);
        if (results.artists != null && !results.artists.isEmpty()) {
            for (JSONObject artist : results.artists) {
                String artistId = artist.optString("_sourceId", artist.optString("id"));
                if (artistId.isEmpty()) continue;
                String name = artist.optString("name");
                String provider = artist.optString("_provider", "Audius");
                Button item = button("♪  " + name + "  ·  Xem bài hát");
                content.addView(item, matchWrap());
                item.setOnClickListener(v -> showArtist(name, artistId, provider));
            }
        } else {
            addLabel(content, results.artistError == null ? "Không tìm thấy nghệ sĩ."
                    : "Không tải được nghệ sĩ: " + results.artistError, 14, Color.GRAY);
        }

        addLabel(content, "Playlist và album", 20, Color.WHITE);
        if (results.playlists != null && !results.playlists.isEmpty()) {
            for (JSONObject album : results.playlists) {
                String name = album.optString("playlist_name",
                        album.optString("name", album.optString("title")));
                JSONObject owner = album.optJSONObject("user");
                String ownerName = owner == null ? album.optString("artist_name") : owner.optString("name");
                Button item = button("♫  " + name + (ownerName.isEmpty() ? "" : "  ·  " + ownerName));
                content.addView(item, matchWrap());
                item.setOnClickListener(v -> {
                    content.removeAllViews();
                    Button returnToResults = button("← Kết quả tìm kiếm");
                    content.addView(returnToResults, matchWrap());
                    returnToResults.setOnClickListener(backView -> searchAll(query));
                    addLabel(content, name, 20, Color.WHITE);
                    runAsync(() -> MusicCatalog.playlistTracks(
                                    album.optString("_sourceId", album.optString("id")),
                                    album.optString("_provider", "Audius"), name),
                            this::addPlaylistSongs);
                });
            }
        } else {
            addLabel(content, results.playlistError == null ? "Không tìm thấy playlist."
                    : "Không tải được playlist: " + results.playlistError, 14, Color.GRAY);
        }

        if (results.songs.isEmpty() && results.artists.isEmpty() && results.playlists.isEmpty()
                && results.songError == null && results.artistError == null && results.playlistError == null) {
            addLabel(content, "Không tìm thấy kết quả phù hợp.", 14, Color.GRAY);
        }
    }

    private static final class SearchResults {
        List<Song> songs = new ArrayList<>();
        List<JSONObject> artists = new ArrayList<>();
        List<JSONObject> playlists = new ArrayList<>();
        String songError;
        String artistError;
        String playlistError;
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
        if (index < 0 || index >= songs.size()) return;
        queue.clear();
        queue.addAll(songs);
        currentQueueIsPlaylist = playlistQueue;
        currentSong = queue.get(index);
        Intent intent = new Intent(this, PlaybackService.class).setAction(PlaybackService.ACTION_PLAY);
        intent.putExtra(PlaybackService.EXTRA_QUEUE, new ArrayList<>(queue));
        intent.putExtra(PlaybackService.EXTRA_INDEX, index);
        intent.putExtra(PlaybackService.EXTRA_SHUFFLE, shuffle);
        showPlayer();
        MusicData.recordPlay(this, currentSong);
        startPlaybackService(intent);
    }

    private void startPlaybackService(Intent intent) {
        if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(this, intent);
        else startService(intent);
    }

    private void showPlayer() {
        currentScreen = "player";
        createShell("Đang phát");
        applyPlayerBackground(null);
        if (currentSong == null) {
            addLabel(content, "Chọn một bài hát trong Khám phá để bắt đầu.", 16, Color.LTGRAY);
            return;
        }
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        playerCoverImage = new ImageView(this);
        playerCoverImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        content.addView(playerCoverImage, new LinearLayout.LayoutParams(dp(270), dp(270)));
        loadImage(currentSong.coverUrl, playerCoverImage);
        TextView title = addLabel(content, currentSong.title, 23, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(18), 0, dp(4));
        title.setTypeface(null, Typeface.BOLD);
        TextView artist = addLabel(content, currentSong.artist, 16, Color.rgb(215, 250, 0));
        artist.setGravity(Gravity.CENTER);
        if (!"Thiết bị".equals(currentSong.provider)) {
            artist.setOnClickListener(v -> showArtist(currentSong.artist,
                    currentSong.artistSourceId, currentSong.provider));
        }
        TextView album = addLabel(content, currentSong.album, 13, Color.GRAY);
        album.setGravity(Gravity.CENTER);
        addSongInformation(currentSong);
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
        ImageButton previous = iconButton(R.drawable.ic_previous, "Bài trước");
        playerPlayButton = iconButton(R.drawable.ic_play, "Phát");
        ImageButton next = iconButton(R.drawable.ic_next, "Bài tiếp theo");
        controls.addView(previous); controls.addView(playerPlayButton); controls.addView(next);
        content.addView(controls);
        previous.setOnClickListener(v -> sendPlaybackAction(PlaybackService.ACTION_PREVIOUS));
        next.setOnClickListener(v -> sendPlaybackAction(PlaybackService.ACTION_NEXT));
        playerPlayButton.setOnClickListener(v -> sendPlaybackAction(PlaybackService.ACTION_TOGGLE));
        LinearLayout options = new LinearLayout(this);
        options.setGravity(Gravity.CENTER);
        ImageButton add = iconButton(R.drawable.ic_add_playlist, "Thêm vào playlist");
        ImageButton share = iconButton(R.drawable.ic_share, "Chia sẻ");
        ImageButton lyrics = iconButton(R.drawable.ic_lyrics, "Lời bài hát");
        ImageButton download = iconButton(R.drawable.ic_downloads, "Tải bài hát về máy");
        options.addView(add); options.addView(share); options.addView(lyrics); options.addView(download);
        content.addView(options);
        add.setOnClickListener(v -> choosePlaylist());
        share.setOnClickListener(v -> shareSong());
        lyrics.setOnClickListener(v -> showLyrics());
        download.setOnClickListener(v -> downloadCurrentSong());
        if (currentQueueIsPlaylist) {
            ImageButton shuffleButton = iconButton(R.drawable.ic_shuffle,
                    shuffle ? "Tắt tráo bài" : "Bật tráo bài");
            shuffleButton.setImageTintList(android.content.res.ColorStateList.valueOf(
                    shuffle ? Color.rgb(215, 250, 0) : Color.LTGRAY));
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
                if (playerPlayButton != null) {
                    playerPlayButton.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
                    playerPlayButton.setContentDescription(playing ? "Tạm dừng" : "Phát");
                }
            });
        });
    }

    private void downloadCurrentSong() {
        if (currentSong == null) return;
        Uri source = Uri.parse(currentSong.audioUrl);
        String scheme = source.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            Toast.makeText(this, "Bài hát này đã có trên thiết bị hoặc không có đường dẫn tải.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        try {
            java.io.File directory = new java.io.File(
                    getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Musibility");
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new java.io.IOException("Không thể tạo thư mục nhạc đã tải.");
            }
            String title = currentSong.title.isEmpty() ? "Bai hat" : currentSong.title;
            String fileName = title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
            if (fileName.isEmpty()) fileName = "Bai hat";
            fileName += "-" + System.currentTimeMillis() + ".mp3";

            DownloadManager.Request request = new DownloadManager.Request(source)
                    .setTitle(currentSong.title)
                    .setDescription("Đang tải về Musibility")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setAllowedOverMetered(true)
                    .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_MUSIC,
                            "Musibility/" + fileName);
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (manager == null) throw new java.io.IOException("Dịch vụ tải xuống của Android không khả dụng.");
            manager.enqueue(request);
            Toast.makeText(this, "Đã bắt đầu tải. Bài hát sẽ xuất hiện trong mục Tải về.",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            showError(e);
        }
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

    private void showDownloads() {
        currentScreen = "downloads";
        createShell("Nhạc trên thiết bị");
        if (!hasAudioLibraryPermission()) {
            addLabel(content, "Cấp quyền để duyệt nhạc khác trên thiết bị. Nhạc tải bằng Musibility vẫn có thể phát bên dưới.", 14, Color.LTGRAY);
            Button grant = button("Cho phép truy cập nhạc");
            content.addView(grant, matchWrap());
            grant.setOnClickListener(v -> requestAudioLibraryPermission());
            runAsync(this::loadAppDownloadedSongs, songs -> {
                if (songs.isEmpty()) {
                    addLabel(content, "Chưa có bài hát nào được tải bằng Musibility.", 14, Color.GRAY);
                    return;
                }
                addLabel(content, songs.size() + " bài hát đã tải bằng Musibility", 13, Color.LTGRAY);
                renderSongs(songs, false);
            });
            return;
        }
        Button refresh = button("↻  Làm mới thư viện");
        content.addView(refresh, matchWrap());
        addLabel(content, "Đang quét các tệp âm thanh trên thiết bị…", 14, Color.LTGRAY);
        refresh.setOnClickListener(v -> showDownloads());
        runAsync(this::loadDeviceSongs, songs -> {
            content.removeViews(1, content.getChildCount() - 1);
            if (songs.isEmpty()) {
                addLabel(content, "Chưa tìm thấy tệp nhạc. Hãy tải bài hát về thiết bị rồi làm mới thư viện.", 14, Color.GRAY);
                return;
            }
            addLabel(content, songs.size() + " bài hát trên thiết bị", 13, Color.LTGRAY);
            renderSongs(songs, false);
        });
    }

    private boolean hasAudioLibraryPermission() {
        String permission = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        return Build.VERSION.SDK_INT < 23
                || ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAudioLibraryPermission() {
        String permission = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        requestPermissions(new String[]{permission}, AUDIO_LIBRARY_PERMISSION);
    }

    private List<Song> loadDeviceSongs() throws Exception {
        List<Song> songs = new ArrayList<>();
        String[] columns = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.TRACK
        };
        String[] queryColumns = columns;
        boolean hasRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
        if (hasRelativePath) {
            queryColumns = new String[columns.length + 1];
            System.arraycopy(columns, 0, queryColumns, 0, columns.length);
            queryColumns[columns.length] = MediaStore.Audio.Media.RELATIVE_PATH;
        }
        try (Cursor cursor = getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, queryColumns, null, null,
                MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")) {
            if (cursor == null) throw new java.io.IOException("Không thể đọc thư viện âm thanh trên thiết bị.");
            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            int artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            int albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
            int durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            int albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
            int displayNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
            int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);
            int mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE);
            int trackColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK);
            int pathColumn = hasRelativePath
                    ? cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH) : -1;
            while (cursor.moveToNext()) {
                String mimeType = cursor.getString(mimeColumn);
                if (mimeType != null && !mimeType.startsWith("audio/")) continue;
                long mediaId = cursor.getLong(idColumn);
                Uri audioUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId);
                String displayName = value(cursor, displayNameColumn);
                String title = cleanMediaValue(value(cursor, titleColumn));
                if (title.isEmpty()) title = withoutExtension(displayName);
                String artist = cleanMediaValue(value(cursor, artistColumn));
                String album = cleanMediaValue(value(cursor, albumColumn));
                long durationMs = cursor.getLong(durationColumn);
                long albumId = cursor.getLong(albumIdColumn);
                String coverUri = albumId > 0
                        ? ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId).toString()
                        : "";
                String albumArtist = "";
                String genre = "";
                String year = "";
                String trackNumber = value(cursor, trackColumn);
                int bitrate = 0;
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                try {
                    retriever.setDataSource(this, audioUri);
                    title = cleanMediaValue(metadata(retriever, MediaMetadataRetriever.METADATA_KEY_TITLE, title));
                    artist = cleanMediaValue(metadata(retriever, MediaMetadataRetriever.METADATA_KEY_ARTIST, artist));
                    album = cleanMediaValue(metadata(retriever, MediaMetadataRetriever.METADATA_KEY_ALBUM, album));
                    albumArtist = cleanMediaValue(metadata(retriever,
                            MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST, ""));
                    genre = metadata(retriever, MediaMetadataRetriever.METADATA_KEY_GENRE, "");
                    year = metadata(retriever, MediaMetadataRetriever.METADATA_KEY_YEAR, "");
                    trackNumber = metadata(retriever, MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER, trackNumber);
                    String metadataDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if (durationMs <= 0 && metadataDuration != null) durationMs = Long.parseLong(metadataDuration);
                    String metadataBitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
                    if (metadataBitrate != null) bitrate = Integer.parseInt(metadataBitrate) / 1000;
                    byte[] embeddedArt = retriever.getEmbeddedPicture();
                    if (embeddedArt != null) coverUri = cacheCover(mediaId, embeddedArt, coverUri);
                } catch (Exception e) {
                    android.util.Log.w("Musibility", "Could not read audio metadata for " + displayName, e);
                } finally {
                    try { retriever.release(); } catch (Exception e) {
                        android.util.Log.w("Musibility", "Could not release metadata retriever", e);
                    }
                }
                String filePath = pathColumn >= 0 ? value(cursor, pathColumn) : "";
                songs.add(new Song(-mediaId - 1, title, artist, 0, album, coverUri,
                        audioUri.toString(), "", (int) Math.max(0, durationMs / 1000), "",
                        "Thiết bị", String.valueOf(mediaId), "", albumArtist, genre, year,
                        trackNumber, displayName, filePath, mimeType, cursor.getLong(sizeColumn), bitrate));
            }
        }
        songs.addAll(loadAppDownloadedSongs());
        return songs;
    }

    private List<Song> loadAppDownloadedSongs() {
        List<Song> songs = new ArrayList<>();
        java.io.File musicDirectory = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (musicDirectory == null) return songs;
        java.io.File downloadDirectory = new java.io.File(musicDirectory, "Musibility");
        java.io.File[] files = downloadDirectory.listFiles(file -> file.isFile()
                && file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".mp3"));
        if (files == null) return songs;
        for (java.io.File file : files) {
            String title = withoutExtension(file.getName()).replaceFirst("-\\d{13}$", "");
            String artist = "";
            String album = "";
            String cover = "";
            long durationMs = 0;
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(file.getAbsolutePath());
                title = metadata(retriever, MediaMetadataRetriever.METADATA_KEY_TITLE, title);
                artist = metadata(retriever, MediaMetadataRetriever.METADATA_KEY_ARTIST, "");
                album = metadata(retriever, MediaMetadataRetriever.METADATA_KEY_ALBUM, "");
                String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (duration != null) durationMs = Long.parseLong(duration);
                byte[] embeddedArt = retriever.getEmbeddedPicture();
                if (embeddedArt != null) {
                    long coverId = -Math.abs((long) file.getAbsolutePath().hashCode()) - 1;
                    cover = cacheCover(coverId, embeddedArt, "");
                }
            } catch (Exception e) {
                android.util.Log.w("Musibility", "Could not read downloaded audio metadata for "
                        + file.getName(), e);
            } finally {
                try { retriever.release(); } catch (Exception e) {
                    android.util.Log.w("Musibility", "Could not release metadata retriever", e);
                }
            }
            long id = -Math.abs((long) file.getAbsolutePath().hashCode()) - 1;
            songs.add(new Song(id, title, artist, 0, album, cover,
                    Uri.fromFile(file).toString(), "", (int) Math.max(0, durationMs / 1000), "",
                    "Đã tải", String.valueOf(id), "", "", "", "", "", file.getName(),
                    file.getAbsolutePath(), "audio/mpeg", file.length(), 0));
        }
        return songs;
    }

    private String value(Cursor cursor, int column) {
        if (column < 0 || cursor.isNull(column)) return "";
        return cursor.getString(column);
    }

    private String cleanMediaValue(String value) {
        return value == null || value.equalsIgnoreCase("<unknown>") ? "" : value.trim();
    }

    private String metadata(MediaMetadataRetriever retriever, int key, String fallback) {
        String value = retriever.extractMetadata(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private String withoutExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private String cacheCover(long mediaId, byte[] image, String fallback) {
        java.io.File cover = new java.io.File(getCacheDir(), "album-art/" + mediaId + ".jpg");
        try {
            java.io.File directory = cover.getParentFile();
            if (directory == null || (!directory.exists() && !directory.mkdirs())) {
                throw new java.io.IOException("Không thể tạo bộ nhớ đệm ảnh bìa.");
            }
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(cover)) {
                output.write(image);
            }
            return Uri.fromFile(cover).toString();
        } catch (Exception e) {
            android.util.Log.w("Musibility", "Could not cache embedded album art", e);
            return fallback;
        }
    }

    private void addSongInformation(Song song) {
        addLabel(content, "Thông tin bài hát", 14, Color.rgb(215, 250, 0));
        addInformationLine("Nguồn", song.provider);
        addInformationLine("Nghệ sĩ album", song.albumArtist);
        addInformationLine("Thể loại", song.genre);
        addInformationLine("Năm phát hành", song.year);
        addInformationLine("Số thứ tự", song.trackNumber);
        if (song.durationSeconds > 0) addInformationLine("Thời lượng", formatTime(song.durationSeconds));
        addInformationLine("Tệp", song.fileName);
        addInformationLine("Thư mục", song.filePath);
        addInformationLine("Định dạng", audioFormat(song.mimeType));
        if (song.fileSizeBytes > 0) addInformationLine("Dung lượng", formatFileSize(song.fileSizeBytes));
        if (song.bitrate > 0) addInformationLine("Bitrate", song.bitrate + " kbps");
    }

    private void addInformationLine(String label, String value) {
        if (value == null || value.trim().isEmpty()) return;
        addLabel(content, label + ": " + value, 12, Color.LTGRAY);
    }

    private String audioFormat(String mimeType) {
        if (mimeType == null || mimeType.isEmpty()) return "";
        String format = mimeType.startsWith("audio/") ? mimeType.substring(6) : mimeType;
        return format.toUpperCase(java.util.Locale.ROOT);
    }

    private String formatFileSize(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) return String.format(java.util.Locale.getDefault(), "%.2f GB", bytes / (1024f * 1024f * 1024f));
        if (bytes >= 1024L * 1024L) return String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1024f * 1024f));
        return String.format(java.util.Locale.getDefault(), "%.0f KB", bytes / 1024f);
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

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == AUDIO_PERMISSION && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) requestAndIdentify();
        if (requestCode == AUDIO_LIBRARY_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) showDownloads();
            else Toast.makeText(this, "Cần quyền đọc âm thanh để hiển thị nhạc trên thiết bị.", Toast.LENGTH_LONG).show();
        }
    }

    private void loadImage(String url, ImageView target) {
        if (url == null || url.isEmpty()) return;
        worker.execute(() -> {
            try {
                Uri uri = Uri.parse(url);
                android.graphics.Bitmap bitmap;
                if ("content".equals(uri.getScheme())) {
                    try (java.io.InputStream input = getContentResolver().openInputStream(uri)) {
                        bitmap = input == null ? null : BitmapFactory.decodeStream(input);
                    }
                } else if ("file".equals(uri.getScheme())) {
                    bitmap = BitmapFactory.decodeFile(uri.getPath());
                } else {
                    java.net.URLConnection connection = new URL(url).openConnection();
                    connection.setConnectTimeout(8000);
                    connection.setReadTimeout(8000);
                    try (java.io.InputStream input = connection.getInputStream()) {
                        bitmap = BitmapFactory.decodeStream(input);
                    }
                }
                if (bitmap != null) mainHandler.post(() -> {
                    target.setImageBitmap(bitmap);
                    if (target == playerCoverImage) applyPlayerBackground(bitmap);
                });
            } catch (Exception e) { android.util.Log.w("Musibility", "Could not load album art", e); }
        });
    }

    private void applyPlayerBackground(android.graphics.Bitmap artwork) {
        int red = 25;
        int green = 48;
        int blue = 70;
        if (artwork != null && artwork.getWidth() > 0 && artwork.getHeight() > 0) {
            long totalRed = 0;
            long totalGreen = 0;
            long totalBlue = 0;
            int count = 0;
            int stepX = Math.max(1, artwork.getWidth() / 24);
            int stepY = Math.max(1, artwork.getHeight() / 24);
            for (int y = 0; y < artwork.getHeight(); y += stepY) {
                for (int x = 0; x < artwork.getWidth(); x += stepX) {
                    int pixel = artwork.getPixel(x, y);
                    if (Color.alpha(pixel) < 128) continue;
                    totalRed += Color.red(pixel);
                    totalGreen += Color.green(pixel);
                    totalBlue += Color.blue(pixel);
                    count++;
                }
            }
            if (count > 0) {
                red = (int) (totalRed / count);
                green = (int) (totalGreen / count);
                blue = (int) (totalBlue / count);
            }
        }
        int top = Color.rgb(Math.min(255, (int) (red * 0.72f + 24)),
                Math.min(255, (int) (green * 0.72f + 24)),
                Math.min(255, (int) (blue * 0.72f + 24)));
        int bottom = Color.rgb((int) (red * 0.30f + 4),
                (int) (green * 0.30f + 4), (int) (blue * 0.30f + 4));
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{top, bottom}));
        getWindow().setStatusBarColor(Color.rgb((int) (red * 0.52f + 10),
                (int) (green * 0.52f + 10), (int) (blue * 0.52f + 10)));
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

    private ImageButton iconButton(int icon, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setImageTintList(android.content.res.ColorStateList.valueOf(Color.rgb(215, 250, 0)));
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setContentDescription(description);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(56)));
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
