package com.vwww.mira;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
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
            deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) == null ? identity.defaultDeviceName() : intent.getStringExtra(EXTRA_DEVICE_NAME);
            discoveryPort = intent.getIntExtra(EXTRA_DISCOVERY_PORT, discoveryPort);
            relayUrl = intent.getStringExtra(EXTRA_RELAY_URL) == null ? "" : intent.getStringExtra(EXTRA_RELAY_URL).trim();
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
                    publishStatus(status);
                }
            }
        );
        controlClient.start();
        Log.i(TAG, "Control client starting relayUrl=" + relayUrl);
    }

    private void stopDiscovery() {
        running.set(false);
        lifecycleGeneration.incrementAndGet();
        closeRelay();
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
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "Control message failed", throwable);
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
            () -> onRelayClosed(sessionId)
        );
        relayClient.start();
        state = "active";
        requestControlOutline();
        Log.i(TAG, "Relay session opening sessionId=" + sessionId);
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
            if ("content-length".equals(key)) contentLength = Integer.parseInt(line.substring(index + 1).trim());
        }
        byte[] body = readExactly(input, contentLength);
        String target = parts[1];
        int queryIndex = target.indexOf('?');
        String path = queryIndex >= 0 ? target.substring(0, queryIndex) : target;
        return new HttpRequest(parts[0], path, body);
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
