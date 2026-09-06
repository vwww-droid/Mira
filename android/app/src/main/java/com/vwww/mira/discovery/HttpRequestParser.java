package com.vwww.mira.discovery;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class HttpRequestParser {
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private HttpRequestParser() {
    }

    static Request read(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int delimiterState = 0;
        while (true) {
            int value = input.read();
            if (value == -1) return null;
            buffer.write(value);
            if (delimiterState == 0 && value == '\r') delimiterState = 1;
            else if (delimiterState == 1 && value == '\n') delimiterState = 2;
            else if (delimiterState == 2 && value == '\r') delimiterState = 3;
            else if (delimiterState == 3 && value == '\n') break;
            else delimiterState = 0;
            if (buffer.size() > MAX_HEADER_BYTES) throw new IOException("HTTP header too large");
        }

        String raw = buffer.toString("ISO-8859-1");
        String[] lines = raw.split("\r\n");
        String[] parts = lines[0].split(" ", 3);
        if (parts.length < 2) throw new IOException("Malformed HTTP request line");
        int contentLength = 0;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int index = line.indexOf(':');
            if (index <= 0) continue;
            String key = line.substring(0, index).trim().toLowerCase(Locale.ROOT);
            if ("content-length".equals(key)) {
                contentLength = parseContentLength(line.substring(index + 1).trim());
            }
        }
        byte[] body = readExactly(input, contentLength);
        String target = parts[1];
        int queryIndex = target.indexOf('?');
        String path = queryIndex >= 0 ? target.substring(0, queryIndex) : target;
        return new Request(parts[0], path, body);
    }

    static void writeJson(OutputStream output, String status, String json) throws IOException {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + status + "\r\n" +
            "Content-Length: " + data.length + "\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.UTF_8));
        output.write(data);
        output.flush();
    }

    private static int parseContentLength(String value) throws IOException {
        try {
            int length = Integer.parseInt(value);
            if (length < 0 || length > MAX_BODY_BYTES) {
                throw new IOException("Invalid Content-Length: " + value);
            }
            return length;
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid Content-Length: " + value, exception);
        }
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

    static final class Request {
        final String method;
        final String path;
        final byte[] body;

        Request(String method, String path, byte[] body) {
            this.method = method;
            this.path = path;
            this.body = body;
        }
    }
}
