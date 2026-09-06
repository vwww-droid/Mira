package com.vwww.mira.screen;

public final class AppScreenCapture {
    private static final AppScreenCapture INSTANCE = new AppScreenCapture();

    static String lastKind = "";
    static String lastValue = "";
    static float lastX;
    static float lastY;
    static int calls;
    static boolean tapAccepted = true;
    static InputResult nextResult = InputResult.ok("ok");

    public static AppScreenCapture getInstance() {
        return INSTANCE;
    }

    static void reset() {
        lastKind = "";
        lastValue = "";
        lastX = Float.NaN;
        lastY = Float.NaN;
        calls = 0;
        tapAccepted = true;
        nextResult = InputResult.ok("ok");
    }

    public boolean dispatchTapFromFrame(float x, float y) {
        record("tap", "");
        lastX = x;
        lastY = y;
        return tapAccepted;
    }

    public InputResult dispatchTextInput(String text) {
        record("text", text);
        return nextResult;
    }

    public InputResult dispatchPaste(String text) {
        record("paste", text);
        return nextResult;
    }

    public InputResult dispatchKeyInput(String key) {
        record("key", key);
        return nextResult;
    }

    public InputResult copyFocusedText() {
        record("copy", "");
        return nextResult;
    }

    public InputResult selectAllFocusedText() {
        record("selectall", "");
        return nextResult;
    }

    public InputResult clearFocusedText() {
        record("clear", "");
        return nextResult;
    }

    private static void record(String kind, String value) {
        calls++;
        lastKind = kind;
        lastValue = value == null ? "" : value;
    }

    static final class InputResult {
        final boolean ok;
        final String message;
        final String text;

        private InputResult(boolean ok, String message, String text) {
            this.ok = ok;
            this.message = message == null ? "" : message;
            this.text = text == null ? "" : text;
        }

        static InputResult ok(String message) {
            return new InputResult(true, message, "");
        }

        static InputResult text(String message, String text) {
            return new InputResult(true, message, text);
        }

        static InputResult error(String message) {
            return new InputResult(false, message, "");
        }
    }
}
