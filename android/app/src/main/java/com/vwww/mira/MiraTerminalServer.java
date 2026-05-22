package com.vwww.mira;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MiraTerminalServer implements Closeable {
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final Context context;
    private final MiraBootstrap bootstrap;
    private final int requestedPort;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final String token = createToken();

    private ServerSocket serverSocket;
    private Thread acceptThread;

    public MiraTerminalServer(Context context, MiraBootstrap bootstrap, int requestedPort) {
        this.context = context.getApplicationContext();
        this.bootstrap = bootstrap;
        this.requestedPort = requestedPort;
    }

    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) return;
        serverSocket = new ServerSocket(requestedPort, 16, java.net.InetAddress.getByName("127.0.0.1"));
        acceptThread = new Thread(this::acceptLoop, "MiraTerminalServer");
        acceptThread.start();
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    public String getToken() {
        return token;
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                executor.execute(() -> handleClient(socket));
            } catch (IOException e) {
                if (running.get()) e.printStackTrace();
            }
        }
    }

    private void handleClient(Socket socket) {
        try (Socket client = socket) {
            client.setTcpNoDelay(true);
            InputStream input = client.getInputStream();
            OutputStream output = client.getOutputStream();
            HttpRequest request = readHttpRequest(input);
            if (request == null) return;

            if (!isAuthorized(request)) {
                writeHttp(output, "403 Forbidden", "text/plain; charset=utf-8", "Forbidden\n".getBytes(StandardCharsets.UTF_8));
                return;
            }

            if ("/ws/terminal".equals(request.path) && isWebSocketUpgrade(request)) {
                handleTerminalWebSocket(input, output, request.headers);
                return;
            }

            if (!"GET".equals(request.method)) {
                writeHttp(output, "405 Method Not Allowed", "text/plain; charset=utf-8", "Method Not Allowed\n".getBytes(StandardCharsets.UTF_8));
                return;
            }

            serveAsset(output, request.path);
        } catch (Throwable throwable) {
            throwable.printStackTrace();
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
        if (lines.length == 0) return null;
        String[] parts = lines[0].split(" ", 3);
        if (parts.length < 2) return null;

        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int index = line.indexOf(':');
            if (index <= 0) continue;
            headers.put(line.substring(0, index).trim().toLowerCase(Locale.ROOT), line.substring(index + 1).trim());
        }

        String target = parts[1];
        String query = "";
        int queryIndex = target.indexOf('?');
        String path = target;
        if (queryIndex >= 0) {
            path = target.substring(0, queryIndex);
            query = target.substring(queryIndex + 1);
        }
        path = URLDecoder.decode(path, "UTF-8");
        return new HttpRequest(parts[0], path, query, headers);
    }

    private boolean isWebSocketUpgrade(HttpRequest request) {
        String connection = request.headers.getOrDefault("connection", "").toLowerCase(Locale.ROOT);
        String upgrade = request.headers.getOrDefault("upgrade", "").toLowerCase(Locale.ROOT);
        return connection.contains("upgrade") && "websocket".equals(upgrade);
    }

    private boolean isAuthorized(HttpRequest request) {
        if (!token.equals(queryParameter(request.query, "token"))) return false;
        return isAllowedOrigin(request.headers.get("origin"));
    }

    private boolean isAllowedOrigin(String origin) {
        if (origin == null || origin.isEmpty()) return true;
        try {
            URI uri = new URI(origin);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!"http".equalsIgnoreCase(scheme)) return false;
            return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
        } catch (URISyntaxException ignored) {
            return false;
        }
    }

    private String queryParameter(String query, String name) {
        if (query == null || query.isEmpty()) return "";
        String[] parts = query.split("&");
        for (String part : parts) {
            int index = part.indexOf('=');
            String key = index >= 0 ? part.substring(0, index) : part;
            if (!name.equals(key)) continue;
            String value = index >= 0 ? part.substring(index + 1) : "";
            try {
                return URLDecoder.decode(value, "UTF-8");
            } catch (IOException ignored) {
                return "";
            }
        }
        return "";
    }

    private void serveAsset(OutputStream output, String path) throws IOException {
        String assetPath = resolveAssetPath(path);
        AssetManager assets = context.getAssets();
        try (InputStream assetInput = assets.open(assetPath)) {
            writeHttp(output, "200 OK", contentType(assetPath), readAll(assetInput));
        } catch (IOException missing) {
            writeHttp(output, "404 Not Found", "text/plain; charset=utf-8", "Not Found\n".getBytes(StandardCharsets.UTF_8));
        }
    }

    private String resolveAssetPath(String path) throws IOException {
        if (path == null || path.isEmpty() || "/".equals(path)) return "web/index.html";
        if (path.contains("..")) throw new IOException("invalid path");
        if (path.startsWith("/")) path = path.substring(1);
        return "web/" + path;
    }

    private String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".json")) return "application/json; charset=utf-8";
        return "application/octet-stream";
    }

    private byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private void writeHttp(OutputStream output, String status, String contentType, byte[] body) throws IOException {
        String headers = "HTTP/1.1 " + status + "\r\n" +
            "Content-Length: " + body.length + "\r\n" +
            "Content-Type: " + contentType + "\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.UTF_8));
        output.write(body);
        output.flush();
    }

    private void handleTerminalWebSocket(InputStream input, OutputStream output, Map<String, String> headers) throws Exception {
        String key = headers.get("sec-websocket-key");
        if (key == null) throw new IOException("Missing Sec-WebSocket-Key");
        String accept = websocketAccept(key);
        output.write(("HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.flush();

        MiraPtySession pty = createPty();
        Object writeLock = new Object();
        Thread readerThread = new Thread(() -> pumpPtyToWebSocket(pty, output, writeLock), "MiraPtyReader-" + pty.getPid());
        Thread waiterThread = new Thread(() -> {
            pty.waitFor();
            pty.close();
        }, "MiraPtyWaiter-" + pty.getPid());
        readerThread.start();
        waiterThread.start();

        try {
            while (running.get()) {
                WebSocketFrame frame = readFrame(input);
                if (frame == null) break;
                if (frame.opcode == 0x8) {
                    sendFrame(output, new byte[0], 0x8, writeLock);
                    break;
                }
                if (frame.opcode == 0x9) {
                    sendFrame(output, frame.payload, 0xA, writeLock);
                    continue;
                }
                if (frame.opcode == 0x2) {
                    pty.write(frame.payload);
                    continue;
                }
                if (frame.opcode != 0x1) continue;
                handleTerminalMessage(pty, new String(frame.payload, StandardCharsets.UTF_8));
            }
        } finally {
            pty.close();
        }
    }

    private MiraPtySession createPty() {
        return MiraPtyFactory.create(context, bootstrap, 24, 80);
    }

    private void handleTerminalMessage(MiraPtySession pty, String message) throws IOException {
        try {
            JSONObject json = new JSONObject(message);
            String type = json.optString("type", "");
            if ("input".equals(type)) {
                pty.write(json.optString("data", "").getBytes(StandardCharsets.UTF_8));
            } else if ("resize".equals(type)) {
                pty.resize(json.optInt("cols", 0), json.optInt("rows", 0), json.optInt("cellWidth", 0), json.optInt("cellHeight", 0));
            }
        } catch (JSONException ignored) {
        }
    }

    private void pumpPtyToWebSocket(MiraPtySession pty, OutputStream output, Object writeLock) {
        byte[] buffer = new byte[8192];
        try {
            while (running.get()) {
                int read = pty.read(buffer);
                if (read == -1) break;
                byte[] payload = new byte[read];
                System.arraycopy(buffer, 0, payload, 0, read);
                sendFrame(output, payload, 0x2, writeLock);
            }
        } catch (Throwable ignored) {
        }
    }

    private String websocketAccept(String key) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA" + "-" + "1");
        byte[] sha1 = digest.digest((key + WS_GUID).getBytes(StandardCharsets.US_ASCII));
        return Base64.encodeToString(sha1, Base64.NO_WRAP);
    }

    private WebSocketFrame readFrame(InputStream input) throws IOException {
        int first = input.read();
        if (first == -1) return null;
        int second = input.read();
        if (second == -1) return null;
        int opcode = first & 0x0F;
        boolean masked = (second & 0x80) != 0;
        long length = second & 0x7F;
        if (length == 126) length = readUnsignedShort(input);
        else if (length == 127) length = readLong(input);
        if (length > 1024 * 1024) throw new IOException("WebSocket frame too large");
        byte[] mask = masked ? readExactly(input, 4) : null;
        byte[] payload = readExactly(input, (int) length);
        if (masked && mask != null) {
            for (int i = 0; i < payload.length; i++) payload[i] = (byte) (payload[i] ^ mask[i % 4]);
        }
        return new WebSocketFrame(opcode, payload);
    }

    private int readUnsignedShort(InputStream input) throws IOException {
        return (input.read() << 8) | input.read();
    }

    private long readLong(InputStream input) throws IOException {
        long value = 0;
        for (int i = 0; i < 8; i++) value = (value << 8) | (input.read() & 0xFFL);
        return value;
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

    private void sendFrame(OutputStream output, byte[] payload, int opcode, Object writeLock) throws IOException {
        synchronized (writeLock) {
            output.write(0x80 | opcode);
            int length = payload.length;
            if (length < 126) {
                output.write(length);
            } else if (length <= 0xFFFF) {
                output.write(126);
                output.write((length >> 8) & 0xFF);
                output.write(length & 0xFF);
            } else {
                output.write(127);
                long longLength = length;
                for (int i = 7; i >= 0; i--) output.write((int) ((longLength >> (8 * i)) & 0xFF));
            }
            output.write(payload);
            output.flush();
        }
    }

    private String createToken() {
        byte[] bytes = new byte[24];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.NO_WRAP | Base64.URL_SAFE);
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) return;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
        executor.shutdownNow();
    }

    private static final class HttpRequest {
        final String method;
        final String path;
        final String query;
        final Map<String, String> headers;

        HttpRequest(String method, String path, String query, Map<String, String> headers) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.headers = headers;
        }
    }

    private static final class WebSocketFrame {
        final int opcode;
        final byte[] payload;

        WebSocketFrame(int opcode, byte[] payload) {
            this.opcode = opcode;
            this.payload = payload;
        }
    }
}
