package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.common.websocket.WebSocketHandler;
import be.elevenways.zenit.common.websocket.WebSocketSession;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.function.Consumer;

/**
 * Streams an instance's live console to a read-only pl-terminal (the
 * ProcessTerminalHandler shape). OUTPUT ONLY by design: a non-TTY container never
 * echoes, so raw keystrokes would be invisible typing -- commands ride the console
 * form's POST endpoint, which also funnels stop-command observation through the hub.
 */
public final class InstanceConsoleHandler implements WebSocketHandler {

    private final WebSocketSession session;
    private final @Nullable Integer instanceId;
    private volatile boolean active = true;
    private @Nullable InstanceConsoleSession console;
    private @Nullable Consumer<String> listener;

    public InstanceConsoleHandler(WebSocketSession session, @Nullable Integer instanceId) {
        this.session = session;
        this.instanceId = instanceId;
    }

    @Override
    public void onOpen() {
        // requiresLogin already refused anonymous handshakes with 401; the per-record
        // manage capability needs the route param, so it runs here. 1008 = policy.
        Principal principal = this.session.getPrincipal();
        if (principal == null || this.instanceId == null
                || !HohenheimAccess.canManageInstance(principal, this.instanceId)) {
            this.active = false;
            this.session.close(1008, "forbidden");
            return;
        }
        InstanceConsoleSession console;
        try {
            console = InstanceConsoles.ensureSession(this.instanceId);
        } catch (Violations refused) {
            this.active = false;
            this.session.sendText("\r\n[" + refused.getMessage() + "]\r\n");
            this.session.close();
            return;
        }
        this.console = console;
        Consumer<String> listener = chunk -> {
            if (this.active && this.session.isOpen()) {
                // \n alone leaves the terminal cursor mid-line; ghostty wants \r\n.
                this.session.sendText(chunk.replace("\n", "\r\n"));
            }
        };
        this.listener = listener;
        // subscribe() replays the session ring, then attaches -- no gap, no double.
        console.subscribe(listener);
        Blast.log("CONSOLE: viewer connected to instance", this.instanceId);
    }

    /** Mid-session re-check of the manage capability (revoked = 1008 by the core). */
    @Override
    public boolean revalidate() {
        Principal principal = this.session.getPrincipal();
        return principal != null && this.instanceId != null
            && HohenheimAccess.canManageInstance(principal, this.instanceId);
    }

    @Override
    public void onClose(int code, String reason) {
        this.active = false;
        Consumer<String> listener = this.listener;
        InstanceConsoleSession console = this.console;
        this.listener = null;
        if (console != null && listener != null) {
            console.unsubscribe(listener);
        }
        Blast.log("CONSOLE: viewer disconnected from instance", this.instanceId);
    }
}
