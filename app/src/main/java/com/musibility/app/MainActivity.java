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
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.musibility.app.databinding.ActivityMainBinding;
import com.musibility.app.databinding.FragmentDiscoverBinding;
import com.musibility.app.databinding.FragmentDownloadsBinding;
import com.musibility.app.databinding.FragmentIdentifyBinding;
import com.musibility.app.databinding.FragmentIdentifyResultBinding;
import com.musibility.app.databinding.FragmentLibraryBinding;
import com.musibility.app.databinding.FragmentLyricsBinding;
import com.musibility.app.databinding.FragmentPlayerBinding;
import com.musibility.app.databinding.FragmentPlaylistsBinding;
import com.musibility.app.databinding.FragmentVoiceConversionBinding;
import com.musibility.app.databinding.ItemSongBinding;
import com.musibility.app.databinding.LayoutMiniPlayerBinding;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int AUDIO_PERMISSION = 41;
    private static final int AUDIO_LIBRARY_PERMISSION = 43;
    private static final int VOICE_AUDIO_PICKER = 9051;
    private static final String VOICE_PREFS = "voice_conversion_settings";
    private static final String DEFAULT_VOICE_SERVER = "";
    private static final int IDENTIFY_SAMPLE_RATE = 44100;
    private static final int IDENTIFY_SAMPLE_SECONDS = 10;

    private ActivityMainBinding binding;
    private LayoutMiniPlayerBinding miniPlayerBinding;

    private final ExecutorService worker = Executors.newFixedThreadPool(3);
    private final Handler mainHandler = new Handler();
    private final ArrayList<Song> queue = new ArrayList<>();

    private Song currentSong;
    private boolean shuffle;
    private boolean repeatOne;
    private boolean currentQueueIsPlaylist;
    private String currentScreen = "discover";

    private OnBackPressedCallback backToDiscoverCallback;
    private int screenGeneration;

    private List<String> lyricLines = new ArrayList<>();
    private List<Long> lyricTimes = new ArrayList<>();

    // Player UI elements
    private TextView playerTime;
    private SeekBar playerSeekBar;
    private ImageButton playerPlayPauseButton;
    private ImageButton playerRepeatButton;
    private ImageButton playerShuffleButton;

    // Voice Conversion State
    private EditText voiceServerInput;
    private EditText voiceApiKeyInput;
    private Spinner voiceSpinner;
    private ArrayAdapter<VoiceOption> voiceSpinnerAdapter;
    private final ArrayList<VoiceOption> availableVoices = new ArrayList<>();
    private Uri selectedVoiceAudioUri;
    private String selectedVoiceAudioName = "";
    private TextView selectedVoiceAudioLabel;
    private TextView voiceStatusLabel;
    private TextView voiceResultPathLabel;
    private ProgressBar voiceProgressBar;
    private Button createVoiceJobButton;
    private Button playVoiceResultButton;
    private volatile boolean voiceJobRunning;
    private volatile String voiceStatusText = "Chọn nhạc, chọn voice rồi nhấn Tạo bản đổi giọng.";
    private volatile int voiceProgress = -1;
    private volatile String activeVoiceJobId = "";
    private File voiceResultFile;

    private TextView lyricsText;
    private LinearLayout identifyResultContainer;
    private Button identifyButton;
    private boolean identifyInProgress;
    private Song identifiedSong;

    private final Runnable lyricsTicker = new Runnable() {
        @Override
        public void run() {
            if (lyricsText != null && currentSong != null && !lyricLines.isEmpty()) {
                int position = getSharedPreferences("musibility_widget", MODE_PRIVATE).getInt("position", 0);
                int active = 0;
                for (int i = 0; i < lyricTimes.size(); i++) {
                    if (lyricTimes.get(i) <= position) active = i;
                }
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
        @Override
        public void onReceive(Context context, Intent intent) {
            Object stateSong = intent.getSerializableExtra("song");
            if (stateSong instanceof Song && (currentSong == null || currentSong.id != ((Song) stateSong).id)) {
                currentSong = (Song) stateSong;
                if ("player".equals(currentScreen)) showPlayer();
                else updateMiniPlayerSong();
            }
            boolean playing = intent.getBooleanExtra("playing", false);
            updateMiniPlayerPlayback(playing);
            repeatOne = intent.getBooleanExtra(PlaybackService.EXTRA_REPEAT_ONE, repeatOne);
            updatePlayerPlaybackControls(playing);
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

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MusicData.clearListeningLocations(this);
        getWindow().setStatusBarColor(Color.rgb(1, 1, 1));
        getWindow().setNavigationBarColor(Color.rgb(1, 1, 1));

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        View root = binding.getRoot();
        int initialPaddingLeft = root.getPaddingLeft();
        int initialPaddingTop = root.getPaddingTop();
        int initialPaddingRight = root.getPaddingRight();
        int initialPaddingBottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets systemBars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(
                    initialPaddingLeft + systemBars.left,
                    initialPaddingTop + systemBars.top,
                    initialPaddingRight + systemBars.right,
                    initialPaddingBottom + systemBars.bottom + dp(8));
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);

        IntentFilter filter = new IntentFilter(PlaybackService.ACTION_STATE);
        ContextCompat.registerReceiver(this, playbackReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

        backToDiscoverCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                if ("identify_result".equals(currentScreen)) {
                    identifiedSong = null;
                    showIdentify();
                } else if ("identify_history".equals(currentScreen)) {
                    if (identifiedSong != null) showIdentifyResult();
                    else showIdentify();
                } else {
                    showDiscover();
                }
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backToDiscoverCallback);

        restoreIdentifiedSong(savedInstanceState);
        if (savedInstanceState != null
                && savedInstanceState.getBoolean("identify_result_visible")
                && identifiedSong != null) {
            showIdentifyResult();
        } else {
            showDiscover();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("identify_result_visible", "identify_result".equals(currentScreen));
        if (identifiedSong == null) return;
        outState.putLong("identified_song_id", identifiedSong.id);
        outState.putString("identified_song_title", identifiedSong.title);
        outState.putString("identified_song_artist", identifiedSong.artist);
        outState.putString("identified_song_album", identifiedSong.album);
        outState.putString("identified_song_cover", identifiedSong.coverUrl);
        outState.putString("identified_song_link", identifiedSong.link);
    }

    private void restoreIdentifiedSong(@Nullable Bundle savedInstanceState) {
        if (savedInstanceState == null) return;
        String title = savedInstanceState.getString("identified_song_title", "");
        String artist = savedInstanceState.getString("identified_song_artist", "");
        if (title.isEmpty() || artist.isEmpty()) return;
        String link = savedInstanceState.getString("identified_song_link", "");
        identifiedSong = new Song(
                savedInstanceState.getLong("identified_song_id"),
                title,
                artist,
                0,
                savedInstanceState.getString("identified_song_album", ""),
                savedInstanceState.getString("identified_song_cover", ""),
                "",
                link,
                0,
                "",
                "AudD",
                link,
                artist
        );
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(playbackReceiver); } catch (IllegalArgumentException ignored) { }
        worker.shutdown();
        mainHandler.removeCallbacks(lyricsTicker);
        super.onDestroy();
    }

    private void createShell(String title) {
        screenGeneration++;
        backToDiscoverCallback.setEnabled(!"discover".equals(currentScreen));
        binding.mainContent.removeAllViews();
        binding.mainScroll.scrollTo(0, 0);

        boolean fullPlayer = "player".equals(currentScreen);
        if (!fullPlayer && currentSong != null) {
            showMiniPlayer();
        } else {
            binding.miniPlayerContainer.setVisibility(View.GONE);
        }

        renderBottomNavigation();
    }

    private void showMiniPlayer() {
        binding.miniPlayerContainer.setVisibility(View.VISIBLE);
        if (miniPlayerBinding == null) {
            binding.miniPlayerContainer.removeAllViews();
            miniPlayerBinding = LayoutMiniPlayerBinding.inflate(getLayoutInflater(), binding.miniPlayerContainer, true);
            miniPlayerBinding.miniPlayerRoot.setOnClickListener(v -> showPlayer());
            miniPlayerBinding.miniBtnAddPlaylist.setOnClickListener(v -> choosePlaylist());
            miniPlayerBinding.miniBtnPlay.setOnClickListener(v -> sendPlaybackAction(PlaybackService.ACTION_TOGGLE));
        }
        updateMiniPlayerSong();
        updateMiniPlayerPlayback(getSharedPreferences("musibility_widget", MODE_PRIVATE).getBoolean("playing", false));
    }

    private void updateMiniPlayerSong() {
        if (currentSong == null || miniPlayerBinding == null) return;
        miniPlayerBinding.miniTitle.setText(currentSong.title);
        miniPlayerBinding.miniArtist.setText(currentSong.artist);
        loadImage(currentSong.coverUrl, miniPlayerBinding.miniCoverImage);
    }

    private void updateMiniPlayerPlayback(boolean playing) {
        if (miniPlayerBinding == null) return;
        miniPlayerBinding.miniBtnPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private void renderBottomNavigation() {
        binding.bottomNavigation.removeAllViews();
        String[][] tabs = {
                {getString(R.string.nav_discover), "discover"},
                {getString(R.string.nav_library), "library"},
                {getString(R.string.nav_player), "player"},
                {getString(R.string.nav_voice_conversion), "voiceconversion"},
                {getString(R.string.nav_identify), "identify"}
        };
        int[] tabIcons = {
                R.drawable.ic_home,
                R.drawable.ic_playlist,
                R.drawable.ic_play,
                R.drawable.ic_voice_conversion,
                R.drawable.ic_mic
        };

        for (int i = 0; i < tabs.length; i++) {
            final String[] tab = tabs[i];
            boolean isSelected = tab[1].equals(currentScreen)
                    || ("identify".equals(tab[1]) && currentScreen.startsWith("identify_"));
            boolean isCenterPlayer = "player".equals(tab[1]);

            int tint = isSelected ? Color.rgb(215, 250, 0) : Color.LTGRAY;

            LinearLayout item = new LinearLayout(this);
            item.setGravity(Gravity.CENTER);
            item.setOrientation(LinearLayout.VERTICAL);

            ImageView icon = new ImageView(this);
            icon.setImageResource(tabIcons[i]);

            if (isCenterPlayer) {
                icon.setBackgroundResource(R.drawable.bg_purple_circle);
                icon.setPadding(dp(6), dp(6), dp(6), dp(6));
                icon.setColorFilter(isSelected ? Color.rgb(215, 250, 0) : Color.WHITE);
                item.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
            } else {
                icon.setColorFilter(tint);
                item.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
            }

            TextView label = new TextView(this);
            label.setText(tab[0]);
            label.setTextSize(9);
            label.setGravity(Gravity.CENTER);
            label.setTextColor(tint);
            label.setMaxLines(1);
            item.addView(label);

            item.setPadding(dp(2), dp(4), dp(2), dp(4));
            item.setOnClickListener(v -> navigate(tab[1]));

            binding.bottomNavigation.addView(item, new LinearLayout.LayoutParams(0, dp(58), 1));
        }
    }

    private void navigate(String page) {
        if ("discover".equals(page)) showDiscover();
        else if ("library".equals(page) || "playlists".equals(page) || "downloads".equals(page)) showLibrary();
        else if ("voiceconversion".equals(page)) showVoiceConversion();
        else if ("identify".equals(page)) showIdentify();
        else showPlayer();
    }

    // -------------------- 1. Khám phá --------------------
    private void showDiscover() {
        currentScreen = "discover";
        createShell(getString(R.string.nav_discover));

        FragmentDiscoverBinding b = FragmentDiscoverBinding.inflate(getLayoutInflater(), binding.mainContent, true);
        b.btnSearch.setOnClickListener(v -> performSearch(b.searchField.getText().toString()));
        b.searchField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch(b.searchField.getText().toString());
                return true;
            }
            return false;
        });

        b.btnShortcutChart.setOnClickListener(v -> loadChart());
        b.btnShortcutPlaylists.setOnClickListener(v -> showLibrary());
        b.btnShortcutIdentify.setOnClickListener(v -> showIdentify());

        renderDiscoverDefaultContent(b.discoverContentContainer);
    }

    private void performSearch(String searchQuery) {
        final String query = searchQuery.trim();
        if (query.isEmpty()) return;
        currentScreen = "discover";
        createShell("Tìm kiếm: " + query);
        runAsync(() -> MusicCatalog.searchTracks(query), this::addSongsToContainer);
    }

    private void renderDiscoverDefaultContent(LinearLayout container) {
        container.removeAllViews();
        LinearLayout chartPanel = discoverPanel(new int[]{Color.rgb(35, 61, 77), Color.rgb(31, 40, 65)});
        TextView chartHeading = addLabel(chartPanel, getString(R.string.trending_title), 19, Color.WHITE);
        chartHeading.setTypeface(null, Typeface.BOLD);
        addLabel(chartPanel, getString(R.string.trending_subtitle), 13, Color.rgb(194, 210, 220));

        LinearLayout chartCards = new LinearLayout(this);
        chartCards.setOrientation(LinearLayout.HORIZONTAL);
        chartPanel.addView(chartCards, matchWrap());
        container.addView(chartPanel, matchWrap());

        LinearLayout recommendations = discoverPanel(new int[]{Color.rgb(70, 45, 84), Color.rgb(45, 38, 76)});
        TextView recommendationTitle = addLabel(recommendations, getString(R.string.recommendations_title), 19, Color.WHITE);
        recommendationTitle.setTypeface(null, Typeface.BOLD);

        List<Song> recent = MusicData.recentSongs(this);
        if (recent.isEmpty()) {
            addLabel(recommendations, "Nghe một vài bài để nhận gợi ý phù hợp với gu của bạn.", 14, Color.rgb(218, 206, 228));
        } else {
            Song latest = recent.get(0);
            addLabel(recommendations, "Dựa trên " + latest.artist, 13, Color.rgb(218, 206, 228));
            LinearLayout recommendationCards = new LinearLayout(this);
            recommendationCards.setOrientation(LinearLayout.HORIZONTAL);
            recommendations.addView(recommendationCards, matchWrap());
            runAsync(() -> {
                List<Song> similar = MusicCatalog.similar(latest);
                List<Song> filtered = new ArrayList<>();
                for (Song song : similar) {
                    if (song.id != latest.id) filtered.add(song);
                }
                return filtered;
            }, list -> renderDiscoverSongs(recommendationCards, list));
        }
        container.addView(recommendations, matchWrap());
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

    private void renderDiscoverSongs(LinearLayout container, List<Song> songs) {
        container.removeAllViews();
        if (songs.isEmpty()) {
            addLabel(container, "Chưa có bài hát.", 13, Color.LTGRAY);
            return;
        }
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        for (int i = 0; i < songs.size(); i++) {
            final int index = i;
            Song song = songs.get(i);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(4), dp(4), dp(4), dp(4));

            ImageView cover = new ImageView(this);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            loadImage(song.coverUrl, cover);

            card.addView(cover, new LinearLayout.LayoutParams(dp(120), dp(120)));

            TextView songTitle = addLabel(card, song.title, 13, Color.WHITE);
            songTitle.setMaxLines(1);
            songTitle.setTypeface(null, Typeface.BOLD);

            TextView songArtist = addLabel(card, song.artist, 11, Color.LTGRAY);
            songArtist.setMaxLines(1);

            card.setOnClickListener(v -> startQueue(songs, index, false));
            row.addView(card, new LinearLayout.LayoutParams(dp(128), -2));
        }
        scroll.addView(row);
        container.addView(scroll, matchWrap());
    }

    private void addSongsToContainer(List<Song> songs) {
        if (songs == null || songs.isEmpty()) {
            addLabel(binding.mainContent, "Không tìm thấy bài hát phù hợp.", 14, Color.LTGRAY);
            return;
        }
        for (int i = 0; i < songs.size(); i++) {
            final int songIndex = i;
            Song song = songs.get(i);
            ItemSongBinding item = ItemSongBinding.inflate(getLayoutInflater(), binding.mainContent, false);
            item.songTitle.setText(song.title);
            item.songArtist.setText(song.artist);
            loadImage(song.coverUrl, item.songCover);
            item.songItemRoot.setOnClickListener(v -> startQueue(songs, songIndex, false));
            binding.mainContent.addView(item.getRoot());
        }
    }

    private void loadChart() {
        currentScreen = "chart";
        createShell(getString(R.string.tab_chart));
        runAsync(MusicCatalog::trending, songs -> {
            for (int i = 0; i < songs.size(); i++) {
                final int index = i;
                Song song = songs.get(i);
                ItemSongBinding item = ItemSongBinding.inflate(getLayoutInflater(), binding.mainContent, false);
                item.songTitle.setText((i + 1) + ".  " + song.title);
                item.songArtist.setText(song.artist);
                loadImage(song.coverUrl, item.songCover);
                item.songItemRoot.setOnClickListener(v -> startQueue(songs, index, false));
                binding.mainContent.addView(item.getRoot());
            }
        });
    }

    // -------------------- 2. Thư viện (Playlist & Tải về) --------------------
    private void showLibrary() {
        currentScreen = "library";
        createShell(getString(R.string.nav_library));

        FragmentLibraryBinding b = FragmentLibraryBinding.inflate(getLayoutInflater(), binding.mainContent, true);

        // --- Playlists section ---
        b.btnCreatePlaylist.setOnClickListener(v -> createPlaylistDialog());
        List<String> names = MusicData.playlistNames(this);
        b.playlistsContainer.removeAllViews();
        if (names.isEmpty()) {
            addLabel(b.playlistsContainer, "Chưa có playlist nào. Hãy tạo mới!", 14, Color.LTGRAY);
        } else {
            for (String name : names) {
                Button item = purpleButton("♫  " + name + "  ·  " + MusicData.playlistSongs(this, name).size() + " bài");
                item.setOnClickListener(v -> showPlaylist(name));
                LinearLayout.LayoutParams lp = matchWrap();
                lp.setMargins(0, 0, 0, dp(8));
                b.playlistsContainer.addView(item, lp);
            }
        }

        // --- Downloads section ---
        b.btnRefreshDownloads.setOnClickListener(v -> showLibrary());

        boolean hasPerm = hasAudioLibraryPermission();

        if (!hasPerm) {
            b.btnGrantStoragePermission.setVisibility(View.VISIBLE);
            b.btnGrantStoragePermission.setOnClickListener(v -> {
                String perm = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
                requestPermissions(new String[]{perm}, AUDIO_LIBRARY_PERMISSION);
            });
        } else {
            b.btnGrantStoragePermission.setVisibility(View.GONE);
        }

        runAsync(() -> loadAppDownloadedSongs(hasPerm), songs -> {
            b.downloadsContainer.removeAllViews();
            if (songs.isEmpty()) {
                b.lblDownloadsStatus.setText("Chưa tìm thấy file âm thanh nào trên thiết bị.");
            } else {
                b.lblDownloadsStatus.setText(songs.size() + " bài hát trên máy");
                for (int i = 0; i < songs.size(); i++) {
                    final int songIndex = i;
                    Song song = songs.get(i);
                    ItemSongBinding item = ItemSongBinding.inflate(getLayoutInflater(), b.downloadsContainer, false);
                    item.songTitle.setText(song.title);
                    item.songArtist.setText(song.artist.isEmpty() ? "Thiết bị" : song.artist);
                    loadImage(song.coverUrl, item.songCover);
                    item.songItemRoot.setOnClickListener(v -> startQueue(songs, songIndex, false));
                    b.downloadsContainer.addView(item.getRoot());
                }
            }
        });
    }

    private boolean hasAudioLibraryPermission() {
        String permission = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == AUDIO_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && "identify".equals(currentScreen) && identifyResultContainer != null) {
                requestAndIdentify(identifyResultContainer);
            } else if ("identify".equals(currentScreen) && identifyResultContainer != null) {
                identifyResultContainer.removeAllViews();
                addLabel(identifyResultContainer, "Cần quyền microphone để nhận diện bài hát.", 15, Color.LTGRAY);
            }
        }
        if (requestCode == AUDIO_LIBRARY_PERMISSION && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && "library".equals(currentScreen)) {
            showLibrary();
        }
    }

    private void showPlaylists() {
        showLibrary();
    }

    private void showDownloads() {
        showLibrary();
    }

    private void createPlaylistDialog() {
        EditText input = new EditText(this);
        input.setHint("Tên playlist");
        new AlertDialog.Builder(this)
                .setTitle("Tạo playlist mới")
                .setView(input)
                .setPositiveButton("Tạo", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        try {
                            MusicData.createPlaylist(this, name);
                            showLibrary();
                        } catch (Exception e) {
                            showError(e);
                        }
                    }
                })
                .setNegativeButton("Hủy", null)
                .show();
    }

    private void showPlaylist(String name) {
        currentScreen = "playlist_detail";
        createShell("Playlist: " + name);
        List<Song> songs = MusicData.playlistSongs(this, name);
        if (songs.isEmpty()) {
            addLabel(binding.mainContent, "Playlist này đang trống.", 14, Color.LTGRAY);
            return;
        }
        addSongsToContainer(songs);
    }

    private List<Song> loadAppDownloadedSongs(boolean includeDeviceAudio) throws IOException {
        List<Song> songs = new ArrayList<>();
        Set<String> processedPaths = new HashSet<>();
        if (includeDeviceAudio) {
            loadDeviceAudioSongs(songs, processedPaths);
        }

        List<File> directoriesToScan = new ArrayList<>();

        File publicDownload = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (publicDownload != null) {
            directoriesToScan.add(new File(publicDownload, "Musibility"));
            directoriesToScan.add(publicDownload);
        }
        File appDownload = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (appDownload != null) {
            directoriesToScan.add(new File(appDownload, "Musibility"));
            directoriesToScan.add(appDownload);
        }
        File appMusic = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (appMusic != null) {
            directoriesToScan.add(new File(appMusic, "Musibility"));
            directoriesToScan.add(appMusic);
        }

        for (File dir : directoriesToScan) {
            if (dir == null || !dir.isDirectory()) continue;
            File[] files = dir.listFiles(file -> {
                if (!file.isFile()) return false;
                String lower = file.getName().toLowerCase(Locale.ROOT);
                return lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".flac")
                        || lower.endsWith(".m4a") || lower.endsWith(".ogg") || lower.endsWith(".aac");
            });
            if (files == null) continue;
            for (File file : files) {
                if (processedPaths.contains(file.getAbsolutePath())) continue;
                processedPaths.add(file.getAbsolutePath());

                String title = withoutExtension(file.getName()).replaceFirst("-\\d{13}$", "");
                String artist = "";
                String album = "";
                String cover = "";
                long durationMs = 0;
                try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
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
                } catch (Exception ignored) { }

                long id = -Math.abs((long) file.getAbsolutePath().hashCode()) - 1;
                String mimeType = file.getName().toLowerCase(Locale.ROOT).endsWith(".wav") ? "audio/wav" : "audio/mpeg";
                songs.add(new Song(id, title, artist, 0, album, cover,
                        Uri.fromFile(file).toString(), "", (int) Math.max(0, durationMs / 1000), "",
                        "Đã tải", String.valueOf(id), "", "", "", "", "", file.getName(),
                        file.getAbsolutePath(), mimeType, file.length(), 0));
            }
        }
        return songs;
    }

    private void loadDeviceAudioSongs(List<Song> songs, Set<String> processedPaths) throws IOException {
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATA
        };
        try (Cursor cursor = getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")) {
            if (cursor == null) {
                throw new IOException("Không thể đọc danh sách âm thanh trên thiết bị.");
            }
            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            int artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            int albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
            int durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            int mimeTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE);
            int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);
            int pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idColumn);
                String path = cursor.isNull(pathColumn) ? "" : cursor.getString(pathColumn);
                if (!path.isEmpty() && !processedPaths.add(path)) continue;

                String title = cursor.getString(titleColumn);
                String artist = cursor.getString(artistColumn);
                String album = cursor.getString(albumColumn);
                String mimeType = cursor.getString(mimeTypeColumn);
                long durationMs = cursor.getLong(durationColumn);
                long size = cursor.getLong(sizeColumn);
                Uri audioUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                String fileName = path.isEmpty() ? title : new File(path).getName();

                songs.add(new Song(id, title, artist, 0, album, "", audioUri.toString(), "",
                        (int) Math.max(0, durationMs / 1000), "", "Thiết bị", String.valueOf(id),
                        "", "", "", "", "", fileName, path, mimeType, size, 0));
            }
        }
    }

    // -------------------- 4. Đổi giọng AI --------------------
    private void showVoiceConversion() {
        currentScreen = "voiceconversion";
        createShell(getString(R.string.voice_conversion_title));

        FragmentVoiceConversionBinding b = FragmentVoiceConversionBinding.inflate(getLayoutInflater(), binding.mainContent, true);

        voiceServerInput = b.inputVoiceServer;
        voiceApiKeyInput = b.inputVoiceApiKey;
        voiceSpinner = b.voiceSpinner;
        voiceProgressBar = b.voiceProgressBar;
        voiceStatusLabel = b.lblVoiceStatus;
        voiceResultPathLabel = b.lblVoiceResultPath;
        createVoiceJobButton = b.btnCreateVoiceJob;
        playVoiceResultButton = b.btnPlayVoiceResult;
        selectedVoiceAudioLabel = b.lblSelectedAudio;

        b.btnPickAudio.setOnClickListener(v -> openVoiceAudioPicker());

        List<Song> downloadedSongs;
        try {
            downloadedSongs = loadAppDownloadedSongs(hasAudioLibraryPermission());
        } catch (IOException e) {
            showError(e);
            downloadedSongs = Collections.emptyList();
        }
        b.recentAudioContainer.removeAllViews();
        if (!downloadedSongs.isEmpty()) {
            addLabel(b.recentAudioContainer, getString(R.string.voice_recent_audio), 13, Color.rgb(215, 250, 0));
            int count = Math.min(3, downloadedSongs.size());
            for (int i = 0; i < count; i++) {
                Song song = downloadedSongs.get(i);
                String display = "♪  " + song.title + (song.artist.isEmpty() ? "" : " - " + song.artist);
                Button recentBtn = purpleButton(display);
                LinearLayout.LayoutParams recentParams = matchWrap();
                recentParams.setMargins(0, dp(2), 0, dp(4));
                b.recentAudioContainer.addView(recentBtn, recentParams);
                recentBtn.setOnClickListener(v -> {
                    selectedVoiceAudioUri = Uri.parse(song.audioUrl);
                    selectedVoiceAudioName = (song.fileName != null && !song.fileName.isEmpty()) ? song.fileName : song.title;
                    selectedVoiceAudioLabel.setText("Đã chọn: " + selectedVoiceAudioName);
                    Toast.makeText(MainActivity.this, "Đã chọn: " + song.title, Toast.LENGTH_SHORT).show();
                });
            }
        }

        voiceSpinnerAdapter = new ArrayAdapter<VoiceOption>(this, android.R.layout.simple_spinner_item, availableVoices) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                return voiceSpinnerItem(position, convertView, parent, false);
            }

            @Override
            public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                return voiceSpinnerItem(position, convertView, parent, true);
            }
        };
        b.voiceSpinner.setAdapter(voiceSpinnerAdapter);

        b.btnCreateVoiceJob.setEnabled(!voiceJobRunning);
        b.lblVoiceStatus.setText(voiceStatusText);
        b.voiceProgressBar.setVisibility(voiceJobRunning ? View.VISIBLE : View.GONE);
        b.voiceProgressBar.setIndeterminate(voiceProgress < 0);
        if (voiceProgress >= 0) b.voiceProgressBar.setProgress(voiceProgress);
        b.btnCreateVoiceJob.setOnClickListener(v -> startVoiceConversionJob());

        b.btnPlayVoiceResult.setVisibility(voiceResultFile != null && voiceResultFile.isFile() ? View.VISIBLE : View.GONE);
        b.btnPlayVoiceResult.setOnClickListener(v -> playVoiceResult());

        if (voiceResultFile != null && voiceResultFile.isFile()) {
            b.lblVoiceResultPath.setText("Đã lưu tại: " + voiceResultFile.getAbsolutePath());
        }

        b.inputVoiceServer.setText(getSharedPreferences(VOICE_PREFS, MODE_PRIVATE).getString("server_url", DEFAULT_VOICE_SERVER));
        b.inputVoiceApiKey.setText(getSharedPreferences(VOICE_PREFS, MODE_PRIVATE).getString("api_key", ""));
        b.btnLoadVoices.setOnClickListener(v -> loadVoiceOptionsFromServer());

        if (availableVoices.isEmpty()) {
            String savedServer = b.inputVoiceServer.getText().toString().trim();
            if (!savedServer.isEmpty()) loadVoiceOptionsFromServer();
            else {
                voiceStatusText = "Nhập địa chỉ IP của PC (cùng Wi-Fi), ví dụ http://192.168.1.20:8000, rồi nhấn Kiểm tra kết nối.";
                b.lblVoiceStatus.setText(voiceStatusText);
            }
        }
    }

    private View voiceSpinnerItem(int position, View convertView, android.view.ViewGroup parent,
                                  boolean dropdown) {
        TextView item = convertView instanceof TextView
                ? (TextView) convertView
                : new TextView(this);
        VoiceOption voice = position >= 0 && position < availableVoices.size()
                ? availableVoices.get(position)
                : null;
        item.setText(voice == null ? "" : voice.label);
        item.setTextColor(Color.WHITE);
        item.setTextSize(16);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(16), dp(12), dp(16), dp(12));
        item.setMinHeight(dp(48));
        item.setBackgroundResource(R.drawable.bg_purple_button);
        return item;
    }

    private void openVoiceAudioPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, VOICE_AUDIO_PICKER);
        } catch (Exception e) {
            showError(new Exception("Không mở được trình chọn file: " + e.getMessage()));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != VOICE_AUDIO_PICKER || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        selectedVoiceAudioUri = data.getData();
        try {
            if ((data.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
                getContentResolver().takePersistableUriPermission(selectedVoiceAudioUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        } catch (Exception ignored) { }
        selectedVoiceAudioName = getDisplayName(selectedVoiceAudioUri);
        if (selectedVoiceAudioName == null || selectedVoiceAudioName.trim().isEmpty()) {
            selectedVoiceAudioName = "audio_upload";
        }
        if (selectedVoiceAudioLabel != null) {
            selectedVoiceAudioLabel.setText("Đã chọn: " + selectedVoiceAudioName);
        }
    }

    private String getDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) { }
        return "audio_upload";
    }

    private void loadVoiceOptionsFromServer() {
        final String base;
        final String key;
        try {
            base = VoiceApiClient.normalizeServerUrl(voiceServerInput == null ? "" : voiceServerInput.getText().toString());
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        key = voiceApiKeyInput == null ? "" : voiceApiKeyInput.getText().toString().trim();
        getSharedPreferences(VOICE_PREFS, MODE_PRIVATE).edit().putString("server_url", base).putString("api_key", key).apply();

        setVoiceConversionStatus("Đang kết nối backend và tải danh sách voice…", true);
        worker.execute(() -> {
            try {
                List<VoiceOption> loaded = VoiceApiClient.fetchVoices(base, key);
                mainHandler.post(() -> {
                    availableVoices.clear();
                    availableVoices.addAll(loaded);
                    if (voiceSpinnerAdapter != null) voiceSpinnerAdapter.notifyDataSetChanged();
                    voiceStatusText = loaded.isEmpty()
                            ? "Kết nối được backend nhưng chưa có voice."
                            : "Đã kết nối. Tìm thấy " + loaded.size() + " voice.";
                    setVoiceConversionStatus(voiceStatusText, false);
                });
            } catch (Exception e) {
                String message = "Không kết nối được backend: " + safeMessage(e);
                mainHandler.post(() -> {
                    setVoiceConversionStatus(message, false);
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void startVoiceConversionJob() {
        if (voiceJobRunning) return;
        final String base;
        try {
            base = VoiceApiClient.normalizeServerUrl(voiceServerInput == null ? "" : voiceServerInput.getText().toString());
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        final String key = voiceApiKeyInput == null ? "" : voiceApiKeyInput.getText().toString().trim();
        final Uri audioUri = selectedVoiceAudioUri;
        final String audioName = selectedVoiceAudioName;
        if (audioUri == null) {
            Toast.makeText(this, "Hãy chọn file nhạc trước.", Toast.LENGTH_SHORT).show();
            return;
        }
        Object selected = voiceSpinner == null ? null : voiceSpinner.getSelectedItem();
        if (!(selected instanceof VoiceOption)) {
            Toast.makeText(this, "Chưa có voice.", Toast.LENGTH_LONG).show();
            return;
        }
        final String voiceId = ((VoiceOption) selected).id;
        getSharedPreferences(VOICE_PREFS, MODE_PRIVATE).edit().putString("server_url", base).putString("api_key", key).apply();
        voiceJobRunning = true;
        voiceProgress = -1;
        setVoiceConversionStatus("Đang tải bài hát lên backend…", true);

        worker.execute(() -> {
            String jobId = "";
            try {
                JSONObject created = VoiceApiClient.uploadJob(getContentResolver(), base, key, audioUri, audioName, voiceId);
                jobId = created.optString("job_id", "");
                if (jobId.isEmpty()) throw new IOException("Backend không trả về job_id: " + created);
                activeVoiceJobId = jobId;

                long deadline = System.currentTimeMillis() + 3L * 60L * 60L * 1000L;
                boolean completed = false;
                while (System.currentTimeMillis() < deadline) {
                    JobState state = VoiceApiClient.getJobState(base, key, jobId);
                    if ("completed".equalsIgnoreCase(state.status)) {
                        setVoiceConversionStatus("Xử lý xong. Đang tải file kết quả về điện thoại…", true);
                        completed = true;
                        break;
                    }
                    if ("failed".equalsIgnoreCase(state.status)) {
                        throw new IOException("Job thất bại: " + state.error);
                    }
                    String progress = state.progress >= 0 ? " (" + state.progress + "%)" : "";
                    String detail = state.message.isEmpty() ? state.status : state.message;
                    setVoiceConversionStatus("Đang xử lý: " + detail + progress, true, state.progress);
                    Thread.sleep(4000L);
                }
                if (!completed) throw new IOException("Hết thời gian chờ.");

                File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File fallbackDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                File output = VoiceApiClient.downloadVoiceResultFile(base, key, jobId, downloadDir, fallbackDir);

                try {
                    MediaScannerConnection.scanFile(this, new String[]{output.getAbsolutePath()}, new String[]{"audio/wav"}, null);
                } catch (Exception ignored) { }

                voiceResultFile = output;
                voiceJobRunning = false;
                String done = "Hoàn tất! Đã tải bản đổi giọng về điện thoại.\nJob ID: " + jobId;
                mainHandler.post(() -> {
                    voiceStatusText = done;
                    if (voiceStatusLabel != null && "voiceconversion".equals(currentScreen)) voiceStatusLabel.setText(done);
                    if (voiceProgressBar != null && "voiceconversion".equals(currentScreen)) voiceProgressBar.setVisibility(View.GONE);
                    if (createVoiceJobButton != null && "voiceconversion".equals(currentScreen)) createVoiceJobButton.setEnabled(true);
                    if (voiceResultPathLabel != null && "voiceconversion".equals(currentScreen)) voiceResultPathLabel.setText("Đã lưu: " + output.getAbsolutePath());
                    if (playVoiceResultButton != null && "voiceconversion".equals(currentScreen)) playVoiceResultButton.setVisibility(View.VISIBLE);
                    Toast.makeText(MainActivity.this, "Đổi giọng xong!", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                voiceJobRunning = false;
                String message = "Lỗi đổi giọng: " + safeMessage(e);
                setVoiceConversionStatus(message, false);
            }
        });
    }

    private void setVoiceConversionStatus(String message, boolean running) {
        setVoiceConversionStatus(message, running, -1);
    }

    private void setVoiceConversionStatus(String message, boolean running, int progress) {
        voiceStatusText = message;
        voiceProgress = progress;
        mainHandler.post(() -> {
            if (!"voiceconversion".equals(currentScreen)) return;
            if (voiceStatusLabel != null) voiceStatusLabel.setText(message);
            if (voiceProgressBar != null) {
                voiceProgressBar.setVisibility(running ? View.VISIBLE : View.GONE);
                voiceProgressBar.setIndeterminate(progress < 0);
                if (progress >= 0) voiceProgressBar.setProgress(progress);
            }
            if (createVoiceJobButton != null) createVoiceJobButton.setEnabled(!running);
        });
    }

    private void playVoiceResult() {
        if (voiceResultFile == null || !voiceResultFile.isFile()) {
            Toast.makeText(this, "Chưa có file kết quả.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            long id = -Math.abs((long) voiceResultFile.getAbsolutePath().hashCode()) - 1;
            String songTitle = (selectedVoiceAudioName == null || selectedVoiceAudioName.trim().isEmpty())
                    ? "Bản đổi giọng AI" : withoutExtension(selectedVoiceAudioName);
            String title = "Đổi giọng: " + songTitle;
            long durationMs = 0;
            try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
                retriever.setDataSource(voiceResultFile.getAbsolutePath());
                String dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (dur != null) durationMs = Long.parseLong(dur);
            } catch (Exception ignored) { }

            Song voiceSong = new Song(id, title, "AI Voice", 0, "Musibility AI", "",
                    Uri.fromFile(voiceResultFile).toString(), "", (int) Math.max(0, durationMs / 1000), "",
                    "Đổi giọng AI", String.valueOf(id), "", "", "AI", "", "", voiceResultFile.getName(),
                    voiceResultFile.getAbsolutePath(), "audio/wav", voiceResultFile.length(), 0);

            startQueue(Collections.singletonList(voiceSong), 0, false);
            Toast.makeText(this, "Đang phát bản đã đổi giọng.", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            showError(new Exception("Không phát được file kết quả: " + safeMessage(e)));
        }
    }

    // -------------------- 5. Nhận diện --------------------
    private void showIdentify() {
        if (identifiedSong != null) {
            showIdentifyResult();
            return;
        }
        currentScreen = "identify";
        createShell(getString(R.string.nav_identify));

        FragmentIdentifyBinding b = FragmentIdentifyBinding.inflate(getLayoutInflater(), binding.mainContent, true);
        identifyResultContainer = b.identifyResultContainer;
        identifyButton = b.btnListenIdentify;
        identifyButton.setEnabled(!identifyInProgress);
        identifyButton.setOnClickListener(v -> requestAndIdentify(b.identifyResultContainer));
        b.btnIdentifyHistory.setOnClickListener(v -> showIdentifyHistory());
    }

    private void showIdentifyResult() {
        if (identifiedSong == null) {
            showIdentify();
            return;
        }
        currentScreen = "identify_result";
        createShell("Bài hát được nhận diện");

        FragmentIdentifyResultBinding b = FragmentIdentifyResultBinding.inflate(
                getLayoutInflater(), binding.mainContent, true);
        b.identifyResultTitle.setText(identifiedSong.title);
        b.identifyResultArtist.setText(identifiedSong.artist);
        loadImage(identifiedSong.coverUrl, b.identifyResultCover);
        b.btnCloseIdentifyResult.setOnClickListener(v -> {
            identifiedSong = null;
            showIdentify();
        });
        b.btnSearchIdentifiedSong.setOnClickListener(v ->
                performSearch(identifiedSong.title + " " + identifiedSong.artist));
        b.btnIdentifyResultHistory.setOnClickListener(v -> showIdentifyHistory());
    }

    private void showIdentifyResult(Song song) {
        identifiedSong = song;
        showIdentifyResult();
    }

    private void showIdentifyHistory() {
        currentScreen = "identify_history";
        createShell("Lịch sử nhận diện");

        Button back = purpleButton("‹  Quay lại nhận diện");
        back.setOnClickListener(v -> {
            if (identifiedSong != null) showIdentifyResult();
            else showIdentify();
        });
        binding.mainContent.addView(back, matchWrap());

        TextView heading = addLabel(binding.mainContent, "Lịch sử nhận diện", 22, Color.WHITE);
        heading.setTypeface(null, Typeface.BOLD);

        List<Song> songs = MusicData.identificationHistory(this);
        if (songs.isEmpty()) {
            addLabel(binding.mainContent, "Bạn chưa nhận diện bài hát nào.", 15, Color.LTGRAY);
            return;
        }
        for (Song song : songs) {
            ItemSongBinding item = ItemSongBinding.inflate(getLayoutInflater(), binding.mainContent, false);
            item.songTitle.setText(song.title);
            item.songArtist.setText(song.artist);
            loadImage(song.coverUrl, item.songCover);
            item.songItemRoot.setOnClickListener(v -> showIdentifyResult(song));
            binding.mainContent.addView(item.getRoot());
        }
    }

    private void requestAndIdentify(LinearLayout resultContainer) {
        if (identifyInProgress) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            identifyResultContainer = resultContainer;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION);
            return;
        }
        identifyInProgress = true;
        if (identifyButton != null) identifyButton.setEnabled(false);
        resultContainer.removeAllViews();
        addLabel(resultContainer, "Đang nghe... Giữ điện thoại gần nguồn nhạc trong khoảng 10 giây.", 16, Color.WHITE);
        ProgressBar pb = new ProgressBar(this);
        resultContainer.addView(pb);

        worker.execute(() -> {
            try {
                Song result = identifyWithAudD();
                mainHandler.post(() -> {
                    identifyInProgress = false;
                    if (identifyButton != null) identifyButton.setEnabled(true);
                    if (isFinishing() || isDestroyed()) return;
                    identifiedSong = result;
                    MusicData.recordIdentification(MainActivity.this, result);
                    if ("identify".equals(currentScreen)) showIdentifyResult();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    identifyInProgress = false;
                    if (identifyButton != null) identifyButton.setEnabled(true);
                    if (isFinishing() || isDestroyed() || !"identify".equals(currentScreen)
                            || identifyResultContainer != resultContainer) return;
                    resultContainer.removeAllViews();
                    addLabel(resultContainer, "Nhận diện thất bại: " + safeMessage(e), 15, Color.LTGRAY);
                    Button retry = purpleButton("Thử lại");
                    resultContainer.addView(retry);
                    retry.setOnClickListener(v -> requestAndIdentify(resultContainer));
                });
            }
        });
    }

    private Song identifyWithAudD() throws Exception {
        if (BuildConfig.AUDD_API_TOKEN.trim().isEmpty()) {
            throw new IllegalStateException(
                    "Chưa cấu hình AudD API token. Thêm audd.apiToken vào local.properties rồi build lại.");
        }

        byte[] audio = recordIdentifySample();
        byte[] wav = createWav(audio, IDENTIFY_SAMPLE_RATE);
        String boundary = "Musibility" + System.currentTimeMillis();
        byte[] requestBody = createAudDRequestBody(boundary, wav);
        HttpURLConnection connection = (HttpURLConnection) new URL("https://api.audd.io/").openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        connection.setFixedLengthStreamingMode(requestBody.length);

        try {
            try (OutputStream output = connection.getOutputStream()) {
                output.write(requestBody);
            }
            int responseCode = connection.getResponseCode();
            InputStream responseStream = responseCode >= 200 && responseCode < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            if (responseStream == null) {
                throw new IOException("AudD trả về mã HTTP " + responseCode + ".");
            }
            String response;
            try (InputStream input = responseStream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int length;
                while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
                response = output.toString(StandardCharsets.UTF_8.name());
            }
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("AudD trả về mã HTTP " + responseCode + ".");
            }

            JSONObject json = new JSONObject(response);
            if (!"success".equalsIgnoreCase(json.optString("status"))) {
                JSONObject error = json.optJSONObject("error");
                String message = error == null ? "" : error.optString("error_message", "");
                throw new IOException(message.isEmpty() ? "AudD không xử lý được bản ghi âm." : message);
            }
            JSONObject result = json.optJSONObject("result");
            if (result == null) {
                throw new IOException("AudD không tìm thấy bài hát trong bản ghi âm.");
            }
            String title = result.optString("title", "").trim();
            String artist = result.optString("artist", "").trim();
            if (title.isEmpty() || artist.isEmpty()) {
                throw new IOException("AudD trả về thông tin bài hát không đầy đủ.");
            }
            String album = result.optString("album", "").trim();
            String link = result.optString("song_link", "").trim();
            return new Song(System.currentTimeMillis(), title, artist, 0, album,
                    identifiedCoverUrl(result), "", link, 0, "",
                    "AudD", link, artist);
        } finally {
            connection.disconnect();
        }
    }

    private String identifiedCoverUrl(JSONObject result) {
        JSONObject appleMusic = result.optJSONObject("apple_music");
        JSONObject artwork = appleMusic == null ? null : appleMusic.optJSONObject("artwork");
        if (artwork != null) {
            String url = artwork.optString("url", "").trim();
            if (!url.isEmpty()) return url.replace("{w}", "600").replace("{h}", "600");
        }

        JSONObject spotify = result.optJSONObject("spotify");
        JSONObject spotifyAlbum = spotify == null ? null : spotify.optJSONObject("album");
        JSONArray images = spotifyAlbum == null ? null : spotifyAlbum.optJSONArray("images");
        if (images != null && images.length() > 0) {
            JSONObject image = images.optJSONObject(0);
            if (image != null) return image.optString("url", "").trim();
        }
        return "";
    }

    private byte[] recordIdentifySample() throws IOException {
        int minBufferSize = AudioRecord.getMinBufferSize(
                IDENTIFY_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBufferSize <= 0) throw new IOException("Thiết bị không hỗ trợ ghi âm PCM.");

        AudioRecord recorder = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                IDENTIFY_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minBufferSize, IDENTIFY_SAMPLE_RATE));
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            throw new IOException("Không thể khởi tạo microphone.");
        }

        int targetSize = IDENTIFY_SAMPLE_RATE * 2 * IDENTIFY_SAMPLE_SECONDS;
        byte[] buffer = new byte[Math.max(minBufferSize, 4096)];
        ByteArrayOutputStream audio = new ByteArrayOutputStream(targetSize);
        boolean recording = false;
        try {
            recorder.startRecording();
            recording = true;
            while (audio.size() < targetSize) {
                int bytesToRead = Math.min(buffer.length, targetSize - audio.size());
                int bytesRead = recorder.read(buffer, 0, bytesToRead, AudioRecord.READ_BLOCKING);
                if (bytesRead <= 0) throw new IOException("Không đọc được âm thanh từ microphone.");
                audio.write(buffer, 0, bytesRead);
            }
            return audio.toByteArray();
        } finally {
            if (recording) recorder.stop();
            recorder.release();
        }
    }

    private byte[] createWav(byte[] pcm, int sampleRate) throws IOException {
        ByteArrayOutputStream wav = new ByteArrayOutputStream(44 + pcm.length);
        wav.write("RIFF".getBytes(StandardCharsets.US_ASCII));
        writeLittleEndianInt(wav, 36 + pcm.length);
        wav.write("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        writeLittleEndianInt(wav, 16);
        writeLittleEndianShort(wav, 1);
        writeLittleEndianShort(wav, 1);
        writeLittleEndianInt(wav, sampleRate);
        writeLittleEndianInt(wav, sampleRate * 2);
        writeLittleEndianShort(wav, 2);
        writeLittleEndianShort(wav, 16);
        wav.write("data".getBytes(StandardCharsets.US_ASCII));
        writeLittleEndianInt(wav, pcm.length);
        wav.write(pcm);
        return wav.toByteArray();
    }

    private void writeLittleEndianInt(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >> 8) & 0xff);
        output.write((value >> 16) & 0xff);
        output.write((value >> 24) & 0xff);
    }

    private void writeLittleEndianShort(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >> 8) & 0xff);
    }

    private byte[] createAudDRequestBody(String boundary, byte[] wav) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream(wav.length + 512);
        String tokenPart = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"api_token\"\r\n\r\n"
                + BuildConfig.AUDD_API_TOKEN + "\r\n";
        body.write(tokenPart.getBytes(StandardCharsets.UTF_8));
        String returnPart = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"return\"\r\n\r\n"
                + "apple_music,spotify\r\n";
        body.write(returnPart.getBytes(StandardCharsets.UTF_8));
        String fileHeader = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"recording.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n";
        body.write(fileHeader.getBytes(StandardCharsets.UTF_8));
        body.write(wav);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return body.toByteArray();
    }

    // -------------------- 6. Trình phát full --------------------
    private void showPlayer() {
        currentScreen = "player";
        createShell(getString(R.string.nav_player));

        if (currentSong == null) {
            addLabel(binding.mainContent, "Chọn một bài hát trong Khám phá để bắt đầu.", 16, Color.LTGRAY);
            return;
        }

        FragmentPlayerBinding b = FragmentPlayerBinding.inflate(getLayoutInflater(), binding.mainContent, true);

        loadImage(currentSong.coverUrl, b.playerCoverImage);
        b.playerSongTitle.setText(currentSong.title);
        b.playerSongArtist.setText(currentSong.artist);
        b.playerSongAlbum.setText(currentSong.album);

        playerTime = b.playerTime;
        playerSeekBar = b.playerSeekbar;
        playerPlayPauseButton = b.playerBtnPlayPause;
        playerRepeatButton = b.playerBtnRepeat;
        playerShuffleButton = b.playerBtnShuffle;
        updatePlayerPlaybackControls(getSharedPreferences("musibility_widget", MODE_PRIVATE)
                .getBoolean("playing", false));

        b.playerSeekbar.setMax(Math.max(1, currentSong.durationSeconds * 1000));
        b.playerSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    Intent seek = new Intent(MainActivity.this, PlaybackService.class).setAction(PlaybackService.ACTION_SEEK);
                    seek.putExtra("position", progress);
                    startPlaybackService(seek);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        b.playerBtnPrevious.setOnClickListener(v -> sendPlaybackAction(PlaybackService.ACTION_PREVIOUS));
        b.playerBtnNext.setOnClickListener(v -> sendPlaybackAction(PlaybackService.ACTION_NEXT));
        b.playerBtnPlayPause.setOnClickListener(v -> sendPlaybackAction(PlaybackService.ACTION_TOGGLE));
        b.playerBtnShuffle.setOnClickListener(v -> {
            shuffle = !shuffle;
            Intent intent = new Intent(this, PlaybackService.class).setAction(PlaybackService.ACTION_SET_SHUFFLE);
            intent.putExtra(PlaybackService.EXTRA_SHUFFLE, shuffle);
            startPlaybackService(intent);
            b.playerBtnShuffle.setImageTintList(android.content.res.ColorStateList.valueOf(
                    shuffle ? Color.rgb(215, 250, 0) : Color.rgb(204, 204, 204)));
        });
        b.playerBtnRepeat.setOnClickListener(v -> {
            repeatOne = !repeatOne;
            Intent intent = new Intent(this, PlaybackService.class).setAction(PlaybackService.ACTION_SET_REPEAT_ONE);
            intent.putExtra(PlaybackService.EXTRA_REPEAT_ONE, repeatOne);
            startPlaybackService(intent);
            updatePlayerPlaybackControls(getSharedPreferences("musibility_widget", MODE_PRIVATE)
                    .getBoolean("playing", false));
        });

        b.playerBtnAddPlaylist.setOnClickListener(v -> choosePlaylist());
        b.playerBtnShare.setOnClickListener(v -> shareCurrentSong());
        b.playerBtnLyrics.setOnClickListener(v -> showLyrics());
        b.playerBtnDownload.setOnClickListener(v -> downloadCurrentSong());
    }

    private void updatePlayerPlaybackControls(boolean playing) {
        if (playerPlayPauseButton != null) {
            playerPlayPauseButton.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
            playerPlayPauseButton.setContentDescription(playing ? "Pause" : "Play");
        }
        if (playerRepeatButton != null) {
            playerRepeatButton.setImageTintList(android.content.res.ColorStateList.valueOf(
                    repeatOne ? Color.rgb(215, 250, 0) : Color.rgb(204, 204, 204)));
        }
        if (playerShuffleButton != null) {
            playerShuffleButton.setImageTintList(android.content.res.ColorStateList.valueOf(
                    shuffle ? Color.rgb(215, 250, 0) : Color.rgb(204, 204, 204)));
        }
    }

    private void showLyrics() {
        currentScreen = "lyrics";
        createShell("Lời bài hát");
        if (currentSong == null) return;

        FragmentLyricsBinding b = FragmentLyricsBinding.inflate(getLayoutInflater(), binding.mainContent, true);
        b.lyricsTitle.setText(currentSong.title + " — " + currentSong.artist);
        lyricsText = b.lyricsContent;

        runAsync(() -> {
            String api = "https://lrclib.net/api/get?artist_name=" + URLEncoder.encode(currentSong.artist, StandardCharsets.UTF_8.name())
                    + "&track_name=" + URLEncoder.encode(currentSong.title, StandardCharsets.UTF_8.name())
                    + "&duration=" + currentSong.durationSeconds;
            return fetchLyricsString(api);
        }, plain -> {
            if (plain == null || plain.isEmpty()) {
                b.lyricsContent.setText("Không tải được lời bài hát.");
            } else {
                b.lyricsContent.setText(plain);
            }
        });
    }

    private String fetchLyricsString(String urlStr) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(urlStr).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            int code = connection.getResponseCode();
            if (code == 200) {
                try (InputStream is = connection.getInputStream(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = is.read(buf)) != -1) baos.write(buf, 0, len);
                    String resp = baos.toString(StandardCharsets.UTF_8.name());
                    JSONObject json = new JSONObject(resp);
                    String plain = json.optString("plainLyrics", "");
                    String synced = json.optString("syncedLyrics", "");
                    return synced.isEmpty() ? plain : synced;
                }
            }
        } catch (Exception ignored) { }
        return "";
    }

    // -------------------- Playback Helpers --------------------
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
        intent.putExtra(PlaybackService.EXTRA_REPEAT_ONE, repeatOne);
        showPlayer();
        MusicData.recordPlay(this, currentSong);
        startPlaybackService(intent);
    }

    private void startPlaybackService(Intent intent) {
        if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(this, intent);
        else startService(intent);
    }

    private void sendPlaybackAction(String action) {
        startPlaybackService(new Intent(this, PlaybackService.class).setAction(action));
    }

    private void downloadCurrentSong() {
        if (currentSong == null) return;
        Uri source = Uri.parse(currentSong.audioUrl);
        String scheme = source.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            Toast.makeText(this, "Bài hát này đã có trên thiết bị hoặc không có đường dẫn tải.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            File directory = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Musibility");
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("Không thể tạo thư mục nhạc đã tải.");
            }
            String title = currentSong.title.isEmpty() ? "Bai hat" : currentSong.title;
            String fileName = title.replaceAll("[\\\\/:*?\"<>|]", "_").trim() + "-" + System.currentTimeMillis() + ".mp3";

            DownloadManager.Request request = new DownloadManager.Request(source)
                    .setTitle(currentSong.title)
                    .setDescription("Đang tải về Musibility")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setAllowedOverMetered(true)
                    .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "Musibility/" + fileName);

            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (manager != null) manager.enqueue(request);
            Toast.makeText(this, "Đã bắt đầu tải. Bài hát sẽ xuất hiện trong mục Tải về.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            showError(e);
        }
    }

    private void shareCurrentSong() {
        if (currentSong == null) return;
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, currentSong.title + " — " + currentSong.artist + (currentSong.link.isEmpty() ? "" : "\n" + currentSong.link));
        startActivity(Intent.createChooser(share, "Chia sẻ bài hát"));
    }

    private void choosePlaylist() {
        List<String> names = MusicData.playlistNames(this);
        if (names.isEmpty()) {
            Toast.makeText(this, "Hãy tạo playlist trước.", Toast.LENGTH_SHORT).show();
            showLibrary();
            return;
        }
        String[] items = names.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("Thêm vào playlist")
                .setItems(items, (dialog, which) -> {
                    try {
                        MusicData.addToPlaylist(this, names.get(which), currentSong);
                        Toast.makeText(this, "Đã thêm vào " + names.get(which), Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        showError(e);
                    }
                })
                .show();
    }

    // -------------------- UI Utilities --------------------
    private Button purpleButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(132, 62, 103), Color.rgb(84, 44, 94)}
        );
        background.setCornerRadius(dp(20));
        button.setBackground(background);
        button.setBackgroundTintList(null);
        return button;
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

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void loadImage(String url, ImageView target) {
        target.setImageResource(R.drawable.ic_launcher_foreground);
        if (url == null || url.trim().isEmpty()) return;
        runAsync(() -> {
            try {
                if (url.startsWith("content://") || url.startsWith("file://")) {
                    return android.graphics.BitmapFactory.decodeStream(getContentResolver().openInputStream(Uri.parse(url)));
                }
                return android.graphics.BitmapFactory.decodeStream(new java.net.URL(url).openStream());
            } catch (Exception e) {
                return null;
            }
        }, bmp -> {
            if (bmp != null) target.setImageBitmap(bmp);
        });
    }

    private String cacheCover(long mediaId, byte[] image, String fallback) {
        File cover = new File(getCacheDir(), "album-art/" + mediaId + ".jpg");
        if (cover.exists()) return Uri.fromFile(cover).toString();
        File dir = cover.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(cover)) {
            output.write(image);
            return Uri.fromFile(cover).toString();
        } catch (Exception e) {
            return fallback;
        }
    }

    private String formatTime(int totalSeconds) {
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
    }

    private String metadata(MediaMetadataRetriever retriever, int key, String fallback) {
        String value = retriever.extractMetadata(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private String withoutExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().trim().isEmpty() ? e.getClass().getSimpleName() : e.getMessage();
    }

    private void showError(Exception e) {
        Toast.makeText(this, safeMessage(e), Toast.LENGTH_LONG).show();
    }

    private interface Task<T> { T run() throws Exception; }
    private interface Consumer<T> { void accept(T result); }

    private <T> void runAsync(Task<T> task, Consumer<T> callback) {
        final int gen = screenGeneration;
        worker.execute(() -> {
            try {
                T result = task.run();
                mainHandler.post(() -> {
                    if (gen == screenGeneration && !isFinishing()) callback.accept(result);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (gen == screenGeneration && !isFinishing()) showError(e);
                });
            }
        });
    }
}
