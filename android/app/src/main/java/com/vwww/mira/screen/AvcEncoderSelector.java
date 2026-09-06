package com.vwww.mira.screen;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.util.Log;
import android.util.Range;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

final class AvcEncoderSelector {
    private static final String TAG = "MiraScreenStreamer";
    private static final String MIME_AVC = "video/avc";
    static final int MAX_WIDTH = 540;
    private static final int VIDEO_SIZE_ALIGNMENT = 16;
    private static final int FRAME_RATE = 10;
    private static final int BITRATE = 220_000;
    private static final String PREFS_NAME = "mira-screen-encoder";

    private final Context context;

    AvcEncoderSelector(Context context) {
        this.context = context.getApplicationContext();
    }

    List<AvcEncoderProfile> selectProfiles(int sourceWidth, int sourceHeight) {
        LinkedHashMap<String, AvcEncoderProfile> profiles = new LinkedHashMap<>();
        AvcEncoderProfile cached = readCachedProfile(sourceWidth, sourceHeight);
        if (cached != null) addProfile(profiles, cached);
        try {
            MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
            for (MediaCodecInfo codecInfo : codecList.getCodecInfos()) {
                if (!codecInfo.isEncoder()) continue;
                if (!supportsMime(codecInfo, MIME_AVC)) continue;
                addCodecProfiles(profiles, codecInfo, sourceWidth, sourceHeight);
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "Unable to inspect AVC encoder capabilities", throwable);
        }
        return new ArrayList<>(profiles.values());
    }

    void rememberSuccessfulProfile(AvcEncoderProfile profile) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String prefix = deviceCacheKey() + ".";
            prefs.edit()
                .putString(prefix + "encoderName", profile.encoderName == null ? "" : profile.encoderName)
                .putInt(prefix + "width", profile.width)
                .putInt(prefix + "height", profile.height)
                .putInt(prefix + "fps", profile.fps)
                .putInt(prefix + "bitrate", profile.bitrate)
                .putBoolean(prefix + "forceBaseline", profile.forceBaseline)
                .apply();
            Log.i(TAG, "cached AVC profile " + profile.describe());
        } catch (Throwable throwable) {
            Log.w(TAG, "Unable to cache AVC profile", throwable);
        }
    }

    private void addCodecProfiles(
        LinkedHashMap<String, AvcEncoderProfile> profiles,
        MediaCodecInfo codecInfo,
        int sourceWidth,
        int sourceHeight
    ) {
        try {
            MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(MIME_AVC);
            MediaCodecInfo.VideoCapabilities video = capabilities.getVideoCapabilities();
            int widthAlignment = Math.max(VIDEO_SIZE_ALIGNMENT, video.getWidthAlignment());
            int heightAlignment = Math.max(VIDEO_SIZE_ALIGNMENT, video.getHeightAlignment());
            Range<Integer> bitrateRange = video.getBitrateRange();
            int[] targetWidths = new int[] {MAX_WIDTH, 512, 480, 432, 360};
            int[] targetFps = new int[] {FRAME_RATE, 8};
            boolean[] baselineModes = new boolean[] {true, false};
            for (int targetWidth : targetWidths) {
                int width = alignDown(Math.min(targetWidth, Math.max(widthAlignment, sourceWidth)), widthAlignment);
                int height = alignDown(Math.round(width * (sourceHeight / (float) Math.max(1, sourceWidth))), heightAlignment);
                if (width <= 0 || height <= 0 || !video.isSizeSupported(width, height)) continue;
                for (int fps : targetFps) {
                    int maxFps = (int) Math.max(1L, Math.round(video.getSupportedFrameRatesFor(width, height).getUpper()));
                    int clampedFps = clamp(fps, 1, maxFps);
                    int bitrate = clamp(BITRATE, bitrateRange.getLower(), bitrateRange.getUpper());
                    for (boolean forceBaseline : baselineModes) {
                        if (forceBaseline && !supportsAvcProfile(capabilities, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)) continue;
                        addProfile(
                            profiles,
                            new AvcEncoderProfile(codecInfo.getName(), width, height, clampedFps, bitrate, forceBaseline, "capability")
                        );
                    }
                }
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "Unable to build AVC profiles for " + codecInfo.getName(), throwable);
        }
    }

    private static void addProfile(LinkedHashMap<String, AvcEncoderProfile> profiles, AvcEncoderProfile profile) {
        profiles.put(profile.key(), profile);
    }

    private static boolean supportsMime(MediaCodecInfo codecInfo, String mime) {
        for (String type : codecInfo.getSupportedTypes()) {
            if (mime.equalsIgnoreCase(type)) return true;
        }
        return false;
    }

    private static boolean supportsAvcProfile(MediaCodecInfo.CodecCapabilities capabilities, int profile) {
        if (capabilities == null || capabilities.profileLevels == null || capabilities.profileLevels.length == 0) return true;
        for (MediaCodecInfo.CodecProfileLevel level : capabilities.profileLevels) {
            if (level.profile == profile) return true;
        }
        return false;
    }

    private AvcEncoderProfile readCachedProfile(int sourceWidth, int sourceHeight) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String prefix = deviceCacheKey() + ".";
            int width = prefs.getInt(prefix + "width", 0);
            int height = prefs.getInt(prefix + "height", 0);
            int fps = prefs.getInt(prefix + "fps", 0);
            int bitrate = prefs.getInt(prefix + "bitrate", 0);
            if (width <= 0 || height <= 0 || fps <= 0 || bitrate <= 0) return null;
            if (width > sourceWidth || height > sourceHeight) return null;
            if (width % VIDEO_SIZE_ALIGNMENT != 0 || height % VIDEO_SIZE_ALIGNMENT != 0) return null;
            return new AvcEncoderProfile(
                prefs.getString(prefix + "encoderName", ""),
                width,
                height,
                fps,
                bitrate,
                prefs.getBoolean(prefix + "forceBaseline", false),
                "cache"
            );
        } catch (Throwable throwable) {
            Log.w(TAG, "Unable to read cached AVC profile", throwable);
            return null;
        }
    }

    private static String deviceCacheKey() {
        return safeKey(Build.MANUFACTURER) + "." + safeKey(Build.MODEL) + ".sdk" + Build.VERSION.SDK_INT;
    }

    private static String safeKey(String value) {
        return (value == null ? "unknown" : value).replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static int alignDown(int value, int alignment) {
        int clamped = Math.max(alignment, value);
        int aligned = clamped - (clamped % alignment);
        return Math.max(alignment, aligned);
    }

    private static int clamp(int value, int lower, int upper) {
        return Math.max(lower, Math.min(value, upper));
    }
}
