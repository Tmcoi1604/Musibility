package com.musibility.app;

import android.content.ContentResolver;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class VoiceApiClient {

    public static String normalizeServerUrl(String raw) throws IOException {
        String base = raw == null ? "" : raw.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (!(base.startsWith("http://") || base.startsWith("https://"))) {
            throw new IOException("Nhập địa chỉ backend, ví dụ http://192.168.1.20:8000");
        }
        return base;
    }

    public static List<VoiceOption> fetchVoices(String base, String apiKey) throws Exception {
        JSONObject response = httpGetJson(base + "/voices", apiKey);
        JSONArray array = response.optJSONArray("voices");
        if (array == null) throw new IOException("API /voices không có trường voices.");
        List<VoiceOption> loaded = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject voice = array.optJSONObject(i);
            if (voice != null) {
                String id = voice.optString("id", "").trim();
                String label = voice.optString("label", "").trim();
                if (!id.isEmpty()) {
                    loaded.add(new VoiceOption(id, label.isEmpty() ? id.replace('_', ' ') : label));
                }
            }
        }
        return loaded;
    }

    public static JSONObject uploadJob(ContentResolver resolver, String base, String apiKey,
                                     Uri audioUri, String originalName, String voiceId) throws Exception {
        String boundary = "----MusibilityVoice" + UUID.randomUUID().toString().replace("-", "");
        HttpURLConnection connection = (HttpURLConnection) new URL(base + "/jobs").openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(120000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        connection.setRequestProperty("Accept", "application/json");
        if (apiKey != null && !apiKey.isEmpty()) connection.setRequestProperty("X-API-Key", apiKey);
        connection.setChunkedStreamingMode(64 * 1024);
        String extension = getAudioExtension(originalName);
        String uploadName = "song" + extension;
        String mime = resolver.getType(audioUri);
        if (mime == null || !mime.startsWith("audio/")) mime = "application/octet-stream";
        try (OutputStream output = connection.getOutputStream()) {
            writeMultipartField(output, boundary, "voice_id", voiceId);
            writeMultipartFileHeader(output, boundary, "song", uploadName, mime);
            try (InputStream input = resolver.openInputStream(audioUri)) {
                if (input == null) throw new IOException("Không đọc được file nhạc đã chọn.");
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            output.write("\r\n".getBytes(StandardCharsets.UTF_8));
            output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
        int code = connection.getResponseCode();
        String response = readHttpResponse(connection, code);
        connection.disconnect();
        if (code < 200 || code >= 300) throw new IOException("POST /jobs trả HTTP " + code + ": " + response);
        return new JSONObject(response);
    }

    public static JobState getJobState(String base, String apiKey, String jobId) throws Exception {
        JSONObject json = httpGetJson(base + "/jobs/" + jobId, apiKey);
        return JobState.fromJson(json);
    }

    public static File downloadVoiceResultFile(String base, String apiKey, String jobId, File targetDir, File fallbackDir) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(base + "/jobs/" + jobId + "/result").openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(120000);
        if (apiKey != null && !apiKey.isEmpty()) connection.setRequestProperty("X-API-Key", apiKey);
        int code = connection.getResponseCode();
        if (code != 200) {
            String detail = readHttpResponse(connection, code);
            connection.disconnect();
            throw new IOException("Không tải được kết quả (HTTP " + code + "): " + detail);
        }

        String fileName = "converted-" + jobId.substring(0, Math.min(8, jobId.length())) + ".wav";
        File outputFile = null;
        FileOutputStream output = null;
        IOException targetFailure = null;
        try {
            if (targetDir != null && (targetDir.isDirectory() || targetDir.mkdirs())) {
                outputFile = new File(targetDir, fileName);
                output = new FileOutputStream(outputFile);
            }
        } catch (IOException e) {
            targetFailure = e;
        }
        if (output == null && fallbackDir != null
                && (fallbackDir.isDirectory() || fallbackDir.mkdirs())) {
            outputFile = new File(fallbackDir, fileName);
            output = new FileOutputStream(outputFile);
        }
        if (output == null) {
            connection.disconnect();
            if (targetFailure != null) throw targetFailure;
            throw new IOException("Không thể tạo thư mục lưu file kết quả.");
        }

        try (InputStream input = connection.getInputStream(); FileOutputStream destination = output) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) destination.write(buffer, 0, count);
            destination.flush();
        } catch (Exception e) {
            if (outputFile.exists() && !outputFile.delete()) {
                throw new IOException("Tải file lỗi và không thể xóa file kết quả dở dang: " + outputFile, e);
            }
            throw e;
        } finally {
            connection.disconnect();
        }
        return outputFile;
    }

    private static JSONObject httpGetJson(String url, String apiKey) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("Accept", "application/json");
        if (apiKey != null && !apiKey.isEmpty()) connection.setRequestProperty("X-API-Key", apiKey);
        int code = connection.getResponseCode();
        String response = readHttpResponse(connection, code);
        connection.disconnect();
        if (code < 200 || code >= 300) throw new IOException("GET " + url + " trả HTTP " + code + ": " + response);
        return new JSONObject(response);
    }

    private static String readHttpResponse(HttpURLConnection connection, int code) throws IOException {
        InputStream stream = (code >= 200 && code < 400) ? connection.getInputStream() : connection.getErrorStream();
        if (stream == null) return "";
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String getAudioExtension(String name) throws IOException {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String[] allowed = {".mp3", ".wav", ".flac", ".m4a", ".ogg"};
        for (String extension : allowed) if (lower.endsWith(extension)) return extension;
        throw new IOException("Backend chưa hỗ trợ định dạng này. Hãy chọn MP3, WAV, FLAC, M4A hoặc OGG.");
    }

    private static void writeMultipartField(OutputStream output, String boundary, String name, String value) throws IOException {
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\n";
        output.write(header.getBytes(StandardCharsets.UTF_8));
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeMultipartFileHeader(OutputStream output, String boundary, String fieldName,
                                                 String filename, String mime) throws IOException {
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + mime + "\r\n\r\n";
        output.write(header.getBytes(StandardCharsets.UTF_8));
    }
}
