package com.vwww.mira.screen;

final class AvcEncoderProfile {
    private static final long ENCODER_CREATE_TIMEOUT_MS = 3000;
    private static final long ENCODER_COLD_CREATE_TIMEOUT_MS = 15000;

    final String encoderName;
    final int width;
    final int height;
    final int fps;
    final int bitrate;
    final boolean forceBaseline;
    final String source;
    boolean coldCreate;

    AvcEncoderProfile(String encoderName, int width, int height, int fps, int bitrate, boolean forceBaseline, String source) {
        this.encoderName = encoderName == null ? "" : encoderName;
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.bitrate = bitrate;
        this.forceBaseline = forceBaseline;
        this.source = source == null ? "" : source;
    }

    long framePeriodMs() {
        return 1000L / Math.max(1, fps);
    }

    long createTimeoutMs() {
        return coldCreate ? ENCODER_COLD_CREATE_TIMEOUT_MS : ENCODER_CREATE_TIMEOUT_MS;
    }

    String key() {
        return encoderName + "|" + width + "x" + height + "|" + fps + "|" + bitrate + "|" + forceBaseline;
    }

    String describe() {
        return "encoder=" + (encoderName.isEmpty() ? "default" : encoderName)
            + " size=" + width + "x" + height
            + " fps=" + fps
            + " bitrate=" + bitrate
            + " baseline=" + forceBaseline
            + " source=" + source;
    }
}
