package be.elevenways.hohenheim.server.process;

import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.websocket.WebSocketHandler;
import be.elevenways.zenit.common.websocket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Streams a child process's stdout/stderr verbatim to a ghostty-web terminal
 * (pl-terminal) and forwards the client's keystrokes to the process's stdin.
 *
 * Wire contract matches plumage's TerminalEndpoint so the same client-side
 * BrowserTerminalBridge can drive this handler:
 *   - server -> client: raw terminal bytes as text frames (ANSI preserved)
 *   - client -> server: raw keystrokes, EXCEPT messages that start with
 *     {"type":"resize" -- those are parsed and currently ignored (child
 *     processes are not PTYs, so TIOCSWINSZ would have no effect anyway).
 */
public class ProcessTerminalHandler implements WebSocketHandler {

    private final WebSocketSession session;
    private final ManagedProcess process;
    private volatile boolean active = true;
    private Consumer<String> logListener;

    public ProcessTerminalHandler(WebSocketSession session, ManagedProcess process) {
        this.session = session;
        this.process = process;
    }

    @Override
    public void onOpen() {
        if (process == null || !process.isAlive()) {
            session.sendText("\r\n[process not found or not running]\r\n");
            session.close();
            return;
        }

        // Replay the rolling buffer so the terminal shows recent output immediately.
        String history = process.getLogText();
        if (history != null && !history.isEmpty()) {
            session.sendText(history);
        }

        // Stream new chunks as they arrive.
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

        // Resize messages are a JSON envelope; ignore for now (no PTY layer).
        if (message.startsWith("{\"type\":\"resize\"")) {
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
}
