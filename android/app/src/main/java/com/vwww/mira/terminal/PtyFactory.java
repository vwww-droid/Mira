package com.vwww.mira.terminal;

import com.vwww.mira.runtime.RuntimeInstaller;

import android.content.Context;
import android.util.Log;

public final class PtyFactory {
    private static final String TAG = "MiraPtyFactory";

    private PtyFactory() {
    }

    public static PtySession create(Context context, RuntimeInstaller runtimeInstaller, int rows, int columns) {
        return create(context, runtimeInstaller, rows, columns, null);
    }

    static PtySession create(Context context, RuntimeInstaller runtimeInstaller, int rows, int columns, SessionToolbox toolbox) {
        return create(PtyLaunchSpec.of(context, runtimeInstaller, rows, columns, toolbox));
    }

    public static PtySession create(
        Context context,
        RuntimeInstaller runtimeInstaller,
        int rows,
        int columns,
        int cellWidth,
        int cellHeight,
        SessionToolbox toolbox
    ) {
        return create(PtyLaunchSpec.of(context, runtimeInstaller, rows, columns, cellWidth, cellHeight, toolbox));
    }

    static PtySession create(PtyLaunchSpec spec) {
        try {
            Log.i(TAG, "Opening native PTY shell=" + spec.getShellPath() + " cwd=" + spec.getCwd() + " rows=" + spec.getRows() + " cols=" + spec.getColumns() + " args=" + spec.getArgs().length);
            PtySession nativeSession = new NativePtyProcess(spec);
            Log.i(TAG, "Selected backend=" + nativeSession.getBackendName());
            return nativeSession;
        } catch (Throwable throwable) {
            Log.w(TAG, "Native PTY backend failed", throwable);
            if (throwable instanceof RuntimeException) throw (RuntimeException) throwable;
            if (throwable instanceof Error) throw (Error) throwable;
            throw new IllegalStateException("Native PTY backend failed", throwable);
        }
    }

    public static void preloadNativeLibrary() {
        NativePtyProcess.ensureNativeLibraryLoaded();
    }
}
