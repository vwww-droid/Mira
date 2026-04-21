package com.termux.terminal;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;

/**
 * Mira 对 Termux terminal-emulator 的最小 PTY 封装。
 *
 * 这个类放在 com.termux.terminal 包内, 是为了复用 terminal-emulator 里的包内 JNI。
 */
public final class MiraPtyProcess implements Closeable {
    private final int fd;
    private final int pid;
    private final FileDescriptor descriptor;
    private final ParcelFileDescriptor parcelDescriptor;
    private final FileInputStream input;
    private final FileOutputStream output;
    private volatile boolean closed;

    public MiraPtyProcess(String shellPath, String cwd, String[] args, String[] env, int rows, int columns) {
        int[] processId = new int[1];
        fd = JNI.createSubprocess(shellPath, cwd, args, env, processId, rows, columns, 0, 0);
        pid = processId[0];
        WrappedDescriptor wrappedDescriptor = wrapFileDescriptor(fd);
        descriptor = wrappedDescriptor.fileDescriptor;
        parcelDescriptor = wrappedDescriptor.parcelFileDescriptor;
        input = new FileInputStream(descriptor);
        output = new FileOutputStream(descriptor);
    }

    public int getPid() {
        return pid;
    }

    public int read(byte[] buffer) throws IOException {
        return input.read(buffer);
    }

    public void write(byte[] data) throws IOException {
        output.write(data);
        output.flush();
    }

    public void resize(int columns, int rows) {
        if (columns <= 0 || rows <= 0 || closed) return;
        JNI.setPtyWindowSize(fd, rows, columns, 0, 0);
    }

    public int waitFor() {
        return JNI.waitFor(pid);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            Os.kill(pid, OsConstants.SIGHUP);
        } catch (ErrnoException ignored) {
        }
        closeQuietly(input);
        closeQuietly(output);
        closeQuietly(parcelDescriptor);
        try {
            JNI.close(fd);
        } catch (Throwable ignored) {
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    private static WrappedDescriptor wrapFileDescriptor(int rawFd) {
        try {
            FileDescriptor descriptor = new FileDescriptor();
            Field field = FileDescriptor.class.getDeclaredField("descriptor");
            field.setAccessible(true);
            field.set(descriptor, rawFd);
            return new WrappedDescriptor(descriptor, null);
        } catch (Throwable reflectionFailed) {
            ParcelFileDescriptor parcelFileDescriptor = ParcelFileDescriptor.adoptFd(rawFd);
            return new WrappedDescriptor(parcelFileDescriptor.getFileDescriptor(), parcelFileDescriptor);
        }
    }

    private static final class WrappedDescriptor {
        final FileDescriptor fileDescriptor;
        final ParcelFileDescriptor parcelFileDescriptor;

        WrappedDescriptor(FileDescriptor fileDescriptor, ParcelFileDescriptor parcelFileDescriptor) {
            this.fileDescriptor = fileDescriptor;
            this.parcelFileDescriptor = parcelFileDescriptor;
        }
    }
}
