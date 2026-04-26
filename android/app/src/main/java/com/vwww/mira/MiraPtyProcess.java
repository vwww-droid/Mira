package com.vwww.mira;

import android.util.Log;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MiraPtyProcess implements MiraPtySession {
    private static final String TAG = "MiraPtyProcess";
    private static final AtomicBoolean NATIVE_LIBRARY_LOADED = new AtomicBoolean(false);
    private static final Object NATIVE_LIBRARY_LOCK = new Object();

    static {
        ensureNativeLibraryLoaded();
    }

    private final long handle;
    private final int pid;
    private final int cellWidth;
    private final int cellHeight;
    private volatile boolean closed;

    static void ensureNativeLibraryLoaded() {
        if (NATIVE_LIBRARY_LOADED.get()) return;
        synchronized (NATIVE_LIBRARY_LOCK) {
            if (NATIVE_LIBRARY_LOADED.get()) return;
            Log.i(TAG, "Loading native library mira_pty");
            System.loadLibrary("mira_pty");
            NATIVE_LIBRARY_LOADED.set(true);
            Log.i(TAG, "Loaded native library mira_pty");
        }
    }

    public MiraPtyProcess(MiraPtyLaunchSpec spec) {
        this(
            spec.getShellPath(),
            spec.getCwd(),
            spec.getArgs(),
            spec.getEnv(),
            spec.getRows(),
            spec.getColumns(),
            spec.getCellWidth(),
            spec.getCellHeight()
        );
    }

    public MiraPtyProcess(String shellPath, String cwd, String[] args, String[] env, int rows, int columns) {
        this(shellPath, cwd, args, env, rows, columns, 0, 0);
    }

    public MiraPtyProcess(String shellPath, String cwd, String[] args, String[] env, int rows, int columns, int cellWidth, int cellHeight) {
        this.cellWidth = Math.max(cellWidth, 0);
        this.cellHeight = Math.max(cellHeight, 0);
        Log.i(TAG, "nativeOpen shell=" + shellPath + " cwd=" + cwd + " rows=" + rows + " cols=" + columns + " args=" + (args == null ? 0 : args.length));
        handle = nativeOpen(shellPath, cwd, args, env, rows, columns, this.cellWidth, this.cellHeight);
        if (handle == 0) throw new IllegalStateException("native PTY open returned null handle");
        Log.i(TAG, "nativeOpen returned handle=" + handle);
        pid = nativePid(handle);
        if (pid <= 0) {
            nativeClose(handle);
            throw new IllegalStateException("native PTY open returned invalid pid");
        }
        Log.i(TAG, "nativePid returned pid=" + pid);
        applyUtf8ModeBestEffort();
        Log.i(TAG, "PTY UTF-8 mode applied pid=" + pid);
    }

    @Override
    public int getPid() {
        return pid;
    }

    @Override
    public int read(byte[] buffer) throws IOException {
        if (closed) return -1;
        return nativeRead(handle, buffer, buffer == null ? 0 : buffer.length);
    }

    @Override
    public void write(byte[] data) throws IOException {
        if (closed) throw new IOException("PTY is closed");
        nativeWrite(handle, data, data == null ? 0 : data.length);
    }

    @Override
    public void resize(int columns, int rows) {
        resize(columns, rows, cellWidth, cellHeight);
    }

    @Override
    public void resize(int columns, int rows, int cellWidth, int cellHeight) {
        if (columns <= 0 || rows <= 0 || closed) return;
        nativeResize(handle, columns, rows, Math.max(cellWidth, 0), Math.max(cellHeight, 0));
        applyUtf8ModeBestEffort();
    }

    @Override
    public void setUtf8Mode() {
        if (closed) return;
        nativeSetUtf8Mode(handle);
    }

    private void applyUtf8ModeBestEffort() {
        try {
            setUtf8Mode();
        } catch (RuntimeException failure) {
            Log.w(TAG, "Unable to refresh PTY UTF-8 mode", failure);
        }
    }

    @Override
    public int waitFor() {
        if (handle == 0) return -1;
        return nativeWaitFor(handle);
    }

    @Override
    public String getBackendName() {
        return "native";
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        nativeClose(handle);
    }

    private static native long nativeOpen(String shellPath, String cwd, String[] args, String[] env, int rows, int columns, int cellWidth, int cellHeight);

    private static native int nativeRead(long handle, byte[] buffer, int length) throws IOException;

    private static native void nativeWrite(long handle, byte[] data, int length) throws IOException;

    private static native void nativeResize(long handle, int columns, int rows, int cellWidth, int cellHeight);

    private static native void nativeSetUtf8Mode(long handle);

    private static native int nativeWaitFor(long handle);

    private static native int nativePid(long handle);

    private static native void nativeKill(long handle, int signalNumber);

    private static native void nativeClose(long handle);
}
