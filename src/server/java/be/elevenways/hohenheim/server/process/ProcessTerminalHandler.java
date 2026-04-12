package be.elevenways.hohenheim.server.process;

import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.websocket.WebSocketHandler;
import be.elevenways.zenit.common.websocket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Streams a child process's stdout/stderr verbatim to a ghostty-web terminal
 * (pl-terminal) and forwards the client's keystrokes to the process's stdin.
 *
 * Wire contract matches plumage's TerminalEndpoint so the same client-side
 * BrowserTerminalBridge can drive this handler:
 *   - server -> client: raw terminal bytes as text frames (ANSI preserved)
 *   - client -> server: raw keystrokes, EXCEPT messages that start with
 *     {"type":"resize" -- those are forwarded to the child as
 *     janeway_propose_geometry + janeway_redraw over the TCP IPC channel so
 *     alchemy/janeway apps can reflow. Regular Node.js children without the
 *     IPC bridge simply ignore the messages.
 */
public class ProcessTerminalHandler implements WebSocketHandler {

    private final WebSocketSession session;
    private final ManagedProcess process;
    private final IpcChannel ipc;
    private volatile boolean active = true;
    private Consumer<String> logListener;

    public ProcessTerminalHandler(WebSocketSession session, ManagedProcess process, IpcChannel ipc) {
        this.session = session;
        this.process = process;
        this.ipc = ipc;
    }

    @Override
    public void onOpen() {
        if (process == null || !process.isAlive()) {
            session.sendText("\r\n[process not found or not running]\r\n");
            session.close();
            return;
        }

        String history = process.getLogText();
        if (history != null && !history.isEmpty()) {
            session.sendText(history);
        }

        logListener = chunk -> {
            if (active && session.isOpen()) {
                session.sendText(chunk);
            }
        };
        process.addLogListener(logListener);

        Blast.log("TERMINAL: connected to pid=" + process.pid());
    }

    @Override
    public void onTextMessage(String message) {
        if (message == null) return;

        if (message.startsWith("{\"type\":\"resize\"")) {
            handleResize(message);
            return;
        }

        if (process == null || !process.isAlive()) return;

        try {
            var stdin = process.process().getOutputStream();
            stdin.write(message.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (Exception e) {
            Blast.log("TERMINAL: write to stdin failed:", e.getMessage());
        }
    }

    @Override
    public void onClose(int code, String reason) {
        active = false;
        if (process != null && logListener != null) {
            process.removeLogListener(logListener);
        }
        Blast.log("TERMINAL: disconnected from pid=" + (process != null ? process.pid() : "null"));
    }

    /**
     * Parse {"type":"resize","cols":N,"rows":N} and forward to the child as
     * janeway_propose_geometry (sets size) + janeway_redraw (forces repaint).
     * Matches the original Node.js Hohenheim controller's two-step sequence.
     */
    private void handleResize(String json) {
        if (ipc == null) return;

        int cols = extractInt(json, "cols");
        int rows = extractInt(json, "rows");
        if (cols <= 0 || rows <= 0) return;

        Map<String, Object> propose = new HashMap<>();
        propose.put("type", "janeway_propose_geometry");
        propose.put("cols", cols);
        propose.put("rows", rows);
        ipc.send(propose);

        Map<String, Object> redraw = new HashMap<>();
        redraw.put("type", "janeway_redraw");
        ipc.send(redraw);
    }

    private static int extractInt(String json, String key) {
        String needle = "\"" + key + "\":";
        int idx = json.indexOf(needle);
        if (idx < 0) return -1;
        int start = idx + needle.length();
        while (start < json.length() && !Character.isDigit(json.charAt(start))) start++;
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        if (start == end) return -1;
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
