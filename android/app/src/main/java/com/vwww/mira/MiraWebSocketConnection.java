package com.vwww.mira;

import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public final class MiraWebSocketConnection implements Closeable {
    private static final String TAG = "MiraWebSocket";
    private static final int MAX_FRAME_SIZE = 1024 * 1024;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final Object writeLock = new Object();
    private volatile Socket socket;
    private volatile InputStream input;
    private volatile OutputStream output;

    private MiraWebSocketConnection(Socket socket, InputStream input, OutputStream output) {
        this.socket = socket;
        this.input = input;
        this.output = output;
    }

    public static MiraWebSocketConnection connect(String url) throws Exception {
        URI uri = new URI(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean tls;
        int defaultPort;
        if ("ws".equals(scheme)) {
            tls = false;
            defaultPort = 80;
        } else if ("wss".equals(scheme)) {
            tls = true;
            defaultPort = 443;
        } else {
            throw new IOException("Only ws:// and wss:// relay URLs are supported");
        }

        String host = uri.getHost();
        if (host == null || host.trim().isEmpty()) throw new IOException("WebSocket host is empty");
        int port = uri.getPort() > 0 ? uri.getPort() : defaultPort;
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();

        Log.i(TAG, "Connecting websocket host=" + host + " port=" + port + " tls=" + tls);
        Socket connected = openSocket(host, port, tls);
        connected.setSoTimeout(15000);
        connected.setTcpNoDelay(true);
        InputStream input = connected.getInputStream();
        OutputStream output = connected.getOutputStream();

        byte[] nonce = new byte[16];
        SECURE_RANDOM.nextBytes(nonce);
        String key = Base64.encodeToString(nonce, Base64.NO_WRAP);
        String hostHeader = port == defaultPort ? host : host + ":" + port;
        String request = "GET " + path + " HTTP/1.1\r\n" +
            "Host: " + hostHeader + "\r\n" +
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
        connected.setSoTimeout(0);
        return new MiraWebSocketConnection(connected, input, output);
    }

    private static Socket openSocket(String host, int port, boolean tls) throws IOException {
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), 5000);
        if (!tls) return raw;
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket sslSocket = (SSLSocket) factory.createSocket(raw, host, port, true);
        SSLParameters parameters = sslSocket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        sslSocket.setSSLParameters(parameters);
        sslSocket.startHandshake();
        return sslSocket;
    }

    public void sendJson(JSONObject json) throws IOException {
        sendFrame(json.toString().getBytes(StandardCharsets.UTF_8), 0x1);
    }

    public void sendPong(byte[] payload) throws IOException {
        sendFrame(payload, 0xA);
    }

    public void sendFrame(byte[] payload, int opcode) throws IOException {
        synchronized (writeLock) {
            OutputStream frameOutput = output;
            if (frameOutput == null) return;
            frameOutput.write(0x80 | opcode);
            int length = payload.length;
            byte[] mask = new byte[4];
            SECURE_RANDOM.nextBytes(mask);
            if (length < 126) {
                frameOutput.write(0x80 | length);
            } else if (length <= 0xFFFF) {
                frameOutput.write(0x80 | 126);
                frameOutput.write((length >> 8) & 0xFF);
                frameOutput.write(length & 0xFF);
            } else {
                frameOutput.write(0x80 | 127);
                long longLength = length;
                for (int i = 7; i >= 0; i--) frameOutput.write((int) ((longLength >> (8 * i)) & 0xFF));
            }
            frameOutput.write(mask);
            for (int i = 0; i < payload.length; i++) frameOutput.write(payload[i] ^ mask[i % 4]);
            frameOutput.flush();
        }
    }

    public WebSocketFrame readFrame() throws IOException {
        WebSocketFrame firstFrame = readRawFrame();
        if (firstFrame.isClose() || firstFrame.isPing() || firstFrame.fin) return firstFrame;
        if (!firstFrame.isText() && !firstFrame.isBinary()) throw new IOException("Unexpected fragmented websocket frame");

        int opcode = firstFrame.opcode;
        ByteArrayOutputStream fragments = new ByteArrayOutputStream();
        fragments.write(firstFrame.payload);
        while (true) {
            WebSocketFrame frame = readRawFrame();
            if (frame.isClose()) return frame;
            if (frame.isPing()) {
                sendPong(frame.payload);
                continue;
            }
            if (frame.opcode != 0x0) throw new IOException("Invalid websocket continuation");
            fragments.write(frame.payload);
            if (fragments.size() > MAX_FRAME_SIZE) throw new IOException("WebSocket message too large");
            if (frame.fin) return new WebSocketFrame(opcode, fragments.toByteArray(), true);
        }
    }

    private WebSocketFrame readRawFrame() throws IOException {
        InputStream frameInput = input;
        if (frameInput == null) throw new IOException("WebSocket closed");
        int first = readByte(frameInput);
        int second = readByte(frameInput);
        boolean fin = (first & 0x80) != 0;
        int opcode = first & 0x0F;
        boolean masked = (second & 0x80) != 0;
        long length = second & 0x7F;
        if (length == 126) length = readUnsignedShort(frameInput);
        else if (length == 127) length = readLong(frameInput);
        if (length > MAX_FRAME_SIZE) throw new IOException("WebSocket frame too large");
        byte[] mask = masked ? readExactly(frameInput, 4) : null;
        byte[] payload = readExactly(frameInput, (int) length);
        if (masked && mask != null) {
            for (int i = 0; i < payload.length; i++) payload[i] = (byte) (payload[i] ^ mask[i % 4]);
        }
        return new WebSocketFrame(opcode, payload, fin);
    }

    private static String readHttpHeader(InputStream input) throws IOException {
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

    private static int readByte(InputStream input) throws IOException {
        int value = input.read();
        if (value == -1) throw new IOException("Unexpected EOF");
        return value;
    }

    private static int readUnsignedShort(InputStream input) throws IOException {
        return (readByte(input) << 8) | readByte(input);
    }

    private static long readLong(InputStream input) throws IOException {
        long value = 0;
        for (int i = 0; i < 8; i++) value = (value << 8) | (readByte(input) & 0xFFL);
        return value;
    }

    private static byte[] readExactly(InputStream input, int length) throws IOException {
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
        Socket closing = socket;
        socket = null;
        input = null;
        output = null;
        if (closing == null) return;
        if (Looper.getMainLooper() == Looper.myLooper()) {
            Thread closer = new Thread(() -> closeQuietly(closing), "MiraWebSocketCloser");
            closer.start();
        } else {
            closeQuietly(closing);
        }
    }

    private static void closeQuietly(Socket closing) {
        try {
            closing.close();
        } catch (IOException ignored) {
        }
    }

    public static final class WebSocketFrame {
        public final int opcode;
        public final byte[] payload;
        public final boolean fin;

        WebSocketFrame(int opcode, byte[] payload) {
            this(opcode, payload, true);
        }

        WebSocketFrame(int opcode, byte[] payload, boolean fin) {
            this.opcode = opcode;
            this.payload = payload;
            this.fin = fin;
        }

        public boolean isClose() {
            return opcode == 0x8;
        }

        public boolean isPing() {
            return opcode == 0x9;
        }

        public boolean isText() {
            return opcode == 0x1;
        }

        public boolean isBinary() {
            return opcode == 0x2;
        }
    }
}
