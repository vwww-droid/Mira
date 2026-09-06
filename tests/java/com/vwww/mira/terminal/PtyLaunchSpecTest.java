package com.vwww.mira.terminal;

import android.content.Context;
import com.vwww.mira.runtime.RuntimeInstaller;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PtyLaunchSpecTest {
    private static void check(boolean value) {
        if (!value) throw new AssertionError();
    }

    private static Map<String, String> environment(PtyLaunchSpec spec) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String entry : spec.getEnv()) {
            int separator = entry.indexOf('=');
            check(separator > 0);
            check(result.put(entry.substring(0, separator), entry.substring(separator + 1)) == null);
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        File root = new File(args[0]).getCanonicalFile();
        File files = new File(root, "files");
        File prefix = new File(files, "usr");
        File home = new File(files, "home");
        File tmp = new File(root, "cache/tmp");
        File shell = new File(prefix, "bin/sh");
        Context context = new Context(files, "com.vwww.mira.test");
        RuntimeInstaller installer = new RuntimeInstaller(prefix, home, tmp, shell);

        PtyLaunchSpec plain = PtyLaunchSpec.of(context, installer, 31, 117, 9, 18, null);
        check(plain.getShellPath().equals(shell.getAbsolutePath()));
        check(plain.getCwd().equals(home.getAbsolutePath()));
        check(plain.getArgs().length == 0);
        check(plain.getRows() == 31 && plain.getColumns() == 117);
        check(plain.getCellWidth() == 9 && plain.getCellHeight() == 18);

        Map<String, String> plainEnv = environment(plain);
        check(plainEnv.get("PREFIX").equals(prefix.getAbsolutePath()));
        check(plainEnv.get("TERMUX_PREFIX").equals(prefix.getAbsolutePath()));
        check(plainEnv.get("HOME").equals(home.getAbsolutePath()));
        check(plainEnv.get("TERMUX_HOME").equals(home.getAbsolutePath()));
        check(plainEnv.get("TMPDIR").equals(tmp.getAbsolutePath()));
        check(plainEnv.get("PATH").equals(prefix.getAbsolutePath() + "/bin:/system/bin:/system/xbin"));
        check(plainEnv.get("MIRA_PATH_PREFIX").isEmpty());
        check(plainEnv.get("MIRA_TOOLBOX_BIN").isEmpty());
        check(plainEnv.get("MIRA_BUSYBOX").isEmpty());
        check(plainEnv.get("MIRA_BUSYBOX_ABI").isEmpty());
        check(plainEnv.get("MIRA_BUSYBOX_ASSET").isEmpty());
        check(plainEnv.get("MIRA_TOOLBOX_MANIFEST").isEmpty());
        check(plainEnv.get("MIRA_COMMAND_SOCKET").equals(new File(files, "run/mira-command.sock").getAbsolutePath()));
        check(plainEnv.get("SHELL").equals(prefix.getAbsolutePath() + "/bin/sh"));
        check(plainEnv.get("MIRA_APP_PACKAGE").equals("com.vwww.mira.test"));
        check(plainEnv.get("ENV").equals(home.getAbsolutePath() + "/.profile"));

        String[] returnedEnv = plain.getEnv();
        returnedEnv[0] = "BROKEN=1";
        check(plain.getEnv()[0].equals("PREFIX=" + prefix.getAbsolutePath()));

        SessionToolbox toolbox = new SessionToolbox(
            new File(root, "session/bin").getAbsolutePath(),
            new File(root, "session/bin/busybox").getAbsolutePath(),
            "arm64-v8a",
            "toolbox/busybox/arm64-v8a/busybox",
            new File(root, "session/toolbox-manifest.json").getAbsolutePath()
        );
        PtyLaunchSpec prepared = PtyLaunchSpec.of(context, installer, 24, 80, toolbox);
        Map<String, String> preparedEnv = environment(prepared);
        check(prepared.getCellWidth() == 0 && prepared.getCellHeight() == 0);
        check(preparedEnv.get("PATH").equals(toolbox.pathPrefix() + ":" + prefix.getAbsolutePath() + "/bin:/system/bin:/system/xbin"));
        check(preparedEnv.get("MIRA_PATH_PREFIX").equals(toolbox.pathPrefix()));
        check(preparedEnv.get("MIRA_TOOLBOX_BIN").equals(toolbox.pathPrefix()));
        check(preparedEnv.get("MIRA_BUSYBOX").equals(toolbox.busyboxPath()));
        check(preparedEnv.get("MIRA_BUSYBOX_ABI").equals(toolbox.busyboxAbi()));
        check(preparedEnv.get("MIRA_BUSYBOX_ASSET").equals(toolbox.busyboxAssetPath()));
        check(preparedEnv.get("MIRA_TOOLBOX_MANIFEST").equals(toolbox.manifestPath()));
        System.out.println("PASS: terminal dimensions, environment, toolbox, and defensive copies");
    }
}
