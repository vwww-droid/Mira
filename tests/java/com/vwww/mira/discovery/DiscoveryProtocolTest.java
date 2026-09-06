package com.vwww.mira.discovery;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public final class DiscoveryProtocolTest {
    private static final byte[] EMPTY = new byte[0];

    public static void main(String[] args) throws Exception {
        testHttpRequestParsing();
        testHttpRequestRejections();
        testUtf8JsonResponseLength();
        testLanDiscoveryAndWakeEndpoint();
        testCloseAndReplacementIsolation();
        System.out.println("PASS: HTTP protocol and LAN discovery lifecycle");
    }

    private static void testHttpRequestParsing() throws Exception {
        byte[] body = "{\"installId\":\"安装-id\"}".getBytes(StandardCharsets.UTF_8);
        byte[] wire = request("POST", "/session/open?source=desktop", body, String.valueOf(body.length));
        HttpRequestParser.Request parsed = HttpRequestParser.read(new FragmentedInputStream(wire));
        check(parsed != null, "complete request must parse");
        check("POST".equals(parsed.method), "method must be preserved");
        check("/session/open".equals(parsed.path), "query must be removed from path");
        check(java.util.Arrays.equals(body, parsed.body), "fragmented body must be read exactly");
        check(HttpRequestParser.read(new ByteArrayInputStream(EMPTY)) == null, "empty EOF must return null");
    }

    private static void testHttpRequestRejections() throws Exception {
        expectIOException("malformed request line", "BROKEN\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        expectIOException("negative content length", request("POST", "/", EMPTY, "-1"));
        expectIOException("nonnumeric content length", request("POST", "/", EMPTY, "wat"));
        expectIOException("overflowing content length", request("POST", "/", EMPTY, "2147483648"));
        expectIOException("body over one MiB", request("POST", "/", EMPTY, "1048577"));
        expectIOException("truncated body", request("POST", "/", new byte[] {1, 2}, "3"));

        ByteArrayOutputStream oversized = new ByteArrayOutputStream();
        oversized.write("GET / HTTP/1.1\r\nX-Fill: ".getBytes(StandardCharsets.ISO_8859_1));
        for (int i = 0; i < 64 * 1024; i++) oversized.write('a');
        oversized.write("\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        expectIOException("header over 64 KiB", oversized.toByteArray());
    }

    private static void testUtf8JsonResponseLength() throws Exception {
        String json = "{\"message\":\"你好 Mira\"}";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        HttpRequestParser.writeJson(output, "200 OK", json);
        byte[] response = output.toByteArray();
        int boundary = indexOf(response, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        check(boundary >= 0, "response must contain a header terminator");
        String headers = new String(response, 0, boundary, StandardCharsets.ISO_8859_1);
        int expected = json.getBytes(StandardCharsets.UTF_8).length;
        check(headers.contains("Content-Length: " + expected + "\r\n"),
            "Content-Length must count UTF-8 bytes");
        check(response.length - boundary - 4 == expected, "response body length must match header");
    }

    private static void testLanDiscoveryAndWakeEndpoint() throws Exception {
        RecordingCallback callback = new RecordingCallback("first");
        LanDiscoveryServer server = new LanDiscoveryServer(new Context(), 0, "install-1", callback);
        server.start();
        server.start();
        try {
            check(server.getDiscoveryPort() > 0, "ephemeral UDP port must be exposed");
            check(server.getWakePort() > 0, "ephemeral wake port must be exposed");

            try (DatagramSocket client = new DatagramSocket()) {
                client.setSoTimeout(250);
                sendUdp(client, server.getDiscoveryPort(), "{\"type\":\"ignored\"}");
                expectUdpTimeout(client, "unrelated datagram must not receive a response");
                sendUdp(client, server.getDiscoveryPort(), "{\"type\":\"mira.discover\"}");
                String response = receiveUdp(client);
                check(response.contains("\"marker\":\"first\""), "discovery must return callback metadata");
                check(callback.lastWakeUrl != null && callback.lastWakeUrl.endsWith(
                    ":" + server.getWakePort() + "/session/open"), "metadata callback must receive bound wake URL");
            }

            check(socketRequest(server.getWakePort(), "GET /session/open HTTP/1.1\r\n\r\n")
                .startsWith("HTTP/1.1 404 Not Found"), "wrong method must return 404");
            check(socketRequest(server.getWakePort(), post("/session/open", "wrong"))
                .contains("wrong installId"), "wrong install id must return 404 JSON");

            String opened = fragmentedSocketRequest(server.getWakePort(), post("/session/open?source=test", "install-1"));
            check(opened.startsWith("HTTP/1.1 200 OK"), "query-bearing wake request must open session");
            check(callback.opens.get() == 1, "valid wake request must invoke callback once");
            check(socketRequest(server.getWakePort(), post("/session/open", "install-1"))
                .startsWith("HTTP/1.1 409 Conflict"), "active session must return conflict");
            check(callback.opens.get() == 2, "conflicting valid request must still reach callback");

            try (Socket empty = connect(server.getWakePort())) {
                empty.shutdownOutput();
                check(empty.getInputStream().read() == -1, "empty EOF must close without a response");
            }
            String truncated = "POST /session/open HTTP/1.1\r\nContent-Length: 20\r\n\r\n{}";
            check(socketRequest(server.getWakePort(), truncated).isEmpty(),
                "truncated body must close without dispatch or response");
            check(callback.opens.get() == 2, "truncated body must not invoke callback");
        } finally {
            server.close();
        }
    }

    private static void testCloseAndReplacementIsolation() throws Exception {
        RecordingCallback oldCallback = new RecordingCallback("old");
        LanDiscoveryServer old = new LanDiscoveryServer(new Context(), 0, "old-id", oldCallback);
        old.start();
        int oldUdpPort = old.getDiscoveryPort();
        int oldWakePort = old.getWakePort();

        Socket incomplete = connect(oldWakePort);
        incomplete.getOutputStream().write("POST /session/open HTTP/1.1\r\n".getBytes(StandardCharsets.ISO_8859_1));
        old.close();
        old.close();
        try {
            check(incomplete.getInputStream().read() == -1, "close must terminate in-flight wake clients");
        } catch (SocketException expected) {
            // A forced close may surface as EOF or connection reset, depending on the host OS.
        }
        incomplete.close();
        try {
            old.start();
            throw new AssertionError("closed server must reject restart");
        } catch (IllegalStateException expected) {
            // A server owns one resource lifetime; the service creates its replacement.
        }
        try (DatagramSocket client = new DatagramSocket()) {
            client.setSoTimeout(250);
            sendUdp(client, oldUdpPort, "{\"type\":\"mira.discover\"}");
            expectUdpTimeout(client, "closed UDP listener must not answer");
        }
        check(oldCallback.metadataCalls.get() == 0, "closed server must not invoke stale callback");

        RecordingCallback replacementCallback = new RecordingCallback("replacement");
        LanDiscoveryServer replacement = new LanDiscoveryServer(new Context(), 0, "new-id", replacementCallback);
        replacement.start();
        try {
            try (DatagramSocket client = new DatagramSocket()) {
                client.setSoTimeout(1000);
                sendUdp(client, replacement.getDiscoveryPort(), "{\"type\":\"mira.discover\"}");
                check(receiveUdp(client).contains("replacement"), "replacement server must answer independently");
            }
            check(socketRequest(replacement.getWakePort(), post("/session/open", "new-id"))
                .startsWith("HTTP/1.1 200 OK"), "replacement wake endpoint must be usable");
            check(oldCallback.opens.get() == 0, "replacement must not dispatch to stale callback");
            check(replacementCallback.opens.get() == 1, "replacement must dispatch to its own callback");
        } finally {
            replacement.close();
        }
    }

    private static byte[] request(String method, String path, byte[] body, String contentLength) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write((method + " " + path + " HTTP/1.1\r\nContent-Length: " + contentLength +
            "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        output.write(body);
        return output.toByteArray();
    }

    private static String post(String path, String installId) {
        byte[] body = ("{\"installId\":\"" + installId + "\"}").getBytes(StandardCharsets.UTF_8);
        return "POST " + path + " HTTP/1.1\r\nContent-Length: " + body.length + "\r\n\r\n" +
            new String(body, StandardCharsets.UTF_8);
    }

    private static String fragmentedSocketRequest(int port, String request) throws Exception {
        try (Socket socket = connect(port)) {
            byte[] data = request.getBytes(StandardCharsets.UTF_8);
            for (int offset = 0; offset < data.length; offset += 3) {
                socket.getOutputStream().write(data, offset, Math.min(3, data.length - offset));
                socket.getOutputStream().flush();
            }
            socket.shutdownOutput();
            return new String(readAll(socket.getInputStream()), StandardCharsets.UTF_8);
        }
    }

    private static String socketRequest(int port, String request) throws Exception {
        try (Socket socket = connect(port)) {
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.shutdownOutput();
            return new String(readAll(socket.getInputStream()), StandardCharsets.UTF_8);
        }
    }

    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1000);
        socket.setSoTimeout(1000);
        return socket;
    }

    private static void sendUdp(DatagramSocket socket, int port, String text) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(data, data.length, InetAddress.getLoopbackAddress(), port));
    }

    private static String receiveUdp(DatagramSocket socket) throws IOException {
        DatagramPacket packet = new DatagramPacket(new byte[4096], 4096);
        socket.receive(packet);
        return new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
    }

    private static void expectUdpTimeout(DatagramSocket socket, String message) throws IOException {
        try {
            receiveUdp(socket);
            throw new AssertionError(message);
        } catch (SocketTimeoutException expected) {
            // Expected absence of a reply.
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static void expectIOException(String label, byte[] input) throws Exception {
        try {
            HttpRequestParser.read(new FragmentedInputStream(input));
            throw new AssertionError(label + " must throw IOException");
        } catch (IOException expected) {
            // Expected protocol rejection.
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class FragmentedInputStream extends ByteArrayInputStream {
        FragmentedInputStream(byte[] data) {
            super(data);
        }

        @Override
        public synchronized int read(byte[] destination, int offset, int length) {
            return super.read(destination, offset, Math.min(1, length));
        }
    }

    private static final class RecordingCallback implements LanDiscoveryServer.Callback {
        final String marker;
        final AtomicInteger metadataCalls = new AtomicInteger();
        final AtomicInteger opens = new AtomicInteger();
        volatile String lastWakeUrl;

        RecordingCallback(String marker) {
            this.marker = marker;
        }

        @Override
        public JSONObject deviceMetadata(String wakeUrl) {
            metadataCalls.incrementAndGet();
            lastWakeUrl = wakeUrl;
            return new JSONObject().put("type", "mira.device").put("marker", marker).put("wakeUrl", wakeUrl);
        }

        @Override
        public boolean openSession(JSONObject request) {
            return opens.incrementAndGet() == 1;
        }
    }
}
