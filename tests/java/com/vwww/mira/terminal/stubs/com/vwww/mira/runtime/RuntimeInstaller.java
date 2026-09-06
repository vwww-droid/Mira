package com.vwww.mira.runtime;

import java.io.File;

public final class RuntimeInstaller {
    private final File prefixDir;
    private final File homeDir;
    private final File tmpDir;
    private final File shellPath;

    public RuntimeInstaller(File prefixDir, File homeDir, File tmpDir, File shellPath) {
        this.prefixDir = prefixDir;
        this.homeDir = homeDir;
        this.tmpDir = tmpDir;
        this.shellPath = shellPath;
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
        return shellPath;
    }
}
