package com.vwww.mira;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mira 最小 bootstrap, 用于创建 APK 私有沙盒里的类 Termux 用户空间骨架。
 *
 * 当前只提供 files/usr/bin/sh wrapper, 不安装 apt 或完整 Termux 包。
 */
public final class MiraBootstrap {
    private static final String TAG = "MiraBootstrap";
    private static final String MANAGED_MARKER = "# Managed by MiraBootstrap";
    private static final String BOOTSTRAP_PREFIX_ASSET_ROOT = "bootstrap/prefix";
    private static final String INSTALL_STATE_FILE_NAME = ".mira-bootstrap-state";
    private static final int INSTALL_STATE_VERSION = 3;
    private static final AtomicBoolean INSTALL_COMPLETED = new AtomicBoolean(false);
    private static final Object INSTALL_LOCK = new Object();

    private final File filesDir;
    private final File prefixDir;
    private final File homeDir;
    private final File tmpDir;
    private final File installStateFile;
    private final AssetManager assets;

    public MiraBootstrap(Context context) {
        Context appContext = context.getApplicationContext();
        assets = appContext.getAssets();
        filesDir = appContext.getFilesDir();
        prefixDir = new File(filesDir, "usr");
        homeDir = new File(filesDir, "home");
        tmpDir = new File(appContext.getCacheDir(), "tmp");
        installStateFile = new File(filesDir, INSTALL_STATE_FILE_NAME);
    }

    public void installIfNeeded() throws IOException {
        if (INSTALL_COMPLETED.get()) {
            ensureRuntimeDirectories();
            return;
        }
        synchronized (INSTALL_LOCK) {
            if (INSTALL_COMPLETED.get()) {
                ensureRuntimeDirectories();
                return;
            }
            if (isBootstrapCurrent()) {
                ensureRuntimeDirectories();
                INSTALL_COMPLETED.set(true);
                Log.i(TAG, "Bootstrap already installed, skip reinstall");
                return;
            }
            long startedAt = SystemClock.elapsedRealtime();
            Log.i(TAG, "Bootstrap install begin prefix=" + prefixDir.getAbsolutePath());
            try {
                ensureRuntimeDirectories();
                installBootstrapPrefixIfAvailable();
                mkdir(new File(prefixDir, "bin"));
                mkdir(new File(prefixDir, "etc"));
                mkdir(new File(prefixDir, "etc/profile.d"));
                mkdir(new File(prefixDir, "tmp"));
                mkdir(new File(prefixDir, "var"));
                mkdir(new File(prefixDir, "share"));

                writeManagedExecutable(new File(prefixDir, "bin/sh"), shellWrapper(), "export MIRA_SANDBOX=1", "export ENV=\"$HOME/.profile\"");
                writeManagedExecutable(new File(prefixDir, "bin/mira-info"), miraInfoScript(), "echo \"Mira sandbox\"");
                writeManagedExecutable(new File(prefixDir, "bin/frida"), fridaWrapperScript());
                writeMiraCommandWrappers();
                writeManagedText(new File(prefixDir, "etc/profile.d/mira-env.sh"), profileHookScript());
                writeManagedText(new File(prefixDir, "etc/profile"), profileScript(), "# Mira minimal profile");
                writeManagedText(new File(homeDir, ".profile"), homeProfileScript(), "# Mira shell profile");
                writeInstallState();
                INSTALL_COMPLETED.set(true);
                Log.i(TAG, "Bootstrap install complete in " + (SystemClock.elapsedRealtime() - startedAt) + "ms");
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Bootstrap install failed after " + (SystemClock.elapsedRealtime() - startedAt) + "ms: " + e.getMessage(), e);
                throw e;
            }
        }
    }

    public File getPrefixDir() {
        return prefixDir;
    }

    public File getHomeDir() {
        return homeDir;
    }

    public File getTmpDir() {
        return tmpDir;
    }

    public File getShellPath() {
        return new File(prefixDir, "bin/sh");
    }

