package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimChannels;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.zenit.common.channel.ChannelHandler;
import be.elevenways.zenit.common.channel.ChannelLink;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.websocket.WebSocketSession;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Streams the live figures of the instances one page shows over ONE link (the overview's one instance, a list's
 * visible rows): the open data and every later message name the instance ids the page shows now, and each frame is a
 * figure carrying its instance id.
 *
 * Admission is core's key guard ({@code KeyedChannel.serve}) asking {@link InstanceViewLinks#viewable} at open, at
 * every message and at every revalidation, so this handler is handed only ids its tenant may view, and N rows cost
 * one capability scope, never N; it reads the rows of those ids once. An id the link may not view, or that names no
 * instance, is left out silently; an open naming nothing it may follow is refused as not permitted, the same for a
 * foreign instance and a missing one. A list longer than the channel's declared cap
 * ({@link HohenheimChannels#INSTANCE_FIGURE_KEYS}) is refused TOO_LARGE by that guard: the open fails, a later message
 * ends the link.
 *
 * AIDEV-NOTE: LOCK ORDER (InstanceFigures' note): the guard's lock (it hands over opens, messages and revalidations
 * one at a time, so two follows never race), then the link monitor (zenit's ServerChannelLink holds it across
 * onClose), then {@link #lock}, then a session monitor of InstanceFigures. Nothing here calls into the link or
 * subscribes while holding {@link #lock}: the hub hands figures to {@link #send} holding no lock, and send only reads
 * its admission under the lock before submitting outside it.
 *
 * AIDEV-NOTE: an id is ADMITTED (reserved, under the lock) before it is subscribed; {@link #send} drops a figure of an
 * id no longer admitted, and a subscription whose id was dropped before it was stored is closed at once. A session
 * that ends while followed (its instance deleted, its probe removed) drops its id, so the next message naming it reads
 * and subscribes it afresh.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class InstanceFigureHandler implements ChannelHandler<Object, Object> {

    private static final AtomicInteger OPENED = new AtomicInteger();

    private final @NonNull ChannelLink<Object, Object> link;
    private final Object lock = new Object();

    /** Guarded by {@link #lock}: the ids admitted and found, and the subscriptions of those that declare a probe. */
    private final Set<Integer> admitted = new LinkedHashSet<>();
    private final Map<Integer, InstanceFigures.Subscription> subscriptions = new LinkedHashMap<>();
    private boolean closed;

    public InstanceFigureHandler(@NonNull ChannelLink<Object, Object> link) {
        this.link = link;
    }

    /** Install the guarded factory; called at the MODULES boot stage. */
    public static void init() {
        HohenheimChannels.INSTANCE_FIGURE_KEYS.serve(InstanceViewLinks::viewable, InstanceFigureHandler::new);
    }

    /** @return how many figure links this node admitted since it booted; one page holds one, however many rows */
    public static int openedLinks() {
        return OPENED.get();
    }

    @Override
    public void onOpen(@Nullable Object openData) {
        // Inside onOpen the link is OPENING and what it submits is queued until the client hears the open, which is
        // where the hub hands over the figures it holds (InstanceStatsHandler's note).
        this.follow(HohenheimChannels.INSTANCE_FIGURE_KEYS.keysOf(openData));
        List<InstanceFigures.Subscription> all;
        synchronized (this.lock) {
            all = this.admitted.isEmpty() ? this.closeAllLocked() : null;
        }
        if (all != null) {
            all.forEach(InstanceFigures.Subscription::close);
            // Typed, so the client ends the link instead of retrying a refusal no wait lifts.
            throw new DomainRefusal(ZenitRefusalReason.PERMISSION_DENIED, "Not permitted");
        }
        OPENED.incrementAndGet();
    }

    /** The page now shows these instances (or the guard re-read what it may view): follow exactly them. */
    @Override
    public void onMessage(@NonNull Object message) {
        this.follow(HohenheimChannels.INSTANCE_FIGURE_KEYS.keysOf(message));
    }

    /** The guard handed over what the link may still view just before: the link stays while it follows any. */
    @Override
    public boolean revalidate(@NonNull WebSocketSession session) {
        synchronized (this.lock) {
            return !this.closed && !this.admitted.isEmpty();
        }
    }

    @Override
    public void onClose() {
        List<InstanceFigures.Subscription> all;
        synchronized (this.lock) {
            all = this.closeAllLocked();
        }
        all.forEach(InstanceFigures.Subscription::close);
    }

    /** @return whether the link follows the instance's figure right now */
    boolean follows(int instanceId) {
        synchronized (this.lock) {
            return this.subscriptions.containsKey(instanceId);
        }
    }

    /** Follow exactly {@code wanted}, ids the guard admitted: stop the ids no longer named, subscribe the new ones. */
    private void follow(@NonNull Set<Integer> wanted) {
        List<InstanceFigures.Subscription> dropped = new ArrayList<>();
        Set<Integer> candidates;
        synchronized (this.lock) {
            if (this.closed) {
                return;
            }
            this.admitted.retainAll(wanted);
            for (Integer id : List.copyOf(this.subscriptions.keySet())) {
                if (!wanted.contains(id)) {
                    dropped.add(this.subscriptions.remove(id));
                }
            }
            candidates = new LinkedHashSet<>(wanted);
            candidates.removeAll(this.admitted);
        }
        dropped.forEach(InstanceFigures.Subscription::close);
        List<Row> rows = candidates.isEmpty() ? List.of()
            : Models.get(InstanceModel.class).find().where(InstanceModel.ID.in(candidates)).all();
        List<Row> fresh = new ArrayList<>();
        synchronized (this.lock) {
            if (this.closed) {
                return;
            }
            for (Row row : rows) {
                if (this.admitted.add(row.get(InstanceModel.ID))) {
                    fresh.add(row);
                }
            }
        }
        if (fresh.isEmpty()) {
            return;
        }
        Map<Integer, InstanceFigures.Subscription> made = InstanceFigures.subscribe(fresh, this::send, this::ended);
        List<InstanceFigures.Subscription> surplus = new ArrayList<>();
        synchronized (this.lock) {
            made.forEach((id, subscription) -> {
                // Dropped since it was admitted (its session ended) or closed.
                if (this.closed || !this.admitted.contains(id)
                        || this.subscriptions.putIfAbsent(id, subscription) != null) {
                    surplus.add(subscription);
                }
            });
        }
        surplus.forEach(InstanceFigures.Subscription::close);
    }

    /** The hub's viewer, called holding no lock. */
    private void send(InstanceFigures.@NonNull Figure figure) {
        synchronized (this.lock) {
            if (this.closed || !this.admitted.contains(figure.instanceId())) {
                return;
            }
        }
        if (!this.link.isClosed()) {
            this.link.submit(figure.toMap());
        }
    }

    /** The hub's end notice, called holding no lock: forget the id, so the next message naming it subscribes again. */
    private void ended(int instanceId) {
        InstanceFigures.Subscription gone;
        synchronized (this.lock) {
            if (this.closed) {
                return;
            }
            this.admitted.remove(instanceId);
            gone = this.subscriptions.remove(instanceId);
        }
        if (gone != null) {
            gone.close();
        }
    }

    /** @return every subscription, for the caller to close once it released the lock */
    private @NonNull List<InstanceFigures.Subscription> closeAllLocked() {
        this.closed = true;
        this.admitted.clear();
        List<InstanceFigures.Subscription> all = List.copyOf(this.subscriptions.values());
        this.subscriptions.clear();
        return all;
    }
}
