package be.elevenways.hohenheim.server.process;

import be.elevenways.protoblast.common.Blast;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
 * {"type":"ready"}                       (legacy envelope {"alchemy":{"ready":true}} also accepted)
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

    private volatile Consumer<Map<String, Object>> messageHandler;

    // Messages that arrive before the handler is attached (a fast child can signal
    // ready during the parent's startup grace window) are buffered, not dropped.
    private final List<Map<String, Object>> pendingMessages = new ArrayList<>();
    private static final int PENDING_CAP = 64;

    public IpcChannel() throws IOException {
        this.serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        this.port = serverSocket.getLocalPort();
    }

    public int getPort() {
        return port;
    }

    public void setMessageHandler(Consumer<Map<String, Object>> handler) {
        List<Map<String, Object>> replay;
        synchronized (pendingMessages) {
            this.messageHandler = handler;
            replay = new ArrayList<>(pendingMessages);
            pendingMessages.clear();
        }
        for (Map<String, Object> msg : replay) {
            handler.accept(msg);
        }
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
                try {
                    Map<String, Object> msg = parseJsonLine(line);
                    if (msg == null) {
                        continue;
                    }
                    Consumer<Map<String, Object>> handler;
                    synchronized (pendingMessages) {
                        handler = messageHandler;
                        if (handler == null) {
                            if (pendingMessages.size() < PENDING_CAP) {
                                pendingMessages.add(msg);
                            }
                            continue;
                        }
                    }
                    handler.accept(msg);
                } catch (Exception e) {
                    Blast.log("IPC: message parse error:", e.getMessage());
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
     * Parse a JSON object line into a Map. Handles string, number, boolean, null,
     * and NESTED OBJECT values -- the nesting matters because legacy children signal
     * readiness as {@code {"alchemy":{"ready":true}}} (the original Node IPC envelope).
     * Arrays are not part of the protocol and parse as raw strings.
     */
    private static Map<String, Object> parseJsonLine(String line) {
        if (line == null) return null;
        line = line.trim();
        if (!line.startsWith("{")) return null;
        int[] pos = {0};
        Object parsed = parseJsonValue(line, pos);
        if (parsed instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        return null;
    }

    private static Object parseJsonValue(String text, int[] pos) {
        skipWhitespace(text, pos);
        if (pos[0] >= text.length()) return null;
        char c = text.charAt(pos[0]);
        if (c == '{') {
            return parseJsonObject(text, pos);
        }
        if (c == '"') {
            return parseJsonString(text, pos);
        }
        if (text.startsWith("true", pos[0])) {
            pos[0] += 4;
            return Boolean.TRUE;
        }
        if (text.startsWith("false", pos[0])) {
            pos[0] += 5;
            return Boolean.FALSE;
        }
        if (text.startsWith("null", pos[0])) {
            pos[0] += 4;
            return null;
        }
        // Number (or anything else, kept raw): read until a structural character.
        int end = pos[0];
        while (end < text.length() && ",}]".indexOf(text.charAt(end)) < 0) end++;
        String raw = text.substring(pos[0], end).trim();
        pos[0] = end;
        try {
            return raw.contains(".") ? Double.parseDouble(raw) : Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    private static Map<String, Object> parseJsonObject(String text, int[] pos) {
        Map<String, Object> result = new ConcurrentHashMap<>();
        pos[0]++;   // consume '{'
        skipWhitespace(text, pos);
        if (pos[0] < text.length() && text.charAt(pos[0]) == '}') {
            pos[0]++;
            return result;
        }
        while (pos[0] < text.length()) {
            skipWhitespace(text, pos);
            if (pos[0] >= text.length() || text.charAt(pos[0]) != '"') break;
            String key = parseJsonString(text, pos);
            skipWhitespace(text, pos);
            if (pos[0] >= text.length() || text.charAt(pos[0]) != ':') break;
            pos[0]++;   // consume ':'
            Object value = parseJsonValue(text, pos);
            if (value != null) {
                result.put(key, value);   // ConcurrentHashMap refuses nulls; absent = null
            }
            skipWhitespace(text, pos);
            if (pos[0] < text.length() && text.charAt(pos[0]) == ',') {
                pos[0]++;
                continue;
            }
            if (pos[0] < text.length() && text.charAt(pos[0]) == '}') {
                pos[0]++;
            }
            break;
        }
        return result;
    }

    private static String parseJsonString(String text, int[] pos) {
        pos[0]++;   // consume opening quote
        StringBuilder raw = new StringBuilder();
        while (pos[0] < text.length()) {
            char c = text.charAt(pos[0]);
            if (c == '\\' && pos[0] + 1 < text.length()) {
                raw.append(c).append(text.charAt(pos[0] + 1));
                pos[0] += 2;
                continue;
            }
            if (c == '"') {
                pos[0]++;
                break;
            }
            raw.append(c);
            pos[0]++;
        }
        return unescapeJson(raw.toString());
    }

    private static void skipWhitespace(String text, int[] pos) {
        while (pos[0] < text.length() && Character.isWhitespace(text.charAt(pos[0]))) pos[0]++;
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
