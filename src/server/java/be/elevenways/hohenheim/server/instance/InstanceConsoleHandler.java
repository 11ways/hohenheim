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
    private InstanceConsoles.@Nullable Subscription subscription;

    public InstanceConsoleHandler(WebSocketSession session, @Nullable Integer instanceId) {
        this.session = session;
        this.instanceId = instanceId;
    }

    @Override
    public void onOpen() {
        // requiresLogin already refused anonymous handshakes with 401; the per-record
        // console capability needs the route param, so it runs here. 1008 = policy.
        Principal principal = this.session.getPrincipal();
        if (principal == null || this.instanceId == null
                || !HohenheimAccess.hasInstanceCapability(
                    principal, this.instanceId, HohenheimAccess.CONSOLE)) {
            this.active = false;
            this.session.close(1008, "forbidden");
            return;
        }
        Consumer<String> listener = chunk -> {
            if (this.active && this.session.isOpen()) {
                // \n alone leaves the terminal cursor mid-line; ghostty wants \r\n.
                this.session.sendText(chunk.replace("\n", "\r\n"));
            }
        };
        try {
            // The hub's subscribe replays the session ring, then attaches -- no gap, no
            // double -- and the ring it replays is already redacted.
            this.subscription = InstanceConsoles.subscribe(this.instanceId, listener);
        } catch (Violations refused) {
            this.active = false;
            this.session.sendText("\r\n[" + refused.getMessage() + "]\r\n");
            this.session.close();
            return;
        }
        Blast.log("CONSOLE: viewer connected to instance", this.instanceId);
    }

    /** Mid-session re-check of the console capability (revoked = 1008 by the core). */
    @Override
    public boolean revalidate() {
        Principal principal = this.session.getPrincipal();
        return principal != null && this.instanceId != null
            && HohenheimAccess.hasInstanceCapability(
                principal, this.instanceId, HohenheimAccess.CONSOLE);
    }

    @Override
    public void onClose(int code, String reason) {
        this.active = false;
        InstanceConsoles.Subscription subscription = this.subscription;
        this.subscription = null;
        if (subscription != null) {
            subscription.close();
        }
        Blast.log("CONSOLE: viewer disconnected from instance", this.instanceId);
    }
}
