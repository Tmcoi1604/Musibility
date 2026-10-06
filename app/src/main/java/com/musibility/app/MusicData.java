package com.musibility.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class MusicData {
    private static final String PREFS = "musibility_data";
    private MusicData() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void recordPlay(Context context, Song song, Location location) {
        SharedPreferences p = prefs(context);
        try {
            JSONArray history = new JSONArray(p.getString("history", "[]"));
            JSONObject item = toJson(song);
            history.put(item);
            while (history.length() > 50) history.remove(0);
            p.edit().putString("history", history.toString()).apply();
            if (location != null) {
                JSONArray places = new JSONArray(p.getString("places", "[]"));
                item.put("lat", location.getLatitude());
                item.put("lng", location.getLongitude());
                item.put("time", System.currentTimeMillis());
                places.put(item);
                while (places.length() > 300) places.remove(0);
                p.edit().putString("places", places.toString()).apply();
            }
        } catch (Exception e) {
            android.util.Log.e("Musibility", "Could not save listening history", e);
        }
    }

    public static void recordLocation(Context context, Song song, Location location) {
        if (location == null) return;
        try {
            JSONArray places = new JSONArray(prefs(context).getString("places", "[]"));
            JSONObject item = toJson(song);
            item.put("lat", location.getLatitude());
            item.put("lng", location.getLongitude());
            item.put("time", System.currentTimeMillis());
            places.put(item);
            while (places.length() > 300) places.remove(0);
            prefs(context).edit().putString("places", places.toString()).apply();
        } catch (Exception e) {
            android.util.Log.e("Musibility", "Could not save listening location", e);
        }
    }

    public static List<Song> recentSongs(Context context) {
        List<Song> result = new ArrayList<>();
        JSONArray history;
        try {
            history = new JSONArray(prefs(context).getString("history", "[]"));
            for (int i = history.length() - 1; i >= 0; i--) result.add(fromJson(history.getJSONObject(i)));
        } catch (Exception e) {
            android.util.Log.e("Musibility", "Could not read listening history", e);
        }
        return result;
    }

    public static List<JSONObject> places(Context context) {
        List<JSONObject> result = new ArrayList<>();
        try {
            JSONArray places = new JSONArray(prefs(context).getString("places", "[]"));
            for (int i = 0; i < places.length(); i++) result.add(places.getJSONObject(i));
        } catch (Exception e) {
            android.util.Log.e("Musibility", "Could not read listening locations", e);
        }
        return result;
    }

    public static void createLocationPlaylist(Context context, String name, List<Song> songs) throws Exception {
        createPlaylist(context, name);
        for (Song song : songs) addToPlaylist(context, name, song);
    }

    public static List<String> playlistNames(Context context) {
        List<String> names = new ArrayList<>();
        try {
            JSONArray playlists = new JSONArray(prefs(context).getString("playlists", "[]"));
            for (int i = 0; i < playlists.length(); i++) names.add(playlists.getJSONObject(i).optString("name"));
        } catch (Exception e) {
            android.util.Log.e("Musibility", "Could not read playlists", e);
        }
        return names;
    }

    public static void createPlaylist(Context context, String name) throws Exception {
        JSONArray playlists = new JSONArray(prefs(context).getString("playlists", "[]"));
        JSONObject playlist = new JSONObject();
        playlist.put("name", name);
        playlist.put("songs", new JSONArray());
        playlists.put(playlist);
        prefs(context).edit().putString("playlists", playlists.toString()).apply();
    }

    public static void addToPlaylist(Context context, String name, Song song) throws Exception {
        JSONArray playlists = new JSONArray(prefs(context).getString("playlists", "[]"));
        for (int i = 0; i < playlists.length(); i++) {
            JSONObject playlist = playlists.getJSONObject(i);
            if (name.equals(playlist.optString("name"))) {
                JSONArray songs = playlist.optJSONArray("songs");
                if (songs == null) songs = new JSONArray();
                songs.put(toJson(song));
                playlists.put(i, playlist);
                prefs(context).edit().putString("playlists", playlists.toString()).apply();
                return;
            }
        }
        throw new IllegalArgumentException("Không tìm thấy playlist");
    }

    public static List<Song> playlistSongs(Context context, String name) {
        List<Song> result = new ArrayList<>();
        try {
            JSONArray playlists = new JSONArray(prefs(context).getString("playlists", "[]"));
            for (int i = 0; i < playlists.length(); i++) {
                JSONObject playlist = playlists.getJSONObject(i);
                if (!name.equals(playlist.optString("name"))) continue;
                JSONArray songs = playlist.optJSONArray("songs");
                if (songs != null) for (int j = 0; j < songs.length(); j++) result.add(fromJson(songs.getJSONObject(j)));
            }
        } catch (Exception e) {
            android.util.Log.e("Musibility", "Could not read playlist songs", e);
        }
        return result;
    }

    private static JSONObject toJson(Song song) throws Exception {
        JSONObject json = new JSONObject();
        json.put("id", song.id); json.put("title", song.title); json.put("artist", song.artist);
        json.put("artistId", song.artistId); json.put("album", song.album);
        json.put("artistSourceId", song.artistSourceId);
        json.put("cover", song.coverUrl);
        json.put("audio", song.audioUrl); json.put("link", song.link);
        json.put("license", song.licenseUrl); json.put("duration", song.durationSeconds);
        json.put("provider", song.provider); json.put("providerTrackId", song.providerTrackId);
        json.put("albumArtist", song.albumArtist); json.put("genre", song.genre);
        json.put("year", song.year); json.put("trackNumber", song.trackNumber);
        json.put("fileName", song.fileName); json.put("filePath", song.filePath);
        json.put("mimeType", song.mimeType); json.put("fileSizeBytes", song.fileSizeBytes);
        json.put("bitrate", song.bitrate);
        return json;
    }

    private static Song fromJson(JSONObject json) {
        return new Song(json.optLong("id"), json.optString("title"), json.optString("artist"),
                json.optLong("artistId"), json.optString("album"), json.optString("cover"),
                json.optString("audio", json.optString("preview")), json.optString("link"),
                json.optInt("duration"), json.optString("license"),
                json.optString("provider", "Jamendo"), json.optString("providerTrackId"),
                json.optString("artistSourceId", json.optString("artistId")),
                json.optString("albumArtist"), json.optString("genre"), json.optString("year"),
                json.optString("trackNumber"), json.optString("fileName"), json.optString("filePath"),
                json.optString("mimeType"), json.optLong("fileSizeBytes"), json.optInt("bitrate"));
    }
}
