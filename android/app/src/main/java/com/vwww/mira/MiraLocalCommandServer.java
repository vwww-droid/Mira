package com.vwww.mira;

import android.content.Context;
import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MiraLocalCommandServer implements Closeable {
    private static final String TAG = "MiraCommandServer";
    private static final String SOCKET_NAME = "mira-command.sock";

    private final Context context;
    private final File socketFile;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private LocalSocket listenSocket;
    private LocalServerSocket serverSocket;
    private Thread acceptThread;

    public MiraLocalCommandServer(Context context) {
        this.context = context.getApplicationContext();
        this.socketFile = socketFile(this.context);
    }

    public static File socketFile(Context context) {
        return new File(runDir(context), SOCKET_NAME);
    }

    public static File runDir(Context context) {
        return new File(context.getFilesDir(), "run");
    }

    public synchronized void start() throws IOException {
        if (running.get()) return;
        File dir = socketFile.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Unable to create command socket dir: " + dir.getAbsolutePath());
        }
        if (dir != null) {
            if (!dir.setReadable(true, true)) throw new IOException("Unable to make command socket dir readable: " + dir.getAbsolutePath());
            if (!dir.setWritable(true, true)) throw new IOException("Unable to make command socket dir writable: " + dir.getAbsolutePath());
            if (!dir.setExecutable(true, true)) throw new IOException("Unable to make command socket dir executable: " + dir.getAbsolutePath());
        }
        if (socketFile.exists() && !socketFile.delete()) {
            throw new IOException("Unable to replace command socket: " + socketFile.getAbsolutePath());
        }

        LocalSocket socket = new LocalSocket();
        socket.bind(new LocalSocketAddress(socketFile.getAbsolutePath(), LocalSocketAddress.Namespace.FILESYSTEM));
        listenSocket = socket;
        serverSocket = new LocalServerSocket(socket.getFileDescriptor());
        running.set(true);
        acceptThread = new Thread(this::acceptLoop, "MiraCommandAccept");
        acceptThread.start();
        Log.i(TAG, "Command socket started path=" + socketFile.getAbsolutePath());
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                LocalSocket client = serverSocket.accept();
                executor.execute(() -> handleClient(client));
            } catch (IOException failure) {
                if (running.get()) Log.w(TAG, "Command socket accept failed", failure);
            }
        }
    }

    private void handleClient(LocalSocket client) {
        try (LocalSocket socket = client) {
            Credentials credentials = socket.getPeerCredentials();
            int uid = credentials == null ? -1 : credentials.getUid();
            if (uid != Process.myUid()) {
                MiraCommandProtocol.writeJson(socket.getOutputStream(), MiraCommandProtocol.resultJson(
                    MiraCommandResult.error("mira command bridge rejected peer uid=" + uid + "\n")
                ));
                return;
            }

            PushbackInputStream input = new PushbackInputStream(socket.getInputStream(), 4);
            byte[] prefix = readPrefix(input);
            if (isTextProtocol(prefix)) {
                String line = readTextLine(input, prefix);
                TextRequest request = parseTextRequest(line);
                MiraCommandResult result = MiraCommandRouter.dispatch(context, request.tool, request.argv);
                MiraCommandProtocol.writeTextResult(socket.getOutputStream(), result);
            } else {
                input.unread(prefix);
                JSONObject request = MiraCommandProtocol.readJson(input);
                String tool = request.optString("tool", "");
                List<String> argv = toStringList(request.optJSONArray("argv"));
                MiraCommandResult result = MiraCommandRouter.dispatch(context, tool, argv);
                MiraCommandProtocol.writeJson(socket.getOutputStream(), MiraCommandProtocol.resultJson(result));
            }
        } catch (Throwable failure) {
            Log.w(TAG, "Command client failed", failure);
        }
    }

    private static byte[] readPrefix(InputStream input) throws IOException {
        byte[] prefix = new byte[4];
        int offset = 0;
        while (offset < prefix.length) {
            int read = input.read(prefix, offset, prefix.length - offset);
            if (read == -1) throw new IOException("empty command request");
            offset += read;
        }
        return prefix;
    }

    private static boolean isTextProtocol(byte[] prefix) {
        return prefix != null && prefix.length == 4 &&
            prefix[0] == 'M' && prefix[1] == 'I' && prefix[2] == 'R' && prefix[3] == 'A';
    }

    private static String readTextLine(InputStream input, byte[] prefix) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int length = 0;
        for (byte value : prefix) {
            buffer[length++] = value;
        }
        while (length < buffer.length) {
            int value = input.read();
            if (value == -1 || value == '\n') break;
            buffer[length++] = (byte) value;
        }
        return new String(buffer, 0, length, StandardCharsets.UTF_8).trim();
    }

    private static TextRequest parseTextRequest(String line) {
        if (line == null || line.isEmpty()) {
            throw new IllegalArgumentException("empty text request");
        }
        String[] parts = line.split(" ");
        if (parts.length < 2 || !"MIRA/1".equals(parts[0])) {
            throw new IllegalArgumentException("invalid text request");
        }
        List<String> argv = new ArrayList<>();
        for (int i = 2; i < parts.length; i++) {
            if (!parts[i].isEmpty()) argv.add(MiraCommandProtocol.decode(parts[i]));
        }
        return new TextRequest(parts[1], argv);
    }

    private static List<String> toStringList(JSONArray array) {
        List<String> values = new ArrayList<>();
        if (array == null) return values;
        for (int i = 0; i < array.length(); i++) {
            values.add(array.optString(i, ""));
        }
        return values;
    }

    @Override
    public synchronized void close() {
        running.set(false);
        closeQuietly(serverSocket);
        closeQuietly(listenSocket);
        serverSocket = null;
        listenSocket = null;
        if (socketFile.exists() && !socketFile.delete()) {
            Log.w(TAG, "Unable to delete command socket " + socketFile.getAbsolutePath());
        }
        executor.shutdownNow();
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    private static final class TextRequest {
        final String tool;
        final List<String> argv;

        TextRequest(String tool, List<String> argv) {
            this.tool = tool;
            this.argv = argv;
        }
    }
}
