package com.vwww.mira;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Settings;

import java.io.ByteArrayOutputStream;
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
    private static final long LOGCAT_TIMEOUT_MS = 8_000L;
    private static final long GETPROP_TIMEOUT_MS = 5_000L;
    private static final long FRIDA_DEFAULT_TIMEOUT_MS = 30_000L;
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
                case "frida-exec":
                    return runFridaExec(context, argv);
                case "frida-load":
                    return runFridaLoad(context, argv);
                case "frida-hook":
                    return runFridaHook(context, argv);
                case "frida-call":
                    return runFridaCall(context, argv);
                case "frida-native-hook":
                    return runFridaNativeHook(context, argv);
                case "frida-status":
                    return runFridaStatus(context);
                case "frida-detach":
                    return runFridaDetach(context);
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

    private static String normalizeNamespace(String namespace) {
        return namespace == null ? "" : namespace.toLowerCase(Locale.ROOT);
    }

    private static String requireValue(List<String> args, int index, String option) {
        if (index >= args.size()) {
            throw new IllegalArgumentException(option + " requires value");
        }
        return args.get(index);
    }

    private static int parseInteger(String value) {
        if (value == null) throw new NumberFormatException("null");
        if (value.startsWith("0x") || value.startsWith("0X")) {
            return (int) Long.parseLong(value.substring(2), 16);
        }
        return Integer.parseInt(value);
    }

    // ---- Frida commands ----

    private static MiraCommandResult runFridaExec(Context context, List<String> argv) {
        if (argv.isEmpty() || "help".equals(argv.get(0)) || "--help".equals(argv.get(0))) {
            return MiraCommandResult.ok(
                "usage: frida-exec <js_code> [--timeout <ms>]\n" +
                "  Execute Frida JavaScript code and return results.\n" +
                "  The code can use send() to return data and Java.perform() for Java hooks.\n"
            );
        }
        long timeout = FRIDA_DEFAULT_TIMEOUT_MS;
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < argv.size(); i++) {
            if ("--timeout".equals(argv.get(i)) && i + 1 < argv.size()) {
                try {
                    timeout = Long.parseLong(argv.get(++i));
                } catch (NumberFormatException e) {
                    return MiraCommandResult.error("frida-exec: invalid timeout value\n");
                }
            } else {
                if (code.length() > 0) code.append(" ");
                code.append(argv.get(i));
            }
        }
        return MiraFridaBridge.getInstance(context).exec(code.toString(), timeout);
    }

    private static MiraCommandResult runFridaLoad(Context context, List<String> argv) {
        if (argv.isEmpty() || "help".equals(argv.get(0)) || "--help".equals(argv.get(0))) {
            return MiraCommandResult.ok(
                "usage: frida-load <script_path> [--timeout <ms>]\n" +
                "  Load and execute a Frida JS script file from the device.\n"
            );
        }
        long timeout = FRIDA_DEFAULT_TIMEOUT_MS;
        String path = null;
        for (int i = 0; i < argv.size(); i++) {
            if ("--timeout".equals(argv.get(i)) && i + 1 < argv.size()) {
                try {
                    timeout = Long.parseLong(argv.get(++i));
                } catch (NumberFormatException e) {
                    return MiraCommandResult.error("frida-load: invalid timeout value\n");
                }
            } else if (path == null) {
                path = argv.get(i);
            }
        }
        if (path == null || path.isEmpty()) {
            return MiraCommandResult.error("frida-load: script path required\n");
        }
        try {
            java.io.File file = new java.io.File(path);
            if (!file.exists()) return MiraCommandResult.error("frida-load: file not found: " + path + "\n");
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int read;
            while ((read = reader.read(buf)) != -1) sb.append(buf, 0, read);
            reader.close();
            return MiraFridaBridge.getInstance(context).exec(sb.toString(), timeout);
        } catch (java.io.IOException e) {
            return MiraCommandResult.error("frida-load: " + e.getMessage() + "\n");
        }
    }

    private static MiraCommandResult runFridaHook(Context context, List<String> argv) {
        if (argv.isEmpty() || "help".equals(argv.get(0)) || "--help".equals(argv.get(0))) {
            return MiraCommandResult.ok(
                "usage: frida-hook <fully.qualified.Class.methodName>\n" +
                "  Install a persistent hook on a Java method.\n" +
                "  Prints arguments and return value for every call.\n"
            );
        }
        return MiraFridaBridge.getInstance(context).hookJavaMethod(argv.get(0));
    }

    private static MiraCommandResult runFridaCall(Context context, List<String> argv) {
        if (argv.isEmpty() || "help".equals(argv.get(0)) || "--help".equals(argv.get(0))) {
            return MiraCommandResult.ok(
                "usage: frida-call <fully.qualified.Class.method> [arg1 arg2 ...]\n" +
                "  Actively call a static Java method with optional arguments.\n"
            );
        }
        String classMethod = argv.get(0);
        List<String> args = argv.size() > 1 ? argv.subList(1, argv.size()) : new ArrayList<>();
        return MiraFridaBridge.getInstance(context).callJavaMethod(classMethod, args);
    }

    private static MiraCommandResult runFridaNativeHook(Context context, List<String> argv) {
        if (argv.size() < 2 || "help".equals(argv.get(0)) || "--help".equals(argv.get(0))) {
            return MiraCommandResult.ok(
                "usage: frida-native-hook <libname.so> <function_name>\n" +
                "  Install a persistent hook on a native function export.\n" +
                "  Prints first 3 arguments and return value for every call.\n"
            );
        }
        return MiraFridaBridge.getInstance(context).hookNativeFunction(argv.get(0), argv.get(1));
    }

    private static MiraCommandResult runFridaStatus(Context context) {
        return MiraFridaBridge.getInstance(context).status();
    }

    private static MiraCommandResult runFridaDetach(Context context) {
        return MiraFridaBridge.getInstance(context).detachAll();
    }
}
