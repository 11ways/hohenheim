package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimChannels;
import be.elevenways.hohenheim.HohenheimFigureFunctions;
import be.elevenways.hohenheim.HohenheimStatsFunctions;
import be.elevenways.hohenheim.instance.LiveFigureState;
import be.elevenways.hohenheim.instance.LiveFigureView;
import be.elevenways.hohenheim.instance.StatusProbe;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceStatus;
import be.elevenways.hohenheim.ports.PublishedPort;
import be.elevenways.hohenheim.server.HohenheimSystemPurposes;
import be.elevenways.hohenheim.server.util.SerialOutbox;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.protoblast.common.time.Backoff;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.common.time.Pace;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.ExecutionIdentity;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Function;

/**
 * THE live figure of a service ("8 of 20 players online"): its declared {@link StatusProbe} asked on its published
 * port while somebody looks at it, the answer fanned out to every viewer, the asking stopped with the last one.
 *
 * The {@link InstanceStats} shape: one session per OBSERVED instance however many tabs and list rows show it, the
 * newest figure held for a joining viewer and nothing else -- no history, no column, no disk. An answered probe is
 * passed off as current) and is asked again on {@link #SILENT_BACKOFF}, so a server that stopped answering is not
 * dialled every few seconds. An instance whose own state says it is not meant to run ({@link InstanceStatus#servable}
 * false: stopped, not deployed yet, being destroyed) is never asked: its figure says that state, read again every
 * {@link #INTERVAL} from the row so a start is followed. At most {@link #MAX_SESSIONS} instances are followed at all
 * and {@link #MAX_PROBES} probes are in flight at once, however many viewers ask.
 *
 * AIDEV-NOTE: the FIRST probe asks the target the subscriber resolved (on the viewer's thread, from the row it already
 * read, so a subscribe costs the probe thread no read); every later probe re-reads the instance as the system first,
 * so a changed probe or port is followed within one interval, and a deleted instance or a removed probe ends the
 * session with a silent figure instead of a frozen one.
 *
 * AIDEV-NOTE: LOCK ORDER, the rule DeployLogHandler keeps: a viewer is never called under a session's monitor. A
 * viewer's submit may wait on a channel link's monitor, and a closing link holds that monitor across its handler's
 * onClose, which detaches from sessions (their monitors). So the order is: the link monitor, then the viewer's own
 * lock (InstanceFigureHandler's), then a session monitor, then an attachment's outbox lock (a leaf). Every figure and
 * end notice is QUEUED on the attachment's {@link SerialOutbox} under the session's monitor, which keeps a joining
 * viewer's held figure ahead of every newer one, and handed over by {@link SerialOutbox#drain} holding no lock: by the
 * subscriber once attach returns, by the asking thread once it recorded. Hence {@link #subscribe} is called holding
 * no lock.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class InstanceFigures {

    /** How long an answered figure stands before the service is asked again. */
    public static final Duration INTERVAL = Duration.ofSeconds(10);

    /** How long one probe may take, connect and answer together. */
    static final Duration PROBE_WAIT = Duration.ofSeconds(3);

    /** The wait after each unanswered probe: the interval first, doubling to two minutes. */
    static final Backoff SILENT_BACKOFF = Backoff.exponential(INTERVAL, 2, Duration.ofMinutes(2));

    /** The most instances asked at once; a viewer of one more hears no figure for it. */
    static final int MAX_SESSIONS = 256;

    /** The most probes in flight at once across every session. */
    static final int MAX_PROBES = 16;

    private static final Semaphore PROBES = new Semaphore(MAX_PROBES);

    private static final Map<Integer, Session> SESSIONS = new ConcurrentHashMap<>();

    /**
     * Runs between a subscribe finding a session and attaching to it; the seam a test widens the attach/detach race
     * window with.
     */
    static volatile @Nullable Consumer<Integer> beforeAttach;

    /** Runs as a session's asking starts, before it looks whether it was stopped; the seam a test stops it in. */
    static volatile @Nullable Consumer<Integer> beforeAsk;

    private InstanceFigures() {
    }

    /**
     * One probe's outcome, or the instance's own state when it is not meant to run.
     *
     * @param at       when the probe finished, epoch millis
     * @param state    {@link LiveFigureState#ANSWERING}, {@link LiveFigureState#SILENT} or
     *                 {@link LiveFigureState#NOT_RUNNING}
     * @param online   the count the answer reports, 0 when unanswered; never negative
     * @param capacity the ceiling the answer reports, 0 when it names none; never negative
     * @param status   the instance's own state a NOT_RUNNING figure says, null otherwise or when unknown
     */
    public record Figure(long at, int instanceId, @NonNull StatusProbe probe, @NonNull LiveFigureState state,
                         int online, int capacity, @Nullable InstanceStatus status) {

        public Figure {
            online = Math.max(0, online);
            capacity = Math.max(0, capacity);
        }

        /** The wire shape the channel and the page's seed both carry. */
        public @NonNull Map<String, Object> toMap() {
            Map<String, Object> map = HohenheimChannels.INSTANCE_FIGURE_KEYS.frame(this.instanceId);
            map.put(HohenheimStatsFunctions.AT_KEY, this.at);
            map.put(HohenheimFigureFunctions.PROBE_KEY, this.probe.token());
            map.put(HohenheimFigureFunctions.STATE_KEY, this.state.token());
            if (this.state == LiveFigureState.ANSWERING) {
                map.put(HohenheimFigureFunctions.ONLINE_KEY, this.online);
                map.put(HohenheimFigureFunctions.CAPACITY_KEY, this.capacity);
            }
            if (this.state == LiveFigureState.NOT_RUNNING && this.status != null) {
                map.put(HohenheimFigureFunctions.STATUS_KEY, this.status.token());
            }
            return map;
        }
    }

    /**
     * What a session asks: the probe, the published port it speaks to and the lane that reaches that port on the
     * workload's host, and the instance's own state, which decides whether it is asked at all.
     *
     * @param port   the host port, or null while none is published (never put online), which reads as unanswered
     * @param status the instance's own state, null for a token this build does not know (never asked: fail closed)
     */
    record Target(int instanceId, @NonNull StatusProbe probe, @Nullable Integer port,
                  @NonNull PublishedPortProbe lane, @Nullable InstanceStatus status) {

        /** @return whether the instance is meant to be running, the only kind that is ever asked */
        boolean running() {
            return this.status != null && this.status.servable();
        }

        /**
         * @param lanes the lane for a server id (null for the local host), asked once per instance
         * @return what to ask for this instance, null when it declares no probe this build speaks
         */
        static @Nullable Target of(@NonNull Row instance,
                                   @NonNull Function<@Nullable Integer, PublishedPortProbe> lanes) {
            Map<String, Object> settings = settingsOf(instance);
            StatusProbe probe = StatusProbe.declaredIn(settings);
            if (probe == null) {
                return null;
            }
            Integer port = null;
            try {
                for (PublishedPort published : PublishedPort.of(settings)) {
                    if (port == null && published.purpose() == probe.purpose()
                            && published.protocol().equals(probe.protocol())) {
                        port = published.hostPort();
                    }
                }
            } catch (Violations unspelled) {
                // A refused port spelling publishes nothing; the deploy says why.
            }
            return new Target(instance.get(InstanceModel.ID), probe, port,
                lanes.apply(instance.get(InstanceModel.SERVER_ID)),
                InstanceStatus.forToken(instance.get(InstanceModel.STATUS)));
        }
    }

    /**
     * The live figure element's data for an instance, read from the row and the hub's memory alone: an instance not
     * meant to run is seeded with its own state, never with a figure from before it stopped.
     *
     * @return null for an instance that declares no status probe
     */
    public static @Nullable LiveFigureView viewOf(@NonNull Row instance) {
        StatusProbe probe = StatusProbe.declaredIn(settingsOf(instance));
        if (probe == null) {
            return null;
        }
        int instanceId = instance.get(InstanceModel.ID);
        InstanceStatus status = InstanceStatus.forToken(instance.get(InstanceModel.STATUS));
        if (status == null || !status.servable()) {
            return new LiveFigureView(instanceId, notRunning(instanceId, probe, status).toMap());
        }
        Figure held = last(instanceId);
        return new LiveFigureView(instanceId,
            held == null || held.state() == LiveFigureState.NOT_RUNNING ? null : held.toMap());
    }

    /** {@link #subscribe(Collection, Consumer, IntConsumer)} for a viewer that needs no end notice. */
    public static @NonNull Map<Integer, Subscription> subscribe(@NonNull Collection<Row> instances,
                                                                @NonNull Consumer<Figure> viewer) {
        return subscribe(instances, viewer, instanceId -> {
        });
    }

    /**
     * Attach one viewer to the figures of several instances, handing it each held figure first; call holding no lock,
     * since the held figures reach the viewer on the calling thread.
     *
     * The lanes are resolved once per distinct host of the rows, so a list of N rows on one host costs what one costs.
     *
     * @param instances rows the caller already admitted and read
     * @param ended     told once of an instance whose session ended while the viewer was still attached (deleted, its
     *                  probe removed, or {@link #shutdown}), after its last figure; nothing more of it reaches the
     *                  viewer until it subscribes again
     * @return a handle per instance that declares a probe and got a session; closing it detaches the viewer, and
     *         stops the asking when it was the last one
     */
    public static @NonNull Map<Integer, Subscription> subscribe(@NonNull Collection<Row> instances,
                                                                @NonNull Consumer<Figure> viewer,
                                                                @NonNull IntConsumer ended) {
        Map<Integer, PublishedPortProbe> lanes = new HashMap<>();
        Map<Integer, Subscription> subscriptions = new LinkedHashMap<>();
        for (Row instance : instances) {
            Target target = Target.of(instance, serverId -> lanes.computeIfAbsent(serverId,
                PublishedPortProbe::forServer));
            if (target == null) {
                continue;
            }
            Subscription subscription = subscribe(target, viewer, ended);
            if (subscription != null) {
                subscriptions.put(target.instanceId(), subscription);
            }
        }
        return subscriptions;
    }

    /** @return the handle, or null when {@link #MAX_SESSIONS} instances are asked already */
    private static @Nullable Subscription subscribe(@NonNull Target target, @NonNull Consumer<Figure> viewer,
                                                    @NonNull IntConsumer ended) {
        int instanceId = target.instanceId();
        while (true) {
            Session session = SESSIONS.compute(instanceId, (id, existing) ->
                existing != null && !existing.stopped ? existing
                    : SESSIONS.size() >= MAX_SESSIONS + (existing != null ? 1 : 0) ? null : new Session(id));
            if (session == null) {
                Blast.log("FIGURE: no session for instance", instanceId, "-", MAX_SESSIONS, "are asked already");
                return null;
            }
            Consumer<Integer> hook = beforeAttach;
            if (hook != null) {
                hook.accept(instanceId);
            }
            // A session its last viewer stopped between the lookup and here refuses the attach; the next lookup
            // replaces it with a fresh one.
            Attached attached = session.attach(viewer, ended, target);
            if (attached != null) {
                // Outside the session's monitor: the held figure reaches the viewer here (the class's lock order).
                attached.outbox.drain();
                return () -> session.detach(attached);
            }
        }
    }

    /** Detach handle; closing twice is a no-op. */
    @FunctionalInterface
    public interface Subscription extends AutoCloseable {
        @Override void close();
    }

    /** @return the figure held for an instance, null when nobody watches it; never asks the service */
    public static @Nullable Figure last(int instanceId) {
        Session session = SESSIONS.get(instanceId);
        return session == null ? null : session.last;
    }

    /** @return how many viewers follow the instance's figure right now */
    public static int viewers(int instanceId) {
        Session session = SESSIONS.get(instanceId);
        return session == null ? 0 : session.viewerCount();
    }

    /** @return whether a session asks the instance right now */
    static boolean asking(int instanceId) {
        Session session = SESSIONS.get(instanceId);
        return session != null && !session.stopped;
    }

    /** Stop every session; the test cleanup hook. */
    public static void shutdown() {
        for (Session session : SESSIONS.values()) {
            session.stop();
        }
        SESSIONS.clear();
    }

    /** Ask the target once; whatever the port answers, a malformed answer included, reads as a figure. */
    static @NonNull Figure read(@NonNull Target target) {
        Integer port = target.port();
        if (port == null) {
            return silent(target);
        }
        PublishedPortProbe lane = target.lane();
        return switch (target.probe()) {
            case MINECRAFT_JAVA -> {
                MinecraftStatusPing.Reading reading = MinecraftStatusPing.read(lane.exchange(port,
                    MinecraftStatusPing.request(port), MinecraftStatusPing::complete, MinecraftStatusPing.MAX_ANSWER,
                    PROBE_WAIT));
                yield reading == null ? silent(target)
                    : new Figure(Now.millis(), target.instanceId(), target.probe(), LiveFigureState.ANSWERING,
                        reading.online(), reading.capacity(), null);
            }
        };
    }

    private static @NonNull Figure silent(@NonNull Target target) {
        return new Figure(Now.millis(), target.instanceId(), target.probe(), LiveFigureState.SILENT, 0, 0, null);
    }

    private static @NonNull Figure notRunning(int instanceId, @NonNull StatusProbe probe,
                                              @Nullable InstanceStatus status) {
        return new Figure(Now.millis(), instanceId, probe, LiveFigureState.NOT_RUNNING, 0, 0, status);
    }

    @SuppressWarnings("unchecked")
    private static @NonNull Map<String, Object> settingsOf(@NonNull Row instance) {
        return instance.get(InstanceModel.SETTINGS) instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    // -- one instance's asking ------------------------------------------------

    /** One viewer's attachment to one session: its own outbox, so what it hears arrives in order and outside locks. */
    private static final class Attached {

        private final @NonNull SerialOutbox<Runnable> outbox;
        private final @NonNull Consumer<Figure> viewer;
        private final @NonNull IntConsumer ended;

        Attached(int instanceId, @NonNull Consumer<Figure> viewer, @NonNull IntConsumer ended) {
            this.viewer = viewer;
            this.ended = ended;
            this.outbox = new SerialOutbox<>(notice -> {
                try {
                    notice.run();
                } catch (RuntimeException viewerFailure) {
                    Blast.log("FIGURE: viewer of instance", instanceId, "threw:", viewerFailure.getMessage());
                }
            });
        }

        void offer(@NonNull Figure figure) {
            this.outbox.offer(() -> this.viewer.accept(figure));
        }

        void offerEnd(int instanceId) {
            this.outbox.offer(() -> this.ended.accept(instanceId));
        }
    }

    /**
     * AIDEV-NOTE: a session is single-use. Attach, detach and stop decide under its monitor, and once stopped (its
     * last viewer left, its instance went, or its loop ended any other way) it never asks again and refuses every
     * attach, so a subscribe racing the last detach makes a fresh session instead of joining a dead one. Nothing is
     * delivered under the monitor, only queued (the class's lock order).
     */
    private static final class Session {

        private final int instanceId;
        private final List<Attached> viewers = new ArrayList<>();

        private volatile @Nullable Target target;
        private volatile @Nullable Figure last;
        private volatile boolean stopped;
        private boolean started;
        private @Nullable Thread thread;

        Session(int instanceId) {
            this.instanceId = instanceId;
        }

        synchronized int viewerCount() {
            return this.viewers.size();
        }

        /**
         * Queues the held figure for the viewer and adds it under the lock the asking records under, starting the
         * asking with the first viewer; the caller drains the attachment once this returns.
         *
         * @return the attachment, null when the session stopped, so the caller looks again
         */
        synchronized @Nullable Attached attach(@NonNull Consumer<Figure> viewer, @NonNull IntConsumer ended,
                                               @NonNull Target target) {
            if (this.stopped) {
                return null;
            }
            this.target = target;
            Attached attached = new Attached(this.instanceId, viewer, ended);
            Figure held = this.last;
            if (held != null) {
                attached.offer(held);
            }
            this.viewers.add(attached);
            if (!this.started) {
                this.started = true;
                // The asking serves every viewer, so it runs as the system, never as the one whose subscribe started
                // it; on a virtual thread, whose interrupt also ends a probe's blocking socket read (stopLocked).
                JobRunner.startVirtualThread(() -> ExecutionIdentity.runDetachedAsSystem(
                    HohenheimSystemPurposes.LIVE_FIGURE, this::ask));
            }
            return attached;
        }

        void detach(@NonNull Attached attached) {
            attached.outbox.close();
            synchronized (this) {
                if (!this.viewers.remove(attached) || !this.viewers.isEmpty()) {
                    return;
                }
                this.stopLocked();
            }
            SESSIONS.remove(this.instanceId, this);
        }

        synchronized void stop() {
            this.stopLocked();
        }

        /**
         * Ends the asking: the wait between probes and a probe in flight are interrupted. The local lane's blocking
         * socket read ends at once because the asking runs on a virtual thread, whose interrupt closes a
         * {@code java.net.Socket} it is blocked on (on a platform thread a read ignores the interrupt, bounded only by
         * its {@code setSoTimeout} within {@link #PROBE_WAIT}); the shell lane's subprocess is stopped.
         */
        private void stopLocked() {
            this.stopped = true;
            Thread asking = this.thread;
            if (asking != null) {
                asking.interrupt();
            }
        }

        /** Hold the figure and queue it for every viewer, under the monitor attach queues the held figure under. */
        private synchronized @NonNull List<Attached> record(@NonNull Figure figure) {
            this.last = figure;
            List<Attached> heard = List.copyOf(this.viewers);
            heard.forEach(attached -> attached.offer(figure));
            return heard;
        }

        /** Ask, hand the figure to every viewer, wait by how it went, until stopped. */
        private void ask() {
            try {
                Consumer<Integer> hook = beforeAsk;
                if (hook != null) {
                    hook.accept(this.instanceId);
                }
                // Inside the try: a session stopped before its asking began still runs the finally, so its viewers
                // hear the end and its admission is released.
                synchronized (this) {
                    if (this.stopped) {
                        return;
                    }
                    this.thread = Thread.currentThread();
                }
                Backoff.Attempts unanswered = SILENT_BACKOFF.attempts();
                boolean first = true;
                while (!this.stopped) {
                    Target aimed = first ? this.target : this.reaim();
                    first = false;
                    if (aimed == null) {
                        // Deleted, or its probe removed: say there is no figure, then end.
                        Target previous = this.target;
                        if (previous != null) {
                            this.deliver(silent(previous));
                        }
                        break;
                    }
                    Figure figure = this.probe(aimed);
                    if (this.stopped) {
                        break;
                    }
                    this.deliver(figure);
                    Duration wait = switch (figure.state()) {
                        case ANSWERING, NOT_RUNNING -> {
                            unanswered.reset();
                            yield INTERVAL;
                        }
                        case SILENT -> unanswered.next(null);
                        // Never recorded by a probe; a full interval claims nothing.
                        case WAITING -> INTERVAL;
                    };
                    Pace.sleep(wait);
                }
            } catch (InterruptedException interrupted) {
                // stop(): the session ends.
            } finally {
                List<Attached> left;
                synchronized (this) {
                    this.stopped = true;
                    this.thread = null;
                    // Viewers still attached hear that the session ended: their instance went or the hub shut down.
                    left = List.copyOf(this.viewers);
                    left.forEach(attached -> attached.offerEnd(this.instanceId));
                }
                SESSIONS.remove(this.instanceId, this);
                Thread.interrupted();
                left.forEach(attached -> attached.outbox.drain());
            }
        }

        /** @return the instance's target read afresh, null when it is gone or declares no probe any more */
        private @Nullable Target reaim() {
            Target fresh;
            try {
                Row instance = Models.get(InstanceModel.class).findById(this.instanceId);
                fresh = instance == null ? null : Target.of(instance, PublishedPortProbe::forServer);
            } catch (RuntimeException unreadable) {
                Blast.log("FIGURE: instance", this.instanceId, "could not be read again:", unreadable.getMessage());
                return this.target;
            }
            if (fresh != null) {
                this.target = fresh;
            }
            return fresh;
        }

        /**
         * One probe inside the global bound; anything it throws reads as unanswered. An instance not meant to run is
         * never asked: its figure is its own state, and it takes no probe slot.
         */
        private @NonNull Figure probe(@NonNull Target aimed) throws InterruptedException {
            if (!aimed.running()) {
                return notRunning(aimed.instanceId(), aimed.probe(), aimed.status());
            }
            PROBES.acquire();
            try {
                return read(aimed);
            } catch (RuntimeException | StackOverflowError failed) {
                Blast.log("FIGURE: probe of instance", this.instanceId, "failed:", failed.getMessage());
                return silent(aimed);
            } finally {
                PROBES.release();
            }
        }

        private void deliver(@NonNull Figure figure) {
            for (Attached attached : this.record(figure)) {
                attached.outbox.drain();
            }
        }
    }
}
