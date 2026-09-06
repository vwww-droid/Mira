package com.vwww.mira.screen;

import android.util.Log;

import org.json.JSONObject;

public final class RemoteInputHandler {
    private static final String TAG = "MiraDiscovery";

    @FunctionalInterface
    public interface ResultSender {
        void send(JSONObject result);
    }

    private final String installId;
    private final ResultSender resultSender;

    public RemoteInputHandler(String installId, ResultSender resultSender) {
        this.installId = installId == null ? "" : installId;
        this.resultSender = resultSender;
    }

    public void handle(JSONObject request) {
        if (!installId.equals(request.optString("installId"))) {
            Log.w(TAG, "Ignoring screen.input for wrong installId");
            return;
        }
        String kind = request.optString("kind", "");
        AppScreenCapture.InputResult result;
        if ("tap".equals(kind)) {
            double x = request.optDouble("x", Double.NaN);
            double y = request.optDouble("y", Double.NaN);
            if (Double.isNaN(x) || Double.isInfinite(x) || Double.isNaN(y) || Double.isInfinite(y)) {
                result = AppScreenCapture.InputResult.error("invalid tap coordinates");
            } else {
                float frameX = clampTapCoordinate(x);
                float frameY = clampTapCoordinate(y);
                boolean accepted = AppScreenCapture.getInstance().dispatchTapFromFrame(frameX, frameY);
                result = accepted ? AppScreenCapture.InputResult.ok("tap dispatched") : AppScreenCapture.InputResult.error("tap not handled");
                Log.i(TAG, "screen tap accepted=" + accepted + " x=" + frameX + " y=" + frameY);
            }
        } else if ("text".equals(kind)) {
            result = AppScreenCapture.getInstance().dispatchTextInput(request.optString("text", ""));
        } else if ("paste".equals(kind)) {
            result = AppScreenCapture.getInstance().dispatchPaste(request.optString("text", ""));
        } else if ("key".equals(kind)) {
            result = AppScreenCapture.getInstance().dispatchKeyInput(request.optString("key", ""));
        } else if ("copy".equals(kind)) {
            result = AppScreenCapture.getInstance().copyFocusedText();
        } else if ("selectall".equals(kind)) {
            result = AppScreenCapture.getInstance().selectAllFocusedText();
        } else if ("clear".equals(kind)) {
            result = AppScreenCapture.getInstance().clearFocusedText();
        } else {
            result = AppScreenCapture.InputResult.error("unsupported screen input kind=" + kind);
        }
        sendResult(request, kind, result);
    }

    private void sendResult(JSONObject request, String kind, AppScreenCapture.InputResult result) {
        try {
            JSONObject response = new JSONObject();
            response.put("type", "screen.input.result");
            response.put("protocol", 1);
            response.put("installId", installId);
            response.put("requestId", request.optString("requestId", ""));
            response.put("clientId", request.optString("clientId", ""));
            response.put("kind", kind == null ? "" : kind);
            response.put("ok", result != null && result.ok);
            response.put("message", result == null ? "input failed" : result.message);
            if (result == null || !result.ok) response.put("error", result == null ? "input failed" : result.message);
            if (result != null && result.text != null && ("copy".equals(kind) || !result.text.isEmpty())) response.put("text", result.text);
            if (resultSender != null) resultSender.send(response);
        } catch (Throwable throwable) {
            Log.w(TAG, "Unable to send screen input result", throwable);
        }
    }

    private static float clampTapCoordinate(double value) {
        double clamped = Math.max(0d, Math.min(value, 100000d));
        return (float) clamped;
    }
}
