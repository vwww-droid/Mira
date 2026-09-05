package com.vwww.mira;

import android.content.Context;
import android.content.res.AssetManager;
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

public final class BootstrapInstallTest {
    static final String PYTHON = "lib/python3.14";
    static final String FRIDA = PYTHON + "/site-packages/_frida.abi3.so";
    static final String PAYLOAD = "unique-runtime-payload-for-crc-test";
    static void zip(File file) throws Exception {
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            for (String name : new String[] {FRIDA, "bin/python3", "bin/pip", "bin/frida-official",
                "bin/mira-frida-agent.py",
                PYTHON + "/site-packages/frida/__init__.py",
                PYTHON + "/site-packages/pip/__init__.py",
                PYTHON + "/zipfile/_path/__init__.py"}) {
                byte[] data = (name.equals(FRIDA) ? PAYLOAD : "content").getBytes("UTF-8");
                CRC32 crc = new CRC32(); crc.update(data);
                ZipEntry entry = new ZipEntry("assets/bootstrap/prefix/arm64-v8a/" + name);
                entry.setMethod(ZipEntry.STORED); entry.setSize(data.length); entry.setCrc(crc.getValue());
                out.putNextEntry(entry); out.write(data); out.closeEntry();
            }
        }
    }
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]); root.mkdirs();
        File apk = new File(root, "test.apk"); zip(apk);
        byte[] bytes = Files.readAllBytes(apk.toPath());
        byte[] match = PAYLOAD.getBytes("UTF-8");
        int offset = -1;
        outer: for (int i = 0; i <= bytes.length - match.length; i++) {
            for (int j = 0; j < match.length; j++) if (bytes[i+j] != match[j]) continue outer;
            offset = i; break;
        }
        check(offset >= 0); bytes[offset] ^= 1; Files.write(apk.toPath(), bytes);
        File files = new File(root, "files"); files.mkdirs();
        File home = new File(files, "home"); home.mkdirs();
        File profile = new File(home, ".profile"); Files.write(profile.toPath(), "user profile".getBytes());
        File old = new File(files, "usr/" + FRIDA); old.getParentFile().mkdirs();
        Files.write(old.toPath(), "previous file".getBytes());
        File state = new File(files, ".mira-bootstrap-state");
        Files.write(state.toPath(), "# Managed by MiraBootstrap\nversion=8\n".getBytes());
        Context context = new Context() {
            public Context getApplicationContext() { return this; }
            public AssetManager getAssets() { return new AssetManager(); }
            public File getFilesDir() { return files; }
            public File getCacheDir() { return new File(root, "cache"); }
            public String getPackageCodePath() { return apk.getAbsolutePath(); }
        };
        MiraBootstrap bootstrap = new MiraBootstrap(context);
        boolean failed = false;
        try { bootstrap.installIfNeeded(); } catch (IOException expected) { failed = true; }
        check(failed); check(!state.exists());
        check(new String(Files.readAllBytes(old.toPath())).equals("previous file"));
        zip(apk); bootstrap.installIfNeeded();
        check(state.isFile());
        check(new String(Files.readAllBytes(old.toPath())).equals(PAYLOAD));
        check(new String(Files.readAllBytes(profile.toPath())).equals("user profile"));
        check(!new File(files, ".mira-extract-.mira-bootstrap-state").exists());
        System.out.println("PASS: actual bootstrap invalidates stale state, rejects corrupt ZIP, recovers, preserves home");
    }
}
