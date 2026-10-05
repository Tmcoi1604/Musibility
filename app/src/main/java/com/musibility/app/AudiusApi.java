package com.musibility.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class AudiusApi {
    private static final String API = "https://api.audius.co/v1/";
    private static final String APP_NAME = "Musibility";

    private AudiusApi() {}

    public static List<Song> searchTracks(String query) throws Exception {
        return parseTracks(get("tracks/search?query=" + encode(query) + "&limit=30"));
    }

    public static List<Song> trending() throws Exception {
        return parseTracks(get("tracks/trending?time=week&limit=30"));
    }

    public static List<JSONObject> searchArtists(String query) throws Exception {
        JSONArray data = get("users/search?query=" + encode(query) + "&limit=20").optJSONArray("data");
        List<JSONObject> artists = new ArrayList<>();
        if (data != null) for (int i = 0; i < data.length(); i++) {
            JSONObject artist = data.optJSONObject(i);
            if (artist == null) continue;
            artist.put("_provider", "Audius");
            artist.put("_sourceId", artist.optString("id"));
            artists.add(artist);
        }
        return artists;
    }

    public static List<Song> artistTracks(String artistId) throws Exception {
        return parseTracks(get("users/" + encode(artistId) + "/tracks?limit=30"));
    }

    public static List<JSONObject> searchPlaylists(String query) throws Exception {
        JSONArray data = get("playlists/search?query=" + encode(query) + "&limit=20").optJSONArray("data");
        List<JSONObject> playlists = new ArrayList<>();
        if (data != null) for (int i = 0; i < data.length(); i++) {
            JSONObject playlist = data.optJSONObject(i);
            if (playlist == null) continue;
            playlist.put("_provider", "Audius");
            playlist.put("_sourceId", playlist.optString("id"));
            playlists.add(playlist);
        }
        return playlists;
    }

    public static List<Song> playlistTracks(String playlistId) throws Exception {
        return parseTracks(get("playlists/" + encode(playlistId) + "/tracks?limit=100"));
    }

    private static JSONObject get(String path) throws Exception {
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection)
                new java.net.URL(API + path + "&app_name=" + encode(APP_NAME)).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "application/json");
        try (java.io.InputStream input = connection.getInputStream();
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            JSONObject response = new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            if (response.has("error")) throw new java.io.IOException(response.optString("error"));
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private static List<Song> parseTracks(JSONObject response) {
        List<Song> songs = new ArrayList<>();
        JSONArray data = response.optJSONArray("data");
        if (data == null) return songs;
        for (int i = 0; i < data.length(); i++) {
            JSONObject track = data.optJSONObject(i);
            if (track == null || !track.optBoolean("is_streamable", true)
                    || track.optBoolean("is_stream_gated", false)
                    || track.optBoolean("is_unlisted", false)) continue;
            JSONObject user = track.optJSONObject("user");
            String artist = user == null ? "" : user.optString("name", user.optString("handle"));
            String artistSourceId = user == null ? track.optString("user_id") : user.optString("id");
            JSONObject artwork = track.optJSONObject("artwork");
            String cover = artwork == null ? "" : artwork.optString("150x150", artwork.optString("480x480"));
            String trackId = track.optString("id");
            if (trackId.isEmpty()) continue;
            String permalink = track.optString("permalink");
            String shareLink = permalink.startsWith("http") ? permalink : "https://audius.co" + permalink;
            long numericId = track.optLong("track_id", (long) trackId.hashCode());
            songs.add(new Song(numericId, track.optString("title"), artist, 0,
                    track.optString("album"), cover,
                    API + "tracks/" + encodeUnchecked(trackId) + "/stream?app_name=" + encodeUnchecked(APP_NAME),
                    shareLink, track.optInt("duration"), "",
                    "Audius", trackId, artistSourceId));
        }
        return songs;
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    private static String encodeUnchecked(String value) {
        try { return encode(value); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid Audius resource ID", e); }
    }
}
