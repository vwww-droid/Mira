package com.vwww.mira;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MiraRelayClient implements Closeable {
    private static final String TAG = "MiraRelayClient";

    private final Context context;
    private final MiraBootstrap bootstrap;
    private final MiraIdentity identity;
    private final String serverWs;
    private final String sessionId;
    private final int initialColumns;
    private final int initialRows;
    private final int initialCellWidth;
    private final int initialCellHeight;
    private final Runnable onClose;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile MiraWebSocketConnection websocket;
    private MiraPtySession pty;
    private MiraToolbox toolbox;
    private Thread workerThread;
    private Thread ptyReaderThread;
    private Thread ptyWaiterThread;

    public MiraRelayClient(
        Context context,
        MiraBootstrap bootstrap,
        MiraIdentity identity,
        String serverWs,
        String sessionId,
        int initialColumns,
        int initialRows,
        int initialCellWidth,
        int initialCellHeight,
        Runnable onClose
    ) {
        this.context = context.getApplicationContext();
        this.bootstrap = bootstrap;
        this.identity = identity;
        this.serverWs = serverWs;
        this.sessionId = sessionId;
        this.initialColumns = initialColumns <= 0 ? 80 : initialColumns;
        this.initialRows = initialRows <= 0 ? 24 : initialRows;
        this.initialCellWidth = Math.max(initialCellWidth, 0);
        this.initialCellHeight = Math.max(initialCellHeight, 0);
        this.onClose = onClose;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        workerThread = new Thread(this::runRelay, "MiraRelayClient");
        workerThread.start();
    }

    public boolean hasSession(String value) {
        return sessionId.equals(value);
    }

    private void runRelay() {
        try {
            Log.i(TAG, "Relay starting sessionId=" + sessionId + " ws=" + serverWs);
            bootstrap.installIfNeeded();
            Log.i(TAG, "Bootstrap ready sessionId=" + sessionId);
            if (!running.get()) return;
            toolbox = MiraToolbox.prepare(context, sessionId);
            Log.i(TAG, "Toolbox ready sessionId=" + sessionId + " bin=" + toolbox.binDirPath());
            if (!running.get()) return;
            Log.i(TAG, "Creating PTY sessionId=" + sessionId);
            pty = MiraPtyFactory.create(context, bootstrap, initialRows, initialColumns, initialCellWidth, initialCellHeight, toolbox);
            Log.i(TAG, "PTY started backend=" + pty.getBackendName() + " pid=" + pty.getPid() + " cols=" + initialColumns + " rows=" + initialRows + " cellWidth=" + initialCellWidth + " cellHeight=" + initialCellHeight);
            if (!running.get()) return;
            Log.i(TAG, "Connecting relay websocket sessionId=" + sessionId + " url=" + serverWs);
            MiraWebSocketConnection connected = MiraWebSocketConnection.connect(serverWs);
            if (!running.get()) {
                connected.close();
                return;
            }
            websocket = connected;
            Log.i(TAG, "Relay websocket connected sessionId=" + sessionId);
            connected.sendJson(attachMessage());
            Log.i(TAG, "Device attached sessionId=" + sessionId + " installId=" + identity.getInstallId());
            ptyReaderThread = new Thread(this::pumpPtyToServer, "MiraRelayPtyReader-" + pty.getPid());
            ptyReaderThread.start();
            MiraPtySession waitingPty = pty;
            ptyWaiterThread = new Thread(() -> waitForPtyExit(waitingPty), "MiraRelayPtyWaiter-" + waitingPty.getPid());
            ptyWaiterThread.start();
            readServerLoop();
        } catch (Throwable throwable) {
            if (!running.get()) {
                Log.i(TAG, "Relay closed");
            } else {
                Log.w(TAG, "Relay failed", throwable);
            }
        } finally {
            close();
            if (onClose != null) onClose.run();
        }
    }

    private void waitForPtyExit(MiraPtySession waitingPty) {
        try {
            int exitCode = waitingPty.waitFor();
            Log.i(TAG, "PTY exited backend=" + waitingPty.getBackendName() + " pid=" + waitingPty.getPid() + " exitCode=" + exitCode);
        } catch (Throwable throwable) {
            Log.w(TAG, "PTY waiter failed", throwable);
        }
    }

    private JSONObject attachMessage() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("type", "device.attach");
        json.put("protocol", 1);
        json.put("installId", identity.getInstallId());
        json.put("sessionId", sessionId);
        return json;
    }

    private void readServerLoop() throws Exception {
        while (running.get()) {
            MiraWebSocketConnection current = websocket;
            if (current == null) break;
            MiraWebSocketConnection.WebSocketFrame frame = current.readFrame();
            if (frame.isClose()) break;
            if (frame.isPing()) {
                current.sendPong(frame.payload);
                continue;
            }
            if (!frame.isText()) continue;
            handleServerMessage(new String(frame.payload, StandardCharsets.UTF_8));
        }
    }

    private void handleServerMessage(String text) throws Exception {
        JSONObject json = new JSONObject(text);
        String type = json.optString("type", "");
        if ("terminal.input".equals(type)) {
            byte[] data = Base64.decode(json.optString("dataBase64", ""), Base64.DEFAULT);
            if (pty != null) pty.write(data);
        } else if ("terminal.resize".equals(type)) {
            if (pty != null) {
                pty.resize(
                    json.optInt("cols", 0),
                    json.optInt("rows", 0),
                    json.optInt("cellWidth", 0),
                    json.optInt("cellHeight", 0)
                );
            }
        } else if ("session.close".equals(type)) {
            running.set(false);
        } else if ("error".equals(type)) {
            Log.w(TAG, "Relay server error: " + json.optString("error"));
        }
    }

    private void pumpPtyToServer() {
        byte[] buffer = new byte[8192];
        try {
            while (running.get() && pty != null) {
                int read = pty.read(buffer);
                if (read == -1) break;
                byte[] payload = new byte[read];
                System.arraycopy(buffer, 0, payload, 0, read);
                JSONObject json = new JSONObject();
                json.put("type", "terminal.output");
                json.put("sessionId", sessionId);
                json.put("dataBase64", Base64.encodeToString(payload, Base64.NO_WRAP));
                sendJson(json);
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                JSONObject json = new JSONObject();
                json.put("type", "session.close");
                json.put("sessionId", sessionId);
                sendJson(json);
            } catch (Throwable ignored) {
            }
        }
    }

    private void sendJson(JSONObject json) throws IOException {
        MiraWebSocketConnection current = websocket;
        if (current != null) current.sendJson(json);
    }

    @Override
    public void close() {
        running.set(false);
        if (pty != null) {
            pty.close();
            pty = null;
        }
        MiraWebSocketConnection closing = websocket;
        websocket = null;
        if (closing != null) closing.close();
        if (toolbox != null) {
            toolbox.close();
            toolbox = null;
        }
    }
}
