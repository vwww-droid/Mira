package com.vwww.mira;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MiraSelfScreenStreamer implements Closeable {
    private static final String TAG = "MiraScreenStreamer";
    private static final String MIME_AVC = "video/avc";
    private static final String CODEC_AVC_BASELINE = "avc1.42E01E";
    private static final int MAX_WIDTH = 540;
    private static final int FRAME_RATE = 10;
    private static final int BITRATE = 220_000;
    private static final int I_FRAME_INTERVAL_SECONDS = 1;
    private static final long FRAME_PERIOD_MS = 1000L / FRAME_RATE;
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
                VideoSize videoSize = chooseVideoSize(rootSize.width, rootSize.height);
                MiraWebSocketConnection connected = MiraWebSocketConnection.connect(screenDeviceWsUrl(relayUrl));
                websocket = connected;
                configureEncoder(videoSize.width, videoSize.height);
                encodeLoop(connected, videoSize, rootSize);
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

    private void configureEncoder(int width, int height) throws Exception {
        closeEncoderOnly();
        codecConfig = null;
        MediaCodec nextEncoder = null;
        try {
            nextEncoder = createEncoder(width, height, true);
        } catch (Throwable first) {
            if (nextEncoder != null) nextEncoder.release();
            Log.w(TAG, "Baseline AVC encoder configure failed, retrying default profile", first);
            nextEncoder = createEncoder(width, height, false);
        }
        encoder = nextEncoder;
        inputSurface = nextEncoder.createInputSurface();
        nextEncoder.start();
    }

    private MediaCodec createEncoder(int width, int height, boolean forceBaseline) throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(MIME_AVC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS);
        if (Build.VERSION.SDK_INT >= 21) {
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        }
        if (forceBaseline && Build.VERSION.SDK_INT >= 21) {
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel3);
        }
        if (Build.VERSION.SDK_INT >= 23) {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        }
        MediaCodec codec = MediaCodec.createEncoderByType(MIME_AVC);
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            return codec;
        } catch (Throwable throwable) {
            try {
                codec.release();
            } catch (Throwable ignored) {
            }
            throw throwable;
        }
    }

    private void encodeLoop(MiraWebSocketConnection connected, VideoSize videoSize, MiraSelfScreenCapture.RootSize rootSize) throws Exception {
        MediaCodec currentEncoder = encoder;
        Surface currentSurface = inputSurface;
        if (currentEncoder == null || currentSurface == null) return;
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        long nextFrameAt = SystemClock.uptimeMillis();
        while (running.get()) {
            long now = SystemClock.uptimeMillis();
            if (now < nextFrameAt) sleepQuietly(nextFrameAt - now);
            nextFrameAt = Math.max(nextFrameAt + FRAME_PERIOD_MS, SystemClock.uptimeMillis());

            MiraSelfScreenCapture.RenderResult render = MiraSelfScreenCapture.getInstance().renderToSurface(currentSurface, videoSize.width, videoSize.height);
            if (!render.available) {
                logFailure("screen render unavailable: " + render.error, null);
                sleepQuietly(250);
                continue;
            }
            drainEncoder(currentEncoder, bufferInfo, connected, videoSize, rootSize, false);
        }
    }

    private void drainEncoder(
        MediaCodec currentEncoder,
        MediaCodec.BufferInfo bufferInfo,
        MiraWebSocketConnection connected,
        VideoSize videoSize,
        MiraSelfScreenCapture.RootSize rootSize,
        boolean endOfStream
    ) throws Exception {
        if (endOfStream && Build.VERSION.SDK_INT >= 18) currentEncoder.signalEndOfInputStream();
        while (running.get()) {
            int outputIndex = currentEncoder.dequeueOutputBuffer(bufferInfo, 0);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            }
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                codecConfig = codecConfigFromFormat(currentEncoder.getOutputFormat());
                codecString = codecStringFromSps(codecConfig, CODEC_AVC_BASELINE);
                connected.sendJson(screenInfo(videoSize, rootSize));
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
                connected.sendJson(screenInfo(videoSize, rootSize));
                currentEncoder.releaseOutputBuffer(outputIndex, false);
                continue;
            }
            if (bufferInfo.size > 0) {
                byte[] payload = toAnnexB(copyBuffer(encodedBuffer, bufferInfo));
                boolean keyFrame = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                if (keyFrame && codecConfig != null && codecConfig.length > 0 && findNalUnit(payload, 7) == null) {
                    payload = concat(codecConfig, payload);
                }
                connected.sendFrame(videoPacket(payload, keyFrame), 0x2);
            }
            boolean ended = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            currentEncoder.releaseOutputBuffer(outputIndex, false);
            if (ended) break;
        }
    }

    private byte[] videoPacket(byte[] payload, boolean keyFrame) {
        long nextSequence = ++sequence;
        long presentationTimeUs = nextSequence * FRAME_PERIOD_MS * 1000L;
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

    private JSONObject screenInfo(VideoSize videoSize, MiraSelfScreenCapture.RootSize rootSize) throws Exception {
        JSONObject json = new JSONObject();
        json.put("type", "screen.video.info");
        json.put("protocol", 1);
        json.put("installId", identity.getInstallId());
        json.put("deviceName", deviceName);
        json.put("codec", codecString);
        json.put("mime", MIME_AVC);
        json.put("format", "annexb");
        json.put("width", videoSize.width);
        json.put("height", videoSize.height);
        json.put("sourceWidth", rootSize.width);
        json.put("sourceHeight", rootSize.height);
        json.put("fps", FRAME_RATE);
        json.put("bitrate", BITRATE);
        json.put("maxWidth", MAX_WIDTH);
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

    private static VideoSize chooseVideoSize(int sourceWidth, int sourceHeight) {
        int width = Math.min(MAX_WIDTH, Math.max(2, sourceWidth));
        int height = Math.max(2, Math.round(width * (sourceHeight / (float) Math.max(1, sourceWidth))));
        width = even(width);
        height = even(height);
        return new VideoSize(width, height);
    }

    private static int even(int value) {
        int result = Math.max(2, value);
        return result % 2 == 0 ? result : result - 1;
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

    private static final class VideoSize {
        final int width;
        final int height;

        VideoSize(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }
}
