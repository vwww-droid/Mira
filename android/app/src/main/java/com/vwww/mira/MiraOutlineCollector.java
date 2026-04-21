package com.vwww.mira;

import android.app.Activity;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class MiraOutlineCollector {
    private static final int MAX_DEPTH = 32;
    private static final int MAX_NODES = 900;
    private static final int MAX_TEXT_LENGTH = 80;
    private static final long MAIN_THREAD_TIMEOUT_MS = 1500;
    private static final long LAYOUT_UPLOAD_DEBOUNCE_MS = 450;
    private static final MiraOutlineCollector INSTANCE = new MiraOutlineCollector();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object activityLock = new Object();
    private WeakReference<Activity> activityRef = new WeakReference<>(null);
    private WeakReference<View> rootRef = new WeakReference<>(null);
    private WeakReference<View> watchedRootRef = new WeakReference<>(null);
    private ViewTreeObserver.OnGlobalLayoutListener layoutListener;
    private JSONObject lastOutline;
    private long lastLayoutUploadAt;

    private MiraOutlineCollector() {
    }

    public static MiraOutlineCollector getInstance() {
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
        installLayoutWatcher(root);
        requestOutlineUpload(root, 120);
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
        }
        removeLayoutWatcher();
    }

    public JSONObject currentOutline() {
        if (Looper.myLooper() == Looper.getMainLooper()) return collectOnMainThread();

        AtomicReference<JSONObject> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(() -> {
            try {
                result.set(collectOnMainThread());
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return fallbackOutline("main thread timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallbackOutline("interrupted");
        } catch (Exception e) {
            return errorOutline(e);
        }
        JSONObject json = result.get();
        return json == null ? fallbackOutline("empty outline") : json;
    }

    private JSONObject collectOnMainThread() {
        try {
            Activity activity;
            View root;
            synchronized (activityLock) {
                activity = activityRef.get();
                root = rootRef.get();
            }
            if (activity == null || activity.isFinishing()) return fallbackOutline("activity unavailable");
            if (root == null) root = decorRoot(activity);
            if (root == null) return fallbackOutline("root unavailable");

            DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            JSONArray nodes = new JSONArray();
            int[] nodeCount = new int[] {0};
            JSONObject rootNode = node(root, 0, "0", nodes, nodeCount);
            JSONObject outline = new JSONObject();
            outline.put("available", true);
            outline.put("schema", "mira.view-bounds.v1");
            outline.put("capturedAt", System.currentTimeMillis());
            outline.put("packageName", activity.getPackageName());
            outline.put("activityName", activity.getClass().getName());
            outline.put("screen", new JSONObject()
                .put("width", metrics.widthPixels)
                .put("height", metrics.heightPixels)
                .put("density", metrics.density));
            outline.put("rootBounds", bounds(root));
            outline.put("root", rootNode);
            outline.put("nodes", nodes);
            outline.put("nodeCount", nodes.length());
            cacheOutline(outline);
            return outline;
        } catch (Exception e) {
            return errorOutline(e);
        }
    }

    private static View decorRoot(Activity activity) {
        Window window = activity.getWindow();
        return window == null ? null : window.getDecorView();
    }

    private JSONObject node(View view, int depth, String path, JSONArray flatNodes, int[] nodeCount) throws Exception {
        JSONObject json = viewMeta(view, depth, path);
        if (shouldInclude(flatNodes, json, nodeCount)) {
            flatNodes.put(viewMeta(view, depth, path));
            nodeCount[0]++;
        }

        JSONArray children = new JSONArray();
        if (view instanceof ViewGroup && depth < MAX_DEPTH && nodeCount[0] < MAX_NODES) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount() && nodeCount[0] < MAX_NODES; i++) {
                View child = group.getChildAt(i);
                if (child != null) children.put(node(child, depth + 1, path + "." + i, flatNodes, nodeCount));
            }
        }
        json.put("children", children);
        return json;
    }

    private JSONObject viewMeta(View view, int depth, String path) throws Exception {
        JSONObject json = new JSONObject();
        JSONObject visibleBounds = visibleBounds(view);
        json.put("path", path);
        json.put("className", view.getClass().getName());
        json.put("simpleClass", view.getClass().getSimpleName());
        json.put("role", role(view));
        json.put("resourceName", resourceName(view));
        json.put("text", textSummary(view));
        json.put("contentDescription", contentDescriptionSummary(view));
        json.put("clickable", view.isClickable());
        json.put("enabled", view.isEnabled());
        json.put("focused", view.isFocused());
        json.put("selected", view.isSelected());
        json.put("visible", view.getVisibility() == View.VISIBLE && view.isShown());
        json.put("alpha", view.getAlpha());
        json.put("depth", depth);
        json.put("bounds", bounds(view));
        json.put("visibleBounds", visibleBounds == null ? JSONObject.NULL : visibleBounds);
        return json;
    }

    private static boolean shouldInclude(JSONArray flatNodes, JSONObject json, int[] nodeCount) {
        if (flatNodes.length() >= MAX_NODES || nodeCount[0] >= MAX_NODES) return false;
        if (!json.optBoolean("visible", false)) return false;
        JSONObject bounds = json.optJSONObject("bounds");
        if (bounds == null || bounds.optInt("width", 0) <= 0 || bounds.optInt("height", 0) <= 0) return false;
        return true;
    }

    private static JSONObject bounds(View view) throws Exception {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return new JSONObject()
            .put("x", location[0])
            .put("y", location[1])
            .put("width", view.getWidth())
            .put("height", view.getHeight())
            .put("left", location[0])
            .put("top", location[1])
            .put("right", location[0] + view.getWidth())
            .put("bottom", location[1] + view.getHeight())
            .put("visible", visibleBounds(view));
    }

    private static JSONObject visibleBounds(View view) throws Exception {
        Rect rect = new Rect();
        boolean hasVisibleBounds = view.getGlobalVisibleRect(rect);
        if (!hasVisibleBounds) return null;
        return new JSONObject()
            .put("x", rect.left)
            .put("y", rect.top)
            .put("left", rect.left)
            .put("top", rect.top)
            .put("right", rect.right)
            .put("bottom", rect.bottom)
            .put("width", rect.width())
            .put("height", rect.height());
    }

    private static String resourceName(View view) {
        int id = view.getId();
        if (id == View.NO_ID) return "";
        try {
            Resources resources = view.getResources();
            return resources == null ? "" : resources.getResourceName(id);
        } catch (Resources.NotFoundException ignored) {
            return "";
        }
    }

    private static String role(View view) {
        if (view instanceof EditText) return "input";
        if (view instanceof Button) return "button";
        if (view instanceof TextView) return "text";
        if (view instanceof ImageView) return "image";
        if (view instanceof ViewGroup) return "group";
        return "view";
    }

    private static String textSummary(View view) {
        if (!(view instanceof TextView)) return "";
        CharSequence value = ((TextView) view).getText();
        return sanitizeText(value);
    }

    private static String contentDescriptionSummary(View view) {
        return sanitizeText(view.getContentDescription());
    }

    private static String sanitizeText(CharSequence value) {
        if (value == null) return "";
        String text = value.toString().replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) return "";
        text = text.replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "[email]");
        text = text.replaceAll("https?://\\S+", "[url]");
        text = text.replaceAll("\\b(?:\\d[ -]?){8,}\\b", "[number]");
        if (text.length() > MAX_TEXT_LENGTH) text = text.substring(0, MAX_TEXT_LENGTH) + "…";
        return text;
    }

    private void installLayoutWatcher(View root) {
        if (root == null) return;
        View watchedRoot = watchedRootRef.get();
        if (watchedRoot == root && layoutListener != null) return;
        removeLayoutWatcher();
        layoutListener = () -> {
            long now = SystemClock.uptimeMillis();
            if (now - lastLayoutUploadAt < LAYOUT_UPLOAD_DEBOUNCE_MS) return;
            lastLayoutUploadAt = now;
            requestOutlineUpload(root, 80);
        };
        watchedRootRef = new WeakReference<>(root);
        ViewTreeObserver observer = root.getViewTreeObserver();
        if (observer != null && observer.isAlive()) observer.addOnGlobalLayoutListener(layoutListener);
    }

    private void removeLayoutWatcher() {
        View root = watchedRootRef.get();
        ViewTreeObserver.OnGlobalLayoutListener listener = layoutListener;
        layoutListener = null;
        watchedRootRef = new WeakReference<>(null);
        if (root == null || listener == null) return;
        ViewTreeObserver observer = root.getViewTreeObserver();
        if (observer != null && observer.isAlive()) observer.removeOnGlobalLayoutListener(listener);
    }

    private static void requestOutlineUpload(View root, long delayMs) {
        if (root == null) return;
        root.postDelayed(MiraDiscoveryService::requestOutlineUpload, delayMs);
    }

    private void cacheOutline(JSONObject outline) {
        try {
            synchronized (activityLock) {
                lastOutline = new JSONObject(outline.toString());
            }
        } catch (Exception ignored) {
        }
    }

    private JSONObject fallbackOutline(String reason) {
        synchronized (activityLock) {
            if (lastOutline != null) {
                try {
                    JSONObject cached = new JSONObject(lastOutline.toString());
                    cached.put("stale", true);
                    cached.put("staleReason", reason);
                    return cached;
                } catch (Exception ignored) {
                }
            }
        }
        return unavailableOutline(reason);
    }

    private static JSONObject unavailableOutline(String reason) {
        try {
            return new JSONObject()
                .put("available", false)
                .put("reason", reason);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private JSONObject errorOutline(Exception e) {
        synchronized (activityLock) {
            if (lastOutline != null) {
                try {
                    JSONObject cached = new JSONObject(lastOutline.toString());
                    cached.put("stale", true);
                    cached.put("staleReason", e.getClass().getSimpleName());
                    return cached;
                } catch (Exception ignored) {
                }
            }
        }
        try {
            return new JSONObject()
                .put("available", false)
                .put("reason", e.getClass().getSimpleName());
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }
}
