package com.vwww.mira;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Settings;
import android.system.ErrnoException;
import android.system.Os;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class MiraCommandRouter {
    private static final long DUMPSYS_TIMEOUT_MS = 10_000L;
    private static final long LOGCAT_TIMEOUT_MS = 30_000L;
    private static final long PROC_AUDIT_LOGCAT_TIMEOUT_MS = 20_000L;
    private static final long GETPROP_TIMEOUT_MS = 5_000L;
    private static final int PROC_AUDIT_MAX_LOG_BYTES = 128 * 1024;
    private static final Set<String> DUMPSYS_ALLOWLIST = new HashSet<>(Arrays.asList(
        "activity",
        "battery",
        "batteryproperties",
        "display",
        "input",
        "power",
        "window"
    ));

    private MiraCommandRouter() {
    }

    static MiraCommandResult dispatch(Context context, String tool, List<String> argv) {
        if (tool == null) tool = "";
        if (argv == null) argv = new ArrayList<>();
        try {
            switch (tool) {
                case "am":
                case "mira-am":
                    return runAm(context, argv);
                case "mira-settings":
                    return runSettings(context, argv);
                case "mira-getprop":
                    return runGetprop(argv);
                case "mira-dumpsys":
                    return runDumpsys(argv);
                case "mira-logcat":
                    return runLogcat(argv);
                case "mira-proc-audit":
                    return runProcAudit(argv);
                default:
                    return MiraCommandResult.error("unsupported Mira command: " + tool + "\n");
            }
        } catch (Throwable failure) {
            return MiraCommandResult.error(tool + ": " + failure.getClass().getSimpleName() + ": " + failure.getMessage() + "\n");
        }
    }

    private static MiraCommandResult runAm(Context context, List<String> argv) throws UnsupportedEncodingException {
        Application application = asApplication(context);
        List<String> effectiveArgv = argv;
        boolean help = argv.isEmpty() || "help".equals(argv.get(0)) || "--help".equals(argv.get(0)) || "--am-help".equals(argv.get(0)) || "-h".equals(argv.get(0));
        if (help) effectiveArgv = new ArrayList<>();

        ByteArrayOutputStream stdoutBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBuffer = new ByteArrayOutputStream();
        int exitCode;
        try (PrintStream stdout = new PrintStream(stdoutBuffer, true, StandardCharsets.UTF_8.name());
             PrintStream stderr = new PrintStream(stderrBuffer, true, StandardCharsets.UTF_8.name())) {
            exitCode = new com.termux.am.Am(stdout, stderr, application).run(effectiveArgv.toArray(new String[0]));
            stdout.flush();
            stderr.flush();
        }

        if (help) exitCode = 0;
        return new MiraCommandResult(
            exitCode,
            stdoutBuffer.toString(StandardCharsets.UTF_8.name()),
            stderrBuffer.toString(StandardCharsets.UTF_8.name())
        );
    }

    private static Application asApplication(Context context) {
        Context applicationContext = context.getApplicationContext();
        if (applicationContext instanceof Application) return (Application) applicationContext;
        throw new IllegalStateException("application context is not an Application");
    }

    private static MiraCommandResult runSettings(Context context, List<String> argv) {
        if (argv.isEmpty() || "help".equals(argv.get(0)) || "--help".equals(argv.get(0))) {
            return MiraCommandResult.ok("usage: mira-settings get system|secure|global KEY\n" +
                "       mira-settings list system|secure|global\n");
        }
        String action = argv.get(0);
        if ("get".equals(action)) {
            if (argv.size() != 3) return MiraCommandResult.error("usage: mira-settings get system|secure|global KEY\n");
            String value = getSetting(context, argv.get(1), argv.get(2));
            return MiraCommandResult.ok((value == null ? "null" : value) + "\n");
        }
        if ("list".equals(action)) {
            if (argv.size() != 2) return MiraCommandResult.error("usage: mira-settings list system|secure|global\n");
            return MiraCommandResult.ok(listSettings(context, argv.get(1)));
        }
        return MiraCommandResult.error("mira-settings: unsupported subcommand: " + action + "\n");
    }

    private static String getSetting(Context context, String namespace, String key) {
        switch (normalizeNamespace(namespace)) {
            case "system":
                return Settings.System.getString(context.getContentResolver(), key);
            case "secure":
                return Settings.Secure.getString(context.getContentResolver(), key);
            case "global":
                return Settings.Global.getString(context.getContentResolver(), key);
            default:
                throw new IllegalArgumentException("unsupported settings namespace: " + namespace);
        }
    }

    private static String listSettings(Context context, String namespace) {
        Uri uri;
        switch (normalizeNamespace(namespace)) {
            case "system":
                uri = Settings.System.CONTENT_URI;
                break;
            case "secure":
                uri = Settings.Secure.CONTENT_URI;
                break;
            case "global":
                uri = Settings.Global.CONTENT_URI;
                break;
            default:
                throw new IllegalArgumentException("unsupported settings namespace: " + namespace);
        }
        StringBuilder output = new StringBuilder();
        try (Cursor cursor = context.getContentResolver().query(uri, new String[] {"name", "value"}, null, null, "name")) {
            if (cursor == null) return "";
            int nameColumn = cursor.getColumnIndex("name");
            int valueColumn = cursor.getColumnIndex("value");
            while (cursor.moveToNext()) {
                output.append(cursor.getString(nameColumn)).append('=');
                String value = cursor.getString(valueColumn);
                if (value != null) output.append(value);
                output.append('\n');
            }
        }
        return output.toString();
    }

    private static MiraCommandResult runGetprop(List<String> argv) {
        if (argv.size() > 2 || (!argv.isEmpty() && ("help".equals(argv.get(0)) || "--help".equals(argv.get(0))))) {
            return MiraCommandResult.ok("usage: mira-getprop [KEY [DEFAULT]]\n");
        }
        if (argv.isEmpty()) {
            return MiraProcessRunner.run(Arrays.asList("/system/bin/getprop"), GETPROP_TIMEOUT_MS);
        }
        String key = argv.get(0);
        String fallback = argv.size() == 2 ? argv.get(1) : "";
        String value = getSystemProperty(key, fallback);
        if (value == null) {
            return MiraProcessRunner.run(Arrays.asList("/system/bin/getprop", key), GETPROP_TIMEOUT_MS);
        }
        return MiraCommandResult.ok(value + "\n");
    }

    private static String getSystemProperty(String key, String fallback) {
        try {
            Class<?> clazz = Class.forName("android.os.SystemProperties");
            Method method = clazz.getDeclaredMethod("get", String.class, String.class);
            method.setAccessible(true);
            Object value = method.invoke(null, key, fallback);
            return value == null ? fallback : String.valueOf(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static MiraCommandResult runDumpsys(List<String> argv) {
        if (argv.isEmpty() || "help".equals(argv.get(0)) || "--help".equals(argv.get(0))) {
            return MiraCommandResult.ok("usage: mira-dumpsys battery|display|window|activity|power|input [args...]\n");
        }
        String service = argv.get(0);
        if (!DUMPSYS_ALLOWLIST.contains(service)) {
            return MiraCommandResult.error("mira-dumpsys: service not allowed: " + service + "\n");
        }
        List<String> command = new ArrayList<>();
        command.add("/system/bin/dumpsys");
        command.add("battery".equals(service) ? "batteryproperties" : service);
        command.addAll(argv.subList(1, argv.size()));
        return MiraProcessRunner.run(command, DUMPSYS_TIMEOUT_MS);
    }

    private static MiraCommandResult runLogcat(List<String> argv) {
        if (!argv.isEmpty() && ("help".equals(argv.get(0)) || "--help".equals(argv.get(0)))) {
            return MiraCommandResult.ok("usage: mira-logcat [-d] [-t COUNT] [-T TIME] [-v FORMAT] [-s TAG] [TAG:LEVEL...]\n");
        }
        List<String> command = new ArrayList<>();
        command.add("/system/bin/logcat");
        boolean exits = false;
        boolean hasCount = false;
        boolean hasFormat = false;
        for (int i = 0; i < argv.size(); i++) {
            String arg = argv.get(i);
            if ("-c".equals(arg) || "--clear".equals(arg) || "-f".equals(arg) || "-r".equals(arg) || "-n".equals(arg) || "-G".equals(arg)) {
                return MiraCommandResult.error("mira-logcat: option not allowed: " + arg + "\n");
            }
            if ("--tag".equals(arg)) {
                command.add("-s");
                command.add(requireValue(argv, ++i, arg));
                continue;
            }
            if ("-d".equals(arg)) exits = true;
            if ("-t".equals(arg) || "-T".equals(arg)) {
                hasCount = true;
                exits = true;
            }
            if (arg.startsWith("-t") || arg.startsWith("-T")) {
                hasCount = true;
                exits = true;
            }
            if ("-v".equals(arg) || arg.startsWith("-v")) hasFormat = true;
            command.add(arg);
        }
        if (!exits) command.add("-d");
        if (!hasCount) {
            command.add("-t");
            command.add("200");
        }
        if (!hasFormat) {
            command.add("-v");
            command.add("time");
        }
        return MiraProcessRunner.run(command, LOGCAT_TIMEOUT_MS);
    }

    private static MiraCommandResult runProcAudit(List<String> argv) {
        int maxPid = intArg(argv, "--max-pid", 10_000, 1, 100_000);
        int startPid = intArg(argv, "--start-pid", 1, 1, 100_000);
        if (startPid > maxPid) startPid = maxPid;
        int logCount = intArg(argv, "--count", 5_000, 1, 5_000);
        int chunkSize = intArg(argv, "--chunk-size", 500, 1, 5_000);
        StringBuilder output = new StringBuilder();
        output.append("ProcAudit app-context scan pid=").append(startPid).append('-').append(maxPid).append('\n');
        output.append("context=").append(readSelfContext()).append('\n');

        byte[] buffer = new byte[256];
        long start = System.nanoTime();
        int emittedLines = 0;
        int filteredLines = 0;
        Set<String> emitted = new HashSet<>();
        for (int pid = startPid; pid <= maxPid; pid++) {
            String directContext = triggerAuditForPid(pid, buffer);
            if (!directContext.isEmpty()) {
                String line = "XATTR-CONTEXT: pid=" + pid + " " + directContext;
                if (emitted.add(line)) {
                    emittedLines++;
                    output.append(line).append('\n');
                }
            }
            if (pid % 50 == 0) {
                try {
                    Thread.sleep(90L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return new MiraCommandResult(130, output.toString(), "mira-proc-audit interrupted\n");
                }
            }
            if (pid == maxPid || ((pid - startPid + 1) % chunkSize == 0)) {
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return new MiraCommandResult(130, output.toString(), "mira-proc-audit interrupted\n");
                }
                int[] counts = collectProcAuditLogs(logCount, output, emitted);
                filteredLines += counts[0];
                emittedLines += counts[1];
            }
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        output.append("\nfiltered_lines=").append(filteredLines).append('\n');
        output.append("emitted_lines=").append(emittedLines).append('\n');
        output.append("elapsed_ms=").append(elapsedMs).append('\n');
        output.append("scan_completed=true\n");
        return MiraCommandResult.ok(output.toString());
    }

    private static int[] collectProcAuditLogs(int logCount, StringBuilder output, Set<String> emitted) {
        int filteredLines = 0;
        int emittedLines = 0;
        String[] commands = {
            "logcat -d -b events -t " + logCount,
            "logcat -d -b main -t " + logCount,
            "logcat -d -b all -t " + logCount
        };
        for (String commandText : commands) {
            MiraCommandResult logs = MiraProcessRunner.run(Arrays.asList("/system/bin/sh", "-c", commandText), PROC_AUDIT_LOGCAT_TIMEOUT_MS);
            output.append("\n## ").append(commandText).append(" exit=").append(logs.exitCode).append(" ##\n");
            String text = logs.stdout;
            if (text.length() > PROC_AUDIT_MAX_LOG_BYTES) {
                text = text.substring(text.length() - PROC_AUDIT_MAX_LOG_BYTES);
            }
            String[] lines = text.split("\\n");
            for (String line : lines) {
                if (!isProcAuditLine(line)) continue;
                filteredLines++;
                if (!emitted.add(line)) continue;
                emittedLines++;
                output.append("PROC-AUDIT: ").append(line).append('\n');
            }
        }
        return new int[] { filteredLines, emittedLines };
    }

    private static String triggerAuditForPid(int pid, byte[] buffer) {
        StringBuilder contexts = null;
        String path = "/proc/" + pid;
        try {
            Os.stat(path);
        } catch (ErrnoException ignored) {
        }
        String statusPath = path + "/status";
        try (FileInputStream input = new FileInputStream(statusPath)) {
            input.read(buffer);
        } catch (Throwable ignored) {
        }
        try {
            contexts = appendXattrContext(contexts, path);
        } catch (ErrnoException ignored) {
        }
        try {
            Os.stat(statusPath);
        } catch (ErrnoException ignored) {
        }
        try {
            contexts = appendXattrContext(contexts, statusPath);
        } catch (ErrnoException ignored) {
        }
        return contexts == null ? "" : contexts.toString();
    }

    private static StringBuilder appendXattrContext(StringBuilder contexts, String path) throws ErrnoException {
        byte[] raw = Os.getxattr(path, "security.selinux");
        if (raw == null || raw.length == 0) return contexts;
        String value = new String(raw, StandardCharsets.UTF_8).replace("\u0000", "").trim();
        if (value.isEmpty()) return contexts;
        if (contexts == null) contexts = new StringBuilder();
        if (contexts.length() > 0) contexts.append(' ');
        contexts.append(path).append('=').append(value);
        return contexts;
    }

    private static boolean isProcAuditLine(String line) {
        if (line == null || line.isEmpty()) return false;
        String lower = line.toLowerCase(Locale.ROOT);
        return (lower.contains("audit") || lower.contains("avc")) &&
            (lower.contains("dev=\"proc\"") || lower.contains("path=\"/proc/"));
    }

    private static String readSelfContext() {
        byte[] buffer = new byte[256];
        try (FileInputStream input = new FileInputStream("/proc/self/attr/current")) {
            int read = input.read(buffer);
            if (read <= 0) return "unknown";
            return new String(buffer, 0, read, StandardCharsets.UTF_8).replace("\u0000", "").trim();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static int intArg(List<String> argv, String name, int fallback, int min, int max) {
        for (int i = 0; i < argv.size() - 1; i++) {
            if (!name.equals(argv.get(i))) continue;
            try {
                int value = Integer.parseInt(argv.get(i + 1));
                return Math.max(min, Math.min(max, value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String normalizeNamespace(String namespace) {
        return namespace == null ? "" : namespace.toLowerCase(Locale.ROOT);
    }

    private static String requireValue(List<String> args, int index, String option) {
        if (index >= args.size()) {
            throw new IllegalArgumentException(option + " requires value");
        }
        return args.get(index);
    }

}
