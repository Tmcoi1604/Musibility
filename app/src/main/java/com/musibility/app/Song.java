package com.musibility.app;

import java.io.Serializable;

public class Song implements Serializable {
    public final long id;
    public final String title;
    public final String artist;
    public final long artistId;
    public final String album;
    public final String coverUrl;
    public final String audioUrl;
    public final String link;
    public final String licenseUrl;
    public final String provider;
    public final String providerTrackId;
    public final String artistSourceId;
    public final int durationSeconds;

    public Song(long id, String title, String artist, long artistId, String album,
                String coverUrl, String audioUrl, String link, int durationSeconds) {
        this(id, title, artist, artistId, album, coverUrl, audioUrl, link, durationSeconds, "");
    }

    public Song(long id, String title, String artist, long artistId, String album,
                String coverUrl, String audioUrl, String link, int durationSeconds, String licenseUrl) {
        this(id, title, artist, artistId, album, coverUrl, audioUrl, link, durationSeconds,
                licenseUrl, "Jamendo", String.valueOf(id), String.valueOf(artistId));
    }

    public Song(long id, String title, String artist, long artistId, String album,
                String coverUrl, String audioUrl, String link, int durationSeconds, String licenseUrl,
                String provider, String providerTrackId, String artistSourceId) {
        this.id = id;
        this.title = title == null ? "" : title;
        this.artist = artist == null ? "" : artist;
        this.artistId = artistId;
        this.album = album == null ? "" : album;
        this.coverUrl = coverUrl == null ? "" : coverUrl;
        this.audioUrl = audioUrl == null ? "" : audioUrl;
        this.link = link == null ? "" : link;
        this.durationSeconds = durationSeconds;
        this.licenseUrl = licenseUrl == null ? "" : licenseUrl;
        this.provider = provider == null ? "" : provider;
        this.providerTrackId = providerTrackId == null ? "" : providerTrackId;
        this.artistSourceId = artistSourceId == null ? "" : artistSourceId;
    }
}
