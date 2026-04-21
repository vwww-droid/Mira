package com.vwww.mira;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
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

    @Override
    protected void onStop() {
        disconnectRelay();
        super.onStop();
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

        root.addView(controls);
        root.addView(spacer());

        setContentView(scrollView);
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
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RELAY_URL, relayUrl)
            .apply();

        Intent intent = new Intent(this, MiraDiscoveryService.class);
        intent.setAction(MiraDiscoveryService.ACTION_START);
        intent.putExtra(MiraDiscoveryService.EXTRA_DEVICE_NAME, identity.defaultDeviceName());
        intent.putExtra(MiraDiscoveryService.EXTRA_RELAY_URL, relayUrl);
        startService(intent);
    }

    private void disconnectRelay() {
        Intent intent = new Intent(this, MiraDiscoveryService.class);
        intent.setAction(MiraDiscoveryService.ACTION_STOP);
        startService(intent);
    }
}
