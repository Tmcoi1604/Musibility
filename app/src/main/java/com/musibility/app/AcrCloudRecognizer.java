package com.musibility.app;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class AcrCloudRecognizer {
    public static final class Result {
        public final String title;
        public final String artist;
        Result(String title, String artist) { this.title = title; this.artist = artist; }
    }

    private AcrCloudRecognizer() {}

    public static Result listenAndIdentify() throws Exception {
        if (BuildConfig.ACR_ACCESS_KEY.isEmpty() || BuildConfig.ACR_ACCESS_SECRET.isEmpty()
                || BuildConfig.ACR_HOST.isEmpty()) {
            throw new IllegalStateException("Chưa cấu hình thông tin ACRCloud trong local.properties.");
        }
        final int sampleRate = 16000;
        int minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) throw new IllegalStateException("Thiết bị không hỗ trợ ghi âm 16 kHz.");
        AudioRecord recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuffer, 8192));
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            throw new IllegalStateException("Không thể khởi tạo microphone.");
        }
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        try {
            recorder.startRecording();
            long end = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < end) {
                int count = recorder.read(buffer, 0, buffer.length);
                if (count > 0) audio.write(buffer, 0, count);
            }
        } finally {
            try { recorder.stop(); } catch (IllegalStateException ignored) { }
            recorder.release();
        }

        long timestamp = System.currentTimeMillis() / 1000;
        String signatureText = "POST\n/v1/identify\n" + BuildConfig.ACR_ACCESS_KEY
                + "\naudio\n1\n" + timestamp;
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(BuildConfig.ACR_ACCESS_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        String signature = android.util.Base64.encodeToString(mac.doFinal(signatureText.getBytes(StandardCharsets.UTF_8)),
                android.util.Base64.NO_WRAP);
        byte[] wav = wav(audio.toByteArray(), sampleRate);
        String sample = android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP);
        String body = "access_key=" + encode(BuildConfig.ACR_ACCESS_KEY)
                + "&data_type=audio&signature_version=1&signature=" + encode(signature)
                + "&sample_bytes=" + wav.length + "&timestamp=" + timestamp + "&sample=" + encode(sample);
        HttpURLConnection connection = (HttpURLConnection) new URL("https://" + BuildConfig.ACR_HOST + "/v1/identify").openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.getBytes(StandardCharsets.UTF_8));
        }
        try {
            int code = connection.getResponseCode();
            java.io.InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            ByteArrayOutputStream responseBytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[2048];
            int count;
            while ((count = stream.read(chunk)) != -1) responseBytes.write(chunk, 0, count);
            JSONObject response = new JSONObject(responseBytes.toString(StandardCharsets.UTF_8.name()));
            if (response.optJSONObject("status") == null || response.getJSONObject("status").optInt("code", -1) != 0) {
                throw new java.io.IOException(response.optJSONObject("status") == null ? "Không nhận diện được bài hát."
                        : response.getJSONObject("status").optString("msg", "Không nhận diện được bài hát."));
            }
            JSONObject music = response.getJSONObject("metadata").getJSONArray("music").getJSONObject(0);
            StringBuilder artists = new StringBuilder();
            org.json.JSONArray artistList = music.optJSONArray("artists");
            if (artistList != null) for (int i = 0; i < artistList.length(); i++) {
                if (artists.length() > 0) artists.append(", ");
                artists.append(artistList.getJSONObject(i).optString("name"));
            }
            return new Result(music.optString("title", "Không rõ tên bài hát"), artists.toString());
        } finally {
            connection.disconnect();
        }
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    private static byte[] wav(byte[] pcm, int sampleRate) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(pcm.length + 44);
        writeAscii(output, "RIFF");
        writeInt(output, pcm.length + 36);
        writeAscii(output, "WAVEfmt ");
        writeInt(output, 16);
        writeShort(output, 1);
        writeShort(output, 1);
        writeInt(output, sampleRate);
        writeInt(output, sampleRate * 2);
        writeShort(output, 2);
        writeShort(output, 16);
        writeAscii(output, "data");
        writeInt(output, pcm.length);
        output.write(pcm);
        return output.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String value) throws Exception {
        out.write(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff); out.write((value >> 8) & 0xff);
        out.write((value >> 16) & 0xff); out.write((value >> 24) & 0xff);
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff); out.write((value >> 8) & 0xff);
    }
}
