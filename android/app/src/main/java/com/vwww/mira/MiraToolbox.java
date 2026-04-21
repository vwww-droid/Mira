package com.vwww.mira;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MiraToolbox implements Closeable {
    private static final String TAG = "MiraToolbox";
    private static final String ASSET_ROOT = "toolbox/busybox";
    private static final String APPLETS_ASSET = "toolbox/applets.txt";
    private static final String MANIFEST_ASSET = "toolbox/manifest.json";

    private final File sessionDir;
    private final File binDir;
    private final File busyboxFile;
    private final File manifestFile;
    private final String busyboxAbi;
    private final String busyboxAssetPath;

    private MiraToolbox(
        File sessionDir,
        File binDir,
        File busyboxFile,
        File manifestFile,
        String busyboxAbi,
        String busyboxAssetPath
    ) {
        this.sessionDir = sessionDir;
        this.binDir = binDir;
        this.busyboxFile = busyboxFile;
        this.manifestFile = manifestFile;
        this.busyboxAbi = busyboxAbi;
        this.busyboxAssetPath = busyboxAssetPath;
    }

    public static MiraToolbox prepare(Context context, String sessionId) throws IOException {
        Context appContext = context.getApplicationContext();
        String safeSessionId = safeName(sessionId == null || sessionId.isEmpty() ? "local" : sessionId);
        File sessionRoot = new File(new File(appContext.getCacheDir(), "mira-sessions"), safeSessionId);
        deleteRecursively(sessionRoot);
        File binDir = new File(sessionRoot, "bin");
        mkdir(binDir);

        BusyBoxAsset asset = selectBusyBoxAsset(appContext.getAssets());
        File busyboxFile = new File(binDir, "busybox");
        copyAsset(appContext.getAssets(), asset.assetPath, busyboxFile);
        chmodExecutable(busyboxFile);
        installApplets(busyboxFile, binDir, loadApplets(appContext.getAssets()));

        File manifestFile = new File(sessionRoot, "toolbox-manifest.json");
        copyAsset(appContext.getAssets(), MANIFEST_ASSET, manifestFile);

        Log.i(TAG, "Prepared session toolbox " + asset.assetPath + " -> " + binDir.getAbsolutePath());
        return new MiraToolbox(sessionRoot, binDir, busyboxFile, manifestFile, asset.abi, asset.assetPath);
    }

    public String pathPrefix() {
        return binDir.getAbsolutePath();
    }

    public String busyboxPath() {
        return busyboxFile.getAbsolutePath();
    }

    public String manifestPath() {
        return manifestFile == null ? "" : manifestFile.getAbsolutePath();
    }

    public String busyboxAbi() {
        return busyboxAbi;
    }

    public String busyboxAssetPath() {
        return busyboxAssetPath;
    }

    @Override
    public void close() {
        deleteRecursively(sessionDir);
    }

    private static void installApplets(File busyboxFile, File binDir, List<String> applets) throws IOException {
        for (String applet : applets) {
            File link = new File(binDir, applet);
            if (link.exists() && !link.delete()) throw new IOException("无法替换 applet: " + link.getAbsolutePath());
            try {
                Os.symlink("busybox", link.getAbsolutePath());
            } catch (ErrnoException symlinkFailed) {
                writeWrapper(link, busyboxFile, applet);
            }
        }
    }

    private static BusyBoxAsset selectBusyBoxAsset(AssetManager assets) throws IOException {
        String[] abis = Build.SUPPORTED_ABIS == null ? new String[0] : Build.SUPPORTED_ABIS;
        for (String abi : abis) {
            String asset = assetForAbi(abi);
            if (asset != null && assetExists(assets, asset)) return new BusyBoxAsset(abi, asset);
        }
        throw new IOException("未找到适配当前 ABI 的 busybox 资产");
    }

    private static String assetForAbi(String abi) {
        if (abi == null) return null;
        String normalized = abi.toLowerCase(Locale.ROOT);
        if ("arm64-v8a".equals(normalized)) return ASSET_ROOT + "/arm64-v8a/busybox";
        if ("armeabi-v7a".equals(normalized) || "armeabi".equals(normalized)) return ASSET_ROOT + "/armeabi-v7a/busybox";
        if ("x86_64".equals(normalized)) return ASSET_ROOT + "/x86_64/busybox";
        if ("x86".equals(normalized)) return ASSET_ROOT + "/x86/busybox";
        return null;
    }

    private static List<String> loadApplets(AssetManager assets) throws IOException {
        List<String> applets = new ArrayList<>();
        try (
            InputStream input = assets.open(APPLETS_ASSET);
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))
        ) {
            String line;
            while ((line = reader.readLine()) != null) {
                String applet = line.trim();
                if (applet.isEmpty() || applet.startsWith("#")) continue;
                applets.add(applet);
            }
        }
        if (applets.isEmpty()) throw new IOException("toolbox applet 清单为空");
        return applets;
    }

    private static boolean assetExists(AssetManager assets, String path) {
        try (InputStream ignored = assets.open(path)) {
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void copyAsset(AssetManager assets, String assetPath, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null) mkdir(parent);
        try (InputStream input = assets.open(assetPath); FileOutputStream output = new FileOutputStream(destination, false)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    private static void writeWrapper(File file, File busyboxFile, String applet) throws IOException {
        String script = "#!/system/bin/sh\n" +
            "exec " + quote(busyboxFile.getAbsolutePath()) + " " + applet + " \"$@\"\n";
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(script.getBytes("UTF-8"));
        }
        chmodExecutable(file);
    }

    private static void chmodExecutable(File file) throws IOException {
        if (!file.setReadable(true, true)) throw new IOException("无法设置可读权限: " + file.getAbsolutePath());
        if (!file.setWritable(true, true)) throw new IOException("无法设置可写权限: " + file.getAbsolutePath());
        if (!file.setExecutable(true, true)) throw new IOException("无法设置可执行权限: " + file.getAbsolutePath());
    }

    private static void mkdir(File directory) throws IOException {
        if (directory.isDirectory()) return;
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("无法创建目录: " + directory.getAbsolutePath());
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        if (!file.delete() && file.exists()) {
            Log.w(TAG, "Unable to delete " + file.getAbsolutePath());
        }
    }

    private static String safeName(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static final class BusyBoxAsset {
        final String abi;
        final String assetPath;

        BusyBoxAsset(String abi, String assetPath) {
            this.abi = abi;
            this.assetPath = assetPath;
        }
    }
}
