package com.vwww.mira;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.termux.terminal.MiraPtyProcess;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MiraRelayClient implements Closeable {
    private static final String TAG = "MiraRelayClient";

    private final Context context;
    private final MiraBootstrap bootstrap;
    private final MiraIdentity identity;
    private final String serverWs;
    private final String sessionId;
    private final int initialColumns;
    private final int initialRows;
    private final Runnable onClose;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object writeLock = new Object();

    private Socket socket;
    private InputStream input;
    private OutputStream output;
    private MiraPtyProcess pty;
    private MiraToolbox toolbox;
    private Thread workerThread;
    private Thread ptyReaderThread;

    public MiraRelayClient(
        Context context,
        MiraBootstrap bootstrap,
        MiraIdentity identity,
        String serverWs,
        String sessionId,
        int initialColumns,
        int initialRows,
        Runnable onClose
    ) {
        this.context = context.getApplicationContext();
        this.bootstrap = bootstrap;
        this.identity = identity;
        this.serverWs = serverWs;
        this.sessionId = sessionId;
        this.initialColumns = initialColumns <= 0 ? 80 : initialColumns;
        this.initialRows = initialRows <= 0 ? 24 : initialRows;
        this.onClose = onClose;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        workerThread = new Thread(this::runRelay, "MiraRelayClient");
        workerThread.start();
    }

    private void runRelay() {
        try {
            bootstrap.installIfNeeded();
            toolbox = MiraToolbox.prepare(context, sessionId);
            pty = MiraPtyFactory.create(context, bootstrap, initialRows, initialColumns, toolbox);
            Log.i(TAG, "PTY started pid=" + pty.getPid() + " cols=" + initialColumns + " rows=" + initialRows);
            connectWebSocket();
            sendJson(attachMessage());
            Log.i(TAG, "Device attached sessionId=" + sessionId + " installId=" + identity.getInstallId());
            ptyReaderThread = new Thread(this::pumpPtyToServer, "MiraRelayPtyReader-" + pty.getPid());
            ptyReaderThread.start();
            readServerLoop();
        } catch (Throwable throwable) {
            Log.w(TAG, "Relay failed", throwable);
        } finally {
            close();
            if (onClose != null) onClose.run();
        }
    }

    private JSONObject attachMessage() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("type", "device.attach");
        json.put("protocol", 1);
        json.put("installId", identity.getInstallId());
        json.put("sessionId", sessionId);
        return json;
    }

    private void connectWebSocket() throws Exception {
        URI uri = new URI(serverWs);
        if (!"ws".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("Only ws:// relay URLs are supported in MVP");
        }
        int port = uri.getPort() > 0 ? uri.getPort() : 80;
        String host = uri.getHost();
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
        Log.i(TAG, "Connecting websocket " + serverWs);
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        socket.setSoTimeout(15000);
        socket.setTcpNoDelay(true);
        input = socket.getInputStream();
        output = socket.getOutputStream();
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        String key = Base64.encodeToString(nonce, Base64.NO_WRAP);
        String request = "GET " + path + " HTTP/1.1\r\n" +
            "Host: " + host + ":" + port + "\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: " + key + "\r\n" +
            "Sec-WebSocket-Version: 13\r\n\r\n";
        output.write(request.getBytes(StandardCharsets.US_ASCII));
        output.flush();
        String response = readHttpHeader(input);
        Log.i(TAG, "WebSocket handshake " + response.split("\r\n", 2)[0]);
        if (!response.toLowerCase(Locale.ROOT).contains("101 switching protocols")) {
            throw new IOException("WebSocket handshake failed: " + response.split("\r\n", 2)[0]);
        }
        socket.setSoTimeout(0);
    }

    private void readServerLoop() throws Exception {
        while (running.get()) {
            WebSocketFrame frame = readFrame(input);
            if (frame == null) break;
            if (frame.opcode == 0x8) break;
            if (frame.opcode == 0x9) {
                sendFrame(frame.payload, 0xA);
                continue;
            }
            if (frame.opcode != 0x1) continue;
            handleServerMessage(new String(frame.payload, StandardCharsets.UTF_8));
        }
    }

    private void handleServerMessage(String text) throws Exception {
        JSONObject json = new JSONObject(text);
        String type = json.optString("type", "");
        if ("terminal.input".equals(type)) {
            byte[] data = Base64.decode(json.optString("dataBase64", ""), Base64.DEFAULT);
            if (pty != null) pty.write(data);
        } else if ("terminal.resize".equals(type)) {
            if (pty != null) pty.resize(json.optInt("cols", 0), json.optInt("rows", 0));
        } else if ("session.close".equals(type)) {
            running.set(false);
        } else if ("error".equals(type)) {
            Log.w(TAG, "Relay server error: " + json.optString("error"));
        }
    }

    private void pumpPtyToServer() {
        byte[] buffer = new byte[8192];
        try {
            while (running.get() && pty != null) {
                int read = pty.read(buffer);
                if (read == -1) break;
                byte[] payload = new byte[read];
                System.arraycopy(buffer, 0, payload, 0, read);
                JSONObject json = new JSONObject();
                json.put("type", "terminal.output");
                json.put("sessionId", sessionId);
                json.put("dataBase64", Base64.encodeToString(payload, Base64.NO_WRAP));
                sendJson(json);
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                JSONObject json = new JSONObject();
                json.put("type", "session.close");
                json.put("sessionId", sessionId);
                sendJson(json);
            } catch (Throwable ignored) {
            }
        }
    }

    private void sendJson(JSONObject json) throws IOException {
        sendFrame(json.toString().getBytes(StandardCharsets.UTF_8), 0x1);
    }

    private void sendFrame(byte[] payload, int opcode) throws IOException {
        synchronized (writeLock) {
            if (output == null) return;
            output.write(0x80 | opcode);
            int length = payload.length;
            byte[] mask = new byte[4];
            new SecureRandom().nextBytes(mask);
            if (length < 126) {
                output.write(0x80 | length);
            } else if (length <= 0xFFFF) {
                output.write(0x80 | 126);
                output.write((length >> 8) & 0xFF);
                output.write(length & 0xFF);
            } else {
                output.write(0x80 | 127);
                long longLength = length;
                for (int i = 7; i >= 0; i--) output.write((int) ((longLength >> (8 * i)) & 0xFF));
            }
            output.write(mask);
            for (int i = 0; i < payload.length; i++) output.write(payload[i] ^ mask[i % 4]);
            output.flush();
        }
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

    private String readHttpHeader(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int state = 0;
        while (true) {
            int value = input.read();
            if (value == -1) throw new IOException("Unexpected EOF");
            buffer.write(value);
            if (state == 0 && value == '\r') state = 1;
            else if (state == 1 && value == '\n') state = 2;
            else if (state == 2 && value == '\r') state = 3;
            else if (state == 3 && value == '\n') break;
            else state = 0;
            if (buffer.size() > 64 * 1024) throw new IOException("HTTP header too large");
        }
        return buffer.toString("ISO-8859-1");
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

    @Override
    public void close() {
        running.set(false);
        if (pty != null) {
            pty.close();
            pty = null;
        }
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        socket = null;
        if (toolbox != null) {
            toolbox.close();
            toolbox = null;
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
