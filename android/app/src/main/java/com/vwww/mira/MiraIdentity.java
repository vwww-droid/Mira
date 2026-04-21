package com.vwww.mira;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.UUID;

public final class MiraIdentity {
    private static final String PREFS = "mira_identity";
    private static final String KEY_INSTALL_ID = "install_id";
    private static final String KEY_DEVICE_SECRET = "device_secret";

    private final Context context;
    private final SharedPreferences preferences;

    public MiraIdentity(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        ensureIdentity();
    }

    public String getInstallId() {
        return preferences.getString(KEY_INSTALL_ID, "");
    }

    public String getDeviceSecret() {
        return preferences.getString(KEY_DEVICE_SECRET, "");
    }

    public String defaultDeviceName() {
        String model = Build.MODEL == null ? "Android" : Build.MODEL;
        return model.trim().isEmpty() ? "Android" : model;
    }

    public String androidIdHash() {
        String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId == null) androidId = "";
        return "sha256:" + sha256(androidId);
    }

    public String primaryArch() {
        if (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0) return Build.SUPPORTED_ABIS[0];
        return Build.CPU_ABI == null ? "unknown" : Build.CPU_ABI;
    }

    public JSONObject deviceMeta(String deviceName, String state, String wakeUrl) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("type", "mira.device");
        json.put("protocol", 1);
        json.put("installId", getInstallId());
        json.put("deviceName", deviceName == null || deviceName.trim().isEmpty() ? defaultDeviceName() : deviceName.trim());
        json.put("packageName", context.getPackageName());
        json.put("androidIdHash", androidIdHash());
        json.put("model", defaultDeviceName());
        json.put("sdk", Build.VERSION.SDK_INT);
        json.put("arch", primaryArch());
        json.put("state", state);
        json.put("wakeUrl", wakeUrl);
        return json;
    }

    private void ensureIdentity() {
        String installId = preferences.getString(KEY_INSTALL_ID, null);
        String secret = preferences.getString(KEY_DEVICE_SECRET, null);
        if (installId != null && secret != null) return;
        byte[] secretBytes = new byte[32];
        new SecureRandom().nextBytes(secretBytes);
        preferences.edit()
            .putString(KEY_INSTALL_ID, installId == null ? UUID.randomUUID().toString() : installId)
            .putString(KEY_DEVICE_SECRET, secret == null ? Base64.encodeToString(secretBytes, Base64.NO_WRAP | Base64.URL_SAFE) : secret)
            .apply();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes("UTF-8"));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) builder.append(String.format("%02x", b & 0xff));
            return builder.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
