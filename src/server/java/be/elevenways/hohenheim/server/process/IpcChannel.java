package be.elevenways.hohenheim.server.process;

import be.elevenways.protoblast.common.Blast;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * IPC channel between the parent process and a child process.
 * Uses a TCP loopback socket with newline-delimited JSON messages.
 *
 * Message format (newline-delimited JSON):
 * {"type":"ready"}
 * {"type":"remcache_set","key":"...","value":"...","maxAge":3600}
 * {"type":"remcache_get","id":1,"key":"..."}
 * {"type":"remcache_peek","id":2,"key":"..."}
 * {"type":"remcache_remove","key":"..."}
 *
 * Response format (for get/peek):
 * {"id":1,"value":"..."}
 */
public class IpcChannel implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final int port;
    private volatile Socket clientSocket;
    private volatile BufferedReader reader;
    private volatile PrintWriter writer;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final CountDownLatch connected = new CountDownLatch(1);

    private Consumer<Map<String, Object>> messageHandler;

    public IpcChannel() throws IOException {
        this.serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        this.port = serverSocket.getLocalPort();
    }

    public int getPort() {
        return port;
    }

    public void setMessageHandler(Consumer<Map<String, Object>> handler) {
        this.messageHandler = handler;
    }

    /**
     * Start accepting a connection in a background thread.
     * Call this before spawning the child process.
     */
    public void startAccepting() {
        Thread.startVirtualThread(() -> {
            try {
                clientSocket = serverSocket.accept();
                reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                writer = new PrintWriter(new OutputStreamWriter(clientSocket.getOutputStream()), true);
                connected.countDown();
                readLoop();
            } catch (IOException e) {
                if (!closed.get()) {
                    Blast.log("IPC: accept failed:", e.getMessage());
                }
            }
        });
    }

    private void readLoop() {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (messageHandler != null) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> msg = parseJsonLine(line);
                        if (msg != null) {
                            messageHandler.accept(msg);
                        }
                    } catch (Exception e) {
                        Blast.log("IPC: message parse error:", e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            if (!closed.get()) {
                Blast.log("IPC: read loop ended:", e.getMessage());
            }
        }
    }

    /**
     * Send a JSON message to the child process.
     */
    public void send(Map<String, Object> message) {
        if (writer != null && !closed.get()) {
            writer.println(toJsonLine(message));
        }
    }

    /**
     * Send a response to a request with an ID. Null values serialize as JSON null.
     */
    public void sendResponse(Object requestId, Object value) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("id", requestId);
        resp.put("value", value);
        send(resp);
    }

    public boolean waitForConnection(long timeoutMs) throws InterruptedException {
        return connected.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public boolean isConnected() {
        return clientSocket != null && !clientSocket.isClosed();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try { if (clientSocket != null) clientSocket.close(); } catch (IOException ignored) {}
            try { serverSocket.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Parse a flat JSON object (no nesting) into a Map.
     * Handles string, number, boolean, and null values.
     */
    private static Map<String, Object> parseJsonLine(String line) {
        if (line == null || !line.startsWith("{")) return null;
        Map<String, Object> result = new ConcurrentHashMap<>();
        // Strip outer braces
        String inner = line.substring(1, line.length() - 1).trim();
        if (inner.isEmpty()) return result;

        int i = 0;
        while (i < inner.length()) {
            // Find key
            int keyStart = inner.indexOf('"', i);
            if (keyStart < 0) break;
            int keyEnd = inner.indexOf('"', keyStart + 1);
            if (keyEnd < 0) break;
            String key = inner.substring(keyStart + 1, keyEnd);

            // Skip colon
            int colon = inner.indexOf(':', keyEnd + 1);
            if (colon < 0) break;
            i = colon + 1;
            while (i < inner.length() && inner.charAt(i) == ' ') i++;

            // Parse value
            Object value;
            if (i < inner.length() && inner.charAt(i) == '"') {
                // Skip escaped quotes within the string
                int valEnd = i + 1;
                while (valEnd < inner.length()) {
                    char c = inner.charAt(valEnd);
                    if (c == '\\' && valEnd + 1 < inner.length()) {
                        valEnd += 2;
                        continue;
                    }
                    if (c == '"') break;
                    valEnd++;
                }
                value = unescapeJson(inner.substring(i + 1, valEnd));
                i = valEnd + 1;
            } else if (i < inner.length() && (inner.charAt(i) == 't' || inner.charAt(i) == 'f')) {
                boolean isTrue = inner.charAt(i) == 't';
                value = isTrue;
                i += isTrue ? 4 : 5;
            } else if (i < inner.length() && inner.charAt(i) == 'n') {
                value = null;
                i += 4;
            } else {
                // Number
                int numEnd = i;
                while (numEnd < inner.length() && inner.charAt(numEnd) != ',' && inner.charAt(numEnd) != '}') numEnd++;
                String numStr = inner.substring(i, numEnd).trim();
                try {
                    value = numStr.contains(".") ? Double.parseDouble(numStr) : Long.parseLong(numStr);
                } catch (NumberFormatException e) {
                    value = numStr;
                }
                i = numEnd;
            }

            result.put(key, value);

            // Skip comma
            while (i < inner.length() && (inner.charAt(i) == ',' || inner.charAt(i) == ' ')) i++;
        }
        return result;
    }

    private static String toJsonLine(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
            Object val = entry.getValue();
            if (val == null || "null".equals(val)) {
                sb.append("null");
            } else if (val instanceof Number) {
                sb.append(val);
            } else if (val instanceof Boolean) {
                sb.append(val);
            } else {
                sb.append("\"").append(escapeJson(val.toString())).append("\"");
            }
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String unescapeJson(String s) {
        return s.replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\\", "\\");
    }
}
