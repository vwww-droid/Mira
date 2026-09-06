package com.vwww.mira.command;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

public final class RemoteCommandHandler {
    private static final String TAG = "MiraDiscovery";

    private final Context context;
    private final String installId;
    private final Executor executor;
    private final ResultSender resultSender;

    public RemoteCommandHandler(
        Context context,
        String installId,
        Executor executor,
        ResultSender resultSender
    ) {
        this.context = context.getApplicationContext();
        this.installId = installId == null ? "" : installId;
        this.executor = executor;
        this.resultSender = resultSender;
    }

    public void handle(JSONObject body) {
        if (!installId.equals(body.optString("installId"))) {
            Log.w(TAG, "Ignoring device.command for wrong installId");
            return;
        }
        String command = body.optString("command", "").trim();
        String requestId = body.optString("requestId", "").trim();
        if (command.isEmpty()) {
            sendErrorResult(body, "mira", "missing command");
            return;
        }
        if (!"mira-logcat".equals(command)) {
            sendErrorResult(body, command, "unsupported command: " + command);
            return;
        }
        if (requestId.isEmpty()) {
            Log.w(TAG, "Ignoring device.command for missing requestId");
            return;
        }
        Log.i(TAG, "Device command scheduled command=" + safeLogValue(command) + " requestId=" + safeLogValue(requestId));
        executor.execute(() -> run(body, command));
    }

    private void run(JSONObject body, String command) {
        String requestId = body.optString("requestId", "").trim();
        try {
            List<String> args = parseArguments(body);
            long startMs = SystemClock.elapsedRealtime();
            CommandResult result = CommandDispatcher.dispatch(context, command, args);
            long elapsedMs = SystemClock.elapsedRealtime() - startMs;
            if (result == null) {
                sendErrorResult(body, command, "command execution failed: empty result");
                return;
            }
            Log.i(TAG, "Device command finished requestId=" + safeLogValue(requestId) + " command=" + safeLogValue(command) + " exit=" + result.exitCode + " elapsedMs=" + elapsedMs);
            JSONObject response = new JSONObject();
            response.put("type", "device.command.result");
            response.put("protocol", 1);
            response.put("installId", installId);
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
            int stdoutBytes = result.stdout == null ? 0 : result.stdout.getBytes(StandardCharsets.UTF_8).length;
            int stderrBytes = result.stderr == null ? 0 : result.stderr.getBytes(StandardCharsets.UTF_8).length;
            Log.i(TAG, "Sending device command result requestId=" + safeLogValue(requestId) + " command=" + safeLogValue(command) + " stdoutBytes=" + stdoutBytes + " stderrBytes=" + stderrBytes);
            resultSender.send(response);
        } catch (Throwable throwable) {
            Log.w(TAG, "Device command failed requestId=" + safeLogValue(requestId) + " command=" + safeLogValue(command), throwable);
            sendErrorResult(body, command, "command execution failed: " + throwable.getMessage());
        }
    }

    private static List<String> parseArguments(JSONObject body) throws JSONException {
        JSONArray rawArgs = body.optJSONArray("arguments");
        if (rawArgs == null) return Collections.emptyList();
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

    private void sendErrorResult(JSONObject request, String command, String error) {
        try {
            String requestId = request.optString("requestId", "").trim();
            if (requestId.isEmpty()) {
                Log.w(TAG, "Cannot send command result without requestId");
                return;
            }
            JSONObject response = new JSONObject();
            response.put("type", "device.command.result");
            response.put("protocol", 1);
            response.put("installId", installId);
            response.put("requestId", requestId);
            response.put("command", command);
            response.put("ok", false);
            response.put("exitCode", 1);
            response.put("stdout", "");
            response.put("stderr", error == null ? "" : error);
            if (error != null) response.put("error", error);
            Log.i(TAG, "Sending device command error result requestId=" + safeLogValue(requestId) + " command=" + safeLogValue(command) + " error=" + safeLogValue(error));
            resultSender.send(response);
        } catch (Throwable throwable) {
            Log.w(TAG, "Unable to send device command result", throwable);
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

    public interface ResultSender {
        void send(JSONObject response);
    }
}
