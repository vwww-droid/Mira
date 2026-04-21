package com.vwww.mira;

import android.app.Activity;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class MiraOutlineCollector {
    private static final int MAX_DEPTH = 32;
    private static final int MAX_TEXT_LENGTH = 80;
    private static final long MAIN_THREAD_TIMEOUT_MS = 1500;
    private static final MiraOutlineCollector INSTANCE = new MiraOutlineCollector();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object activityLock = new Object();
    private WeakReference<Activity> activityRef = new WeakReference<>(null);
    private WeakReference<View> rootRef = new WeakReference<>(null);

    private MiraOutlineCollector() {
    }

    public static MiraOutlineCollector getInstance() {
        return INSTANCE;
    }

    public void register(Activity activity) {
        if (activity == null) return;
        View root = decorRoot(activity);
        synchronized (activityLock) {
            activityRef = new WeakReference<>(activity);
            rootRef = new WeakReference<>(root);
        }
    }

    public void unregister(Activity activity) {
        synchronized (activityLock) {
            Activity current = activityRef.get();
            if (activity != null && current != null && current != activity) return;
            activityRef = new WeakReference<>(null);
            rootRef = new WeakReference<>(null);
        }
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
                return unavailableOutline("main thread timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unavailableOutline("interrupted");
        } catch (Exception e) {
            return errorOutline(e);
        }
        JSONObject json = result.get();
        return json == null ? new JSONObject() : json;
    }

    private JSONObject collectOnMainThread() {
        try {
            Activity activity;
            View root;
            synchronized (activityLock) {
                activity = activityRef.get();
                root = rootRef.get();
            }
            if (activity == null || activity.isFinishing()) {
                return new JSONObject()
                    .put("available", false)
                    .put("reason", "activity unavailable");
            }
            if (root == null) root = decorRoot(activity);
            if (root == null) {
                return new JSONObject()
                    .put("available", false)
                    .put("reason", "root unavailable");
            }

            DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            JSONObject outline = new JSONObject();
            outline.put("available", true);
            outline.put("capturedAt", System.currentTimeMillis());
            outline.put("packageName", activity.getPackageName());
            outline.put("activityName", activity.getClass().getName());
            outline.put("screen", new JSONObject()
                .put("width", metrics.widthPixels)
                .put("height", metrics.heightPixels)
                .put("density", metrics.density));
            outline.put("rootBounds", bounds(root));
            outline.put("root", node(root, 0));
            return outline;
        } catch (Exception e) {
            return errorOutline(e);
        }
    }

    private static View decorRoot(Activity activity) {
        Window window = activity.getWindow();
        return window == null ? null : window.getDecorView();
    }

    private JSONObject node(View view, int depth) throws Exception {
        JSONObject json = new JSONObject();
        json.put("className", view.getClass().getName());
        json.put("resourceName", resourceName(view));
        json.put("text", textSummary(view));
        json.put("clickable", view.isClickable());
        json.put("enabled", view.isEnabled());
        json.put("visible", view.getVisibility() == View.VISIBLE && view.isShown());
        json.put("depth", depth);
        json.put("bounds", bounds(view));

        JSONArray children = new JSONArray();
        if (view instanceof ViewGroup && depth < MAX_DEPTH) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child != null) children.put(node(child, depth + 1));
            }
        }
        json.put("children", children);
        return json;
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

    private static String textSummary(View view) {
        if (!(view instanceof TextView)) return "";
        CharSequence value = ((TextView) view).getText();
        if (value == null) return "";
        String text = value.toString().replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) return "";
        text = text.replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "[email]");
        text = text.replaceAll("https?://\\S+", "[url]");
        text = text.replaceAll("\\b(?:\\d[ -]?){8,}\\b", "[number]");
        if (text.length() > MAX_TEXT_LENGTH) text = text.substring(0, MAX_TEXT_LENGTH) + "…";
        return text;
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

    private static JSONObject errorOutline(Exception e) {
        try {
            return new JSONObject()
                .put("available", false)
                .put("reason", e.getClass().getSimpleName());
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }
}
