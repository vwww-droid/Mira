package com.vwww.mira.command;

import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

final class LocalCommandProtocol {
    static final int PROTOCOL_VERSION = 1;
    private static final int MAX_FRAME_BYTES = 4 * 1024 * 1024;

    private LocalCommandProtocol() {
    }

    static JSONObject readJson(InputStream input) throws IOException, JSONException {
        DataInputStream dataInput = new DataInputStream(input);
        int length = dataInput.readInt();
        if (length <= 0 || length > MAX_FRAME_BYTES) {
            throw new IOException("invalid command frame length: " + length);
        }
        byte[] payload = new byte[length];
        dataInput.readFully(payload);
        return new JSONObject(new String(payload, StandardCharsets.UTF_8));
    }

    static void writeJson(OutputStream output, JSONObject json) throws IOException {
        byte[] payload = json.toString().getBytes(StandardCharsets.UTF_8);
        if (payload.length > MAX_FRAME_BYTES) {
            throw new IOException("command frame too large: " + payload.length);
        }
        DataOutputStream dataOutput = new DataOutputStream(output);
        dataOutput.writeInt(payload.length);
        dataOutput.write(payload);
        dataOutput.flush();
    }

    static JSONObject resultJson(CommandResult result) throws JSONException {
        return new JSONObject()
            .put("protocol", PROTOCOL_VERSION)
            .put("exitCode", result.exitCode)
            .put("stdout", result.stdout)
            .put("stderr", result.stderr);
    }

    static void writeTextResult(OutputStream output, CommandResult result) throws IOException {
        String payload = "MIRA/1 EXIT " + result.exitCode + "\n" +
            "STDOUT " + encode(result.stdout) + "\n" +
            "STDERR " + encode(result.stderr) + "\n" +
            "END\n";
        output.write(payload.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    static String encode(String text) {
        return Base64.encodeToString((text == null ? "" : text).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    static String decode(String text) {
        byte[] data = Base64.decode(text == null ? "" : text, Base64.DEFAULT);
        return new String(data, StandardCharsets.UTF_8);
    }
}
