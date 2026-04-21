package com.vwww.mira;

import android.content.Context;

import com.termux.terminal.MiraPtyProcess;

public final class MiraPtyFactory {
    private MiraPtyFactory() {
    }

    public static MiraPtyProcess create(Context context, MiraBootstrap bootstrap, int rows, int columns) {
        return create(context, bootstrap, rows, columns, null);
    }

    public static MiraPtyProcess create(Context context, MiraBootstrap bootstrap, int rows, int columns, MiraToolbox toolbox) {
        String shell = bootstrap.getShellPath().getAbsolutePath();
        String cwd = bootstrap.getHomeDir().getAbsolutePath();
        String prefix = bootstrap.getPrefixDir().getAbsolutePath();
        String home = bootstrap.getHomeDir().getAbsolutePath();
        String tmp = bootstrap.getTmpDir().getAbsolutePath();
        String basePath = prefix + "/bin:/system/bin:/system/xbin";
        String path = toolbox == null ? basePath : toolbox.pathPrefix() + ":" + basePath;
        String[] args = new String[] {"sh"};
        String[] env = new String[] {
            "PREFIX=" + prefix,
            "HOME=" + home,
            "TMPDIR=" + tmp,
            "PATH=" + path,
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "MIRA_SANDBOX=1",
            "MIRA_PREFIX=" + prefix,
            "MIRA_PATH_PREFIX=" + (toolbox == null ? "" : toolbox.pathPrefix()),
            "MIRA_TOOLBOX_BIN=" + (toolbox == null ? "" : toolbox.pathPrefix()),
            "MIRA_BUSYBOX=" + (toolbox == null ? "" : toolbox.busyboxPath()),
            "SHELL=" + prefix + "/bin/sh",
            "MIRA_APP_PACKAGE=" + context.getPackageName(),
            "ENV=" + home + "/.profile"
        };
        return new MiraPtyProcess(shell, cwd, args, env, rows, columns);
    }
}
