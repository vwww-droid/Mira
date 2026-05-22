package com.vwww.mira;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Enumeration;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class MiraDiscoveryService extends Service {
    public static final String ACTION_START = "com.vwww.mira.discovery.START";
    public static final String ACTION_STOP = "com.vwww.mira.discovery.STOP";
    public static final String ACTION_STATUS = "com.vwww.mira.discovery.STATUS";
    public static final String EXTRA_DEVICE_NAME = "device_name";
    public static final String EXTRA_DISCOVERY_PORT = "discovery_port";
    public static final String EXTRA_RELAY_URL = "relay_url";
    public static final String EXTRA_STATUS = "status";

    private static final String TAG = "MiraDiscovery";

    private static volatile MiraDiscoveryService activeService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger lifecycleGeneration = new AtomicInteger(0);
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private MiraIdentity identity;
    private MiraBootstrap bootstrap;
    private DatagramSocket udpSocket;
    private ServerSocket wakeServer;
    private WifiManager.MulticastLock multicastLock;
    private Thread udpThread;
    private Thread wakeThread;
    private String deviceName = "Mira Device";
    private String relayUrl = "";
    private int discoveryPort = 8766;
    private volatile String state = "idle";
    private volatile MiraRelayClient relayClient;
    private volatile MiraControlClient controlClient;
    private volatile MiraSelfScreenStreamer screenStreamer;
    private volatile MiraLocalCommandServer commandServer;
    private volatile MiraTerminalServer terminalServer;

    @Override
    public void onCreate() {
        super.onCreate();
        activeService = this;
        identity = new MiraIdentity(this);
        bootstrap = new MiraBootstrap(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopDiscovery();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null) {
            String nextDeviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) == null ? identity.defaultDeviceName() : intent.getStringExtra(EXTRA_DEVICE_NAME);
            int nextDiscoveryPort = intent.getIntExtra(EXTRA_DISCOVERY_PORT, discoveryPort);
            String nextRelayUrl = intent.getStringExtra(EXTRA_RELAY_URL) == null ? "" : intent.getStringExtra(EXTRA_RELAY_URL).trim();
            if (running.get() && sameStartConfig(nextDeviceName, nextDiscoveryPort, nextRelayUrl)) {
                ensureScreenStreamer();
                publishStatus(nextRelayUrl.isEmpty() ? state : "control ready");
                Log.i(TAG, "Ignoring duplicate start for current relayUrl=" + relayUrl);
                return START_NOT_STICKY;
            }
            deviceName = nextDeviceName;
            discoveryPort = nextDiscoveryPort;
            relayUrl = nextRelayUrl;
        }
        if (running.get()) stopDiscovery();
        startDiscovery();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopDiscovery();
        executor.shutdownNow();
        if (activeService == this) activeService = null;
        super.onDestroy();
    }

    private void startDiscovery() {
        if (!running.compareAndSet(false, true)) return;
        int generation = lifecycleGeneration.incrementAndGet();
        state = "idle";
        try {
            bootstrap.installIfNeeded();
            startCommandServer();
            startTerminalServer();
            if (!relayUrl.isEmpty()) {
                startControlClient();
                return;
            }
            acquireMulticastLock();

            wakeServer = new ServerSocket();
            wakeServer.setReuseAddress(true);
            wakeServer.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0), 16);

            udpSocket = new DatagramSocket(null);
            udpSocket.setReuseAddress(true);
            udpSocket.setBroadcast(true);
            udpSocket.bind(new InetSocketAddress(discoveryPort));
        } catch (IOException e) {
            running.set(false);
            lifecycleGeneration.incrementAndGet();
            closeCommandServer();
            closeTerminalServer();
            releaseMulticastLock();
            publishStatus("startup failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
        Log.i(TAG, "Discovery started udp=" + discoveryPort + " wake=" + wakeServer.getLocalPort() + " ip=" + localIPv4());
        DatagramSocket currentUdpSocket = udpSocket;
        ServerSocket currentWakeServer = wakeServer;
        udpThread = new Thread(() -> udpLoop(generation, currentUdpSocket, currentWakeServer), "MiraDiscoveryUdp");
        wakeThread = new Thread(() -> wakeLoop(generation, currentWakeServer), "MiraDiscoveryWake");
        udpThread.start();
        wakeThread.start();
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
                    if ("control ready".equals(status)) ensureScreenStreamer();
                    publishStatus(status);
                }
            }
        );
        controlClient.start();
        startScreenStreamer();
        Log.i(TAG, "Control client starting relayUrl=" + relayUrl);
    }

    private synchronized void startScreenStreamer() {
        closeScreenStreamer();
        if (relayUrl == null || relayUrl.trim().isEmpty()) return;
        MiraSelfScreenStreamer streamer = new MiraSelfScreenStreamer(this, identity, deviceName, relayUrl);
        screenStreamer = streamer;
        streamer.start();
    }

    private synchronized void ensureScreenStreamer() {
        if (relayUrl == null || relayUrl.trim().isEmpty()) return;
        if (screenStreamer != null && screenStreamer.isAlive()) return;
        closeScreenStreamer();
        MiraSelfScreenStreamer streamer = new MiraSelfScreenStreamer(this, identity, deviceName, relayUrl);
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
        MiraLocalCommandServer server = new MiraLocalCommandServer(this);
        server.start();
        commandServer = server;
    }

    private synchronized void startTerminalServer() throws IOException {
        if (terminalServer != null) return;
        MiraTerminalServer server = new MiraTerminalServer(this, bootstrap, 0);
        server.start();
        terminalServer = server;
        writeTerminalTokenFile(server.getToken());
        Log.i(TAG, "Mira Web Terminal listening on http://127.0.0.1:" + server.getPort() + "/?token=<redacted>");
    }

    private void writeTerminalTokenFile(String token) throws IOException {
        File runDir = MiraLocalCommandServer.runDir(this);
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

    private void stopDiscovery() {
        running.set(false);
        lifecycleGeneration.incrementAndGet();
        closeScreenStreamer();
        closeRelay();
        closeCommandServer();
        closeTerminalServer();
        if (controlClient != null) {
            controlClient.close();
            controlClient = null;
        }
        closeQuietly(udpSocket);
        closeQuietly(wakeServer);
        releaseMulticastLock();
        udpSocket = null;
        wakeServer = null;
        publishStatus("disconnected");
    }

    public static void requestOutlineUpload() {
        MiraDiscoveryService service = activeService;
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

    private void udpLoop(int generation, DatagramSocket socket, ServerSocket server) {
        byte[] buffer = new byte[65535];
        while (running.get() && lifecycleGeneration.get() == generation) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String text = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                JSONObject request = new JSONObject(text);
                if (!"mira.discover".equals(request.optString("type"))) continue;
                Log.i(TAG, "Discovery request from " + packet.getAddress().getHostAddress() + ":" + packet.getPort());
                String wakeUrl = "http://" + localIPv4() + ":" + server.getLocalPort() + "/session/open";
                JSONObject response = identity.deviceMeta(deviceName, state, wakeUrl);
                byte[] payload = response.toString().getBytes(StandardCharsets.UTF_8);
                DatagramPacket reply = new DatagramPacket(payload, payload.length, packet.getAddress(), packet.getPort());
                socket.send(reply);
                Log.i(TAG, "Discovery response sent to " + packet.getAddress().getHostAddress() + ":" + packet.getPort());
            } catch (Throwable throwable) {
                if (running.get() && lifecycleGeneration.get() == generation) Log.w(TAG, "Discovery loop error", throwable);
            }
        }
    }

    private void wakeLoop(int generation, ServerSocket server) {
        while (running.get() && lifecycleGeneration.get() == generation) {
            try {
                Socket socket = server.accept();
                executor.execute(() -> handleWakeClient(socket));
            } catch (IOException e) {
                if (running.get() && lifecycleGeneration.get() == generation) Log.w(TAG, "Wake loop error", e);
            }
        }
    }

    private void handleWakeClient(Socket socket) {
        try (Socket client = socket) {
            client.setTcpNoDelay(true);
            InputStream input = client.getInputStream();
            OutputStream output = client.getOutputStream();
            HttpRequest request = readHttpRequest(input);
            if (request == null) return;
            if (!"POST".equals(request.method) || !"/session/open".equals(request.path)) {
                writeJson(output, "404 Not Found", new JSONObject().put("error", "not found"));
                return;
            }
            JSONObject body = new JSONObject(new String(request.body, StandardCharsets.UTF_8));
            if (!identity.getInstallId().equals(body.optString("installId"))) {
                writeJson(output, "404 Not Found", new JSONObject().put("error", "wrong installId"));
                return;
            }
            if (!openRelaySession(body)) {
                writeJson(output, "409 Conflict", new JSONObject().put("error", "session already active"));
                return;
            }
            writeJson(output, "200 OK", new JSONObject().put("ok", true).put("state", state));
        } catch (Throwable throwable) {
            Log.w(TAG, "Wake request failed", throwable);
        }
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
                handleDeviceCommand(body);
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "Control message failed", throwable);
        }
    }

    private void handleDeviceCommand(JSONObject body) {
        if (!identity.getInstallId().equals(body.optString("installId"))) {
            Log.w(TAG, "Ignoring device.command for wrong installId");
            return;
        }
        String command = body.optString("command", "").trim();
        String requestId = body.optString("requestId", "").trim();
        if (command.isEmpty()) {
            sendDeviceCommandResult(body, "mira", false, "", "missing command");
            return;
        }
        if (!"mira-logcat".equals(command)) {
            sendDeviceCommandResult(
                body,
                command,
                false,
                "",
                "unsupported command: " + command
            );
            return;
        }
        if (requestId.isEmpty()) {
            Log.w(TAG, "Ignoring device.command for missing requestId");
            return;
        }
        Log.i(TAG, "Device command scheduled command=" + safeLogValue(command) + " requestId=" + safeLogValue(requestId));
        executor.execute(() -> runDeviceCommand(body, command));
    }

    private void runDeviceCommand(JSONObject body, String command) {
        String requestId = body.optString("requestId", "").trim();
        try {
            List<String> args = parseCommandArguments(body);
            long startMs = SystemClock.elapsedRealtime();
            MiraCommandResult result = MiraCommandRouter.dispatch(this, command, args);
            long elapsedMs = SystemClock.elapsedRealtime() - startMs;
            if (result == null) {
                sendDeviceCommandResult(body, command, false, "", "command execution failed: empty result");
                return;
            }
            Log.i(TAG, "Device command finished requestId=" + safeLogValue(requestId) + " command=" + safeLogValue(command) + " exit=" + result.exitCode + " elapsedMs=" + elapsedMs);
            JSONObject response = new JSONObject();
            response.put("type", "device.command.result");
            response.put("protocol", 1);
            response.put("installId", identity.getInstallId());
            response.put("requestId", requestId);
            response.put("command", command);
            response.put("ok", result.exitCode == 0);
            response.put("exitCode", result.exitCode);
            response.put("stdout", result.stdout);
            response.put("stderr", result.stderr == null ? "" : result.stderr);
            if (result.exitCode != 0 && (result.stderr == null || result.stderr.isEmpty())) {
                response.put("error", "command failed with exit code " + result.exitCode);
            }
            if (result.exitCode == 0 && result.stderr != null && result.stderr.length() > 0) {
                response.put("warning", result.stderr);
            }
            int stdoutBytes = result.stdout == null ? 0 : result.stdout.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            int stderrBytes = result.stderr == null ? 0 : result.stderr.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            Log.i(TAG, "Sending device command result requestId=" + safeLogValue(requestId) + " command=" + safeLogValue(command) + " stdoutBytes=" + stdoutBytes + " stderrBytes=" + stderrBytes);
            MiraControlClient client = controlClient;
            if (client != null) client.sendJsonDirect(response);
        } catch (Throwable throwable) {
            Log.w(TAG, "Device command failed requestId=" + safeLogValue(requestId) + " command=" + safeLogValue(command), throwable);
            sendDeviceCommandResult(
                body,
                command,
                false,
                "",
                "command execution failed: " + throwable.getMessage()
            );
        }
    }

    private List<String> parseCommandArguments(JSONObject body) throws JSONException {
        JSONArray rawArgs = body.optJSONArray("arguments");
        if (rawArgs == null) return java.util.Collections.emptyList();
        List<String> args = new ArrayList<>();
        for (int i = 0; i < rawArgs.length(); i++) {
            String arg = rawArgs.optString(i, null);
            if (arg == null) {
                throw new JSONException("arguments[" + i + "] is not string");
            }
            args.add(arg);
        }
        return args;
    }

    private void sendDeviceCommandResult(JSONObject request, String command, boolean ok, String stdout, String error) {
        try {
            String requestId = request.optString("requestId", "").trim();
            if (requestId.isEmpty()) {
                Log.w(TAG, "Cannot send command result without requestId");
                return;
            }
            JSONObject response = new JSONObject();
            response.put("type", "device.command.result");
            response.put("protocol", 1);
            response.put("installId", identity.getInstallId());
            response.put("requestId", requestId);
            response.put("command", command);
            response.put("ok", ok);
            response.put("exitCode", ok ? 0 : 1);
            response.put("stdout", stdout);
            response.put("stderr", error == null ? "" : error);
            if (!ok && error != null) response.put("error", error);
            Log.i(TAG, "Sending device command error result requestId=" + safeLogValue(requestId) + " command=" + safeLogValue(command) + " error=" + safeLogValue(error));
            MiraControlClient client = controlClient;
            if (client != null) client.sendJsonDirect(response);
        } catch (Throwable throwable) {
            Log.w(TAG, "Unable to send device command result", throwable);
        }
    }

    private void handleScreenInput(JSONObject body) {
        if (!identity.getInstallId().equals(body.optString("installId"))) {
            Log.w(TAG, "Ignoring screen.input for wrong installId");
            return;
        }
        String kind = body.optString("kind", "");
        MiraSelfScreenCapture.InputResult result;
        if ("tap".equals(kind)) {
            double x = body.optDouble("x", Double.NaN);
            double y = body.optDouble("y", Double.NaN);
            if (Double.isNaN(x) || Double.isInfinite(x) || Double.isNaN(y) || Double.isInfinite(y)) {
                result = MiraSelfScreenCapture.InputResult.error("invalid tap coordinates");
            } else {
                float frameX = clampTapCoordinate(x);
                float frameY = clampTapCoordinate(y);
                boolean accepted = MiraSelfScreenCapture.getInstance().dispatchTapFromFrame(frameX, frameY);
                result = accepted ? MiraSelfScreenCapture.InputResult.ok("tap dispatched") : MiraSelfScreenCapture.InputResult.error("tap not handled");
                Log.i(TAG, "screen tap accepted=" + accepted + " x=" + frameX + " y=" + frameY);
            }
        } else if ("text".equals(kind)) {
            result = MiraSelfScreenCapture.getInstance().dispatchTextInput(body.optString("text", ""));
        } else if ("paste".equals(kind)) {
            result = MiraSelfScreenCapture.getInstance().dispatchPaste(body.optString("text", ""));
        } else if ("key".equals(kind)) {
            result = MiraSelfScreenCapture.getInstance().dispatchKeyInput(body.optString("key", ""));
        } else if ("copy".equals(kind)) {
            result = MiraSelfScreenCapture.getInstance().copyFocusedText();
        } else if ("selectall".equals(kind)) {
            result = MiraSelfScreenCapture.getInstance().selectAllFocusedText();
        } else if ("clear".equals(kind)) {
            result = MiraSelfScreenCapture.getInstance().clearFocusedText();
        } else {
            result = MiraSelfScreenCapture.InputResult.error("unsupported screen input kind=" + kind);
        }
        sendScreenInputResult(body, kind, result);
    }

    private void sendScreenInputResult(JSONObject request, String kind, MiraSelfScreenCapture.InputResult result) {
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
        if (relayClient != null) return false;
        String sessionId = body.optString("sessionId");
        state = "opening";
        relayClient = new MiraRelayClient(
            this,
            bootstrap,
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

    private void acquireMulticastLock() {
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) return;
            multicastLock = wifiManager.createMulticastLock("mira-discovery");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        } catch (Throwable throwable) {
            Log.w(TAG, "Unable to acquire multicast lock", throwable);
        }
    }

    private void releaseMulticastLock() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        } catch (Throwable throwable) {
            Log.w(TAG, "Unable to release multicast lock", throwable);
        } finally {
            multicastLock = null;
        }
    }

    private HttpRequest readHttpRequest(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int state = 0;
        while (true) {
            int value = input.read();
            if (value == -1) return null;
            buffer.write(value);
            if (state == 0 && value == '\r') state = 1;
            else if (state == 1 && value == '\n') state = 2;
            else if (state == 2 && value == '\r') state = 3;
            else if (state == 3 && value == '\n') break;
            else state = 0;
            if (buffer.size() > 64 * 1024) throw new IOException("HTTP header too large");
        }
        String raw = buffer.toString("ISO-8859-1");
        String[] lines = raw.split("\r\n");
        String[] parts = lines[0].split(" ", 3);
        int contentLength = 0;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int index = line.indexOf(':');
            if (index <= 0) continue;
            String key = line.substring(0, index).trim().toLowerCase(Locale.ROOT);
            if ("content-length".equals(key)) contentLength = parseContentLength(line.substring(index + 1).trim());
        }
        byte[] body = readExactly(input, contentLength);
        String target = parts[1];
        int queryIndex = target.indexOf('?');
        String path = queryIndex >= 0 ? target.substring(0, queryIndex) : target;
        return new HttpRequest(parts[0], path, body);
    }

    private static float clampTapCoordinate(double value) {
        double clamped = Math.max(0d, Math.min(value, 100000d));
        return (float) clamped;
    }

    private static int parseContentLength(String value) throws IOException {
        try {
            int length = Integer.parseInt(value);
            if (length < 0 || length > 1024 * 1024) throw new IOException("Invalid Content-Length: " + value);
            return length;
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid Content-Length: " + value, exception);
        }
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

    private byte[] readExactly(InputStream input, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(data, offset, length - offset);
            if (read == -1) throw new IOException("Unexpected EOF");
            offset += read;
        }
        return data;
    }

    private void writeJson(OutputStream output, String status, JSONObject body) throws IOException {
        byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + status + "\r\n" +
            "Content-Length: " + data.length + "\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.UTF_8));
        output.write(data);
        output.flush();
    }

    private String localIPv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface networkInterface : Collections.list(interfaces)) {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;
                for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) return address.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    private void closeQuietly(Object closeable) {
        try {
            if (closeable instanceof DatagramSocket) ((DatagramSocket) closeable).close();
            if (closeable instanceof ServerSocket) ((ServerSocket) closeable).close();
        } catch (Throwable ignored) {
        }
    }

    private static final class HttpRequest {
        final String method;
        final String path;
        final byte[] body;

        HttpRequest(String method, String path, byte[] body) {
            this.method = method;
            this.path = path;
            this.body = body;
        }
    }
}
