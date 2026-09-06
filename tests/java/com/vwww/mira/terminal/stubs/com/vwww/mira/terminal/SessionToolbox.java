package com.vwww.mira.terminal;

public final class SessionToolbox {
    private final String pathPrefix;
    private final String busyboxPath;
    private final String busyboxAbi;
    private final String busyboxAssetPath;
    private final String manifestPath;

    public SessionToolbox(String pathPrefix, String busyboxPath, String busyboxAbi, String busyboxAssetPath, String manifestPath) {
        this.pathPrefix = pathPrefix;
        this.busyboxPath = busyboxPath;
        this.busyboxAbi = busyboxAbi;
        this.busyboxAssetPath = busyboxAssetPath;
        this.manifestPath = manifestPath;
    }

    public String pathPrefix() {
        return pathPrefix;
    }

    public String busyboxPath() {
        return busyboxPath;
    }

    public String busyboxAbi() {
        return busyboxAbi;
    }

    public String busyboxAssetPath() {
        return busyboxAssetPath;
    }

    public String manifestPath() {
        return manifestPath;
    }
}
