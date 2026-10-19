package com.musibility.app;

import org.json.JSONObject;

public class JobState {
    public final String jobId;
    public final String status;
    public final int progress;
    public final String message;
    public final String error;

    public JobState(String jobId, String status, int progress, String message, String error) {
        this.jobId = jobId == null ? "" : jobId;
        this.status = status == null ? "" : status;
        this.progress = progress;
        this.message = message == null ? "" : message;
        this.error = error == null ? "" : error;
    }

    public static JobState fromJson(JSONObject json) {
        if (json == null) return new JobState("", "unknown", -1, "", "");
        String status = json.optString("status", "unknown");
        if ("done".equalsIgnoreCase(status)) status = "completed";
        else if ("error".equalsIgnoreCase(status)) status = "failed";
        String message = json.optString("step", json.optString("message", ""));
        return new JobState(
                json.optString("job_id", ""),
                status,
                json.optInt("progress", -1),
                message,
                json.optString("error", "")
        );
    }
}
