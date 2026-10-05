package com.musibility.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class JamendoApi {
    private static final String API = "https://api.jamendo.com/v3.0/";
    private JamendoApi() {}

    public static List<Song> searchTracks(String query) throws Exception {
        return parseSongs(get("tracks", "namesearch=" + encode(query)
                + "&order=popularity_total_desc&limit=30"));
    }

    public static List<Song> chart() throws Exception {
        return parseSongs(get("tracks", "order=popularity_total_desc&limit=30"));
    }

    public static List<JSONObject> searchArtists(String query) throws Exception {
        JSONArray results = get("artists", "namesearch=" + encode(query) + "&limit=20")
                .optJSONArray("results");
        List<JSONObject> artists = new ArrayList<>();
        if (results != null) for (int i = 0; i < results.length(); i++) {
            JSONObject artist = results.optJSONObject(i);
            if (artist != null) {
                artist.put("_provider", "Jamendo");
                artist.put("_sourceId", artist.optString("id"));
                artists.add(artist);
            }
        }
        return artists;
    }

    public static List<Song> artistTracks(long artistId) throws Exception {
        return parseSongs(get("tracks", "artist_id=" + artistId
                + "&order=popularity_total_desc&limit=30"));
    }

    public static List<JSONObject> searchAlbums(String query) throws Exception {
        JSONArray results = get("albums", "namesearch=" + encode(query) + "&limit=20")
                .optJSONArray("results");
        List<JSONObject> albums = new ArrayList<>();
        if (results != null) for (int i = 0; i < results.length(); i++) {
            JSONObject album = results.optJSONObject(i);
            if (album != null) {
                album.put("_provider", "Jamendo");
                album.put("_sourceId", album.optString("id"));
                albums.add(album);
            }
        }
        return albums;
    }

    public static List<Song> albumTracks(long albumId) throws Exception {
        return parseSongs(get("tracks", "album_id=" + albumId + "&limit=100"));
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    private static JSONObject get(String endpoint, String query) throws Exception {
        if (BuildConfig.JAMENDO_CLIENT_ID.isEmpty()) {
            throw new IllegalStateException("Cần Jamendo client ID miễn phí. Xem README.md để tạo và cấu hình client ID.");
        }
        String url = API + endpoint + "/?client_id=" + encode(BuildConfig.JAMENDO_CLIENT_ID)
                + "&format=json&audioformat=mp32&imagesize=300&" + query;
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection)
                new java.net.URL(url).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "application/json");
        try (java.io.InputStream input = connection.getInputStream();
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            JSONObject response = new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            JSONObject headers = response.optJSONObject("headers");
            if (headers != null && (headers.optInt("code", 0) != 0
                    || !headers.optString("error_message", "").isEmpty())) {
                throw new java.io.IOException(headers.optString("error_message", "Jamendo API request failed."));
            }
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private static List<Song> parseSongs(JSONObject response) {
        List<Song> songs = new ArrayList<>();
        JSONArray results = response.optJSONArray("results");
        if (results == null) return songs;
        for (int i = 0; i < results.length(); i++) {
            JSONObject track = results.optJSONObject(i);
            if (track == null || track.optString("audio").isEmpty()) continue;
            String license = track.optString("license_ccurl");
            if (license.isEmpty()) continue;
            songs.add(new Song(track.optLong("id"), track.optString("name"),
                    track.optString("artist_name"), track.optLong("artist_id"),
                    track.optString("album_name"), track.optString("album_image"),
                    track.optString("audio"), track.optString("shareurl"),
                    track.optInt("duration"), license, "Jamendo",
                    track.optString("id"), track.optString("artist_id")));
        }
        return songs;
    }
}
