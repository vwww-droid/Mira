package com.vwww.mira;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.Closeable;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MiraControlClient implements Closeable {
    public interface Callback {
        void onControlMessage(JSONObject message);
        void onControlStatus(String status);
    }

    public interface StateProvider {
        String currentState();
    }

    public interface OutlineProvider {
        JSONObject currentOutline();
    }

    private static final String TAG = "MiraControlClient";
    private static final long OUTLINE_PERIOD_SECONDS = 2;

    private final Context context;
    private final MiraIdentity identity;
    private final String deviceName;
    private final String relayUrl;
    private final StateProvider stateProvider;
    private final OutlineProvider outlineProvider;
    private final Callback callback;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean controlReady = new AtomicBoolean(false);
    private final ScheduledExecutorService outboundExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "MiraControlSender");
        thread.setDaemon(true);
        return thread;
    });

    private volatile MiraWebSocketConnection websocket;
    private Thread workerThread;

    public MiraControlClient(
        Context context,
        MiraIdentity identity,
        String deviceName,
        String relayUrl,
        StateProvider stateProvider,
        OutlineProvider outlineProvider,
        Callback callback
    ) {
        this.context = context.getApplicationContext();
        this.identity = identity;
        this.deviceName = deviceName;
        this.relayUrl = relayUrl;
        this.stateProvider = stateProvider;
        this.outlineProvider = outlineProvider;
        this.callback = callback;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        outboundExecutor.scheduleWithFixedDelay(this::sendOutlineIfReady, OUTLINE_PERIOD_SECONDS, OUTLINE_PERIOD_SECONDS, TimeUnit.SECONDS);
        workerThread = new Thread(this::runLoop, "MiraControlClient");
        workerThread.start();
    }

    private void runLoop() {
        while (running.get()) {
            try {
                String controlWs = controlWsUrl(relayUrl);
                MiraWebSocketConnection connected = MiraWebSocketConnection.connect(controlWs);
                if (!running.get()) {
                    connected.close();
                    break;
                }
                websocket = connected;
                controlReady.set(false);
                connected.sendJson(registerMessage());
                notifyStatus("control connected");
                readControlLoop();
            } catch (Throwable throwable) {
                if (running.get()) {
                    Log.w(TAG, "Control channel failed", throwable);
                    notifyStatus("control disconnected: " + describeFailure(throwable));
                    sleepQuietly(2000);
                }
            } finally {
                closeSocketOnly();
            }
        }
    }

    private void readControlLoop() throws Exception {
        while (running.get()) {
            MiraWebSocketConnection current = websocket;
            if (current == null) break;
            MiraWebSocketConnection.WebSocketFrame frame = current.readFrame();
            if (frame.isClose()) break;
            if (frame.isPing()) {
                current.sendPong(frame.payload);
                continue;
            }
            if (!frame.isText()) continue;
            JSONObject message = new JSONObject(new String(frame.payload, StandardCharsets.UTF_8));
            String type = message.optString("type", "");
            if ("control.ready".equals(type)) {
                controlReady.set(true);
                notifyStatus("control ready");
                sendOutline();
                continue;
            }
            if (callback != null) callback.onControlMessage(message);
        }
    }

    public void sendJson(JSONObject json) {
        if (json == null || outboundExecutor.isShutdown()) return;
        try {
            outboundExecutor.execute(() -> {
                try {
                    MiraWebSocketConnection current = websocket;
                    if (!running.get() || current == null) return;
                    current.sendJson(json);
                } catch (Throwable throwable) {
                    Log.w(TAG, "Control send failed", throwable);
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    public void sendOutline() {
        if (outboundExecutor.isShutdown()) return;
        try {
            outboundExecutor.execute(this::sendOutlineIfReady);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void sendOutlineIfReady() {
        if (!running.get() || !controlReady.get() || outlineProvider == null) return;
        try {
            JSONObject message = new JSONObject();
            message.put("type", "device.outline");
            message.put("transport", "control");
            message.put("installId", identity.getInstallId());
            message.put("deviceName", deviceName);
            message.put("state", stateProvider == null ? "idle" : stateProvider.currentState());
            message.put("capturedAt", System.currentTimeMillis());
            message.put("outline", outlineProvider.currentOutline());
            MiraWebSocketConnection current = websocket;
            if (current != null) current.sendJson(message);
        } catch (Throwable throwable) {
            Log.w(TAG, "Outline send failed", throwable);
        }
    }

    private JSONObject registerMessage() throws Exception {
        JSONObject json = identity.deviceMeta(deviceName, stateProvider == null ? "idle" : stateProvider.currentState(), "");
        json.put("type", "device.register");
        json.put("transport", "control");
        json.put("relayUrl", normalizeRelayUrl(relayUrl));
        return json;
    }

    private String normalizeRelayUrl(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty()) return "";
        if (!raw.contains("://")) return "https://" + raw;
        return raw;
    }

    private String controlWsUrl(String value) throws Exception {
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
        if (path == null || path.isEmpty() || "/".equals(path)) path = "/ws/control";
        else if (!path.endsWith("/ws/control")) path = path.replaceAll("/+$", "") + "/ws/control";
        return scheme + "://" + authority + path;
    }

    private void notifyStatus(String status) {
        Log.i(TAG, status);
        if (callback != null) callback.onControlStatus(status);
    }

    private String describeFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof UnknownHostException) {
                String host = current.getMessage();
                return "DNS failed" + (host == null || host.trim().isEmpty() ? "" : ": " + host);
            }
            current = current.getCause();
        }
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty() ? throwable.getClass().getSimpleName() : message;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeSocketOnly() {
        controlReady.set(false);
        MiraWebSocketConnection closing = websocket;
        websocket = null;
        if (closing != null) closing.close();
    }

    @Override
    public void close() {
        running.set(false);
        controlReady.set(false);
        outboundExecutor.shutdownNow();
        closeSocketOnly();
    }
}
