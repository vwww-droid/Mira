package com.vwww.mira.screen;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class RemoteInputHandlerTest {
    private static final String INSTALL_ID = "install-123";

    private static void check(boolean value) {
        if (!value) throw new AssertionError();
    }

    private static void equal(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static JSONObject request(String installId, String kind) {
        return new JSONObject()
            .put("type", "screen.input")
            .put("installId", installId)
            .put("requestId", "request-7")
            .put("clientId", "client-9")
            .put("kind", kind);
    }

    private static String response(List<JSONObject> sent) {
        check(sent.size() == 1);
        return sent.get(0).toString();
    }

    public static void main(String[] args) throws Exception {
        List<JSONObject> sent = new ArrayList<>();
        RemoteInputHandler handler = new RemoteInputHandler(INSTALL_ID, sent::add);

        AppScreenCapture.reset();
        handler.handle(request("someone-else", "clear"));
        check(sent.isEmpty());
        check(AppScreenCapture.calls == 0);

        for (Object invalid : new Object[] {null, "not-a-number", Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            sent.clear();
            AppScreenCapture.reset();
            JSONObject tap = request(INSTALL_ID, "tap").put("y", 20);
            if (invalid != null) tap.put("x", invalid);
            handler.handle(tap);
            check(AppScreenCapture.calls == 0);
            equal("{\"type\":\"screen.input.result\",\"protocol\":1,\"installId\":\"install-123\",\"requestId\":\"request-7\",\"clientId\":\"client-9\",\"kind\":\"tap\",\"ok\":false,\"message\":\"invalid tap coordinates\",\"error\":\"invalid tap coordinates\"}", response(sent));
        }

        sent.clear();
        AppScreenCapture.reset();
        handler.handle(request(INSTALL_ID, "tap").put("x", -25).put("y", 100025));
        check(AppScreenCapture.calls == 1);
        check(AppScreenCapture.lastX == 0f && AppScreenCapture.lastY == 100000f);
        equal("{\"type\":\"screen.input.result\",\"protocol\":1,\"installId\":\"install-123\",\"requestId\":\"request-7\",\"clientId\":\"client-9\",\"kind\":\"tap\",\"ok\":true,\"message\":\"tap dispatched\"}", response(sent));

        sent.clear();
        AppScreenCapture.reset();
        AppScreenCapture.tapAccepted = false;
        handler.handle(request(INSTALL_ID, "tap").put("x", 0).put("y", 100000));
        check(AppScreenCapture.lastX == 0f && AppScreenCapture.lastY == 100000f);
        equal("{\"type\":\"screen.input.result\",\"protocol\":1,\"installId\":\"install-123\",\"requestId\":\"request-7\",\"clientId\":\"client-9\",\"kind\":\"tap\",\"ok\":false,\"message\":\"tap not handled\",\"error\":\"tap not handled\"}", response(sent));

        String[][] routed = new String[][] {
            {"text", "text", "hello", "text accepted", ""},
            {"paste", "paste", "pasted", "paste accepted", ""},
            {"key", "key", "Enter", "key accepted", ""},
            {"copy", "copy", "", "copied", "clipboard"},
            {"selectall", "selectall", "", "selected", ""},
            {"clear", "clear", "", "cleared", ""}
        };
        for (String[] golden : routed) {
            sent.clear();
            AppScreenCapture.reset();
            AppScreenCapture.nextResult = "copy".equals(golden[0])
                ? AppScreenCapture.InputResult.text(golden[3], golden[4])
                : AppScreenCapture.InputResult.ok(golden[3]);
            JSONObject input = request(INSTALL_ID, golden[0]);
            if ("text".equals(golden[0]) || "paste".equals(golden[0])) input.put("text", golden[2]);
            if ("key".equals(golden[0])) input.put("key", golden[2]);
            handler.handle(input);
            check(AppScreenCapture.calls == 1);
            equal(golden[1], AppScreenCapture.lastKind);
            equal(golden[2], AppScreenCapture.lastValue);
            String textField = "copy".equals(golden[0]) ? ",\"text\":\"" + golden[4] + "\"" : "";
            equal("{\"type\":\"screen.input.result\",\"protocol\":1,\"installId\":\"install-123\",\"requestId\":\"request-7\",\"clientId\":\"client-9\",\"kind\":\"" + golden[0] + "\",\"ok\":true,\"message\":\"" + golden[3] + "\"" + textField + "}", response(sent));
        }

        sent.clear();
        AppScreenCapture.reset();
        AppScreenCapture.nextResult = AppScreenCapture.InputResult.text("copied", "");
        handler.handle(new JSONObject().put("installId", INSTALL_ID).put("kind", "copy"));
        equal("{\"type\":\"screen.input.result\",\"protocol\":1,\"installId\":\"install-123\",\"requestId\":\"\",\"clientId\":\"\",\"kind\":\"copy\",\"ok\":true,\"message\":\"copied\",\"text\":\"\"}", response(sent));

        sent.clear();
        AppScreenCapture.reset();
        AppScreenCapture.nextResult = null;
        handler.handle(request(INSTALL_ID, "text").put("text", "ignored"));
        equal("{\"type\":\"screen.input.result\",\"protocol\":1,\"installId\":\"install-123\",\"requestId\":\"request-7\",\"clientId\":\"client-9\",\"kind\":\"text\",\"ok\":false,\"message\":\"input failed\",\"error\":\"input failed\"}", response(sent));

        sent.clear();
        AppScreenCapture.reset();
        AppScreenCapture.nextResult = AppScreenCapture.InputResult.error("clear failed");
        handler.handle(request(INSTALL_ID, "clear"));
        equal("{\"type\":\"screen.input.result\",\"protocol\":1,\"installId\":\"install-123\",\"requestId\":\"request-7\",\"clientId\":\"client-9\",\"kind\":\"clear\",\"ok\":false,\"message\":\"clear failed\",\"error\":\"clear failed\"}", response(sent));

        sent.clear();
        AppScreenCapture.reset();
        handler.handle(request(INSTALL_ID, "unknown"));
        check(AppScreenCapture.calls == 0);
        equal("{\"type\":\"screen.input.result\",\"protocol\":1,\"installId\":\"install-123\",\"requestId\":\"request-7\",\"clientId\":\"client-9\",\"kind\":\"unknown\",\"ok\":false,\"message\":\"unsupported screen input kind=unknown\",\"error\":\"unsupported screen input kind=unknown\"}", response(sent));

        AppScreenCapture.reset();
        RemoteInputHandler throwingSender = new RemoteInputHandler(INSTALL_ID, result -> {
            throw new RuntimeException("sender failed");
        });
        throwingSender.handle(request(INSTALL_ID, "clear"));
        check(AppScreenCapture.calls == 1);

        System.out.println("PASS: remote input validation, routing, clamping, and result schema");
    }
}
