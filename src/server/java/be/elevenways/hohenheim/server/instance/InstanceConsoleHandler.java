package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.common.websocket.WebSocketHandler;
import be.elevenways.zenit.common.websocket.WebSocketSession;
import be.elevenways.zenit.server.operation.OperationPipeline;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Streams an instance's live console to a pl-terminal, and -- when the workload sits
 * behind a pseudo-terminal ({@code ConsoleKind.TTY}) -- carries the viewer's keystrokes
 * and resize frames back to it, the deleted process-terminal shape over the driver's
 * attach.
 *
 * A PLAIN console stays OUTPUT ONLY by design: a non-TTY container never echoes, so raw
 * keystrokes would be invisible typing -- commands ride the console form's POST endpoint,
 * which also funnels stop-command observation through the hub. Which of the two a viewer
 * gets is the session's fact ({@link InstanceConsoles.Viewer#interactive()}), never a
 * client choice.
 */
public final class InstanceConsoleHandler implements WebSocketHandler {

    private final WebSocketSession session;
    private final @Nullable Integer instanceId;
    private volatile boolean active = true;
    private InstanceConsoles.@Nullable Viewer viewer;

    public InstanceConsoleHandler(WebSocketSession session, @Nullable Integer instanceId) {
        this.session = session;
        this.instanceId = instanceId;
    }

    @Override
    public void onOpen() {
        // requiresLogin already refused anonymous handshakes with 401; the per-record
        // admission needs the route param, so it runs here. 1008 = policy.
        if (!this.admitted()) {
            this.active = false;
            this.session.close(1008, "forbidden");
            return;
        }
        InstanceConsoles.Viewer attached;
        try {
            attached = InstanceConsoles.attach(this.instanceId);
        } catch (Violations refused) {
            this.active = false;
            this.session.sendText("\r\n[" + refused.getMessage() + "]\r\n");
            this.session.close();
            return;
        }
        boolean interactive = attached.interactive();
        Consumer<String> listener = chunk -> {
            if (this.active && this.session.isOpen()) {
                // A pseudo-terminal already emits \r\n; a pipe's bare \n leaves the
                // terminal cursor mid-line, and ghostty wants \r\n.
                this.session.sendText(interactive ? chunk : chunk.replace("\n", "\r\n"));
            }
        };
        this.viewer = attached;
        // The hub's follow replays the session ring, then attaches -- no gap, no double --
        // and the ring it replays is already redacted.
        attached.follow(listener);
        Blast.log("CONSOLE: viewer connected to instance", this.instanceId,
            interactive ? "(interactive)" : "(plain)");
    }

    /** Mid-session re-check of the admission (revoked = 1008 by the core). */
    @Override
    public boolean revalidate() {
        return this.admitted();
    }

    /**
     * Whether the console operation is offered to this socket's principal on its instance: the operation's own gate,
     * applicability and authorization, so the socket admits exactly whom the console form does.
     *
     * AIDEV-NOTE: the socket is a console SURFACE, and its own capability check let an admin (or a hand-granted
     * delegate) type raw input into a product-generated instance's TTY that every placed surface refuses (review 8
     * D01). Never check the capability here again beside the operation: ask the operation.
     */
    private boolean admitted() {
        Principal principal = this.session.getPrincipal();
        if (principal == null || this.instanceId == null) {
            return false;
        }
        Row instance = Models.get(InstanceModel.class).findById(this.instanceId);
        return instance != null && !(OperationPipeline.offer(InstanceOperations.CONSOLE_COMMAND,
            AccessContext.detached(principal), instance) instanceof OperationPipeline.Offer.Hidden);
    }

    /**
     * Keystrokes, or one resize control frame (see {@link TerminalControlFrames}) -- for
     * an interactive console. A plain console ignores inbound text: its input is the
     * command form, and dropping a frame here rather than writing it blind keeps the
     * "invisible typing" the class note describes impossible.
     */
    @Override
    public void onTextMessage(String message) {
        InstanceConsoles.Viewer attached = this.viewer;
        if (!this.active || attached == null || !attached.interactive()
                || message == null || message.isEmpty()) {
            return;
        }
        int[] size = TerminalControlFrames.resizeOf(message);
        try {
            if (size != null) {
                attached.resize(size[0], size[1]);
            } else {
                attached.write(message);
            }
        } catch (IOException refused) {
            // Never silent: a terminal that swallows input looks like breakage.
            Blast.log("CONSOLE: input to instance", this.instanceId, "refused:",
                refused.getMessage());
        }
    }

    @Override
    public void onClose(int code, String reason) {
        this.active = false;
        InstanceConsoles.Viewer attached = this.viewer;
        this.viewer = null;
        if (attached != null) {
            attached.close();
        }
        Blast.log("CONSOLE: viewer disconnected from instance", this.instanceId);
    }
}
