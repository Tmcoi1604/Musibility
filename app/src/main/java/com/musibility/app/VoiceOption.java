package com.musibility.app;

import java.io.Serializable;

public class VoiceOption implements Serializable {
    public final String id;
    public final String label;

    public VoiceOption(String id, String label) {
        this.id = id == null ? "" : id;
        this.label = label == null ? "" : label;
    }

    @Override
    public String toString() {
        return label;
    }
}
