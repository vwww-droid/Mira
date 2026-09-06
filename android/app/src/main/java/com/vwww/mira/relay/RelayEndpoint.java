package com.vwww.mira.relay;

import java.net.URI;
import java.util.Locale;

public final class RelayEndpoint {
    private static final String CONTROL_PATH = "/ws/control";
    private static final String SCREEN_DEVICE_PATH = "/ws/screen/device";

    private RelayEndpoint() {
    }

    public static String controlWebSocket(String value) throws Exception {
        return endpointWebSocket(value, CONTROL_PATH);
    }

    public static String screenWebSocket(String value) throws Exception {
        return endpointWebSocket(value, SCREEN_DEVICE_PATH);
    }

    static String normalizeBaseUrl(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty()) return "";
        if (!raw.contains("://")) return "https://" + raw;
        return raw;
    }

    private static String endpointWebSocket(String value, String endpointPath) throws Exception {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty()) throw new IllegalArgumentException("Relay URL is empty");
        if (!raw.contains("://")) raw = "https://" + raw;
        URI uri = new URI(raw);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ("http".equals(scheme)) scheme = "ws";
        else if ("https".equals(scheme)) scheme = "wss";
        else if (!"ws".equals(scheme) && !"wss".equals(scheme)) throw new IllegalArgumentException("Unsupported Relay URL scheme");
        String authority = uri.getRawAuthority();
        if (authority == null || authority.trim().isEmpty()) throw new IllegalArgumentException("Relay URL host is empty");
        String path = uri.getRawPath();
        if (path == null || path.isEmpty() || "/".equals(path)) path = endpointPath;
        else if (!path.endsWith(endpointPath)) path = path.replaceAll("/+$", "") + endpointPath;
        return scheme + "://" + authority + path;
    }
}
