package com.vwww.mira;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Mira 最小 bootstrap, 用于创建 APK 私有沙盒里的类 Termux 用户空间骨架。
 *
 * 当前只提供 files/usr/bin/sh wrapper, 不安装 apt 或完整 Termux 包。
 */
public final class MiraBootstrap {
    private final File filesDir;
    private final File prefixDir;
    private final File homeDir;
    private final File tmpDir;

    public MiraBootstrap(Context context) {
        filesDir = context.getFilesDir();
        prefixDir = new File(filesDir, "usr");
        homeDir = new File(filesDir, "home");
        tmpDir = new File(context.getCacheDir(), "tmp");
    }

    public void installIfNeeded() throws IOException {
        mkdir(prefixDir);
        mkdir(homeDir);
        mkdir(tmpDir);
        mkdir(new File(prefixDir, "bin"));
        mkdir(new File(prefixDir, "etc"));
        mkdir(new File(prefixDir, "tmp"));
        mkdir(new File(prefixDir, "var"));
        mkdir(new File(prefixDir, "share"));

        writeExecutable(new File(prefixDir, "bin/sh"), shellWrapper());
        writeExecutable(new File(prefixDir, "bin/mira-info"), miraInfoScript());
        writeMiraCommandWrappers();
        writeText(new File(prefixDir, "etc/profile"), profileScript());
        writeText(new File(homeDir, ".profile"), homeProfileScript());
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

    private void writeExecutable(File file, String content) throws IOException {
        writeText(file, content);
        if (!file.setReadable(true, true)) throw new IOException("无法设置可读权限: " + file.getAbsolutePath());
        if (!file.setWritable(true, true)) throw new IOException("无法设置可写权限: " + file.getAbsolutePath());
        if (!file.setExecutable(true, true)) throw new IOException("无法设置可执行权限: " + file.getAbsolutePath());
    }

    private void writeText(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) mkdir(parent);
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String shellWrapper() {
        String prefix = quote(prefixDir.getAbsolutePath());
        String home = quote(homeDir.getAbsolutePath());
        String tmp = quote(tmpDir.getAbsolutePath());
        return "#!/system/bin/sh\n" +
            "export PREFIX=" + prefix + "\n" +
            "export HOME=" + home + "\n" +
            "export TMPDIR=" + tmp + "\n" +
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
        return "# Mira minimal profile\n" +
            "MIRA_BASE_PATH=\"$PREFIX/bin:/system/bin:/system/xbin\"\n" +
            "if [ -n \"$MIRA_PATH_PREFIX\" ]; then\n" +
            "  export PATH=\"$MIRA_PATH_PREFIX:$MIRA_BASE_PATH\"\n" +
            "else\n" +
            "  export PATH=\"$MIRA_BASE_PATH\"\n" +
            "fi\n" +
            "export MIRA_SANDBOX=1\n";
    }

    private String homeProfileScript() {
        return "# Mira shell profile\n" +
            "[ -n \"$PREFIX\" ] && [ -f \"$PREFIX/etc/profile\" ] && . \"$PREFIX/etc/profile\"\n" +
            "alias am='mira-am'\n" +
            "export PS1='mira $ '\n";
    }

    private String miraInfoScript() {
        return "#!/system/bin/sh\n" +
            "echo \"Mira sandbox\"\n" +
            "echo \"PREFIX=$PREFIX\"\n" +
            "echo \"HOME=$HOME\"\n" +
            "echo \"TMPDIR=$TMPDIR\"\n" +
            "echo \"SHELL=$SHELL\"\n";
    }

    private void writeMiraCommandWrappers() throws IOException {
        String[] commands = new String[] {
            "am",
            "mira-am",
            "mira-settings",
            "mira-getprop",
            "mira-dumpsys",
            "mira-logcat",
            "frida-status",
            "frida-exec",
            "frida-load",
            "frida-hook",
            "frida-call",
            "frida-native-hook",
            "frida-detach"
        };
        File binDir = new File(prefixDir, "bin");
        for (String command : commands) {
            writeExecutable(new File(binDir, command), miraCommandScript(command));
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
}
