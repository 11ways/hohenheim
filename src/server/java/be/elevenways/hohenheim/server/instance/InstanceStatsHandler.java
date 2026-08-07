package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimChannels;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.channel.ChannelException;
import be.elevenways.zenit.common.channel.ChannelHandler;
import be.elevenways.zenit.common.channel.ServerChannelLink;
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
 * every other instance surface rides. It is re-decided on a cadence while the link is
 * live, so a revoked grant closes a running stream.
 *
 * AIDEV-NOTE: the periodic re-check is NOT belt-and-braces over the framework's WebSocket
 * revalidator. That revalidator keys on the WebSocketEndpoint, and the channel gateway is
 * ONE socket carrying many links, so it cannot know that this particular link is bound to
 * instance #7. {@link ChannelHandler#revalidate} only runs before a RESUME. Without the
 * cadence below, a grant revoked mid-session would keep pushing until the tab closed.
 */
public final class InstanceStatsHandler implements ChannelHandler<Object, Object> {

    /** How often a live link's manage capability is re-decided. */
    private static final long REVALIDATION_INTERVAL_MS = 15_000;

    private final @NonNull ServerChannelLink<Object, Object> link;

    private @Nullable Integer instanceId;
    private InstanceStats.@Nullable Subscription subscription;
    private volatile long lastChecked;

    public InstanceStatsHandler(@NonNull ServerChannelLink<Object, Object> link) {
        this.link = link;
    }

    /** Install the factory; called at the MODULES boot stage. */
    public static void init() {
        HohenheimChannels.INSTANCE_STATS.setHandlerFactory(InstanceStatsHandler::new);
    }

    @Override
    public void onOpen(@Nullable Object openData) {
        Integer instanceId = parseInstanceId(openData);
        if (instanceId == null || !this.permitted(instanceId)) {
            // The SAME refusal for "no such instance" and "not yours": naming the
            // difference would make this an instance-existence oracle.
            throw new ChannelException("Not permitted");
        }
        this.instanceId = instanceId;
        this.lastChecked = System.currentTimeMillis();

        Consumer<InstanceStats.Sample> viewer = sample -> {
            if (!this.link.isOpen()) {
                return;
            }
            if (System.currentTimeMillis() - this.lastChecked > REVALIDATION_INTERVAL_MS) {
                this.lastChecked = System.currentTimeMillis();
                if (!this.permitted(instanceId)) {
                    Blast.log("STATS: capability revoked mid-stream; closing link for instance",
                        instanceId);
                    this.link.close();
                    return;
                }
            }
            this.link.submit(sample.toMap());
        };
        try {
            this.subscription = InstanceStats.subscribe(instanceId, viewer);
        } catch (IOException noStream) {
            // A stopped or unreachable workload has nothing to stream. Named, not silent:
            // the client's ready future fails and the page renders the empty state.
            throw new ChannelException("No live stats: " + noStream.getMessage());
        }
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
        WebSocketSession session = this.link.getSession();
        Principal principal = session == null ? null : session.getPrincipal();
        return principal != null && HohenheimAccess.hasInstanceCapability(
            principal, instanceId, HohenheimAccess.VIEW);
    }

    private static @Nullable Integer parseInstanceId(@Nullable Object openData) {
        if (openData instanceof Number number) {
            return number.intValue();
        }
        if (openData == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(openData).trim());
        } catch (NumberFormatException notAnId) {
            return null;
        }
    }
}
