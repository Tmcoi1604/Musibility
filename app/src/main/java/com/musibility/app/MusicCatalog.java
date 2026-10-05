package com.musibility.app;

import org.json.JSONObject;

import java.io.IOException;
import java.util.List;

public final class MusicCatalog {
    private MusicCatalog() {}

    public static List<Song> searchTracks(String query) throws Exception {
        try {
            List<Song> songs = AudiusApi.searchTracks(query);
            if (!songs.isEmpty()) return songs;
        } catch (Exception audiusError) {
            return fallback(() -> JamendoApi.searchTracks(query), audiusError);
        }
        return JamendoApi.searchTracks(query);
    }

    public static List<Song> trending() throws Exception {
        try {
            List<Song> songs = AudiusApi.trending();
            if (!songs.isEmpty()) return songs;
        } catch (Exception audiusError) {
            return fallback(JamendoApi::chart, audiusError);
        }
        return JamendoApi.chart();
    }

    public static List<JSONObject> searchArtists(String query) throws Exception {
        try {
            List<JSONObject> artists = AudiusApi.searchArtists(query);
            if (!artists.isEmpty()) return artists;
        } catch (Exception audiusError) {
            return fallback(JamendoApi::searchArtists, query, audiusError);
        }
        return JamendoApi.searchArtists(query);
    }

    public static List<Song> artistTracks(String name, String artistId, String provider) throws Exception {
        if ("Audius".equals(provider)) {
            try {
                List<Song> songs = AudiusApi.artistTracks(artistId);
                if (!songs.isEmpty()) return songs;
            } catch (Exception audiusError) {
                return fallback(() -> JamendoApi.searchTracks(name), audiusError);
            }
            return JamendoApi.searchTracks(name);
        }
        try {
            long id = Long.parseLong(artistId);
            List<Song> songs = JamendoApi.artistTracks(id);
            if (!songs.isEmpty()) return songs;
        } catch (NumberFormatException ignored) {
            // An artist from Audius has no Jamendo numeric ID.
        } catch (Exception jamendoError) {
            return fallback(() -> AudiusApi.searchTracks(name), jamendoError);
        }
        return AudiusApi.searchTracks(name);
    }

    public static List<JSONObject> searchPlaylists(String query) throws Exception {
        try {
            List<JSONObject> playlists = AudiusApi.searchPlaylists(query);
            if (!playlists.isEmpty()) return playlists;
        } catch (Exception audiusError) {
            return fallback(JamendoApi::searchAlbums, query, audiusError);
        }
        return JamendoApi.searchAlbums(query);
    }

    public static List<Song> playlistTracks(String id, String provider, String name) throws Exception {
        if ("Audius".equals(provider)) {
            try {
                List<Song> songs = AudiusApi.playlistTracks(id);
                if (!songs.isEmpty()) return songs;
            } catch (Exception audiusError) {
                return fallback(() -> JamendoApi.searchTracks(name), audiusError);
            }
            return JamendoApi.searchTracks(name);
        }
        try {
            return JamendoApi.albumTracks(Long.parseLong(id));
        } catch (Exception jamendoError) {
            return fallback(() -> AudiusApi.searchTracks(name), jamendoError);
        }
    }

    public static List<Song> similar(Song song) throws Exception {
        return artistTracks(song.artist, song.artistSourceId, song.provider);
    }

    private interface Work<T> { T run() throws Exception; }

    private static <T> T fallback(Work<T> fallback, Exception primaryError) throws Exception {
        try { return fallback.run(); }
        catch (Exception fallbackError) {
            fallbackError.addSuppressed(primaryError);
            throw new IOException("Audius no está disponible y Jamendo tampoco pudo responder: "
                    + fallbackError.getMessage(), fallbackError);
        }
    }

    private static <T> T fallback(QueryWork<T> fallback, String query, Exception primaryError) throws Exception {
        return fallback(() -> fallback.run(query), primaryError);
    }

    private interface QueryWork<T> { T run(String query) throws Exception; }
}
