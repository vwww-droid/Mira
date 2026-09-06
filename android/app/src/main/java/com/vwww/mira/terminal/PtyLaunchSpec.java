package com.vwww.mira.terminal;

import com.vwww.mira.command.LocalCommandServer;
import com.vwww.mira.runtime.RuntimeInstaller;

import android.content.Context;

import java.io.File;

final class PtyLaunchSpec {
    private final String shellPath;
    private final String cwd;
    private final String[] args;
    private final String[] env;
    private final int rows;
    private final int columns;
    private final int cellWidth;
    private final int cellHeight;

    private PtyLaunchSpec(
        String shellPath,
        String cwd,
        String[] args,
        String[] env,
        int rows,
        int columns,
        int cellWidth,
        int cellHeight
    ) {
        this.shellPath = shellPath;
        this.cwd = cwd;
        this.args = args == null ? new String[0] : args.clone();
        this.env = env == null ? new String[0] : env.clone();
        this.rows = rows;
        this.columns = columns;
        this.cellWidth = cellWidth;
        this.cellHeight = cellHeight;
    }

    static PtyLaunchSpec of(
        Context context,
        RuntimeInstaller runtimeInstaller,
        int rows,
        int columns,
        SessionToolbox toolbox
    ) {
        return of(context, runtimeInstaller, rows, columns, 0, 0, toolbox);
    }

    static PtyLaunchSpec of(
        Context context,
        RuntimeInstaller runtimeInstaller,
        int rows,
        int columns,
        int cellWidth,
        int cellHeight,
        SessionToolbox toolbox
    ) {
        File shell = runtimeInstaller.getShellPath();
        File homeDir = runtimeInstaller.getHomeDir();
        File prefixDir = runtimeInstaller.getPrefixDir();
        File tmpDir = runtimeInstaller.getTmpDir();

        String prefix = prefixDir.getAbsolutePath();
        String home = homeDir.getAbsolutePath();
        String basePath = prefix + "/bin:/system/bin:/system/xbin";
        String toolboxPath = toolbox == null ? "" : toolbox.pathPrefix();
        String path = toolbox == null ? basePath : toolboxPath + ":" + basePath;
        String commandSocket = LocalCommandServer.socketFile(context).getAbsolutePath();

        String[] args = new String[0];
        String[] env = new String[] {
            "PREFIX=" + prefix,
            "TERMUX_PREFIX=" + prefix,
            "HOME=" + home,
            "TERMUX_HOME=" + home,
            "TMPDIR=" + tmpDir.getAbsolutePath(),
            "PATH=" + path,
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "MIRA_SANDBOX=1",
            "MIRA_PREFIX=" + prefix,
            "MIRA_PATH_PREFIX=" + toolboxPath,
            "MIRA_TOOLBOX_BIN=" + toolboxPath,
            "MIRA_BUSYBOX=" + (toolbox == null ? "" : toolbox.busyboxPath()),
            "MIRA_BUSYBOX_ABI=" + (toolbox == null ? "" : toolbox.busyboxAbi()),
            "MIRA_BUSYBOX_ASSET=" + (toolbox == null ? "" : toolbox.busyboxAssetPath()),
            "MIRA_TOOLBOX_MANIFEST=" + (toolbox == null ? "" : toolbox.manifestPath()),
            "MIRA_COMMAND_SOCKET=" + commandSocket,
            "SHELL=" + prefix + "/bin/sh",
            "MIRA_APP_PACKAGE=" + context.getPackageName(),
            "ENV=" + home + "/.profile"
        };
        return new PtyLaunchSpec(shell.getAbsolutePath(), home, args, env, rows, columns, cellWidth, cellHeight);
    }

    String getShellPath() {
        return shellPath;
    }

    String getCwd() {
        return cwd;
    }

    String[] getArgs() {
        return args.clone();
    }

    String[] getEnv() {
        return env.clone();
    }

    int getRows() {
        return rows;
    }

    int getColumns() {
        return columns;
    }

    int getCellWidth() {
        return cellWidth;
    }

    int getCellHeight() {
        return cellHeight;
    }
}