    private void mkdir(File directory) throws IOException {
        if (directory.isDirectory()) return;
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("无法创建目录: " + directory.getAbsolutePath());
        }
    }

    private void ensureRuntimeDirectories() throws IOException {
        mkdir(prefixDir);
        mkdir(homeDir);
        mkdir(tmpDir);
    }

    private boolean isBootstrapCurrent() throws IOException {
        if (!installStateFile.isFile()) return false;
        String state = readText(installStateFile);
        if (!state.contains("version=" + INSTALL_STATE_VERSION)) return false;
        return prefixDir.isDirectory()
            && homeDir.isDirectory()
            && new File(prefixDir, "bin/sh").isFile()
            && new File(prefixDir, "bin/frida").isFile()
            && new File(prefixDir, "bin/python3").isFile()
            && new File(prefixDir, "bin/frida-official").isFile()
            && new File(prefixDir, "etc/profile").isFile()
            && new File(homeDir, ".profile").isFile();
    }

    private void writeInstallState() throws IOException {
        writeText(installStateFile, MANAGED_MARKER + "\nversion=" + INSTALL_STATE_VERSION + "\n");
    }

    private void writeExecutable(File file, String content) throws IOException {
        writeText(file, content);
        if (!file.setReadable(true, true)) throw new IOException("无法设置可读权限: " + file.getAbsolutePath());
        if (!file.setWritable(true, true)) throw new IOException("无法设置可写权限: " + file.getAbsolutePath());
        if (!file.setExecutable(true, true)) throw new IOException("无法设置可执行权限: " + file.getAbsolutePath());
    }

    private void writeManagedExecutable(File file, String content, String... legacyMarkers) throws IOException {
        if (!shouldWriteManagedFile(file, legacyMarkers)) return;
        writeExecutable(file, content);
    }

    private void writeManagedText(File file, String content, String... legacyMarkers) throws IOException {
        if (!shouldWriteManagedFile(file, legacyMarkers)) return;
        writeText(file, content);
    }

    private void writeText(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) mkdir(parent);
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private boolean shouldWriteManagedFile(File file, String... legacyMarkers) throws IOException {
        if (!file.exists()) return true;
        String existing = readText(file);
        if (existing.contains(MANAGED_MARKER)) return true;
        if (legacyMarkers != null) {
            for (String marker : legacyMarkers) {
                if (marker != null && !marker.isEmpty() && existing.contains(marker)) return true;
            }
        }
        Log.i(TAG, "Preserve existing non-managed file " + file.getAbsolutePath());
        return false;
    }

    private String readText(File file) throws IOException {
        byte[] buffer = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < buffer.length) {
                int read = input.read(buffer, offset, buffer.length - offset);
                if (read < 0) break;
                offset += read;
            }
            return new String(buffer, 0, offset, StandardCharsets.UTF_8);
        }
    }

    private String shellWrapper() {
        String prefix = quote(prefixDir.getAbsolutePath());
        String home = quote(homeDir.getAbsolutePath());
        String tmp = quote(tmpDir.getAbsolutePath());
        return "#!/system/bin/sh\n" +
            MANAGED_MARKER + "\n" +
            "export PREFIX=" + prefix + "\n" +
            "export TERMUX_PREFIX=\"$PREFIX\"\n" +
            "export HOME=" + home + "\n" +
            "export TERMUX_HOME=\"$HOME\"\n" +
            "export TMPDIR=" + tmp + "\n" +
            "export LD_LIBRARY_PATH=\"$PREFIX/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}\"\n" +
            "MIRA_BASE_PATH=\"$PREFIX/bin:/system/bin:/system/xbin\"\n" +
            "if [ -n \"$MIRA_PATH_PREFIX\" ]; then\n" +
            "  export PATH=\"$MIRA_PATH_PREFIX:$MIRA_BASE_PATH\"\n" +
            "else\n" +
            "  export PATH=\"$MIRA_BASE_PATH\"\n" +
            "fi\n" +
            "export TERM=\"${TERM:-xterm-256color}\"\n" +
            "export COLORTERM=\"${COLORTERM:-truecolor}\"\n" +
            "export MIRA_SANDBOX=1\n" +
            "export MIRA_PREFIX=\"$PREFIX\"\n" +
            "export SHELL=\"$PREFIX/bin/sh\"\n" +
            "export ENV=\"$HOME/.profile\"\n" +
            "exec /system/bin/sh \"$@\"\n";
    }

    private String profileScript() {
        return MANAGED_MARKER + "\n" +
            "# Mira minimal profile\n" +
            "if [ -d \"$PREFIX/etc/profile.d\" ]; then\n" +
            "  for mira_profile in \"$PREFIX\"/etc/profile.d/*.sh; do\n" +
            "    [ -f \"$mira_profile\" ] && . \"$mira_profile\"\n" +
            "  done\n" +
            "fi\n";
    }

    private String profileHookScript() {
        return MANAGED_MARKER + "\n" +
            "MIRA_BASE_PATH=\"$PREFIX/bin:/system/bin:/system/xbin\"\n" +
            "if [ -n \"$MIRA_PATH_PREFIX\" ]; then\n" +
            "  export PATH=\"$MIRA_PATH_PREFIX:$MIRA_BASE_PATH\"\n" +
            "else\n" +
            "  export PATH=\"$MIRA_BASE_PATH\"\n" +
            "fi\n" +
            "export TERMUX_PREFIX=\"$PREFIX\"\n" +
            "export TERMUX_HOME=\"$HOME\"\n" +
            "export LD_LIBRARY_PATH=\"$PREFIX/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}\"\n" +
            "export MIRA_SANDBOX=1\n";
    }

    private String homeProfileScript() {
        return MANAGED_MARKER + "\n" +
            "# Mira shell profile\n" +
            "[ -n \"$PREFIX\" ] && [ -f \"$PREFIX/etc/profile\" ] && . \"$PREFIX/etc/profile\"\n" +
            "alias am='mira-am'\n" +
            "export PS1='mira $ '\n";
    }

    private String miraInfoScript() {
        return "#!/system/bin/sh\n" +
            MANAGED_MARKER + "\n" +
            "echo \"Mira sandbox\"\n" +
            "echo \"PREFIX=$PREFIX\"\n" +
            "echo \"HOME=$HOME\"\n" +
            "echo \"TMPDIR=$TMPDIR\"\n" +
            "echo \"SHELL=$SHELL\"\n";
    }

    private String fridaWrapperScript() {
        return "#!/system/bin/sh\n" +
            MANAGED_MARKER + "\n" +
            "if [ -n \"$PREFIX\" ] && [ -x \"$PREFIX/bin/frida-official\" ] && [ -x \"$PREFIX/bin/python3\" ]; then\n" +
            "  export LD_LIBRARY_PATH=\"$PREFIX/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}\"\n" +
            "  mira_needs_default_target=1\n" +
            "  for mira_arg in \"$@\"; do\n" +
            "    case \"$mira_arg\" in\n" +
            "      --status)\n" +
            "        exec \"$PREFIX/bin/python3\" -c \"import frida, json; dev=frida.get_device_manager().add_remote_device('127.0.0.1:27042'); ps=dev.enumerate_processes(); first=ps[0] if ps else None; print(json.dumps({'frida': frida.__version__, 'connected': True, 'processCount': len(ps), 'pid': getattr(first, 'pid', None), 'target': getattr(first, 'name', None)}, separators=(',', ':')))\"\n" +
            "        ;;\n" +
            "      -h|--help|--version)\n" +
            "        mira_needs_default_target=0\n" +
            "        ;;\n" +
            "      -D|--device|-U|--usb|-R|--remote|-H|--host|-f|--file|-F|--attach-frontmost|-n|--attach-name|-N|--attach-identifier|-p|--attach-pid|-W|--await)\n" +
            "        mira_needs_default_target=0\n" +
            "        ;;\n" +
            "    esac\n" +
            "  done\n" +
            "  if [ \"$mira_needs_default_target\" = \"1\" ]; then\n" +
            "    exec \"$PREFIX/bin/frida-official\" -H 127.0.0.1 -n Gadget \"$@\"\n" +
            "  fi\n" +
            "  exec \"$PREFIX/bin/frida-official\" \"$@\"\n" +
            "fi\n" +
            "if [ -n \"$MIRA_FRIDA_NATIVE\" ] && [ -x \"$MIRA_FRIDA_NATIVE\" ]; then\n" +
            "  exec \"$MIRA_FRIDA_NATIVE\" \"$@\"\n" +
            "fi\n" +
            "if [ -n \"$MIRA_PATH_PREFIX\" ] && [ -x \"$MIRA_PATH_PREFIX/frida-native\" ]; then\n" +
            "  exec \"$MIRA_PATH_PREFIX/frida-native\" \"$@\"\n" +
            "fi\n" +
            "if [ -n \"$MIRA_TOOLBOX_BIN\" ] && [ -x \"$MIRA_TOOLBOX_BIN/frida-native\" ]; then\n" +
            "  exec \"$MIRA_TOOLBOX_BIN/frida-native\" \"$@\"\n" +
            "fi\n" +
            "echo \"frida: no packaged fallback is available\" >&2\n" +
            "exit 127\n";
    }

    private void writeMiraCommandWrappers() throws IOException {
        String[] commands = new String[] {
            "am",
            "mira-am",
            "mira-settings",
            "mira-getprop",
            "mira-dumpsys",
            "mira-logcat"
        };
        File binDir = new File(prefixDir, "bin");
        deleteLegacyFridaWrappers(binDir);
        for (String command : commands) {
            writeExecutable(new File(binDir, command), miraCommandScript(command));
        }
    }

    private void deleteLegacyFridaWrappers(File binDir) throws IOException {
        String[] legacyCommands = new String[] {
            "frida-status",
            "frida-exec",
            "frida-load",
            "frida-hook",
            "frida-call",
            "frida-native-hook",
            "frida-detach"
        };
        for (String command : legacyCommands) {
            File wrapper = new File(binDir, command);
            if (wrapper.exists() && !wrapper.delete()) {
                throw new IOException("无法删除旧版 Frida wrapper: " + wrapper.getAbsolutePath());
            }
        }
    }

    private String miraCommandScript(String command) {
        return "#!/system/bin/sh\n" +
            "if [ -z \"$MIRA_COMMAND_SOCKET\" ]; then\n" +
            "  echo \"" + command + ": MIRA_COMMAND_SOCKET is not set\" >&2\n" +
            "  exit 1\n" +
            "fi\n" +
            "mira_b64() {\n" +
            "  if [ -n \"$MIRA_BUSYBOX\" ] && [ -x \"$MIRA_BUSYBOX\" ]; then \"$MIRA_BUSYBOX\" base64 \"$@\"; else /system/bin/toybox base64 \"$@\"; fi\n" +
            "}\n" +
            "request=\"MIRA/1 " + command + "\"\n" +
            "for arg in \"$@\"; do\n" +
            "  encoded=$(printf '%s' \"$arg\" | mira_b64 | tr -d '\\n')\n" +
            "  request=\"$request $encoded\"\n" +
            "done\n" +
            "response=$(printf '%s\\n' \"$request\" | /system/bin/toybox nc -U -w 10 \"$MIRA_COMMAND_SOCKET\")\n" +
            "status=$?\n" +
            "if [ \"$status\" -ne 0 ]; then\n" +
            "  echo \"" + command + ": command bridge unavailable\" >&2\n" +
            "  exit \"$status\"\n" +
            "fi\n" +
            "exit_code=$(printf '%s\\n' \"$response\" | sed -n 's/^MIRA\\/1 EXIT //p' | head -1)\n" +
            "stdout_b64=$(printf '%s\\n' \"$response\" | sed -n 's/^STDOUT //p' | head -1)\n" +
            "stderr_b64=$(printf '%s\\n' \"$response\" | sed -n 's/^STDERR //p' | head -1)\n" +
            "[ -n \"$stdout_b64\" ] && printf '%s' \"$stdout_b64\" | mira_b64 -d\n" +
            "[ -n \"$stderr_b64\" ] && printf '%s' \"$stderr_b64\" | mira_b64 -d >&2\n" +
            "case \"$exit_code\" in ''|*[!0-9]*) exit 1 ;; *) exit \"$exit_code\" ;; esac\n";
    }

    private String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private void installBootstrapPrefixIfAvailable() throws IOException {
        String assetRoot = selectBootstrapPrefixAssetRoot();
        if (assetRoot == null) {
            Log.i(TAG, "No packaged bootstrap prefix for current ABI, keeping minimal prefix");
            return;
        }
        extractAssetTree(assetRoot, prefixDir, "");
        Log.i(TAG, "Installed bootstrap prefix assets from " + assetRoot);
    }

    private String selectBootstrapPrefixAssetRoot() throws IOException {
        String[] abis = Build.SUPPORTED_ABIS == null ? new String[0] : Build.SUPPORTED_ABIS;
        for (String abi : abis) {
            String assetRoot = bootstrapPrefixAssetRootForAbi(abi);
            if (assetRoot != null && assetDirectoryExists(assetRoot)) return assetRoot;
        }
        return null;
    }

    private String bootstrapPrefixAssetRootForAbi(String abi) {
        if (abi == null) return null;
        String normalized = abi.toLowerCase(Locale.ROOT);
        if ("arm64-v8a".equals(normalized)) return BOOTSTRAP_PREFIX_ASSET_ROOT + "/arm64-v8a";
        if ("armeabi-v7a".equals(normalized) || "armeabi".equals(normalized)) return BOOTSTRAP_PREFIX_ASSET_ROOT + "/armeabi-v7a";
        if ("x86_64".equals(normalized)) return BOOTSTRAP_PREFIX_ASSET_ROOT + "/x86_64";
        if ("x86".equals(normalized)) return BOOTSTRAP_PREFIX_ASSET_ROOT + "/x86";
        return null;
    }

    private boolean assetDirectoryExists(String assetPath) throws IOException {
        String[] children = assets.list(assetPath);
        return children != null && children.length > 0;
    }

    private void extractAssetTree(String assetRoot, File destinationRoot, String relativePath) throws IOException {
        String assetPath = relativePath.isEmpty() ? assetRoot : assetRoot + "/" + relativePath;
        String[] children = assets.list(assetPath);
        if (children == null || children.length == 0) {
            File targetFile = relativePath.isEmpty() ? destinationRoot : new File(destinationRoot, relativePath);
            copyAsset(assetPath, targetFile);
            if (shouldBeExecutable(relativePath)) {
                if (!targetFile.setReadable(true, true)) throw new IOException("无法设置可读权限: " + targetFile.getAbsolutePath());
                if (!targetFile.setWritable(true, true)) throw new IOException("无法设置可写权限: " + targetFile.getAbsolutePath());
                if (!targetFile.setExecutable(true, true)) throw new IOException("无法设置可执行权限: " + targetFile.getAbsolutePath());
            }
            return;
        }
        File targetDir = relativePath.isEmpty() ? destinationRoot : new File(destinationRoot, relativePath);
        mkdir(targetDir);
        for (String child : children) {
            String childRelativePath = relativePath.isEmpty() ? child : relativePath + "/" + child;
            extractAssetTree(assetRoot, destinationRoot, childRelativePath);
        }
    }

    private void copyAsset(String assetPath, File destination) throws IOException {
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

    private boolean shouldBeExecutable(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) return false;
        String normalized = relativePath.replace('\\', '/');
        return normalized.startsWith("bin/") || normalized.startsWith("libexec/") || normalized.startsWith("sbin/");
    }
}
