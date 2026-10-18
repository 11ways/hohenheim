package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.HohenheimChannels;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.channel.ChannelHandler;
import be.elevenways.zenit.common.channel.ChannelLink;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.common.websocket.WebSocketSession;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Streams one instance's live resource samples to a subscribed viewer over the channel
 * layer (the {@code InstanceConsoleHandler} shape, one transport over).
 *
 * The channel declares only {@code requiresLogin}, because a record capability is not a
 * permission -- so admission is decided HERE, per record, through the same precedence walk
 * every other instance surface rides.
 *
 * AIDEV-NOTE: {@link #revalidate} is that admission again, and zenit asks it (through
 * {@link ChannelHandler#admits}) at every revalidation of the gateway socket: its periodic
 * check and, at once, a committed access change naming the viewer (a revoked or denied
 * record grant). A failed check cuts this link alone, so no private re-check cadence lives
 * here any more; an idle stream is cut as surely as a busy one.
 */
public final class InstanceStatsHandler implements ChannelHandler<Object, Object> {

    private final @NonNull ChannelLink<Object, Object> link;

    private @Nullable Integer instanceId;
    private InstanceStats.@Nullable Subscription subscription;

    public InstanceStatsHandler(@NonNull ChannelLink<Object, Object> link) {
        this.link = link;
    }

    /** Install the factory; called at the MODULES boot stage. */
    public static void init() {
        HohenheimChannels.INSTANCE_STATS.setHandlerFactory(InstanceStatsHandler::new);
    }

    @Override
    public void onOpen(@Nullable Object openData) {
        Integer instanceId = RawValues.parsedInt(openData);
        if (instanceId == null || !this.permitted(instanceId)) {
            // The SAME refusal for "no such instance" and "not yours": naming the
            // difference would make this an instance-existence oracle. Typed, so the
            // client ends the link instead of retrying a refusal no wait lifts.
            throw new DomainRefusal(ZenitRefusalReason.PERMISSION_DENIED, "Not permitted");
        }
        this.instanceId = instanceId;

        Consumer<InstanceStats.Sample> viewer = sample -> {
            if (this.link.isOpen()) {
                this.link.submit(sample.toMap());
            }
        };
        try {
            this.subscription = InstanceStats.subscribe(instanceId, viewer);
        } catch (IOException noStream) {
            // A stopped or unreachable workload has nothing to stream. Named, not silent:
            // the client's ready future fails and the page renders the empty state.
            // AIDEV-NOTE: the reason is the DAEMON's or transport's own text (socket
            // paths, host names, ssh failures), which is operator inventory -- the
            // InstanceOverview install_error rule. A delegated viewer is told there
            // is nothing to stream; the operator gets the reason, and the log keeps it.
            Blast.log("STATS: no live stats for instance", instanceId, "-", noStream.getMessage());
            throw new DomainRefusal(ZenitRefusalReason.OPERATION_UNAVAILABLE, this.isOperator()
                ? "No live stats: " + noStream.getMessage()
                : "No live stats");
        }
    }

    /** Whether the viewer is an operator, who may read the daemon's own failure text. */
    private boolean isOperator() {
        Principal principal = this.link.getPrincipal();
        return principal != null && AccessContext.detached(principal).hasPermission(HohenheimSources.ADMIN_ACCESS);
    }

    @Override
    public boolean revalidate(@NonNull WebSocketSession session) {
        Integer instanceId = this.instanceId;
        return instanceId != null && this.permitted(instanceId);
    }

    @Override
    public void onClose() {
        InstanceStats.Subscription subscription = this.subscription;
        this.subscription = null;
        if (subscription != null) {
            subscription.close();
        }
    }

    /**
     * The principal-only capability walk (no conduit exists on a channel link). Live
     * stats are OBSERVATION of the tenant's own record, so they ask {@code view} -- the
     * same capability the page carrying the chart is scoped by.
     */
    private boolean permitted(int instanceId) {
        // The link's own face: the session's principal with an anonymous one folded to
        // null, and the seam a socketless FakeChannelLink answers too.
        Principal principal = this.link.getPrincipal();
        return principal != null && HohenheimAccess.hasInstanceCapability(
            principal, instanceId, HohenheimCapabilities.VIEW);
    }
}
