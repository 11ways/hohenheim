package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.common.websocket.WebSocketHandler;
import be.elevenways.zenit.common.websocket.WebSocketSession;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;

/**
 * The VM framebuffer rescue console: a grant-checked proxy that streams the guest's VGA
 * framebuffer to the browser as server-captured PNG snapshots (BINARY frames) and carries
 * keyboard/mouse input the other way over a {@link FramebufferSource}. It is hypervisor-
 * side by construction (the SPICE/VGA console, not a guest agent), so it works while the
 * guest is broken -- before the agent is up, at a boot prompt, on a kernel panic -- which
 * is exactly why a guest-side protocol cannot substitute for it.
 *
 * AIDEV-NOTE: authorization has TWO layers and neither is bespoke. The handshake's
 * requiresLogin already refused anonymous with 401; the per-record CONSOLE capability
 * needs the route param, so it runs in onOpen (1008 on refusal). Mid-session, core's
 * default-on revalidator calls {@link #revalidate()} every interval and closes the
 * socket 1008 the instant CONSOLE stops holding -- a revoked grant, a disabled account or
 * a logout disconnects an OPEN console, it is not merely refused on next connect.
 *
 * AIDEV-NOTE: the verb is CONSOLE, not MANAGE, and this note used to say MANAGE at both
 * points above -- drift left behind by the commit that split the instance capability into
 * enforced verbs. Nobody gained authority (CONSOLE is {@code impliedBy(MANAGE)}, so every
 * manage holder still reaches this), but the real floor is one verb LOWER than the prose
 * claimed: a CONSOLE-only grantee reaches the framebuffer. That is the intended reading of
 * the split -- a rescue console is exactly what CONSOLE is for -- and it is what
 * {@code VmFramebufferRevocationTest}'s CONSOLE-only journey pins, because a test that
 * grants MANAGE passes whichever verb the handler asks for.
 *
 * AIDEV-NOTE: frames are SNAPSHOTS, not per-region SPICE draws. qemu GLZ-compresses every
 * display image regardless of the client's compression preference (verified live on
 * daystrom 2026-08-06), so a live streaming decoder would require implementing SPICE's
 * image codecs. That is a named follow-up; the snapshot lane is a complete rescue console.
 */
public final class VmFramebufferHandler implements WebSocketHandler {

    /** Snapshot cadence while the framebuffer keeps changing. */
    private static final long ACTIVE_INTERVAL_MS = 250;

    /** Backed-off cadence once several identical frames arrive (idle screen). */
    private static final long IDLE_INTERVAL_MS = 1000;

    /** Identical frames before backing off to the idle cadence. */
    private static final int IDLE_AFTER = 4;

    private final WebSocketSession session;
    private final @Nullable Integer instanceId;
    private final FramebufferSource.@NonNull Factory sourceFactory;
    private volatile boolean active;
    private @Nullable FramebufferSource source;

    public VmFramebufferHandler(WebSocketSession session, @Nullable Integer instanceId) {
        this(session, instanceId, IncusFramebufferSource::open);
    }

    /** Test seam: inject a fake {@link FramebufferSource} so the grant/revocation contract
     *  can be proven over a real socket without a live daemon. */
    public VmFramebufferHandler(WebSocketSession session, @Nullable Integer instanceId,
                                FramebufferSource.@NonNull Factory sourceFactory) {
        this.session = session;
        this.instanceId = instanceId;
        this.sourceFactory = sourceFactory;
    }

    @Override
    public void onOpen() {
        Principal principal = this.session.getPrincipal();
        if (principal == null || this.instanceId == null
                || !HohenheimAccess.hasInstanceCapability(
                    principal, this.instanceId, HohenheimAccess.CONSOLE)) {
            this.session.close(1008, "forbidden");
            return;
        }
        Row instance = Models.get(InstanceModel.class).findById(this.instanceId);
        if (instance == null || !IncusVmKind.ID.toString().equals(instance.get(InstanceModel.KIND))) {
            this.session.sendText("not a VM instance");
            this.session.close(1008, "not a vm");
            return;
        }
        String statusValue = instance.get(InstanceModel.STATUS);
        if (!InstanceModel.STATUS_RUNNING.equals(statusValue)
                && !InstanceModel.STATUS_STARTING.equals(statusValue)) {
            this.session.sendText("instance not running");
            this.session.close();
            return;
        }
        Integer id = this.instanceId;
        String serverName = ServerModel.nameOf(instance.get(InstanceModel.SERVER_ID));
        String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, id);
        FramebufferSource opened;
        try {
            opened = this.sourceFactory.open(id, serverName, handle);
        } catch (Exception refused) {
            this.session.sendText("console unavailable: " + refused.getMessage());
            this.session.close();
            return;
        }
        this.source = opened;
        this.active = true;
        JobRunner.startVirtualThread(this::pump);
        Blast.log("FRAMEBUFFER: viewer connected to VM instance", this.instanceId);
    }

    /** Server-captured PNG snapshots, pushed as binary frames while they change. */
    private void pump() {
        FramebufferSource src = this.source;
        if (src == null) {
            return;
        }
        byte[] previousDigest = null;
        int identical = 0;
        while (this.active && this.session.isOpen()) {
            try {
                byte[] frame = src.snapshot();
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(frame);
                if (!Arrays.equals(digest, previousDigest)) {
                    // Blocking send: natural backpressure, never an unbounded queue.
                    this.session.sendBinaryBlocking(frame);
                    previousDigest = digest;
                    identical = 0;
                } else if (identical < IDLE_AFTER) {
                    identical++;
                }
                Thread.sleep(identical >= IDLE_AFTER ? IDLE_INTERVAL_MS : ACTIVE_INTERVAL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception lost) {
                if (this.active && this.session.isOpen()) {
                    this.session.sendText("framebuffer stream ended: " + lost.getMessage());
                    this.session.close();
                }
                return;
            }
        }
    }

    /** Input frames from the browser: DRY maps translated to SPICE scancodes/mouse. */
    @Override
    public void onTextMessage(String message) {
        FramebufferSource src = this.source;
        if (!this.active || src == null) {
            return;
        }
        Object parsed = Zenit.DRY.parse(message);
        if (!(parsed instanceof Map<?, ?> frame)) {
            return;
        }
        try {
            handleInput(src, frame);
        } catch (Exception ignored) {
            // A single malformed input frame never tears the console down.
        }
    }

    private void handleInput(FramebufferSource src, Map<?, ?> frame) throws Exception {
        String type = String.valueOf(frame.get("t"));
        switch (type) {
            case "k" -> {
                Object code = frame.get("c");
                if (code instanceof String name) {
                    src.key(name, Boolean.TRUE.equals(frame.get("d")));
                }
            }
            case "mm" -> src.mousePosition(intOf(frame.get("x")), intOf(frame.get("y")),
                intOf(frame.get("b")));
            case "mb" -> src.mouseButton(intOf(frame.get("n")),
                Boolean.TRUE.equals(frame.get("d")), intOf(frame.get("b")));
            default -> {
                // Unknown frame types are ignored, never fatal.
            }
        }
    }

    private static int intOf(@Nullable Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    /** Mid-session re-check of CONSOLE; a false return makes core close the socket 1008. */
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
        FramebufferSource src = this.source;
        this.source = null;
        if (src != null) {
            src.close();
        }
        Blast.log("FRAMEBUFFER: viewer disconnected from VM instance", this.instanceId);
    }
}
