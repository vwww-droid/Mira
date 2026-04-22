package com.vwww.mira;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.Selection;
import android.text.Spannable;
import android.util.Log;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class MiraSelfScreenCapture {
    private static final String TAG = "MiraSelfScreen";
    private static final long MAIN_THREAD_TIMEOUT_MS = 1800;
    private static final MiraSelfScreenCapture INSTANCE = new MiraSelfScreenCapture();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object activityLock = new Object();
    private WeakReference<Activity> activityRef = new WeakReference<>(null);
    private WeakReference<View> rootRef = new WeakReference<>(null);
    private volatile CaptureGeometry lastGeometry;

    private MiraSelfScreenCapture() {
    }

    public static MiraSelfScreenCapture getInstance() {
        return INSTANCE;
    }

    public void register(Activity activity) {
        if (activity == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> register(activity));
            return;
        }
        View root = decorRoot(activity);
        synchronized (activityLock) {
            activityRef = new WeakReference<>(activity);
            rootRef = new WeakReference<>(root);
        }
    }

    public void unregister(Activity activity) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> unregister(activity));
            return;
        }
        synchronized (activityLock) {
            Activity current = activityRef.get();
            if (activity != null && current != null && current != activity) return;
            activityRef = new WeakReference<>(null);
            rootRef = new WeakReference<>(null);
            lastGeometry = null;
        }
    }

    public CaptureResult captureJpeg(int maxWidth, int quality) {
        if (Looper.myLooper() == Looper.getMainLooper()) return captureJpegOnMainThread(maxWidth, quality);

        AtomicReference<CaptureResult> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(() -> {
            try {
                result.set(captureJpegOnMainThread(maxWidth, quality));
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return CaptureResult.error("main thread timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CaptureResult.error("interrupted");
        } catch (Throwable throwable) {
            return CaptureResult.error(describe(throwable));
        }
        CaptureResult value = result.get();
        return value == null ? CaptureResult.error("empty capture") : value;
    }

    public RootSize currentRootSize() {
        if (Looper.myLooper() == Looper.getMainLooper()) return currentRootSizeOnMainThread();

        AtomicReference<RootSize> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(() -> {
            try {
                result.set(currentRootSizeOnMainThread());
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return RootSize.unavailable("main thread timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RootSize.unavailable("interrupted");
        } catch (Throwable throwable) {
            return RootSize.unavailable(describe(throwable));
        }
        RootSize value = result.get();
        return value == null ? RootSize.unavailable("empty root size") : value;
    }

    public RenderResult renderToSurface(Surface surface, int outputWidth, int outputHeight) {
        if (surface == null || !surface.isValid()) return RenderResult.error("surface unavailable");
        if (outputWidth <= 0 || outputHeight <= 0) return RenderResult.error("invalid output size");
        if (Looper.myLooper() == Looper.getMainLooper()) return renderToSurfaceOnMainThread(surface, outputWidth, outputHeight);

        AtomicReference<RenderResult> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(() -> {
            try {
                result.set(renderToSurfaceOnMainThread(surface, outputWidth, outputHeight));
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return RenderResult.error("main thread timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RenderResult.error("interrupted");
        } catch (Throwable throwable) {
            return RenderResult.error(describe(throwable));
        }
        RenderResult value = result.get();
        return value == null ? RenderResult.error("empty render") : value;
    }

    public boolean dispatchTapFromFrame(float frameX, float frameY) {
        if (Float.isNaN(frameX) || Float.isInfinite(frameX) || Float.isNaN(frameY) || Float.isInfinite(frameY)) return false;
        if (Looper.myLooper() == Looper.getMainLooper()) return dispatchTapOnMainThread(frameX, frameY);

        AtomicReference<Boolean> result = new AtomicReference<>(false);
        CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(() -> {
            try {
                result.set(dispatchTapOnMainThread(frameX, frameY));
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Throwable throwable) {
            Log.w(TAG, "Tap dispatch failed", throwable);
            return false;
        }
        return Boolean.TRUE.equals(result.get());
    }

    public InputResult dispatchTextInput(String text) {
        String value = text == null ? "" : text;
        if (Looper.myLooper() == Looper.getMainLooper()) return dispatchTextInputOnMainThread(value, false);
        return callOnMainThread(() -> dispatchTextInputOnMainThread(value, false), InputResult.error("text input failed"));
    }

    public InputResult dispatchPaste(String text) {
        String value = text == null ? "" : text;
        if (Looper.myLooper() == Looper.getMainLooper()) return dispatchTextInputOnMainThread(value, true);
        return callOnMainThread(() -> dispatchTextInputOnMainThread(value, true), InputResult.error("paste failed"));
    }

    public InputResult dispatchKeyInput(String key) {
        String value = key == null ? "" : key;
        if (Looper.myLooper() == Looper.getMainLooper()) return dispatchKeyInputOnMainThread(value);
        return callOnMainThread(() -> dispatchKeyInputOnMainThread(value), InputResult.error("key input failed"));
    }

    public InputResult copyFocusedText() {
        if (Looper.myLooper() == Looper.getMainLooper()) return copyFocusedTextOnMainThread();
        return callOnMainThread(this::copyFocusedTextOnMainThread, InputResult.error("copy failed"));
    }

    public InputResult selectAllFocusedText() {
        if (Looper.myLooper() == Looper.getMainLooper()) return selectAllFocusedTextOnMainThread();
        return callOnMainThread(this::selectAllFocusedTextOnMainThread, InputResult.error("select all failed"));
    }

    public InputResult clearFocusedText() {
        if (Looper.myLooper() == Looper.getMainLooper()) return clearFocusedTextOnMainThread();
        return callOnMainThread(this::clearFocusedTextOnMainThread, InputResult.error("clear failed"));
    }

    private CaptureResult captureJpegOnMainThread(int maxWidth, int quality) {
        try {
            Activity activity;
            View root;
            synchronized (activityLock) {
                activity = activityRef.get();
                root = rootRef.get();
            }
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return CaptureResult.error("activity unavailable");
            if (root == null) root = decorRoot(activity);
            if (root == null || root.getWindowToken() == null) return CaptureResult.error("root unavailable");
            int sourceWidth = root.getWidth();
            int sourceHeight = root.getHeight();
            if (sourceWidth <= 0 || sourceHeight <= 0) return CaptureResult.error("root has empty bounds");

            int clampedQuality = Math.max(1, Math.min(quality, 100));
            float scale = 1f;
            if (maxWidth > 0 && sourceWidth > maxWidth) scale = maxWidth / (float) sourceWidth;
            int outputWidth = Math.max(1, Math.round(sourceWidth * scale));
            int outputHeight = Math.max(1, Math.round(sourceHeight * scale));

            Bitmap bitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            if (scale != 1f) canvas.scale(scale, scale);
            root.draw(canvas);

            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(32 * 1024, outputWidth * outputHeight / 8));
            boolean compressed = bitmap.compress(Bitmap.CompressFormat.JPEG, clampedQuality, output);
            bitmap.recycle();
            if (!compressed) return CaptureResult.error("jpeg compression failed");

            long capturedAt = System.currentTimeMillis();
            lastGeometry = new CaptureGeometry(sourceWidth, sourceHeight, outputWidth, outputHeight, capturedAt);
            return CaptureResult.success(output.toByteArray(), outputWidth, outputHeight, sourceWidth, sourceHeight, capturedAt);
        } catch (OutOfMemoryError error) {
            return CaptureResult.error("out of memory");
        } catch (Throwable throwable) {
            return CaptureResult.error(describe(throwable));
        }
    }

    private RootSize currentRootSizeOnMainThread() {
        try {
            Activity activity;
            View root;
            synchronized (activityLock) {
                activity = activityRef.get();
                root = rootRef.get();
            }
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return RootSize.unavailable("activity unavailable");
            if (root == null) root = decorRoot(activity);
            if (root == null || root.getWindowToken() == null) return RootSize.unavailable("root unavailable");
            int width = root.getWidth();
            int height = root.getHeight();
            if (width <= 0 || height <= 0) return RootSize.unavailable("root has empty bounds");
            return RootSize.available(width, height);
        } catch (Throwable throwable) {
            return RootSize.unavailable(describe(throwable));
        }
    }

    private RenderResult renderToSurfaceOnMainThread(Surface surface, int outputWidth, int outputHeight) {
        Canvas canvas = null;
        try {
            Activity activity;
            View root;
            synchronized (activityLock) {
                activity = activityRef.get();
                root = rootRef.get();
            }
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return RenderResult.error("activity unavailable");
            if (root == null) root = decorRoot(activity);
            if (root == null || root.getWindowToken() == null) return RenderResult.error("root unavailable");
            int sourceWidth = root.getWidth();
            int sourceHeight = root.getHeight();
            if (sourceWidth <= 0 || sourceHeight <= 0) return RenderResult.error("root has empty bounds");

            canvas = surface.lockCanvas(null);
            canvas.drawColor(Color.BLACK);
            canvas.save();
            canvas.scale(outputWidth / (float) sourceWidth, outputHeight / (float) sourceHeight);
            root.draw(canvas);
            canvas.restore();

            long capturedAt = System.currentTimeMillis();
            lastGeometry = new CaptureGeometry(sourceWidth, sourceHeight, outputWidth, outputHeight, capturedAt);
            return RenderResult.success(outputWidth, outputHeight, sourceWidth, sourceHeight, capturedAt);
        } catch (Throwable throwable) {
            return RenderResult.error(describe(throwable));
        } finally {
            if (canvas != null) {
                try {
                    surface.unlockCanvasAndPost(canvas);
                } catch (Throwable throwable) {
                    Log.w(TAG, "Unable to post encoder surface canvas", throwable);
                }
            }
        }
    }

    private boolean dispatchTapOnMainThread(float frameX, float frameY) {
        try {
            Activity activity;
            View root;
            synchronized (activityLock) {
                activity = activityRef.get();
                root = rootRef.get();
            }
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
            if (root == null) root = decorRoot(activity);
            if (root == null || root.getWidth() <= 0 || root.getHeight() <= 0) return false;

            CaptureGeometry geometry = lastGeometry;
            float x = frameX;
            float y = frameY;
            if (geometry != null && geometry.outputWidth > 0 && geometry.outputHeight > 0) {
                x = frameX * geometry.sourceWidth / (float) geometry.outputWidth;
                y = frameY * geometry.sourceHeight / (float) geometry.outputHeight;
            }
            x = Math.max(0f, Math.min(x, Math.max(0, root.getWidth() - 1)));
            y = Math.max(0f, Math.min(y, Math.max(0, root.getHeight() - 1)));

            long downTime = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent up = MotionEvent.obtain(downTime, downTime + 48, MotionEvent.ACTION_UP, x, y, 0);
            boolean handledDown;
            boolean handledUp;
            try {
                handledDown = root.dispatchTouchEvent(down);
                handledUp = root.dispatchTouchEvent(up);
            } finally {
                down.recycle();
                up.recycle();
            }
            root.postDelayed(MiraDiscoveryService::requestOutlineUpload, 180);
            Log.i(TAG, "tap frame=" + frameX + "," + frameY + " view=" + x + "," + y + " handled=" + handledDown + "/" + handledUp);
            return handledDown || handledUp;
        } catch (Throwable throwable) {
            Log.w(TAG, "Tap dispatch failed", throwable);
            return false;
        }
    }

    private InputResult dispatchTextInputOnMainThread(String text, boolean updateClipboard) {
        try {
            if (text == null || text.isEmpty()) return InputResult.ok("empty text");
            Activity activity = currentActivity();
            View root = currentRoot(activity);
            if (activity == null || root == null) return InputResult.error("activity unavailable");
            View focus = focusedView(activity, root);
            if (updateClipboard) setClipboard(activity, text);
            if (insertIntoEditable(focus, text)) {
                root.postDelayed(MiraDiscoveryService::requestOutlineUpload, 80);
                return InputResult.ok("text inserted");
            }
            boolean handled = dispatchCharacters(focus == null ? root : focus, text);
            root.postDelayed(MiraDiscoveryService::requestOutlineUpload, 80);
            return handled ? InputResult.ok("text dispatched") : InputResult.error("focused view does not accept text");
        } catch (Throwable throwable) {
            Log.w(TAG, "Text input failed", throwable);
            return InputResult.error(describe(throwable));
        }
    }

    private InputResult dispatchKeyInputOnMainThread(String key) {
        try {
            Activity activity = currentActivity();
            View root = currentRoot(activity);
            if (activity == null || root == null) return InputResult.error("activity unavailable");
            int keyCode = keyCodeFor(key);
            if (keyCode == 0) return InputResult.error("unsupported key: " + key);
            View target = focusedView(activity, root);
            if (target == null) target = root;
            if (("Backspace".equals(key) || "Delete".equals(key)) && deleteFromEditable(target, "Delete".equals(key))) {
                root.postDelayed(MiraDiscoveryService::requestOutlineUpload, 80);
                return InputResult.ok("deleted");
            }
            long now = SystemClock.uptimeMillis();
            KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0);
            KeyEvent up = new KeyEvent(now, now + 16, KeyEvent.ACTION_UP, keyCode, 0);
            boolean handled = target.dispatchKeyEvent(down) || root.dispatchKeyEvent(down);
            handled = target.dispatchKeyEvent(up) || root.dispatchKeyEvent(up) || handled;
            root.postDelayed(MiraDiscoveryService::requestOutlineUpload, 80);
            return handled ? InputResult.ok("key dispatched") : InputResult.error("key not handled: " + key);
        } catch (Throwable throwable) {
            Log.w(TAG, "Key input failed", throwable);
            return InputResult.error(describe(throwable));
        }
    }

    private InputResult copyFocusedTextOnMainThread() {
        try {
            Activity activity = currentActivity();
            View root = currentRoot(activity);
            if (activity == null || root == null) return InputResult.error("activity unavailable");
            View focus = focusedView(activity, root);
            if (!(focus instanceof TextView)) return InputResult.error("focused view is not text");
            TextView textView = (TextView) focus;
            CharSequence value = textView.getText();
            if (value == null) value = "";
            int start = Math.max(0, textView.getSelectionStart());
            int end = Math.max(0, textView.getSelectionEnd());
            String text;
            if (start != end && start <= value.length() && end <= value.length()) {
                text = value.subSequence(Math.min(start, end), Math.max(start, end)).toString();
            } else {
                text = value.toString();
            }
            setClipboard(activity, text);
            return InputResult.text("copied", text);
        } catch (Throwable throwable) {
            Log.w(TAG, "Copy focused text failed", throwable);
            return InputResult.error(describe(throwable));
        }
    }

    private InputResult selectAllFocusedTextOnMainThread() {
        try {
            Activity activity = currentActivity();
            View root = currentRoot(activity);
            if (activity == null || root == null) return InputResult.error("activity unavailable");
            View focus = focusedView(activity, root);
            if (!(focus instanceof TextView)) return InputResult.error("focused view is not text");
            TextView textView = (TextView) focus;
            CharSequence value = textView.getText();
            int length = value == null ? 0 : value.length();
            textView.requestFocus();
            if (textView instanceof EditText) {
                ((EditText) textView).setSelection(0, length);
            } else if (value instanceof Spannable) {
                Selection.setSelection((Spannable) value, 0, length);
            } else {
                return InputResult.error("focused text is not selectable");
            }
            root.postDelayed(MiraDiscoveryService::requestOutlineUpload, 80);
            return InputResult.ok("selected all");
        } catch (Throwable throwable) {
            Log.w(TAG, "Select all focused text failed", throwable);
            return InputResult.error(describe(throwable));
        }
    }

    private InputResult clearFocusedTextOnMainThread() {
        try {
            Activity activity = currentActivity();
            View root = currentRoot(activity);
            if (activity == null || root == null) return InputResult.error("activity unavailable");
            View focus = focusedView(activity, root);
            if (!(focus instanceof TextView)) return InputResult.error("focused view is not text");
            TextView textView = (TextView) focus;
            Editable editable = textView instanceof EditText ? ((EditText) textView).getText() : textView.getEditableText();
            if (editable == null) return InputResult.error("focused text is not editable");
            editable.clear();
            if (textView instanceof EditText) ((EditText) textView).setSelection(0);
            root.postDelayed(MiraDiscoveryService::requestOutlineUpload, 80);
            return InputResult.ok("cleared");
        } catch (Throwable throwable) {
            Log.w(TAG, "Clear focused text failed", throwable);
            return InputResult.error(describe(throwable));
        }
    }

    private Activity currentActivity() {
        synchronized (activityLock) {
            Activity activity = activityRef.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
            return activity;
        }
    }

    private View currentRoot(Activity activity) {
        if (activity == null) return null;
        View root;
        synchronized (activityLock) {
            root = rootRef.get();
        }
        if (root == null) root = decorRoot(activity);
        if (root == null || root.getWindowToken() == null) return null;
        return root;
    }

    private static View focusedView(Activity activity, View root) {
        View focus = activity == null ? null : activity.getCurrentFocus();
        if (focus == null && root != null) focus = root.findFocus();
        return focus == null ? root : focus;
    }

    private static boolean insertIntoEditable(View focus, String text) {
        if (!(focus instanceof TextView)) return false;
        TextView textView = (TextView) focus;
        Editable editable = textView instanceof EditText ? ((EditText) textView).getText() : textView.getEditableText();
        if (editable == null) return false;
        int start = textView.getSelectionStart();
        int end = textView.getSelectionEnd();
        if (start < 0 || end < 0) {
            start = editable.length();
            end = editable.length();
        }
        int left = Math.max(0, Math.min(start, end));
        int right = Math.max(0, Math.max(start, end));
        left = Math.min(left, editable.length());
        right = Math.min(right, editable.length());
        editable.replace(left, right, text);
        int cursor = left + text.length();
        if (textView instanceof EditText) ((EditText) textView).setSelection(Math.min(cursor, editable.length()));
        return true;
    }

    private static boolean deleteFromEditable(View focus, boolean forward) {
        if (!(focus instanceof TextView)) return false;
        TextView textView = (TextView) focus;
        Editable editable = textView instanceof EditText ? ((EditText) textView).getText() : textView.getEditableText();
        if (editable == null) return false;
        int length = editable.length();
        int start = textView.getSelectionStart();
        int end = textView.getSelectionEnd();
        if (start < 0 || end < 0) {
            start = length;
            end = length;
        }
        int left = Math.max(0, Math.min(start, end));
        int right = Math.max(0, Math.max(start, end));
        left = Math.min(left, length);
        right = Math.min(right, length);
        if (left != right) {
            editable.delete(left, right);
            if (textView instanceof EditText) ((EditText) textView).setSelection(Math.min(left, editable.length()));
            return true;
        }
        if (forward) {
            if (left >= length) return false;
            int next = Math.min(length, left + Character.charCount(Character.codePointAt(editable, left)));
            editable.delete(left, next);
            if (textView instanceof EditText) ((EditText) textView).setSelection(Math.min(left, editable.length()));
            return true;
        }
        if (left <= 0) return false;
        int previous = Math.max(0, left - Character.charCount(Character.codePointBefore(editable, left)));
        editable.delete(previous, left);
        if (textView instanceof EditText) ((EditText) textView).setSelection(Math.min(previous, editable.length()));
        return true;
    }

    private static boolean dispatchCharacters(View target, String text) {
        try {
            KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray());
            if (events == null || events.length == 0) return false;
            boolean handled = false;
            for (KeyEvent event : events) handled = target.dispatchKeyEvent(event) || handled;
            return handled;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void setClipboard(Activity activity, String text) {
        ClipboardManager manager = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager != null) manager.setPrimaryClip(ClipData.newPlainText("Mira", text == null ? "" : text));
    }

    private static int keyCodeFor(String key) {
        if ("Backspace".equals(key)) return KeyEvent.KEYCODE_DEL;
        if ("Delete".equals(key)) return KeyEvent.KEYCODE_FORWARD_DEL;
        if ("Enter".equals(key)) return KeyEvent.KEYCODE_ENTER;
        if ("Tab".equals(key)) return KeyEvent.KEYCODE_TAB;
        if ("Escape".equals(key)) return KeyEvent.KEYCODE_ESCAPE;
        if ("ArrowLeft".equals(key)) return KeyEvent.KEYCODE_DPAD_LEFT;
        if ("ArrowRight".equals(key)) return KeyEvent.KEYCODE_DPAD_RIGHT;
        if ("ArrowUp".equals(key)) return KeyEvent.KEYCODE_DPAD_UP;
        if ("ArrowDown".equals(key)) return KeyEvent.KEYCODE_DPAD_DOWN;
        if ("Home".equals(key)) return KeyEvent.KEYCODE_MOVE_HOME;
        if ("End".equals(key)) return KeyEvent.KEYCODE_MOVE_END;
        return 0;
    }

    private interface MainCallable<T> {
        T call();
    }

    private <T> T callOnMainThread(MainCallable<T> callable, T fallback) {
        AtomicReference<T> result = new AtomicReference<>(fallback);
        CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(() -> {
            try {
                result.set(callable.call());
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return fallback;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (Throwable throwable) {
            return fallback;
        }
        return result.get();
    }

    private static View decorRoot(Activity activity) {
        Window window = activity.getWindow();
        return window == null ? null : window.getDecorView();
    }

    private static String describe(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty() ? throwable.getClass().getSimpleName() : message;
    }

    private static final class CaptureGeometry {
        final int sourceWidth;
        final int sourceHeight;
        final int outputWidth;
        final int outputHeight;
        final long capturedAt;

        CaptureGeometry(int sourceWidth, int sourceHeight, int outputWidth, int outputHeight, long capturedAt) {
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.outputWidth = outputWidth;
            this.outputHeight = outputHeight;
            this.capturedAt = capturedAt;
        }
    }

    public static final class CaptureResult {
        public final boolean available;
        public final String error;
        public final byte[] jpeg;
        public final int width;
        public final int height;
        public final int sourceWidth;
        public final int sourceHeight;
        public final long capturedAt;

        private CaptureResult(boolean available, String error, byte[] jpeg, int width, int height, int sourceWidth, int sourceHeight, long capturedAt) {
            this.available = available;
            this.error = error;
            this.jpeg = jpeg;
            this.width = width;
            this.height = height;
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.capturedAt = capturedAt;
        }

        static CaptureResult success(byte[] jpeg, int width, int height, int sourceWidth, int sourceHeight, long capturedAt) {
            return new CaptureResult(true, "", jpeg, width, height, sourceWidth, sourceHeight, capturedAt);
        }

        static CaptureResult error(String error) {
            return new CaptureResult(false, error == null ? "capture failed" : error, null, 0, 0, 0, 0, System.currentTimeMillis());
        }
    }

    public static final class RootSize {
        public final boolean available;
        public final int width;
        public final int height;
        public final String error;

        private RootSize(boolean available, int width, int height, String error) {
            this.available = available;
            this.width = width;
            this.height = height;
            this.error = error;
        }

        static RootSize available(int width, int height) {
            return new RootSize(true, width, height, "");
        }

        static RootSize unavailable(String error) {
            return new RootSize(false, 0, 0, error == null ? "root unavailable" : error);
        }
    }

    public static final class RenderResult {
        public final boolean available;
        public final String error;
        public final int width;
        public final int height;
        public final int sourceWidth;
        public final int sourceHeight;
        public final long capturedAt;

        private RenderResult(boolean available, String error, int width, int height, int sourceWidth, int sourceHeight, long capturedAt) {
            this.available = available;
            this.error = error;
            this.width = width;
            this.height = height;
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.capturedAt = capturedAt;
        }

        static RenderResult success(int width, int height, int sourceWidth, int sourceHeight, long capturedAt) {
            return new RenderResult(true, "", width, height, sourceWidth, sourceHeight, capturedAt);
        }

        static RenderResult error(String error) {
            return new RenderResult(false, error == null ? "render failed" : error, 0, 0, 0, 0, System.currentTimeMillis());
        }
    }

    public static final class InputResult {
        public final boolean ok;
        public final String message;
        public final String text;

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
