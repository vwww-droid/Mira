package android.content;

import android.net.wifi.WifiManager;

public class Context {
    public static final String WIFI_SERVICE = "wifi";

    private final WifiManager wifiManager = new WifiManager();

    public Context getApplicationContext() {
        return this;
    }

    public Object getSystemService(String name) {
        return WIFI_SERVICE.equals(name) ? wifiManager : null;
    }
}
