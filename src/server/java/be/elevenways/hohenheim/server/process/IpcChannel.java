package be.elevenways.hohenheim.server.process;

import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.server.security.SecureTokens;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * IPC channel between the parent process and a child process.
 * Uses a TCP loopback socket with newline-delimited JSON messages.
 *
 * AIDEV-NOTE: loopback is NOT an authorization boundary here -- every local user can
 * reach this port. The channel therefore authenticates: the child's FIRST line must
 * be an auth message carrying the per-channel secret handed to it in
 * HOHENHEIM_IPC_TOKEN, compared constant-time. The complementary half is
 * WorkloadIdentity: it ENFORCES (not merely documents) that managed processes run
 * under distinct, per-site-claimed system users, because with a shared uid the token
 * is readable from /proc/<pid>/environ and this handshake authenticates the thief.
 * Shape adopted from DevTunnelServerHandler (pre-auth cap + auth window + the
 * accept loop surviving a refused peer), because the old single accept() let any
 * local user connect first and wedge the victim's channel forever.
 *
 * Handshake (from the child, first line):
 * {"type":"auth","token":"<HOHENHEIM_IPC_TOKEN>"}
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

    /** How long an accepted peer has to present the token before it is dropped. */
    private static final long AUTH_TIMEOUT_MS = 10_000;

    /** Concurrent unauthenticated handshakes tolerated; further peers are dropped at once. */
    private static final int MAX_PRE_AUTH_CONNECTIONS = 8;

    /** Longest handshake line accepted, so a peer cannot stream unbounded pre-auth data. */
    private static final int MAX_AUTH_LINE_CHARS = 4096;

    private final ServerSocket serverSocket;
    private final int port;
    private final String secret;
    private final Object peerLock = new Object();
    private volatile Socket clientSocket;
    private volatile PrintWriter writer;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger preAuthConnections = new AtomicInteger();
    private final CountDownLatch connected = new CountDownLatch(1);

    private volatile Consumer<Map<String, Object>> messageHandler;

    // Messages that arrive before the handler is attached (a fast child can signal
    // ready during the parent's startup grace window) are buffered, not dropped.
    private final List<Map<String, Object>> pendingMessages = new ArrayList<>();
    private static final int PENDING_CAP = 64;

    public IpcChannel() throws IOException {
        this.serverSocket = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
        this.port = serverSocket.getLocalPort();
        this.secret = SecureTokens.randomToken(24);
    }

    public int getPort() {
        return port;
    }

    /** The per-child secret; hand it to the child exactly like the port. */
    public String getSecret() {
        return secret;
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
     * Start accepting connections in a background thread.
     * Call this before spawning the child process.
     */
    public void startAccepting() {
        Thread.startVirtualThread(this::acceptLoop);
    }

    /**
     * Accepts for the channel's whole lifetime: a peer that fails the handshake, or
     * an authenticated peer that disconnects, leaves the channel connectable again.
     */
    private void acceptLoop() {
        while (!closed.get()) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                if (!closed.get()) {
                    Blast.log("IPC: accept failed:", e.getMessage());
                }
                return;
            }
            if (preAuthConnections.incrementAndGet() > MAX_PRE_AUTH_CONNECTIONS) {
                preAuthConnections.decrementAndGet();
                closeQuietly(socket);
                continue;
            }
            // Off the accept lane: a peer that stalls mid-handshake must not delay
            // the real child's connection.
            Thread.startVirtualThread(() -> handshakeAndServe(socket));
        }
    }

    /**
     * Authenticate one accepted peer and, if it is the child, serve it.
     *
     * AIDEV-NOTE: the pre-auth slot is released the MOMENT authentication succeeds
     * (a genuine pre-auth -> connected transition), NOT when the peer disconnects.
     * The old code decremented only after readLoop returned, so an authenticated,
     * SERVING child held a pre-auth slot for its entire lifetime -- the counter
     * conflated pre-auth with connected, leaving an effective pre-auth budget of 7
     * while a child was attached and letting a local peer that connected first
     * permanently occupy a slot. A separate cap on CONNECTED peers is unnecessary:
     * only one peer is ever attached (the peerLock guard closes any authenticated
     * latecomer at once), and only the token holder can pass authenticate(), so the
     * pre-auth cap already bounds every token-less flood.
     */
    private void handshakeAndServe(Socket socket) {
        AtomicBoolean preAuthReleased = new AtomicBoolean(false);
        Runnable releasePreAuth = () -> {
            if (preAuthReleased.compareAndSet(false, true)) {
                preAuthConnections.decrementAndGet();
            }
        };
        try {
            BufferedReader reader;
            try {
                socket.setSoTimeout((int) AUTH_TIMEOUT_MS);
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                if (!authenticate(reader)) {
                    closeQuietly(socket);
                    return;
                }
                socket.setSoTimeout(0);
            } catch (SocketTimeoutException e) {
                Blast.log("IPC: dropped a peer that never authenticated");
                closeQuietly(socket);
                return;
            } catch (IOException e) {
                closeQuietly(socket);
                return;
            }

            // Authenticated: this peer is CONNECTED, not pre-auth. Free its slot now
            // so an attached child never consumes pre-auth budget for its lifetime.
            releasePreAuth.run();

            PrintWriter peerWriter = new PrintWriter(
                new OutputStreamWriter(quietOutputStream(socket)), true);
            synchronized (peerLock) {
                if (closed.get() || clientSocket != null) {
                    // The child is already attached; an authenticated latecomer waits for
                    // the current peer to go away rather than displacing it.
                    closeQuietly(socket);
                    return;
                }
                clientSocket = socket;
                writer = peerWriter;
            }
            connected.countDown();
            try {
                readLoop(reader);
            } finally {
                synchronized (peerLock) {
                    if (clientSocket == socket) {
                        clientSocket = null;
                        writer = null;
                    }
                }
                closeQuietly(socket);
            }
        } finally {
            // Idempotent: covers every failure path (auth refusal, timeout, IOException)
            // that returned before the connected transition released the slot.
            releasePreAuth.run();
        }
    }

    /**
     * @return true when the peer's first line is an auth message carrying this
     *         channel's secret; a correct token inside any other message type is
     *         refused, so "auth is the first line" is a real wire contract
     */
    private boolean authenticate(BufferedReader reader) throws IOException {
        String line = readBoundedLine(reader);
        Map<String, Object> message = line != null ? parseJsonLine(line) : null;
        Object token = message != null ? message.get("token") : null;
        boolean ok = message != null
            && "auth".equals(message.get("type"))
            && token instanceof String presented
            && SecureTokens.constantTimeEquals(secret, presented);
        if (!ok) {
            Blast.log("IPC: refused an unauthenticated peer on port", port);
        }
        return ok;
    }

    /** Reads one line, refusing a peer that streams past the handshake budget. */
    private static String readBoundedLine(BufferedReader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        int read;
        while ((read = reader.read()) >= 0) {
            char c = (char) read;
            if (c == '\n') {
                return line.toString();
            }
            if (line.length() >= MAX_AUTH_LINE_CHARS) {
                return null;
            }
            if (c != '\r') {
                line.append(c);
            }
        }
        return null;
    }

    private void readLoop(BufferedReader reader) {
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

    private static OutputStream quietOutputStream(Socket socket) {
        try {
            return socket.getOutputStream();
        } catch (IOException e) {
            return OutputStream.nullOutputStream();
        }
    }

    private static void closeQuietly(Socket socket) {
        try { socket.close(); } catch (IOException ignored) {}
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
