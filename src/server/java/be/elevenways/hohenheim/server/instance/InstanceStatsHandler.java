package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimChannels;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.channel.ChannelHandler;
import be.elevenways.zenit.common.channel.ChannelLink;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
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
 * permission -- so admission is decided per record, through {@link InstanceViewLinks}, the
 * precedence walk every other instance surface rides.
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
        int instanceId = InstanceViewLinks.admitted(this.link, openData);
        this.instanceId = instanceId;

        // AIDEV-NOTE: inside onOpen the link is OPENING, neither open nor closed, and what it submits is queued until
        // the client hears the open: that is where the hub replays the readings it holds, so a tab whose link opens
        // after a sample still reads it. An isOpen() guard here dropped the whole replay.
        Consumer<InstanceStats.Sample> viewer = sample -> {
            if (!this.link.isClosed()) {
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
        return InstanceViewLinks.isOperator(this.link);
    }

    @Override
    public boolean revalidate(@NonNull WebSocketSession session) {
        Integer instanceId = this.instanceId;
        return instanceId != null && InstanceViewLinks.views(this.link, instanceId);
    }

    @Override
    public void onClose() {
        InstanceStats.Subscription subscription = this.subscription;
        this.subscription = null;
        if (subscription != null) {
            subscription.close();
        }
    }
}
