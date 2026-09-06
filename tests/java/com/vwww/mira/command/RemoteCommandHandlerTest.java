package com.vwww.mira.command;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public final class RemoteCommandHandlerTest {
    public static void main(String[] args) {
        RecordingSender sender = new RecordingSender();
        QueuedExecutor executor = new QueuedExecutor();
        RemoteCommandHandler handler = new RemoteCommandHandler(new Context(), "install-7", executor, sender);

        handler.handle(request("another-install", "wrong-id", "mira-logcat"));
        assertEquals(0, sender.responses.size(), "wrong install identity is ignored");
        assertEquals(0, executor.tasks.size(), "wrong install identity is not scheduled");

        handler.handle(request("install-7", "", "mira-logcat"));
        assertEquals(0, sender.responses.size(), "missing requestId is ignored");

        JSONObject missing = request("install-7", "missing-command", "");
        handler.handle(missing);
        assertError(sender.remove(), "missing-command", "mira", "missing command");

        JSONObject unsupported = request("install-7", "unsupported", "mira-settings");
        handler.handle(unsupported);
        assertError(sender.remove(), "unsupported", "mira-settings", "unsupported command: mira-settings");

        CommandDispatcher.next = new CommandResult(0, "log output\n", "advisory\n");
        JSONObject success = request("install-7", "success", "mira-logcat")
            .put("arguments", new JSONArray().put("-d").put("Tag:I"));
        handler.handle(success);
        assertEquals(1, executor.tasks.size(), "allowed command is scheduled");
        assertEquals(0, sender.responses.size(), "result waits for executor");
        executor.runNext();
        JSONObject successResponse = sender.remove();
        assertCommon(successResponse, "success", "mira-logcat", true, 0);
        assertEquals("log output\n", successResponse.optString("stdout"), "success stdout");
        assertEquals("advisory\n", successResponse.optString("stderr"), "success stderr");
        assertEquals("advisory\n", successResponse.optString("warning"), "successful stderr is warning");
        assertEquals("-d", CommandDispatcher.lastArgv.get(0), "first argument reaches dispatcher");
        assertEquals("Tag:I", CommandDispatcher.lastArgv.get(1), "second argument reaches dispatcher");

        CommandDispatcher.next = new CommandResult(9, "partial", "");
        handler.handle(request("install-7", "failed", "mira-logcat"));
        executor.runNext();
        JSONObject failed = sender.remove();
        assertCommon(failed, "failed", "mira-logcat", false, 9);
        assertEquals("command failed with exit code 9", failed.optString("error"),
            "failed command without stderr gets fallback error");

        CommandDispatcher.failure = new IllegalStateException("boom");
        handler.handle(request("install-7", "exception", "mira-logcat"));
        executor.runNext();
        assertError(sender.remove(), "exception", "mira-logcat", "command execution failed: boom");

        System.out.println("PASS: remote command identity, validation, dispatch, and result schema");
    }

    private static JSONObject request(String installId, String requestId, String command) {
        return new JSONObject()
            .put("installId", installId)
            .put("requestId", requestId)
            .put("command", command);
    }

    private static void assertError(JSONObject response, String requestId, String command, String error) {
        assertCommon(response, requestId, command, false, 1);
        assertEquals(error, response.optString("stderr"), "error stderr");
        assertEquals(error, response.optString("error"), "error field");
    }

    private static void assertCommon(JSONObject response, String requestId, String command, boolean ok, int exitCode) {
        assertEquals("device.command.result", response.optString("type"), "response type");
        assertEquals("1", response.optString("protocol"), "protocol version");
        assertEquals("install-7", response.optString("installId"), "response install identity");
        assertEquals(requestId, response.optString("requestId"), "request correlation");
        assertEquals(command, response.optString("command"), "command echo");
        assertEquals(String.valueOf(ok), response.optString("ok"), "ok flag");
        assertEquals(String.valueOf(exitCode), response.optString("exitCode"), "exit code");
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static final class RecordingSender implements RemoteCommandHandler.ResultSender {
        final List<JSONObject> responses = new ArrayList<>();

        @Override
        public void send(JSONObject response) {
            responses.add(response);
        }

        JSONObject remove() {
            if (responses.isEmpty()) throw new AssertionError("expected response");
            return responses.remove(0);
        }
    }

    private static final class QueuedExecutor implements Executor {
        final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runNext() {
            if (tasks.isEmpty()) throw new AssertionError("expected queued task");
            tasks.remove(0).run();
        }
    }
}
