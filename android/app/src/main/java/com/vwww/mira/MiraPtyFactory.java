package com.vwww.mira;

import android.content.Context;
import android.util.Log;

public final class MiraPtyFactory {
    private static final String TAG = "MiraPtyFactory";

    private MiraPtyFactory() {
    }

    public static MiraPtySession create(Context context, MiraBootstrap bootstrap, int rows, int columns) {
        return create(context, bootstrap, rows, columns, null);
    }

    public static MiraPtySession create(Context context, MiraBootstrap bootstrap, int rows, int columns, MiraToolbox toolbox) {
        return create(MiraPtyLaunchSpec.of(context, bootstrap, rows, columns, toolbox));
    }

    public static MiraPtySession create(
        Context context,
        MiraBootstrap bootstrap,
        int rows,
        int columns,
        int cellWidth,
        int cellHeight,
        MiraToolbox toolbox
    ) {
        return create(MiraPtyLaunchSpec.of(context, bootstrap, rows, columns, cellWidth, cellHeight, toolbox));
    }

    public static MiraPtySession create(MiraPtyLaunchSpec spec) {
        try {
            Log.i(TAG, "Opening native PTY shell=" + spec.getShellPath() + " cwd=" + spec.getCwd() + " rows=" + spec.getRows() + " cols=" + spec.getColumns() + " args=" + spec.getArgs().length);
            MiraPtySession nativeSession = new MiraPtyProcess(spec);
            Log.i(TAG, "Selected backend=" + nativeSession.getBackendName());
            return nativeSession;
        } catch (Throwable throwable) {
            Log.w(TAG, "Native PTY backend failed", throwable);
            if (throwable instanceof RuntimeException) throw (RuntimeException) throwable;
            if (throwable instanceof Error) throw (Error) throwable;
            throw new IllegalStateException("Native PTY backend failed", throwable);
        }
    }
}
