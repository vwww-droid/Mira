package com.vwww.mira;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.SystemClock;
import android.util.Range;
import android.util.Log;
import android.view.Surface;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class MiraSelfScreenStreamer implements Closeable {
    private static final String TAG = "MiraScreenStreamer";
    private static final String MIME_AVC = "video/avc";
    private static final String CODEC_AVC_BASELINE = "avc1.42E01E";
    private static final int MAX_WIDTH = 540;
    private static final int VIDEO_SIZE_ALIGNMENT = 16;
    private static final int FRAME_RATE = 10;
    private static final int BITRATE = 220_000;
    private static final int I_FRAME_INTERVAL_SECONDS = 1;
    private static final long ENCODER_CREATE_TIMEOUT_MS = 3000;
    private static final long ENCODER_CONFIGURE_TIMEOUT_MS = 3000;
    private static final long FIRST_FRAME_TIMEOUT_MS = 3000;
    private static final String PREFS_NAME = "mira-screen-encoder";
    private static final int PACKET_HEADER_BYTES = 20;
    private static final byte FLAG_KEY_FRAME = 1;

    private final Context context;
    private final MiraIdentity identity;
    private final String deviceName;
    private final String relayUrl;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Thread workerThread;
    private volatile MiraWebSocketConnection websocket;
    private volatile MediaCodec encoder;
    private volatile Surface inputSurface;
    private volatile long lastFailureLogAt;
    private long sequence;
    private byte[] codecConfig;
    private String codecString = CODEC_AVC_BASELINE;
    private VideoProfile activeProfile;

    public MiraSelfScreenStreamer(Context context, MiraIdentity identity, String deviceName, String relayUrl) {
        this.context = context.getApplicationContext();
        this.identity = identity;
        this.deviceName = deviceName;
        this.relayUrl = relayUrl;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        workerThread = new Thread(this::runLoop, "MiraScreenStreamer");
        workerThread.start();
    }

    public boolean isAlive() {
        Thread thread = workerThread;
        return running.get() && thread != null && thread.isAlive();
    }

    private void runLoop() {
        while (running.get()) {
            try {
                MiraSelfScreenCapture.RootSize rootSize = waitForRootSize();
                if (!running.get()) break;
                if (!rootSize.available) {
                    logFailure("screen root unavailable: " + rootSize.error, null);
                    sleepQuietly(500);
                    continue;
                }
                VideoProfile profile = configureEncoder(rootSize);
                if (!running.get()) break;
                MiraWebSocketConnection connected = MiraWebSocketConnection.connect(screenDeviceWsUrl(relayUrl));
                websocket = connected;
                connected.sendJson(screenInfo(profile, rootSize));
                Log.i(TAG, "screen video info sent codec=" + codecString + " profile=" + profile.describe() + " source=" + rootSize.width + "x" + rootSize.height);
                encodeLoop(connected, profile, rootSize);
            } catch (Throwable throwable) {
                if (running.get()) {
                    logFailure("h264 screen stream failed", throwable);
                    sleepQuietly(1000);
                }
            } finally {
                closeEncoderOnly();
                closeSocketOnly();
            }
        }
    }

    private MiraSelfScreenCapture.RootSize waitForRootSize() {
        MiraSelfScreenCapture.RootSize rootSize = MiraSelfScreenCapture.getInstance().currentRootSize();
        long deadline = SystemClock.uptimeMillis() + 3000;
        while (running.get() && !rootSize.available && SystemClock.uptimeMillis() < deadline) {
            sleepQuietly(150);
            rootSize = MiraSelfScreenCapture.getInstance().currentRootSize();
        }
        return rootSize;
    }

    private VideoProfile configureEncoder(MiraSelfScreenCapture.RootSize rootSize) throws Exception {
        closeEncoderOnly();
        codecConfig = null;
        activeProfile = null;
        List<VideoProfile> profiles = buildVideoProfiles(rootSize.width, rootSize.height);
        Throwable lastFailure = null;
        Set<String> skippedEncoders = new HashSet<>();
        for (VideoProfile profile : profiles) {
            String encoderKey = profile.encoderName == null ? "" : profile.encoderName;
            if (skippedEncoders.contains(encoderKey)) {
                Log.i(TAG, "skipping AVC encoder candidate after create timeout " + profile.describe());
                continue;
            }
            closeEncoderOnly();
            codecConfig = null;
            try {
                Log.i(TAG, "configuring AVC encoder candidate " + profile.describe());
                MediaCodec nextEncoder = createEncoder(profile);
                encoder = nextEncoder;
                Log.i(TAG, "creating AVC input surface " + profile.describe());
                inputSurface = nextEncoder.createInputSurface();
                Log.i(TAG, "starting AVC encoder " + profile.describe());
                nextEncoder.start();
                activeProfile = profile;
                sequence = 0;
                Log.i(TAG, "AVC encoder started " + profile.describe());
                return profile;
            } catch (Throwable throwable) {
                lastFailure = throwable;
                Log.w(TAG, "AVC encoder candidate failed " + profile.describe(), throwable);
                if (throwable instanceof CodecCreateTimeoutException) {
                    skippedEncoders.add(encoderKey);
                }
                sleepQuietly(150);
            }
        }
        if (lastFailure instanceof Exception) throw (Exception) lastFailure;
        if (lastFailure instanceof Error) throw (Error) lastFailure;
        throw new IllegalStateException("No usable AVC encoder profile");
    }

    private MediaCodec createEncoder(VideoProfile profile) throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(MIME_AVC, profile.width, profile.height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, profile.bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, profile.fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS);
        if (Build.VERSION.SDK_INT >= 21) {
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        }
        if (profile.forceBaseline && Build.VERSION.SDK_INT >= 21) {
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31);
        }
        if (Build.VERSION.SDK_INT >= 23) {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        }
        MediaCodec codec = createCodecWithTimeout(profile);
        try {
            Log.i(TAG, "AVC codec created " + profile.describe() + " format=" + format);
            Log.i(TAG, "AVC codec configure begin " + profile.describe());
            configureCodecWithTimeout(codec, format, profile);
            Log.i(TAG, "AVC codec configure ok " + profile.describe());
            return codec;
        } catch (Throwable throwable) {
            try {
                codec.release();
            } catch (Throwable ignored) {
            }
            throw throwable;
        }
    }

    private MediaCodec createCodecWithTimeout(VideoProfile profile) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<MediaCodec> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicBoolean abandoned = new AtomicBoolean(false);
        Thread createThread = new Thread(() -> {
            MediaCodec codec = null;
            try {
                codec = profile.encoderName == null || profile.encoderName.isEmpty()
                    ? MediaCodec.createEncoderByType(MIME_AVC)
                    : MediaCodec.createByCodecName(profile.encoderName);
                if (abandoned.get()) {
                    try {
                        codec.release();
                    } catch (Throwable ignored) {
                    }
                } else {
                    result.set(codec);
                    codec = null;
                }
            } catch (Throwable throwable) {
                error.set(throwable);
            } finally {
                latch.countDown();
                if (codec != null) {
                    try {
                        codec.release();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "MiraAvcCreate");
        createThread.setDaemon(true);
        createThread.start();

        boolean completed;
        try {
            completed = latch.await(ENCODER_CREATE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        if (!completed) {
            abandoned.set(true);
            Log.w(TAG, "AVC codec create timeout " + profile.describe() + " timeoutMs=" + ENCODER_CREATE_TIMEOUT_MS);
            throw new CodecCreateTimeoutException(profile.encoderName);
        }
        Throwable throwable = error.get();
        if (throwable != null) {
            if (throwable instanceof Exception) throw (Exception) throwable;
            if (throwable instanceof Error) throw (Error) throwable;
            throw new RuntimeException(throwable);
        }
        MediaCodec codec = result.get();
        if (codec == null) throw new IllegalStateException("AVC codec create returned null");
        return codec;
    }

    private void configureCodecWithTimeout(MediaCodec codec, MediaFormat format, VideoProfile profile) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread configureThread = new Thread(() -> {
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            } catch (Throwable throwable) {
                error.set(throwable);
            } finally {
                latch.countDown();
            }
        }, "MiraAvcConfigure");
        configureThread.setDaemon(true);
        configureThread.start();

        boolean completed;
        try {
            completed = latch.await(ENCODER_CONFIGURE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        if (!completed) {
            Log.w(TAG, "AVC codec configure timeout " + profile.describe() + " timeoutMs=" + ENCODER_CONFIGURE_TIMEOUT_MS);
            try {
                codec.release();
            } catch (Throwable ignored) {
            }
            throw new IllegalStateException("AVC codec configure timeout");
        }
        Throwable throwable = error.get();
        if (throwable == null) return;
        if (throwable instanceof Exception) throw (Exception) throwable;
        if (throwable instanceof Error) throw (Error) throwable;
        throw new RuntimeException(throwable);
    }

    private void encodeLoop(MiraWebSocketConnection connected, VideoProfile profile, MiraSelfScreenCapture.RootSize rootSize) throws Exception {
        MediaCodec currentEncoder = encoder;
        Surface currentSurface = inputSurface;
        if (currentEncoder == null || currentSurface == null) return;
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        long nextFrameAt = SystemClock.uptimeMillis();
        long startedAt = nextFrameAt;
        boolean sawFrame = false;
        while (running.get()) {
            long now = SystemClock.uptimeMillis();
            if (now < nextFrameAt) sleepQuietly(nextFrameAt - now);
            nextFrameAt = Math.max(nextFrameAt + profile.framePeriodMs(), SystemClock.uptimeMillis());

            MiraSelfScreenCapture.RenderResult render = MiraSelfScreenCapture.getInstance().renderToSurface(currentSurface, profile.width, profile.height);
            if (!render.available) {
                logFailure("screen render unavailable: " + render.error, null);
                sleepQuietly(250);
                continue;
            }
            if (drainEncoder(currentEncoder, bufferInfo, connected, profile, rootSize, false)) {
                if (!sawFrame) {
                    sawFrame = true;
                    rememberSuccessfulProfile(profile);
                }
            } else if (!sawFrame && SystemClock.uptimeMillis() - startedAt > FIRST_FRAME_TIMEOUT_MS) {
                throw new IllegalStateException("AVC encoder produced no frames within " + FIRST_FRAME_TIMEOUT_MS + "ms for " + profile.describe());
            }
        }
    }

    private boolean drainEncoder(
        MediaCodec currentEncoder,
        MediaCodec.BufferInfo bufferInfo,
        MiraWebSocketConnection connected,
        VideoProfile profile,
        MiraSelfScreenCapture.RootSize rootSize,
        boolean endOfStream
    ) throws Exception {
        boolean sentFrame = false;
        if (endOfStream && Build.VERSION.SDK_INT >= 18) currentEncoder.signalEndOfInputStream();
        while (running.get()) {
            int outputIndex = currentEncoder.dequeueOutputBuffer(bufferInfo, 0);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return sentFrame;
            }
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                codecConfig = codecConfigFromFormat(currentEncoder.getOutputFormat());
                codecString = codecStringFromSps(codecConfig, CODEC_AVC_BASELINE);
                connected.sendJson(screenInfo(profile, rootSize));
                continue;
            }
            if (outputIndex < 0) continue;

            ByteBuffer encodedBuffer = currentEncoder.getOutputBuffer(outputIndex);
            if (encodedBuffer == null) {
                currentEncoder.releaseOutputBuffer(outputIndex, false);
                continue;
            }
            if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                codecConfig = toAnnexB(copyBuffer(encodedBuffer, bufferInfo));
                codecString = codecStringFromSps(codecConfig, codecString);
                connected.sendJson(screenInfo(profile, rootSize));
                currentEncoder.releaseOutputBuffer(outputIndex, false);
                continue;
            }
            if (bufferInfo.size > 0) {
                byte[] payload = toAnnexB(copyBuffer(encodedBuffer, bufferInfo));
                boolean keyFrame = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                if (keyFrame && codecConfig != null && codecConfig.length > 0 && findNalUnit(payload, 7) == null) {
                    payload = concat(codecConfig, payload);
                }
                byte[] packet = videoPacket(payload, keyFrame, profile);
                connected.sendFrame(packet, 0x2);
                sentFrame = true;
                if (sequence == 1 || keyFrame) {
                    Log.i(TAG, "screen frame sent seq=" + sequence + " key=" + keyFrame + " bytes=" + payload.length);
                }
            }
            boolean ended = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            currentEncoder.releaseOutputBuffer(outputIndex, false);
            if (ended) break;
        }
        return sentFrame;
    }

    private byte[] videoPacket(byte[] payload, boolean keyFrame, VideoProfile profile) {
        long nextSequence = ++sequence;
        long presentationTimeUs = nextSequence * profile.framePeriodMs() * 1000L;
        ByteBuffer packet = ByteBuffer.allocate(PACKET_HEADER_BYTES + payload.length);
        packet.put((byte) 'M');
        packet.put((byte) 'H');
        packet.put((byte) 'S');
        packet.put((byte) '1');
        packet.put(keyFrame ? FLAG_KEY_FRAME : (byte) 0);
        packet.put((byte) 0);
        packet.putShort((short) 0);
        packet.putInt((int) Math.min(Integer.MAX_VALUE, nextSequence));
        packet.putLong(presentationTimeUs);
        packet.put(payload);
        return packet.array();
    }

    private JSONObject screenInfo(VideoProfile profile, MiraSelfScreenCapture.RootSize rootSize) throws Exception {
        JSONObject json = new JSONObject();
        json.put("type", "screen.video.info");
        json.put("protocol", 1);
        json.put("installId", identity.getInstallId());
        json.put("deviceName", deviceName);
        json.put("codec", codecString);
        json.put("mime", MIME_AVC);
        json.put("format", "annexb");
        json.put("width", profile.width);
        json.put("height", profile.height);
        json.put("sourceWidth", rootSize.width);
        json.put("sourceHeight", rootSize.height);
        json.put("fps", profile.fps);
        json.put("bitrate", profile.bitrate);
        json.put("maxWidth", MAX_WIDTH);
        json.put("encoderName", profile.encoderName == null ? "" : profile.encoderName);
        json.put("profileSource", profile.source);
        json.put("forceBaseline", profile.forceBaseline);
        return json;
    }

    private static byte[] copyBuffer(ByteBuffer buffer, MediaCodec.BufferInfo info) {
        ByteBuffer duplicate = buffer.duplicate();
        duplicate.position(info.offset);
        duplicate.limit(info.offset + info.size);
        byte[] data = new byte[info.size];
        duplicate.get(data);
        return data;
    }

    private static byte[] codecConfigFromFormat(MediaFormat format) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            appendCsd(output, format, "csd-0");
            appendCsd(output, format, "csd-1");
            byte[] data = output.toByteArray();
            return data.length == 0 ? null : data;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void appendCsd(ByteArrayOutputStream output, MediaFormat format, String key) {
        ByteBuffer buffer = format.getByteBuffer(key);
        if (buffer == null) return;
        ByteBuffer duplicate = buffer.duplicate();
        byte[] data = new byte[duplicate.remaining()];
        duplicate.get(data);
        if (data.length == 0) return;
        data = toAnnexB(data);
        output.write(data, 0, data.length);
    }

    private static byte[] toAnnexB(byte[] data) {
        if (data == null || data.length == 0) return data;
        if (startsWithStartCode(data)) return data;
        byte[] converted = lengthPrefixedToAnnexB(data, 4);
        if (converted != null) return converted;
        converted = lengthPrefixedToAnnexB(data, 2);
        if (converted != null) return converted;
        return concat(new byte[] {0, 0, 0, 1}, data);
    }

    private static boolean startsWithStartCode(byte[] data) {
        return data.length >= 4 && data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1
            || data.length >= 3 && data[0] == 0 && data[1] == 0 && data[2] == 1;
    }

    private static byte[] lengthPrefixedToAnnexB(byte[] data, int lengthSize) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(data.length + 16);
            int offset = 0;
            int units = 0;
            while (offset + lengthSize <= data.length) {
                int length = 0;
                for (int i = 0; i < lengthSize; i++) length = (length << 8) | (data[offset + i] & 0xFF);
                offset += lengthSize;
                if (length <= 0 || offset + length > data.length) return null;
                output.write(new byte[] {0, 0, 0, 1}, 0, 4);
                output.write(data, offset, length);
                offset += length;
                units++;
            }
            if (offset != data.length || units == 0) return null;
            return output.toByteArray();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String codecStringFromSps(byte[] annexB, String fallback) {
        byte[] sps = findNalUnit(annexB, 7);
        if (sps == null || sps.length < 4) return fallback;
        return String.format(Locale.US, "avc1.%02X%02X%02X", sps[1] & 0xFF, sps[2] & 0xFF, sps[3] & 0xFF);
    }

    private static byte[] findNalUnit(byte[] annexB, int nalType) {
        if (annexB == null) return null;
        int offset = 0;
        while (offset < annexB.length) {
            int start = findStartCode(annexB, offset);
            if (start < 0) return null;
            int nalStart = annexB[start + 2] == 1 ? start + 3 : start + 4;
            int next = findStartCode(annexB, nalStart);
            int nalEnd = next < 0 ? annexB.length : next;
            if (nalStart < nalEnd && (annexB[nalStart] & 0x1F) == nalType) {
                byte[] unit = new byte[nalEnd - nalStart];
                System.arraycopy(annexB, nalStart, unit, 0, unit.length);
                return unit;
            }
            offset = nalEnd;
        }
        return null;
    }

    private static int findStartCode(byte[] data, int from) {
        for (int i = Math.max(0, from); i + 3 <= data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) return i;
            if (i + 4 <= data.length && data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1) return i;
        }
        return -1;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private List<VideoProfile> buildVideoProfiles(int sourceWidth, int sourceHeight) {
        LinkedHashMap<String, VideoProfile> profiles = new LinkedHashMap<>();
        VideoProfile cached = readCachedProfile(sourceWidth, sourceHeight);
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

    private void addCodecProfiles(
        LinkedHashMap<String, VideoProfile> profiles,
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
                            new VideoProfile(codecInfo.getName(), width, height, clampedFps, bitrate, forceBaseline, "capability")
                        );
                    }
                }
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "Unable to build AVC profiles for " + codecInfo.getName(), throwable);
        }
    }

    private void addProfile(LinkedHashMap<String, VideoProfile> profiles, VideoProfile profile) {
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

    private VideoProfile readCachedProfile(int sourceWidth, int sourceHeight) {
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
            return new VideoProfile(
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

    private void rememberSuccessfulProfile(VideoProfile profile) {
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

    private String screenDeviceWsUrl(String value) throws Exception {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty()) throw new IllegalArgumentException("Relay URL is empty");
        if (!raw.contains("://")) raw = "https://" + raw;
        URI uri = new URI(raw);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ("http".equals(scheme)) scheme = "ws";
        else if ("https".equals(scheme)) scheme = "wss";
        else if (!"ws".equals(scheme) && !"wss".equals(scheme)) throw new IllegalArgumentException("Unsupported Relay URL scheme");
        String authority = uri.getRawAuthority();
        if (authority == null || authority.trim().isEmpty()) throw new IllegalArgumentException("Relay URL host is empty");
        String path = uri.getRawPath();
        if (path == null || path.isEmpty() || "/".equals(path)) path = "/ws/screen/device";
        else if (!path.endsWith("/ws/screen/device")) path = path.replaceAll("/+$", "") + "/ws/screen/device";
        return scheme + "://" + authority + path;
    }

    private void logFailure(String message, Throwable throwable) {
        long now = System.currentTimeMillis();
        if (now - lastFailureLogAt < 5000) return;
        lastFailureLogAt = now;
        if (throwable == null) Log.w(TAG, message);
        else Log.w(TAG, message, throwable);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(Math.max(1, millis));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeSocketOnly() {
        MiraWebSocketConnection closing = websocket;
        websocket = null;
        if (closing != null) closing.close();
    }

    private void closeEncoderOnly() {
        Surface closingSurface = inputSurface;
        inputSurface = null;
        if (closingSurface != null) {
            try {
                closingSurface.release();
            } catch (Throwable ignored) {
            }
        }
        MediaCodec closingEncoder = encoder;
        encoder = null;
        if (closingEncoder != null) {
            try {
                closingEncoder.stop();
            } catch (Throwable ignored) {
            }
            try {
                closingEncoder.release();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void close() {
        running.set(false);
        closeSocketOnly();
        closeEncoderOnly();
        Thread thread = workerThread;
        if (thread != null) thread.interrupt();
    }

    private static final class VideoProfile {
        final String encoderName;
        final int width;
        final int height;
        final int fps;
        final int bitrate;
        final boolean forceBaseline;
        final String source;

        VideoProfile(String encoderName, int width, int height, int fps, int bitrate, boolean forceBaseline, String source) {
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

    private static final class CodecCreateTimeoutException extends Exception {
        CodecCreateTimeoutException(String encoderName) {
            super("AVC codec create timeout for " + (encoderName == null || encoderName.isEmpty() ? "default encoder" : encoderName));
        }
    }
}
