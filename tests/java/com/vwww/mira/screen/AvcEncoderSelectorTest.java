package com.vwww.mira.screen;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AvcEncoderSelectorTest {
    private static final String AVC = "video/avc";

    public static void main(String[] args) {
        candidateOrderAndDuplicateFpsAreStable();
        cacheRoundTripUsesTheLegacyDeviceKey();
        invalidAndUnreadableCachesAreIgnored();
        System.out.println("PASS: AVC encoder selection ordering, deduplication, and cache fallbacks");
    }

    private static void candidateOrderAndDuplicateFpsAreStable() {
        FakeContext context = new FakeContext();
        MediaCodecInfo decoder = codec("decoder", false, AVC, 512, 8, true);
        MediaCodecInfo wrongMime = codec("vp8.encoder", true, "video/x-vnd.on2.vp8", 512, 8, true);
        MediaCodecInfo first = codec("codec.first", true, AVC, 512, 8, true);
        MediaCodecInfo second = codec("codec.second", true, AVC, 512, 10, false);
        MediaCodecList.setCodecInfos(new MediaCodecInfo[] {decoder, wrongMime, first, second});

        List<AvcEncoderProfile> profiles = new AvcEncoderSelector(context).selectProfiles(1000, 500);

        // 10 fps clamps to 8 for the first codec, so its 10/8 passes collapse to one key per baseline mode.
        assertEquals(16, profiles.size(), "deduplicated candidate count");
        assertProfile(profiles.get(0), "codec.first", 512, 256, 8, 180_000, true, "capability");
        assertProfile(profiles.get(1), "codec.first", 512, 256, 8, 180_000, false, "capability");
        assertProfile(profiles.get(7), "codec.first", 352, 176, 8, 180_000, false, "capability");
        assertProfile(profiles.get(8), "codec.second", 512, 256, 10, 180_000, false, "capability");
        assertProfile(profiles.get(9), "codec.second", 512, 256, 8, 180_000, false, "capability");
    }

    private static void cacheRoundTripUsesTheLegacyDeviceKey() {
        FakeContext context = new FakeContext();
        Build.MANUFACTURER = "Acme Corp";
        Build.MODEL = "Model/One";
        Build.VERSION.SDK_INT = 35;
        AvcEncoderSelector selector = new AvcEncoderSelector(context);
        AvcEncoderProfile successful = new AvcEncoderProfile(
            "codec.cached", 480, 240, 8, 180_000, true, "capability"
        );

        selector.rememberSuccessfulProfile(successful);

        FakePreferences preferences = context.preferences("mira-screen-encoder");
        String prefix = "Acme_Corp.Model_One.sdk35.";
        assertEquals("codec.cached", preferences.values.get(prefix + "encoderName"), "legacy encoder key");
        assertEquals(480, preferences.values.get(prefix + "width"), "legacy width key");
        assertEquals(240, preferences.values.get(prefix + "height"), "legacy height key");
        assertEquals(8, preferences.values.get(prefix + "fps"), "legacy fps key");
        assertEquals(180_000, preferences.values.get(prefix + "bitrate"), "legacy bitrate key");
        assertEquals(true, preferences.values.get(prefix + "forceBaseline"), "legacy baseline key");

        // A capability with the same key replaces the cached value without moving its first insertion slot.
        MediaCodecList.setCodecInfos(new MediaCodecInfo[] {
            codec("codec.cached", true, AVC, 512, 8, true),
            codec("codec.after", true, AVC, 512, 8, true)
        });
        List<AvcEncoderProfile> deduplicated = selector.selectProfiles(1000, 500);
        assertProfile(deduplicated.get(0), "codec.cached", 480, 240, 8, 180_000, true, "capability");

        MediaCodecList.setFailure(new IllegalStateException("codec service unavailable"));
        List<AvcEncoderProfile> profiles = new AvcEncoderSelector(context).selectProfiles(1000, 500);
        assertEquals(1, profiles.size(), "cache survives capability enumeration failure");
        assertProfile(profiles.get(0), "codec.cached", 480, 240, 8, 180_000, true, "cache");
    }

    private static void invalidAndUnreadableCachesAreIgnored() {
        Build.MANUFACTURER = "Vendor";
        Build.MODEL = "Device";
        Build.VERSION.SDK_INT = 34;
        MediaCodecInfo fallback = codec("codec.fallback", true, AVC, 512, 8, true);
        MediaCodecList.setCodecInfos(new MediaCodecInfo[] {fallback});

        FakeContext invalidContext = new FakeContext();
        FakePreferences invalid = invalidContext.preferences("mira-screen-encoder");
        seedCache(invalid, "Vendor.Device.sdk34.", 496, 250); // height is not 16-byte aligned.
        List<AvcEncoderProfile> invalidProfiles =
            new AvcEncoderSelector(invalidContext).selectProfiles(1000, 500);
        assertEquals("codec.fallback", invalidProfiles.get(0).encoderName, "unaligned cache ignored");
        assertEquals("capability", invalidProfiles.get(0).source, "unaligned cache source ignored");

        FakeContext oversizedContext = new FakeContext();
        seedCache(oversizedContext.preferences("mira-screen-encoder"), "Vendor.Device.sdk34.", 1008, 512);
        List<AvcEncoderProfile> oversizedProfiles =
            new AvcEncoderSelector(oversizedContext).selectProfiles(800, 400);
        assertEquals("codec.fallback", oversizedProfiles.get(0).encoderName, "oversized cache ignored");

        FakeContext throwingContext = new FakeContext();
        throwingContext.preferences("mira-screen-encoder").throwOnRead = true;
        List<AvcEncoderProfile> throwingProfiles =
            new AvcEncoderSelector(throwingContext).selectProfiles(1000, 500);
        assertEquals("codec.fallback", throwingProfiles.get(0).encoderName, "cache read failure ignored");
    }

    private static MediaCodecInfo codec(
        String name, boolean encoder, String mime, int maxWidth, int maxFps, boolean baseline
    ) {
        MediaCodecInfo.VideoCapabilities video =
            new MediaCodecInfo.VideoCapabilities(16, 16, maxWidth, maxFps, 100_000, 180_000);
        MediaCodecInfo.CodecProfileLevel[] levels = baseline
            ? new MediaCodecInfo.CodecProfileLevel[] {
                new MediaCodecInfo.CodecProfileLevel(MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            }
            : new MediaCodecInfo.CodecProfileLevel[] {new MediaCodecInfo.CodecProfileLevel(8)};
        return new MediaCodecInfo(name, encoder, new String[] {mime},
            new MediaCodecInfo.CodecCapabilities(video, levels));
    }

    private static void seedCache(FakePreferences preferences, String prefix, int width, int height) {
        preferences.values.put(prefix + "encoderName", "codec.invalid-cache");
        preferences.values.put(prefix + "width", width);
        preferences.values.put(prefix + "height", height);
        preferences.values.put(prefix + "fps", 8);
        preferences.values.put(prefix + "bitrate", 150_000);
        preferences.values.put(prefix + "forceBaseline", true);
    }

    private static void assertProfile(
        AvcEncoderProfile actual, String encoderName, int width, int height, int fps, int bitrate,
        boolean forceBaseline, String source
    ) {
        assertEquals(encoderName, actual.encoderName, "encoderName");
        assertEquals(width, actual.width, "width");
        assertEquals(height, actual.height, "height");
        assertEquals(fps, actual.fps, "fps");
        assertEquals(bitrate, actual.bitrate, "bitrate");
        assertEquals(forceBaseline, actual.forceBaseline, "forceBaseline");
        assertEquals(source, actual.source, "source");
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static final class FakeContext extends Context {
        private final Map<String, FakePreferences> stores = new HashMap<>();

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            return preferences(name);
        }

        FakePreferences preferences(String name) {
            FakePreferences existing = stores.get(name);
            if (existing != null) return existing;
            FakePreferences created = new FakePreferences();
            stores.put(name, created);
            return created;
        }
    }

    private static final class FakePreferences implements SharedPreferences, SharedPreferences.Editor {
        final Map<String, Object> values = new HashMap<>();
        boolean throwOnRead;

        @Override public int getInt(String key, int fallback) {
            checkReadable();
            Object value = values.get(key);
            return value instanceof Integer ? (Integer) value : fallback;
        }

        @Override public String getString(String key, String fallback) {
            checkReadable();
            Object value = values.get(key);
            return value instanceof String ? (String) value : fallback;
        }

        @Override public boolean getBoolean(String key, boolean fallback) {
            checkReadable();
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : fallback;
        }

        @Override public SharedPreferences.Editor edit() { return this; }
        @Override public SharedPreferences.Editor putInt(String key, int value) { values.put(key, value); return this; }
        @Override public SharedPreferences.Editor putString(String key, String value) { values.put(key, value); return this; }
        @Override public SharedPreferences.Editor putBoolean(String key, boolean value) { values.put(key, value); return this; }
        @Override public void apply() {}

        private void checkReadable() {
            if (throwOnRead) throw new IllegalStateException("preferences unavailable");
        }
    }
}
