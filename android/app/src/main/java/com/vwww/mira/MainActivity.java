package com.vwww.mira;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.URI;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String PREFS = "mira_ui";
    private static final String KEY_RELAY_URL = "relay_url";

    private MiraIdentity identity;
    private EditText relayUrlInput;
    private TextView statusText;
    private boolean receiverRegistered;
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!MiraDiscoveryService.ACTION_STATUS.equals(intent.getAction())) return;
            setStatus(intent.getStringExtra(MiraDiscoveryService.EXTRA_STATUS));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        identity = new MiraIdentity(this);
        showControlPage();
        MiraOutlineCollector.getInstance().register(this);
        MiraSelfScreenCapture.getInstance().register(this);
        requestOutlineUploadSoon();
    }

    @Override
    protected void onStart() {
        super.onStart();
        MiraOutlineCollector.getInstance().register(this);
        MiraSelfScreenCapture.getInstance().register(this);
        requestOutlineUploadSoon();
        if (!receiverRegistered) {
            registerReceiver(statusReceiver, new IntentFilter(MiraDiscoveryService.ACTION_STATUS));
            receiverRegistered = true;
        }
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        MiraOutlineCollector.getInstance().unregister(this);
        MiraSelfScreenCapture.getInstance().unregister(this);
        super.onDestroy();
    }

    private void showControlPage() {
        SharedPreferences preferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36, 36, 36, 36);
        root.setLayoutParams(new ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT,
            ScrollView.LayoutParams.MATCH_PARENT
        ));
        scrollView.addView(root);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView title = title("Mira", 44, Typeface.create("serif", Typeface.BOLD));
        title.setLetterSpacing(0.04f);
        title.setLayoutParams(new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ));
        titleRow.addView(title);

        TextView byline = title("by vw2x", 13, Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        byline.setLetterSpacing(0.12f);
        byline.setGravity(Gravity.RIGHT);
        titleRow.addView(byline);
        root.addView(titleRow);

        root.addView(spacer());

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setGravity(Gravity.CENTER_HORIZONTAL);
        controls.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        relayUrlInput = input("Relay URL", preferences.getString(KEY_RELAY_URL, ""));
        controls.addView(relayUrlInput);

        Button start = new Button(this);
        start.setText("Connect Relay");
        start.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        start.setOnClickListener(view -> connectRelay());
        controls.addView(button(start));

        Button stop = new Button(this);
        stop.setText("Disconnect");
        stop.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        stop.setOnClickListener(view -> disconnectRelay());
        controls.addView(button(stop));

        statusText = title("Status: disconnected", 14, Typeface.create("monospace", Typeface.NORMAL));
        statusText.setPadding(0, 18, 0, 0);
        controls.addView(statusText);

        root.addView(controls);
        root.addView(spacer());

        setContentView(scrollView);
        MiraOutlineCollector.getInstance().register(this);
        MiraSelfScreenCapture.getInstance().register(this);
    }

    private View spacer() {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ));
        return view;
    }

    private Button button(Button button) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 16, 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    private TextView title(String text, int size, Typeface typeface) {
        TextView textView = new TextView(this);
        textView.setText(text);
        textView.setTextSize(size);
        textView.setTypeface(typeface);
        return textView;
    }

    private EditText input(String hint, String value) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setText(value);
        editText.setSingleLine(true);
        editText.setPadding(0, 18, 0, 18);
        editText.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        editText.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return editText;
    }

    private void connectRelay() {
        String relayUrl = relayUrlInput.getText().toString().trim();
        if (relayUrl.isEmpty()) return;
        if (isPhoneLocalhostUrl(relayUrl)) {
            setStatus("Do not use localhost on phone. Paste the Android Relay URL, for example http://192.168.x.x:8765.");
            return;
        }
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RELAY_URL, relayUrl)
            .apply();

        Intent intent = new Intent(this, MiraDiscoveryService.class);
        intent.setAction(MiraDiscoveryService.ACTION_START);
        intent.putExtra(MiraDiscoveryService.EXTRA_DEVICE_NAME, identity.defaultDeviceName());
        intent.putExtra(MiraDiscoveryService.EXTRA_RELAY_URL, relayUrl);
        startService(intent);
        setStatus("connecting relay");
        requestOutlineUploadSoon();
    }

    private void disconnectRelay() {
        Intent intent = new Intent(this, MiraDiscoveryService.class);
        intent.setAction(MiraDiscoveryService.ACTION_STOP);
        startService(intent);
        setStatus("disconnected");
    }

    private void requestOutlineUploadSoon() {
        View decor = getWindow() == null ? null : getWindow().getDecorView();
        if (decor == null) return;
        decor.postDelayed(MiraDiscoveryService::requestOutlineUpload, 250);
    }

    private void setStatus(String status) {
        if (statusText == null) return;
        String value = status == null || status.trim().isEmpty() ? "unknown" : status.trim();
        statusText.setText("Status: " + value);
    }

    private boolean isPhoneLocalhostUrl(String value) {
        try {
            String raw = value.trim();
            if (!raw.contains("://")) raw = "https://" + raw;
            URI uri = new URI(raw);
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return "localhost".equals(host) || "0.0.0.0".equals(host) || "::1".equals(host) || host.startsWith("127.");
        } catch (Exception ignored) {
            return false;
        }
    }
}
