package com.vwww.mira.relay;

import com.vwww.mira.device.DeviceIdentity;
import com.vwww.mira.device.DeviceMetrics;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.Closeable;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DeviceControlClient implements Closeable {
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
    private static final long METRICS_PERIOD_SECONDS = 1;

    private final Context context;
    private final DeviceIdentity identity;
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

    private volatile WebSocketConnection websocket;
    private Thread workerThread;

    public DeviceControlClient(
        Context context,
        DeviceIdentity identity,
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
        outboundExecutor.scheduleWithFixedDelay(this::sendMetricsIfReady, METRICS_PERIOD_SECONDS, METRICS_PERIOD_SECONDS, TimeUnit.SECONDS);
        workerThread = new Thread(this::runLoop, "MiraControlClient");
        workerThread.start();
    }

    private void runLoop() {
        while (running.get()) {
            try {
                String controlWs = RelayEndpoint.controlWebSocket(relayUrl);
                WebSocketConnection connected = WebSocketConnection.connect(controlWs);
                if (!running.get()) {
                    connected.close();
                    break;
                }
                websocket = connected;
                controlReady.set(false);
                connected.sendJson(registerMessage());
                notifyStatus("control connected");
                readControlLoop();
                if (running.get()) notifyStatus("control disconnected: closed");
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
            WebSocketConnection current = websocket;
            if (current == null) break;
            WebSocketConnection.WebSocketFrame frame = current.readFrame();
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
                sendOutlineIfReady("control.ready");
                sendMetricsIfReady();
                continue;
            }
            if (callback != null) callback.onControlMessage(message);
        }
    }

    public void sendJson(JSONObject json) {
        if (json == null || outboundExecutor.isShutdown()) return;
        try {
            outboundExecutor.execute(() -> sendJsonDirect(json, "queued"));
        } catch (RejectedExecutionException ignored) {
        }
    }

    public void sendJsonDirect(JSONObject json) {
        sendJsonDirect(json, "direct");
    }

    private void sendJsonDirect(JSONObject json, String mode) {
        if (json == null) return;
        try {
            WebSocketConnection current = websocket;
            if (!running.get() || current == null) return;
            current.sendJson(json);
        } catch (Throwable throwable) {
            Log.w(TAG, "Control send failed mode=" + safeLogValue(mode) + " type=" + safeLogValue(json.optString("type", "")), throwable);
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
        sendOutlineIfReady("timer");
    }

    private void sendOutlineIfReady(String trigger) {
        if (!running.get() || !controlReady.get() || outlineProvider == null) return;
        try {
            WebSocketConnection current = websocket;
            if (current == null) return;
            JSONObject outline = outlineProvider.currentOutline();
            int nodes = outline.optJSONArray("nodes") == null ? -1 : outline.optJSONArray("nodes").length();
            JSONObject message = new JSONObject();
            message.put("type", "device.outline");
            message.put("transport", "control");
            message.put("installId", identity.getInstallId());
            message.put("deviceName", deviceName);
            message.put("state", stateProvider == null ? "idle" : stateProvider.currentState());
            message.put("capturedAt", System.currentTimeMillis());
            message.put("outline", outline);
            int bytes = message.toString().getBytes(StandardCharsets.UTF_8).length;
            Log.i(TAG, "posting outline trigger=" + safeLogValue(trigger) + " available=" + outline.optBoolean("available", false) + " nodes=" + nodes + " bytes=" + bytes);
            current.sendJson(message);
            Log.i(TAG, "outline posted trigger=" + safeLogValue(trigger) + " nodes=" + nodes);
        } catch (Throwable throwable) {
            Log.w(TAG, "Outline send failed trigger=" + safeLogValue(trigger), throwable);
        }
    }

    private void sendMetricsIfReady() {
        if (!running.get() || !controlReady.get()) return;
        try {
            WebSocketConnection current = websocket;
            if (current == null) return;
            JSONObject message = new JSONObject();
            message.put("type", "device.metrics");
            message.put("protocol", 1);
            message.put("installId", identity.getInstallId());
            message.put("deviceName", deviceName);
            message.put("state", stateProvider == null ? "idle" : stateProvider.currentState());
            message.put("metrics", DeviceMetrics.snapshot(context));
            current.sendJson(message);
        } catch (Throwable throwable) {
            Log.w(TAG, "Metrics send failed", throwable);
        }
    }

    private JSONObject registerMessage() throws Exception {
        JSONObject json = identity.deviceMeta(deviceName, stateProvider == null ? "idle" : stateProvider.currentState(), "");
        json.put("type", "device.register");
        json.put("transport", "control");
        json.put("relayUrl", RelayEndpoint.normalizeBaseUrl(relayUrl));
        return json;
    }

    private static String safeLogValue(String value) {
        if (value == null) return "";
        StringBuilder builder = new StringBuilder(Math.min(value.length(), 128));
        int limit = Math.min(value.length(), 128);
        for (int i = 0; i < limit; i++) {
            char ch = value.charAt(i);
            if (ch == '\r' || ch == '\n' || ch == '\t' || Character.isISOControl(ch)) builder.append('_');
            else builder.append(ch);
        }
        if (value.length() > limit) builder.append("...");
        return builder.toString();
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
        WebSocketConnection closing = websocket;
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
