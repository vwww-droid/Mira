package com.vwww.mira.screen;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import org.json.JSONObject;

import com.vwww.mira.MiraIdentity;
import com.vwww.mira.MiraWebSocketConnection;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class AppScreenStreamer implements Closeable {
    private static final String TAG = "MiraScreenStreamer";
    private static final String MIME_AVC = "video/avc";
    private static final String CODEC_AVC_BASELINE = "avc1.42E01E";
    private static final int I_FRAME_INTERVAL_SECONDS = 1;
    private static final long ENCODER_CONFIGURE_TIMEOUT_MS = 3000;
    private static final long FIRST_FRAME_TIMEOUT_MS = 3000;

    private final MiraIdentity identity;
    private final String deviceName;
    private final String relayUrl;
    private final AvcEncoderSelector encoderSelector;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Thread workerThread;
    private volatile MiraWebSocketConnection websocket;
    private volatile MediaCodec encoder;
    private volatile Surface inputSurface;
    private volatile long lastFailureLogAt;
    private long sequence;
    private byte[] codecConfig;
    private String codecString = CODEC_AVC_BASELINE;
    private AvcEncoderProfile activeProfile;

    public AppScreenStreamer(Context context, MiraIdentity identity, String deviceName, String relayUrl) {
        this.identity = identity;
        this.deviceName = deviceName;
        this.relayUrl = relayUrl;
        this.encoderSelector = new AvcEncoderSelector(context);
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
                AppScreenCapture.RootSize rootSize = waitForRootSize();
                if (!running.get()) break;
                if (!rootSize.available) {
                    logFailure("screen root unavailable: " + rootSize.error, null);
                    sleepQuietly(500);
                    continue;
                }
                AvcEncoderProfile profile = configureEncoder(rootSize);
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

    private AppScreenCapture.RootSize waitForRootSize() {
        AppScreenCapture.RootSize rootSize = AppScreenCapture.getInstance().currentRootSize();
        long deadline = SystemClock.uptimeMillis() + 3000;
        while (running.get() && !rootSize.available && SystemClock.uptimeMillis() < deadline) {
            sleepQuietly(150);
            rootSize = AppScreenCapture.getInstance().currentRootSize();
        }
        return rootSize;
    }

    private AvcEncoderProfile configureEncoder(AppScreenCapture.RootSize rootSize) throws Exception {
        closeEncoderOnly();
        codecConfig = null;
        activeProfile = null;
        List<AvcEncoderProfile> profiles = encoderSelector.selectProfiles(rootSize.width, rootSize.height);
        Throwable lastFailure = null;
        Set<String> skippedEncoders = new HashSet<>();
        boolean coldCreate = true;
        for (AvcEncoderProfile profile : profiles) {
            String encoderKey = profile.encoderName == null ? "" : profile.encoderName;
            if (skippedEncoders.contains(encoderKey)) {
                Log.i(TAG, "skipping AVC encoder candidate after create timeout " + profile.describe());
                continue;
            }
            profile.coldCreate = coldCreate;
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
                coldCreate = false;
                lastFailure = throwable;
                Log.w(TAG, "AVC encoder candidate failed " + profile.describe(), throwable);
                if (throwable instanceof CodecCreateTimeoutException) {
                    skippedEncoders.add(encoderKey);
                    break;
                }
                sleepQuietly(150);
            }
        }
        if (lastFailure instanceof Exception) throw (Exception) lastFailure;
        if (lastFailure instanceof Error) throw (Error) lastFailure;
        throw new IllegalStateException("No usable AVC encoder profile");
    }

    private MediaCodec createEncoder(AvcEncoderProfile profile) throws Exception {
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

    private MediaCodec createCodecWithTimeout(AvcEncoderProfile profile) throws Exception {
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
            completed = latch.await(profile.createTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        if (!completed) {
            abandoned.set(true);
            Log.w(TAG, "AVC codec create timeout " + profile.describe() + " timeoutMs=" + profile.createTimeoutMs());
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

    private void configureCodecWithTimeout(MediaCodec codec, MediaFormat format, AvcEncoderProfile profile) throws Exception {
        long startedAt = SystemClock.uptimeMillis();
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } finally {
            long elapsedMs = SystemClock.uptimeMillis() - startedAt;
            if (elapsedMs > ENCODER_CONFIGURE_TIMEOUT_MS) {
                Log.w(TAG, "AVC codec configure slow " + profile.describe() + " elapsedMs=" + elapsedMs);
            }
        }
    }

    private void encodeLoop(MiraWebSocketConnection connected, AvcEncoderProfile profile, AppScreenCapture.RootSize rootSize) throws Exception {
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

            AppScreenCapture.RenderResult render = AppScreenCapture.getInstance().renderToSurface(currentSurface, profile.width, profile.height);
            if (!render.available) {
                logFailure("screen render unavailable: " + render.error, null);
                sleepQuietly(250);
                continue;
            }
            if (drainEncoder(currentEncoder, bufferInfo, connected, profile, rootSize, false)) {
                if (!sawFrame) {
                    sawFrame = true;
                    encoderSelector.rememberSuccessfulProfile(profile);
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
        AvcEncoderProfile profile,
        AppScreenCapture.RootSize rootSize,
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
                codecString = AvcBitstream.codecStringFromSps(codecConfig, CODEC_AVC_BASELINE);
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
                codecConfig = AvcBitstream.toAnnexB(copyBuffer(encodedBuffer, bufferInfo));
                codecString = AvcBitstream.codecStringFromSps(codecConfig, codecString);
                connected.sendJson(screenInfo(profile, rootSize));
                currentEncoder.releaseOutputBuffer(outputIndex, false);
                continue;
            }
            if (bufferInfo.size > 0) {
                byte[] payload = AvcBitstream.toAnnexB(copyBuffer(encodedBuffer, bufferInfo));
                boolean keyFrame = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                payload = AvcBitstream.withCodecConfig(payload, codecConfig, keyFrame);
                long nextSequence = ++sequence;
                long presentationTimeUs = nextSequence * profile.framePeriodMs() * 1000L;
                byte[] packet = ScreenVideoPacket.encode(payload, keyFrame, nextSequence, presentationTimeUs);
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

    private JSONObject screenInfo(AvcEncoderProfile profile, AppScreenCapture.RootSize rootSize) throws Exception {
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
        json.put("maxWidth", AvcEncoderSelector.MAX_WIDTH);
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
        data = AvcBitstream.toAnnexB(data);
        output.write(data, 0, data.length);
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

    private static final class CodecCreateTimeoutException extends Exception {
        CodecCreateTimeoutException(String encoderName) {
            super("AVC codec create timeout for " + (encoderName == null || encoderName.isEmpty() ? "default encoder" : encoderName));
        }
    }
}
