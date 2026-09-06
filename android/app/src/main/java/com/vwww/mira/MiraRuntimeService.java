package com.vwww.mira;

import com.vwww.mira.discovery.LanDiscoveryServer;
import com.vwww.mira.command.LocalCommandServer;
import com.vwww.mira.command.RemoteCommandHandler;
import com.vwww.mira.runtime.RuntimeInstaller;
import com.vwww.mira.screen.AppScreenCapture;
import com.vwww.mira.screen.AppScreenStreamer;
import com.vwww.mira.terminal.LocalTerminalServer;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class MiraRuntimeService extends Service {
    public static final String ACTION_START = "com.vwww.mira.discovery.START";
    public static final String ACTION_STOP = "com.vwww.mira.discovery.STOP";
    public static final String ACTION_STATUS = "com.vwww.mira.discovery.STATUS";
    public static final String EXTRA_DEVICE_NAME = "device_name";
    public static final String EXTRA_DISCOVERY_PORT = "discovery_port";
    public static final String EXTRA_RELAY_URL = "relay_url";
    public static final String EXTRA_STATUS = "status";

    private static final String TAG = "MiraDiscovery";

    private static volatile MiraRuntimeService activeService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger lifecycleGeneration = new AtomicInteger(0);
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private MiraIdentity identity;
    private RuntimeInstaller runtimeInstaller;
    private volatile LanDiscoveryServer discoveryServer;
    private String deviceName = "Mira Device";
    private String relayUrl = "";
    private int discoveryPort = 8766;
    private volatile String state = "idle";
    private volatile MiraRelayClient relayClient;
    private volatile MiraControlClient controlClient;
    private volatile AppScreenStreamer screenStreamer;
    private volatile LocalCommandServer commandServer;
    private RemoteCommandHandler remoteCommandHandler;
    private volatile LocalTerminalServer terminalServer;
    private volatile boolean controlReady;

    @Override
    public void onCreate() {
        super.onCreate();
        activeService = this;
        identity = new MiraIdentity(this);
        runtimeInstaller = new RuntimeInstaller(this);
        remoteCommandHandler = new RemoteCommandHandler(
            this,
            identity.getInstallId(),
            executor,
            response -> {
                MiraControlClient client = controlClient;
                if (client != null) client.sendJsonDirect(response);
            }
        );
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopRuntime();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null) {
            String nextDeviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) == null ? identity.defaultDeviceName() : intent.getStringExtra(EXTRA_DEVICE_NAME);
            int nextDiscoveryPort = intent.getIntExtra(EXTRA_DISCOVERY_PORT, discoveryPort);
            String nextRelayUrl = intent.getStringExtra(EXTRA_RELAY_URL) == null ? "" : intent.getStringExtra(EXTRA_RELAY_URL).trim();
            if (running.get() && sameStartConfig(nextDeviceName, nextDiscoveryPort, nextRelayUrl)) {
                publishStatus(nextRelayUrl.isEmpty() ? state : (controlReady ? "control ready" : "connecting relay"));
                Log.i(TAG, "Ignoring duplicate start for current relayUrl=" + relayUrl);
                return START_NOT_STICKY;
            }
            deviceName = nextDeviceName;
            discoveryPort = nextDiscoveryPort;
            relayUrl = nextRelayUrl;
        }
        if (running.get()) stopRuntime();
        startRuntime();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopRuntime();
        executor.shutdownNow();
        if (activeService == this) activeService = null;
        super.onDestroy();
    }

    private void startRuntime() {
        if (!running.compareAndSet(false, true)) return;
        int generation = lifecycleGeneration.incrementAndGet();
        state = "idle";
        controlReady = false;
        try {
            runtimeInstaller.installIfNeeded();
            startCommandServer();
            startTerminalServer();
            if (!relayUrl.isEmpty()) {
                startControlClient();
                return;
            }
            LanDiscoveryServer server = new LanDiscoveryServer(
                this,
                discoveryPort,
                identity.getInstallId(),
                new LanDiscoveryServer.Callback() {
                    @Override
                    public JSONObject deviceMetadata(String wakeUrl) throws JSONException {
                        return identity.deviceMeta(deviceName, state, wakeUrl);
                    }

                    @Override
                    public boolean openSession(JSONObject request) {
                        return openRelaySession(generation, request);
                    }
                }
            );
            server.start();
            discoveryServer = server;
        } catch (IOException | RuntimeException e) {
            running.set(false);
            lifecycleGeneration.incrementAndGet();
            closeCommandServer();
            closeTerminalServer();
            closeDiscoveryServer();
            publishStatus("startup failed: " + e.getMessage());
            if (e instanceof RuntimeException) throw (RuntimeException) e;
            throw new RuntimeException(e);
        }
    }

    private void startControlClient() {
        publishStatus("connecting relay");
        controlClient = new MiraControlClient(
            this,
            identity,
            deviceName,
            relayUrl,
            () -> state,
            MiraOutlineCollector.getInstance()::currentOutline,
            new MiraControlClient.Callback() {
                @Override
                public void onControlMessage(JSONObject message) {
                    handleControlMessage(message);
                }

                @Override
                public void onControlStatus(String status) {
                    Log.i(TAG, "Control status " + status);
                    if ("control ready".equals(status)) {
                        controlReady = true;
                        ensureScreenStreamer();
                    } else {
                        controlReady = false;
                        closeScreenStreamer();
                    }
                    publishStatus(status);
                }
            }
        );
        controlClient.start();
        Log.i(TAG, "Control client starting relayUrl=" + relayUrl);
    }

    private synchronized void startScreenStreamer() {
        closeScreenStreamer();
        if (relayUrl == null || relayUrl.trim().isEmpty()) return;
        if (!controlReady) {
            Log.i(TAG, "Screen streamer start skipped until control ready");
            return;
        }
        AppScreenStreamer streamer = new AppScreenStreamer(this, identity, deviceName, relayUrl);
        screenStreamer = streamer;
        streamer.start();
    }

    private synchronized void ensureScreenStreamer() {
        if (relayUrl == null || relayUrl.trim().isEmpty()) return;
        if (!controlReady) {
            Log.i(TAG, "Screen streamer ensure skipped until control ready");
            return;
        }
        if (screenStreamer != null && screenStreamer.isAlive()) return;
        closeScreenStreamer();
        AppScreenStreamer streamer = new AppScreenStreamer(this, identity, deviceName, relayUrl);
        screenStreamer = streamer;
        streamer.start();
        Log.i(TAG, "Screen streamer ensured relayUrl=" + relayUrl);
    }

    private boolean sameStartConfig(String nextDeviceName, int nextDiscoveryPort, String nextRelayUrl) {
        return discoveryPort == nextDiscoveryPort
            && String.valueOf(deviceName).equals(String.valueOf(nextDeviceName))
            && String.valueOf(relayUrl).equals(String.valueOf(nextRelayUrl));
    }

    private synchronized void startCommandServer() throws IOException {
        if (commandServer != null) return;
        LocalCommandServer server = new LocalCommandServer(this);
        server.start();
        commandServer = server;
    }

    private synchronized void startTerminalServer() throws IOException {
        if (terminalServer != null) return;
        LocalTerminalServer server = new LocalTerminalServer(this, runtimeInstaller, 0);
        server.start();
        terminalServer = server;
        writeTerminalTokenFile(server.getToken());
        Log.i(TAG, "Mira Web Terminal listening on http://127.0.0.1:" + server.getPort() + "/?token=<redacted>");
    }

    private void writeTerminalTokenFile(String token) throws IOException {
        File runDir = LocalCommandServer.runDir(this);
        if (!runDir.isDirectory() && !runDir.mkdirs() && !runDir.isDirectory()) {
            throw new IOException("Unable to create run dir: " + runDir.getAbsolutePath());
        }
        File tokenFile = new File(runDir, "mira-terminal-token");
        try (FileOutputStream output = new FileOutputStream(tokenFile, false)) {
            output.write((token == null ? "" : token).getBytes(StandardCharsets.UTF_8));
            output.write('\n');
        }
        if (!tokenFile.setReadable(true, true)) throw new IOException("Unable to make terminal token readable by owner");
        if (!tokenFile.setWritable(true, true)) throw new IOException("Unable to make terminal token writable by owner");
    }

    private void stopRuntime() {
        running.set(false);
        lifecycleGeneration.incrementAndGet();
        controlReady = false;
        closeScreenStreamer();
        closeRelay();
        closeCommandServer();
        closeTerminalServer();
        if (controlClient != null) {
            controlClient.close();
            controlClient = null;
        }
        closeDiscoveryServer();
        publishStatus("disconnected");
    }

    public static void requestOutlineUpload() {
        MiraRuntimeService service = activeService;
        if (service != null) service.requestControlOutline();
    }

    private void requestControlOutline() {
        MiraControlClient client = controlClient;
        if (client != null) client.sendOutline();
    }

    private void publishStatus(String status) {
        Intent intent = new Intent(ACTION_STATUS);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_STATUS, status);
        sendBroadcast(intent);
    }

    private void handleControlMessage(JSONObject body) {
        try {
            String type = body.optString("type", "");
            if ("session.open".equals(type)) {
                if (!identity.getInstallId().equals(body.optString("installId"))) {
                    Log.w(TAG, "Ignoring session.open for wrong installId");
                    return;
                }
                openRelaySession(body);
            } else if ("session.close".equals(type)) {
                closeRelay();
            } else if ("screen.input".equals(type)) {
                handleScreenInput(body);
            } else if ("device.command".equals(type)) {
                remoteCommandHandler.handle(body);
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "Control message failed", throwable);
        }
    }

    private void handleScreenInput(JSONObject body) {
        if (!identity.getInstallId().equals(body.optString("installId"))) {
            Log.w(TAG, "Ignoring screen.input for wrong installId");
            return;
        }
        String kind = body.optString("kind", "");
        AppScreenCapture.InputResult result;
        if ("tap".equals(kind)) {
            double x = body.optDouble("x", Double.NaN);
            double y = body.optDouble("y", Double.NaN);
            if (Double.isNaN(x) || Double.isInfinite(x) || Double.isNaN(y) || Double.isInfinite(y)) {
                result = AppScreenCapture.InputResult.error("invalid tap coordinates");
            } else {
                float frameX = clampTapCoordinate(x);
                float frameY = clampTapCoordinate(y);
                boolean accepted = AppScreenCapture.getInstance().dispatchTapFromFrame(frameX, frameY);
                result = accepted ? AppScreenCapture.InputResult.ok("tap dispatched") : AppScreenCapture.InputResult.error("tap not handled");
                Log.i(TAG, "screen tap accepted=" + accepted + " x=" + frameX + " y=" + frameY);
            }
        } else if ("text".equals(kind)) {
            result = AppScreenCapture.getInstance().dispatchTextInput(body.optString("text", ""));
        } else if ("paste".equals(kind)) {
            result = AppScreenCapture.getInstance().dispatchPaste(body.optString("text", ""));
        } else if ("key".equals(kind)) {
            result = AppScreenCapture.getInstance().dispatchKeyInput(body.optString("key", ""));
        } else if ("copy".equals(kind)) {
            result = AppScreenCapture.getInstance().copyFocusedText();
        } else if ("selectall".equals(kind)) {
            result = AppScreenCapture.getInstance().selectAllFocusedText();
        } else if ("clear".equals(kind)) {
            result = AppScreenCapture.getInstance().clearFocusedText();
        } else {
            result = AppScreenCapture.InputResult.error("unsupported screen input kind=" + kind);
        }
        sendScreenInputResult(body, kind, result);
    }

    private void sendScreenInputResult(JSONObject request, String kind, AppScreenCapture.InputResult result) {
        try {
            JSONObject response = new JSONObject();
            response.put("type", "screen.input.result");
            response.put("protocol", 1);
            response.put("installId", identity.getInstallId());
            response.put("requestId", request.optString("requestId", ""));
            response.put("clientId", request.optString("clientId", ""));
            response.put("kind", kind == null ? "" : kind);
            response.put("ok", result != null && result.ok);
            response.put("message", result == null ? "input failed" : result.message);
            if (result == null || !result.ok) response.put("error", result == null ? "input failed" : result.message);
            if (result != null && result.text != null && ("copy".equals(kind) || !result.text.isEmpty())) response.put("text", result.text);
            MiraControlClient client = controlClient;
            if (client != null) client.sendJson(response);
        } catch (Throwable throwable) {
            Log.w(TAG, "Unable to send screen input result", throwable);
        }
    }

    private synchronized boolean openRelaySession(JSONObject body) {
        return openRelaySession(lifecycleGeneration.get(), body);
    }

    private synchronized boolean openRelaySession(int generation, JSONObject body) {
        if (!running.get() || lifecycleGeneration.get() != generation) return false;
        if (relayClient != null) return false;
        String sessionId = body.optString("sessionId");
        state = "opening";
        relayClient = new MiraRelayClient(
            this,
            runtimeInstaller,
            identity,
            body.optString("serverWs"),
            sessionId,
            body.optInt("cols", 80),
            body.optInt("rows", 24),
            body.optInt("cellWidth", 0),
            body.optInt("cellHeight", 0),
            () -> onRelayClosed(sessionId)
        );
        relayClient.start();
        state = "active";
        requestControlOutline();
        Log.i(TAG, "Relay session opening sessionId=" + safeLogValue(sessionId));
        return true;
    }

    private synchronized void onRelayClosed(String sessionId) {
        if (relayClient != null && !relayClient.hasSession(sessionId)) {
            Log.i(TAG, "Ignoring stale relay close sessionId=" + sessionId);
            return;
        }
        relayClient = null;
        state = "idle";
        requestControlOutline();
        Log.i(TAG, "Relay session closed");
    }

    private synchronized void closeRelay() {
        if (relayClient != null) {
            relayClient.close();
            relayClient = null;
        }
        state = "idle";
        requestControlOutline();
    }

    private synchronized void closeCommandServer() {
        if (commandServer != null) {
            commandServer.close();
            commandServer = null;
        }
    }

    private synchronized void closeTerminalServer() {
        if (terminalServer != null) {
            terminalServer.close();
            terminalServer = null;
        }
    }

    private synchronized void closeScreenStreamer() {
        if (screenStreamer != null) {
            screenStreamer.close();
            screenStreamer = null;
        }
    }

    private void closeDiscoveryServer() {
        LanDiscoveryServer server;
        synchronized (this) {
            server = discoveryServer;
            discoveryServer = null;
        }
        if (server != null) server.close();
    }

    private static float clampTapCoordinate(double value) {
        double clamped = Math.max(0d, Math.min(value, 100000d));
        return (float) clamped;
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

}
