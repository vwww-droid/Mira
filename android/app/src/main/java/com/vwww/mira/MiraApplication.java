package com.vwww.mira;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import java.util.concurrent.atomic.AtomicBoolean;

public final class MiraApplication extends Application {
    private static final String TAG = "MiraApplication";
    private static final AtomicBoolean BOOTSTRAP_INSTALL_SCHEDULED = new AtomicBoolean(false);
    private static final AtomicBoolean DYNAMIC_LOAD_COMPLETED = new AtomicBoolean(false);
    private static final AtomicBoolean DYNAMIC_LOAD_SCHEDULED = new AtomicBoolean(false);
    private static final Object DYNAMIC_LOAD_LOCK = new Object();

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            MiraPtyProcess.ensureNativeLibraryLoaded();
        } catch (Throwable t) {
            Log.w(TAG, "mira_pty preload failed: " + t.getMessage(), t);
        }
        scheduleBootstrapInstall();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                registerVisibleActivity(activity);
            }

            @Override
            public void onActivityStarted(Activity activity) {
                registerVisibleActivity(activity);
            }

            @Override
            public void onActivityResumed(Activity activity) {
                registerVisibleActivity(activity);
            }

            @Override
            public void onActivityPaused(Activity activity) {
            }

            @Override
            public void onActivityStopped(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
                MiraOutlineCollector.getInstance().unregister(activity);
            }
        });
    }

    static boolean ensureDynamicLibraryLoaded() {
        if (DYNAMIC_LOAD_COMPLETED.get()) return true;
        synchronized (DYNAMIC_LOAD_LOCK) {
            if (DYNAMIC_LOAD_COMPLETED.get()) return true;
            long startedAt = SystemClock.elapsedRealtime();
            try {
                System.loadLibrary("dynamic");
                DYNAMIC_LOAD_COMPLETED.set(true);
                Log.i(TAG, "libdynamic.so loaded successfully in " + (SystemClock.elapsedRealtime() - startedAt) + "ms");
                return true;
            } catch (UnsatisfiedLinkError e) {
                Log.w(TAG, "libdynamic.so not available: " + e.getMessage());
            } catch (Throwable t) {
                Log.w(TAG, "libdynamic.so load failed: " + t.getMessage(), t);
            }
            return false;
        }
    }

    private void registerVisibleActivity(Activity activity) {
        MiraOutlineCollector.getInstance().register(activity);
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (decor != null) decor.postDelayed(MiraDiscoveryService::requestOutlineUpload, 160);
        scheduleDynamicLibraryLoad();
    }

    private void scheduleDynamicLibraryLoad() {
        if (DYNAMIC_LOAD_COMPLETED.get()) return;
        if (!DYNAMIC_LOAD_SCHEDULED.compareAndSet(false, true)) return;
        Thread loader = new Thread(() -> {
            SystemClock.sleep(900);
            ensureDynamicLibraryLoaded();
        }, "MiraFridaLoader");
        loader.setDaemon(true);
        loader.start();
    }

    private void scheduleBootstrapInstall() {
        if (!BOOTSTRAP_INSTALL_SCHEDULED.compareAndSet(false, true)) return;
        Log.i(TAG, "Scheduling bootstrap install on background thread");
        Thread installer = new Thread(() -> {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            } catch (Throwable t) {
                Log.w(TAG, "Unable to lower bootstrap installer priority: " + t.getMessage(), t);
            }
            long startedAt = SystemClock.elapsedRealtime();
            try {
                new MiraBootstrap(this).installIfNeeded();
                Log.i(TAG, "Background bootstrap install finished in " + (SystemClock.elapsedRealtime() - startedAt) + "ms");
            } catch (Throwable t) {
                Log.w(TAG, "Background bootstrap install failed after " + (SystemClock.elapsedRealtime() - startedAt) + "ms: " + t.getMessage(), t);
            }
        }, "MiraBootstrapInit");
        installer.setDaemon(true);
        installer.start();
    }
}
