package be.elevenways.hohenheim.server.process;

import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.websocket.WebSocketHandler;
import be.elevenways.zenit.common.websocket.WebSocketSession;

import java.util.function.Consumer;

/**
 * WebSocket handler that streams a child process's stdout/stderr to the client,
 * and forwards client input to the process's stdin.
 * Equivalent to the original Node.js 'terminallink' linkup.
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
            session.sendText("{\"type\":\"error\",\"message\":\"Process not found or not running\"}");
            session.close();
            return;
        }

        session.sendText("{\"type\":\"connected\",\"pid\":" + process.pid() + ",\"port\":" + process.port() + "}");

        // Send existing log history
        String logHtml = process.getLogHtml();
        if (logHtml != null && !logHtml.isEmpty()) {
            session.sendText("{\"type\":\"history\",\"data\":\"" + escapeJson(logHtml) + "\"}");
        }

        // Stream new output lines as they arrive
        logListener = line -> {
            if (active && session.isOpen()) {
                session.sendText("{\"type\":\"output\",\"data\":\"" + escapeJson(line) + "\"}");
            }
        };
        process.addLogListener(logListener);

        Blast.log("TERMINAL: connected to pid=" + process.pid());
    }

    @Override
    public void onTextMessage(String message) {
        // Forward client input to the process's stdin
        if (process != null && process.isAlive()) {
            try {
                var stdin = process.process().getOutputStream();
                stdin.write(message.getBytes());
                stdin.flush();
            } catch (Exception e) {
                Blast.log("TERMINAL: write to stdin failed:", e.getMessage());
            }
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

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
