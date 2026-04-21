package com.vwww.mira;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final String PREFS = "mira_ui";
    private static final String KEY_RELAY_URL = "relay_url";

    private MiraIdentity identity;
    private EditText relayUrlInput;

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

        TextView title = title("Mira", 44, Typeface.create("serif", Typeface.BOLD));
        title.setLetterSpacing(0.04f);
        root.addView(title);

        TextView byline = title("by vw2x", 13, Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        byline.setLetterSpacing(0.12f);
        byline.setPadding(2, 0, 0, 30);
        root.addView(byline);

        relayUrlInput = input("Relay URL", preferences.getString(KEY_RELAY_URL, ""));
        root.addView(relayUrlInput);

        Button start = new Button(this);
        start.setText("Connect Relay");
        start.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        start.setOnClickListener(view -> connectRelay());
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("Disconnect");
        stop.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        stop.setOnClickListener(view -> disconnectRelay());
        root.addView(stop);

        setContentView(scrollView);
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
        return editText;
    }

    private void connectRelay() {
        String relayUrl = relayUrlInput.getText().toString().trim();
        if (relayUrl.isEmpty()) return;
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RELAY_URL, relayUrl)
            .apply();

        Intent intent = new Intent(this, MiraDiscoveryService.class);
        intent.setAction(MiraDiscoveryService.ACTION_START);
        intent.putExtra(MiraDiscoveryService.EXTRA_DEVICE_NAME, identity.defaultDeviceName());
        intent.putExtra(MiraDiscoveryService.EXTRA_RELAY_URL, relayUrl);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);
    }

    private void disconnectRelay() {
        Intent intent = new Intent(this, MiraDiscoveryService.class);
        intent.setAction(MiraDiscoveryService.ACTION_STOP);
        startService(intent);
    }
}
