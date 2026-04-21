package com.vwww.mira;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final String PREFS = "mira_ui";
    private static final String KEY_DEVICE_NAME = "device_name";
    private static final String KEY_DISCOVERY_PORT = "discovery_port";

    private MiraTerminalServer server;
    private WebView webView;
    private MiraIdentity identity;
    private EditText deviceNameInput;
    private EditText discoveryPortInput;
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        identity = new MiraIdentity(this);
        showControlPage();
    }

    private void showControlPage() {
        SharedPreferences preferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36, 36, 36, 36);
        scrollView.addView(root);

        TextView title = new TextView(this);
        title.setText("Mira Remote Terminal");
        title.setTextSize(24);
        root.addView(title);

        TextView identityView = new TextView(this);
        identityView.setText("installId: " + identity.getInstallId() + "\narch: " + identity.primaryArch());
        identityView.setTextSize(12);
        identityView.setPadding(0, 16, 0, 24);
        root.addView(identityView);

        deviceNameInput = input("Device Name", preferences.getString(KEY_DEVICE_NAME, identity.defaultDeviceName()));
        discoveryPortInput = input("Discovery Port", preferences.getString(KEY_DISCOVERY_PORT, "8766"));
        root.addView(deviceNameInput);
        root.addView(discoveryPortInput);

        Button start = new Button(this);
        start.setText("Start Discovery");
        start.setOnClickListener(view -> startDiscovery());
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("Stop Discovery");
        stop.setOnClickListener(view -> stopDiscovery());
        root.addView(stop);

        Button local = new Button(this);
        local.setText("Local Terminal");
        local.setOnClickListener(view -> startLocalTerminal());
        root.addView(local);

        statusView = new TextView(this);
        statusView.setText("Idle. Start discovery, then scan from Mira Relay server.");
        statusView.setPadding(0, 24, 0, 0);
        root.addView(statusView);

        setContentView(scrollView);
    }

    private EditText input(String hint, String value) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setText(value);
        editText.setSingleLine(true);
        editText.setPadding(0, 18, 0, 18);
        return editText;
    }

    private void startDiscovery() {
        String deviceName = deviceNameInput.getText().toString().trim();
        String portText = discoveryPortInput.getText().toString().trim();
        int port = 8766;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException ignored) {
        }
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_DEVICE_NAME, deviceName)
            .putString(KEY_DISCOVERY_PORT, String.valueOf(port))
            .apply();

        Intent intent = new Intent(this, MiraDiscoveryService.class);
        intent.setAction(MiraDiscoveryService.ACTION_START);
        intent.putExtra(MiraDiscoveryService.EXTRA_DEVICE_NAME, deviceName);
        intent.putExtra(MiraDiscoveryService.EXTRA_DISCOVERY_PORT, port);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);
        statusView.setText("Discovery running on UDP port " + port + ". Scan from Mira Relay server.");
    }

    private void stopDiscovery() {
        Intent intent = new Intent(this, MiraDiscoveryService.class);
        intent.setAction(MiraDiscoveryService.ACTION_STOP);
        startService(intent);
        statusView.setText("Discovery stopped.");
    }

    private void startLocalTerminal() {
        try {
            MiraBootstrap bootstrap = new MiraBootstrap(this);
            bootstrap.installIfNeeded();
            closeLocalTerminal();
            server = new MiraTerminalServer(this, bootstrap, 0);
            server.start();
            setupWebView();
            String url = "http://127.0.0.1:" + server.getPort() + "/?token=" + server.getToken();
            if (BuildConfig.DEBUG) Log.i("Mira", "Mira Web Terminal listening on " + url);
            webView.loadUrl(url);
        } catch (Throwable throwable) {
            TextView errorView = new TextView(this);
            errorView.setText("Mira 启动失败\n\n" + throwable);
            errorView.setTextSize(14);
            errorView.setPadding(32, 32, 32, 32);
            setContentView(errorView);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        setContentView(webView);
    }

    private void closeLocalTerminal() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        if (server != null) {
            server.close();
            server = null;
        }
    }

    @Override
    protected void onDestroy() {
        closeLocalTerminal();
        super.onDestroy();
    }
}
