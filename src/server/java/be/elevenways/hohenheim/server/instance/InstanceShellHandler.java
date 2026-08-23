package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.dry.Dry;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.MessageResolvers;
import be.elevenways.zenit.common.setting.ContentLocales;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.common.websocket.WebSocketHandler;
import be.elevenways.zenit.common.websocket.WebSocketSession;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * One interactive shell over the terminal WebSocket: keystrokes up, terminal output down,
 * and the {@code pl-terminal} resize control frame in between.
 *
 * <p>Unlike {@code InstanceConsoleHandler} this is bidirectional by design -- a
 * pseudo-terminal echoes, so typing is visible and a POST-per-line form would be the wrong
 * shape. It answers to {@code shell}, never {@code console}: the console reaches the
 * workload's own primary process, this starts a new program.</p>
 *
 * AIDEV-NOTE: teardown rides the framework. {@code WebSocketTeardown} guarantees
 * {@link #onClose} runs EXACTLY once and ALWAYS -- including a server-initiated close the
 * peer never answers -- and {@code InstanceShell.Session.close} is itself idempotent, so
 * the two ends of the same session can both call it without a second audit entry or a
 * second socket close.
 */
public final class InstanceShellHandler implements WebSocketHandler {

    /** The control frame {@code pl-terminal} sends when its geometry changes. */
    private static final String RESIZE_TYPE = "resize";

    private final @NonNull WebSocketSession session;
    private final @Nullable Integer instanceId;
    private InstanceShell.@Nullable Session shell;

    public InstanceShellHandler(@NonNull WebSocketSession session, @Nullable Integer instanceId) {
        this.session = session;
        this.instanceId = instanceId;
    }

    @Override
    public void onOpen() {
        Principal principal = this.session.getPrincipal();
        if (this.instanceId == null || principal == null) {
            this.session.close(1008, "forbidden");
            return;
        }
        try {
            // The service asks the capability again on its own funnel; nothing here
            // decides authorization, so a future caller cannot get a wider door.
            this.shell = new InstanceShell().open(this.instanceId, principal,
                InstanceShell.DEFAULT_COLS, InstanceShell.DEFAULT_ROWS,
                this::sendOutput, this::endSocket);
        } catch (Violations refused) {
            // Named, in the terminal itself: an operator must be able to read WHY a shell
            // did not open, and a bare 1008 says nothing.
            this.session.sendText("\r\n[" + messageOf(refused) + "]\r\n");
            this.session.close(1008, "refused");
        }
    }

    /**
     * Mid-session re-check of the shell capability (revoked = 1008 by the core), and the
     * session is torn down with it rather than left attached to a dead socket.
     */
    @Override
    public boolean revalidate() {
        Principal principal = this.session.getPrincipal();
        boolean permitted = principal != null && this.instanceId != null
            && HohenheimAccess.hasInstanceCapability(
                principal, this.instanceId, HohenheimAccess.SHELL);
        if (!permitted) {
            InstanceShell.Session live = this.shell;
            if (live != null) {
                live.close(InstanceShell.EndReason.REVOKED);
            }
        }
        return permitted;
    }

    /**
     * Keystrokes, or one resize control frame.
     *
     * AIDEV-NOTE: the discriminator is STRUCTURAL -- a JSON object carrying
     * {@code type:"resize"} plus numeric cols/rows -- and everything else is keystrokes,
     * verbatim. The ambiguity that leaves is deliberate and bounded: a viewer who pastes
     * exactly that JSON object resizes their OWN terminal instead of typing it. Nothing
     * about another session, another record or another identity is reachable this way, so
     * a second control channel would be cost without a safety gain.
     */
    @Override
    public void onTextMessage(String message) {
        InstanceShell.Session live = this.shell;
        if (live == null || message == null || message.isEmpty()) {
            return;
        }
        int[] size = resizeOf(message);
        if (size != null) {
            live.resize(size[0], size[1]);
            return;
        }
        live.write(message);
    }

    @Override
    public void onClose(int code, String reason) {
        InstanceShell.Session live = this.shell;
        this.shell = null;
        if (live != null) {
            live.close(InstanceShell.EndReason.CLIENT);
        }
    }

    /** Terminal bytes toward the viewer; a closed socket simply drops them. */
    private void sendOutput(@NonNull String text) {
        if (this.session.isOpen()) {
            this.session.sendText(text);
        }
    }

    /** The session ended on its own (shell exit, idle sweep): close the socket with it. */
    private void endSocket() {
        if (this.session.isOpen()) {
            this.session.close();
        }
    }

    /** {@code {"type":"resize","cols":N,"rows":N}} as [cols, rows], or null for keystrokes. */
    private static int @Nullable [] resizeOf(@NonNull String message) {
        // Cheap reject first: a keystroke stream must not pay for a parse attempt.
        if (message.length() < 2 || message.charAt(0) != '{'
                || !message.contains(RESIZE_TYPE)) {
            return null;
        }
        Object parsed;
        try {
            parsed = new Dry().parse(message);
        } catch (RuntimeException notJson) {
            return null;
        }
        if (!(parsed instanceof Map<?, ?> frame)
                || !RESIZE_TYPE.equals(frame.get("type"))
                || !(frame.get("cols") instanceof Number cols)
                || !(frame.get("rows") instanceof Number rows)) {
            return null;
        }
        return new int[] { cols.intValue(), rows.intValue() };
    }

    /**
     * The first violation's resolved text, so a refusal keeps the reason it names.
     *
     * AIDEV-NOTE: the installation's default CONTENT locale, never a hardcoded tag and
     * never the debug {@code Violations.getMessage()} -- there is no conduit on a socket,
     * so no viewer locale chain exists to read. Same choice {@code Violations.describedIn}
     * makes for the same reason.
     */
    private static @NonNull String messageOf(@NonNull Violations violations) {
        if (violations.all().isEmpty()) {
            return String.valueOf(violations.getMessage());
        }
        Violation first = violations.all().get(0);
        try {
            return String.valueOf(first.message().resolve(
                LocaleChain.of(ContentLocales.getDefault()), MessageResolvers.getDefault()));
        } catch (RuntimeException unresolved) {
            Blast.log("SHELL: could not resolve refusal text:", unresolved.getMessage());
            return first.message().key();
        }
    }
}
