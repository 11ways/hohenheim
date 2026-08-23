package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceService.Resolved;
import be.elevenways.hohenheim.server.runtime.ConsoleStream;
import be.elevenways.hohenheim.server.runtime.PtySupport;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.Accountability;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * THE interactive shell of the instance tier: one pseudo-terminal inside a workload, as
 * that workload's own non-root uid, gated on the delegable {@code shell} capability.
 *
 * <p>THREE gates, all on this funnel, in this order:</p>
 * <ol>
 *   <li>the caller holds {@code shell} on THIS record (principal-only walk -- see below);</li>
 *   <li>the workload declares a NON-ROOT {@code runUser};</li>
 *   <li>its driver offers a pseudo-terminal at all.</li>
 * </ol>
 *
 * AIDEV-NOTE: gate 2 -- the non-root uid -- is the whole security argument, so it is an
 * explicit REFUSAL here rather than an accident of how a kind happens to be configured.
 * Hohenheim cannot use per-container user-namespace remapping: {@code UsernsMode} is a
 * DAEMON-level setting hohenheim does not own, and {@code ContainerHardening.refuseEscapes}
 * refuses it on every create. So uid 0 inside a container IS uid 0 on the host, bounded
 * only by drop-ALL capabilities and no-new-privileges -- a real but thin margin, and not
 * one to hand a tenant an interactive terminal behind. {@code WorkspaceKind} derives a bare
 * high uid with no host account ({@code WorkspaceUids}), and THAT is what makes a shell
 * there defensible. A kind that runs as root gets a named refusal, never a shell.
 *
 * AIDEV-NOTE: the capability is checked through the PRINCIPAL-only walk and a null
 * principal is refused, deliberately NOT through
 * {@code HohenheimAccess.requireOperationCapability}. That funnel default-ALLOWS whenever
 * no tenant conduit is in flight (its own docblock names the deferred inversion), and a
 * WebSocket handler is exactly that case -- using it here would have been a gate that
 * always passed on the one surface that needs it most.
 *
 * AIDEV-NOTE: exec and shell are NOT two spellings of one verb. exec is ADMIN, single-shot
 * and may run as any user the workload declares; shell is a tenant verb bounded to a
 * non-root workload and to an interactive terminal. Do not fold them.
 */
public final class InstanceShell {

    /** Geometry a client gets before it has measured its own terminal. */
    public static final int DEFAULT_COLS = 100;
    public static final int DEFAULT_ROWS = 30;

    /**
     * A session with no keystrokes and no resize for this long is closed.
     *
     * AIDEV-NOTE: measured on INPUT, never on output: a shell running {@code tail -f}
     * produces output forever with nobody at the keyboard, and treating that as activity
     * would mean an abandoned terminal never closes.
     */
    public static final long IDLE_TIMEOUT_MS = 15 * 60 * 1000;

    /** How often the idle sweep runs; a session lives at most this much past its timeout. */
    private static final long SWEEP_INTERVAL_MS = 30 * 1000;

    /** Concurrent sessions one instance may carry, over every viewer of that record. */
    public static final int MAX_SESSIONS_PER_INSTANCE = 3;

    /** The fallback interpreter, used when the image declares none. */
    static final String FALLBACK_SHELL = "/bin/sh";

    /**
     * How long a freshly started shell is given to fall over before it is believed.
     *
     * AIDEV-NOTE: MEASURED at ~50ms against Docker 29.7 (a missing interpreter reports
     * ExitCode 127 that fast); 250ms is the margin, and it is the ONLY latency the missing
     * -shell refusal costs. The alternative -- a buffered probe exec before opening --
     * was deleted: it doubled the daemon round trips on the hot path AND rode
     * {@code DockerClient.exec}, whose 10-minute cap would have parked a WebSocket
     * handshake for ten minutes on a contended daemon (OBSERVED, 2026-08-23).
     */
    private static final long START_SETTLE_MS = 250;

    /** Activity actions: a shell opening and a shell closing are both audit events. */
    public static final String ACTIVITY_OPEN = "shell_open";
    public static final String ACTIVITY_CLOSE = "shell_close";

    private static final Map<Integer, List<Session>> LIVE = new ConcurrentHashMap<>();

    private static final ScheduledExecutorService SWEEPER =
        Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "hohenheim-shell-idle-sweep");
            thread.setDaemon(true);
            return thread;
        });

    private static final AtomicBoolean SWEEPING = new AtomicBoolean();

    private final @NonNull InstanceService instances;

    public InstanceShell() {
        this(new InstanceService());
    }

    public InstanceShell(@NonNull InstanceService instances) {
        this.instances = instances;
    }

    /** Why a session ended; recorded on the activity entry. */
    public enum EndReason {
        /** The viewer closed the terminal (or its socket died). */
        CLIENT,
        /** The shell itself exited, or the stream ended. */
        EXITED,
        /** No keystroke for {@link #IDLE_TIMEOUT_MS}. */
        IDLE,
        /** The capability was revoked while the session was live. */
        REVOKED
    }

    /** One live shell as its consumer sees it; every method is safe after close. */
    public interface Session {

        /** Forward keystrokes toward the shell; a no-op once the session ended. */
        void write(@NonNull String data);

        /** Tell the pseudo-terminal its new geometry; a no-op once the session ended. */
        void resize(int cols, int rows);

        /** End the session; idempotent, and the SECOND call does nothing at all. */
        void close(@NonNull EndReason reason);

        boolean isOpen();
    }

    /**
     * Whether an interactive shell can be offered for this instance AT ALL -- the question
     * the tab asks so an absent lane renders as a named state instead of an error banner.
     * Answers the runtime and uid gates only; the CAPABILITY is the caller's to ask.
     */
    public @NonNull Availability availabilityOf(int instanceId) {
        Resolved resolved;
        try {
            resolved = this.instances.resolve(instanceId);
        } catch (Violations unresolvable) {
            return Availability.NO_RUNTIME_LANE;
        }
        // The UID is asked first, here and in open(), and the two orders must stay the
        // same or the tab would state one reason while the socket refuses with another.
        Integer runUser = resolved.spec().runUser();
        if (runUser == null || runUser == 0) {
            return Availability.RUNS_AS_ROOT;
        }
        if (!(resolved.runtime() instanceof PtySupport pty) || !pty.supportsPty()) {
            return Availability.NO_RUNTIME_LANE;
        }
        return Availability.AVAILABLE;
    }

    /** The three answers {@link #availabilityOf} can give, each a named state in the UI. */
    public enum Availability {
        AVAILABLE,
        /** The driver has no pseudo-terminal lane (today: every Incus workload). */
        NO_RUNTIME_LANE,
        /** The workload runs as uid 0 in its container, so a shell there is refused. */
        RUNS_AS_ROOT
    }

    /**
     * Open one interactive shell.
     *
     * @param principal the identity the socket authenticated; null is refused
     * @param output    receives terminal bytes as text, on the session's pump thread
     * @param onEnd     runs once when the session ends for any reason (the socket's cue
     *                  to close); it must not throw
     * @throws Violations {@code instance_not_permitted}, {@code shell_not_running},
     *         {@code shell_unsupported_runtime}, {@code shell_requires_nonroot_workload},
     *         {@code shell_missing_in_image}, {@code shell_too_many_sessions},
     *         {@code shell_failed}
     */
    public @NonNull Session open(int instanceId, @Nullable Principal principal,
                                 int cols, int rows,
                                 @NonNull Consumer<String> output,
                                 @NonNull Runnable onEnd) {

        // GATE 1, first and on the funnel: nothing about the instance is resolved for a
        // caller who may not shell into it, so this is not an existence oracle either.
        if (principal == null || principal.isAnonymous()
                || !HohenheimAccess.hasInstanceCapability(principal, instanceId,
                    HohenheimAccess.SHELL)) {
            throw Violations.ofForm(Microcopy.of("instance_not_permitted")
                .withFilter("scope", "violations"));
        }

        Resolved resolved = this.instances.resolve(instanceId);
        Row instance = resolved.row();
        InstanceOperationGuard.requireOperable(instance);

        if (!InstanceModel.STATUS_RUNNING.equals(instance.get(InstanceModel.STATUS))) {
            throw refusal("shell_not_running", instance);
        }

        // GATE 2: the uid. See the class note -- this is the mitigation the whole feature
        // stands on, so it is a refusal by name and not a configuration accident.
        //
        // AIDEV-NOTE: BEFORE the driver gate, deliberately. Running as root is a fact about
        // the WORKLOAD and is true whichever driver carries it, while "no shell lane" is a
        // fact about the platform -- and every Docker kind but the workspace is built
        // without a streaming lane, so a driver-first order would answer "this runtime has
        // no shell lane" for a root Docker container, which is both less actionable and,
        // read literally, wrong. The cost is that an Incus workload with no runUser hears
        // the uid reason rather than the runtime one; both are true of it.
        Integer runUser = resolved.spec().runUser();
        if (runUser == null || runUser == 0) {
            throw refusal("shell_requires_nonroot_workload", instance);
        }

        // GATE 3: the driver. An Incus workload has no pseudo-terminal lane yet, exactly
        // as it has no file lane, and says so rather than failing mid-handshake.
        if (!(resolved.runtime() instanceof PtySupport pty) || !pty.supportsPty()) {
            throw refusal("shell_unsupported_runtime", instance);
        }

        List<Session> sessions = LIVE.computeIfAbsent(instanceId,
            key -> new CopyOnWriteArrayList<>());
        // Bound BEFORE the daemon is asked for anything: an unbounded lane is a way to
        // spend a host's process table from a browser tab.
        synchronized (sessions) {
            sessions.removeIf(session -> !session.isOpen());
            if (sessions.size() >= MAX_SESSIONS_PER_INSTANCE) {
                throw Violations.ofForm(Microcopy.of("shell_too_many_sessions")
                    .withFilter("scope", "violations")
                    .withArg("name", String.valueOf((Object) instance.get(InstanceModel.NAME)))
                    .withArg("max", String.valueOf(MAX_SESSIONS_PER_INSTANCE)));
            }
        }

        List<String> candidates = shellCandidates(instance);
        Started started = openFirstWorkingShell(pty, resolved, instance, candidates, cols, rows);

        LiveSession session = new LiveSession(instanceId, runUser, started.shell(), started.pty(),
            output, onEnd, accountabilityOf(principal), Db.currentOrDefault());
        sessions.add(session);
        session.start();
        startSweeper();

        session.record(ACTIVITY_OPEN, started.shell() + " as uid " + runUser);
        Blast.log("SHELL: opened on instance", instanceId, "as uid", runUser,
            "->", started.shell());
        return session;
    }

    /** Close every live session of one instance (a stop, a redeploy, a destroy). */
    public static void closeSessionsOf(int instanceId, @NonNull EndReason reason) {
        List<Session> sessions = LIVE.remove(instanceId);
        if (sessions == null) {
            return;
        }
        for (Session session : sessions) {
            session.close(reason);
        }
    }

    /** Live sessions of one instance; the concurrency bound's observable half. */
    public static int liveSessionCount(int instanceId) {
        List<Session> sessions = LIVE.get(instanceId);
        if (sessions == null) {
            return 0;
        }
        int live = 0;
        for (Session session : sessions) {
            if (session.isOpen()) {
                live++;
            }
        }
        return live;
    }

    /** One started pseudo-terminal and the interpreter it is running. */
    private record Started(@NonNull String shell, PtySupport.@NonNull PtySession pty) {}

    /**
     * THE interpreters to try, in order: the runtime image's declared one, then
     * {@link #FALLBACK_SHELL}.
     *
     * AIDEV-NOTE: never a hardcoded bash. The image row carries a {@code shell} column
     * precisely so an image can declare its own, and an image that declares one it does not
     * ship must produce a named refusal rather than a terminal that opens and instantly
     * dies with an unreadable exit code.
     */
    private static @NonNull List<String> shellCandidates(@NonNull Row instance) {
        List<String> candidates = new ArrayList<>();
        Object imageId = instance.get(InstanceModel.RUNTIME_IMAGE_ID);
        if (imageId instanceof Integer id) {
            Row image = Models.get(RuntimeImageModel.class).findById(id);
            String declared = image == null ? null : image.get(RuntimeImageModel.SHELL);
            if (declared != null && !declared.isBlank()) {
                candidates.add(declared.trim());
            }
        }
        if (!candidates.contains(FALLBACK_SHELL)) {
            candidates.add(FALLBACK_SHELL);
        }
        return candidates;
    }

    /**
     * Start the first candidate that actually runs, closing any that did not.
     *
     * @throws Violations {@code shell_missing_in_image} when none of them runs,
     *         {@code shell_failed} when the driver could not be reached at all
     */
    private static @NonNull Started openFirstWorkingShell(@NonNull PtySupport pty,
                                                          @NonNull Resolved resolved,
                                                          @NonNull Row instance,
                                                          @NonNull List<String> candidates,
                                                          int cols, int rows) {
        for (String candidate : candidates) {
            PtySupport.PtySession attempt;
            try {
                attempt = pty.openPty(resolved.spec(), List.of(candidate), cols, rows);
            } catch (IOException e) {
                throw Violations.ofForm(Microcopy.of("shell_failed")
                    .withFilter("scope", "violations")
                    .withArg("name", String.valueOf((Object) instance.get(InstanceModel.NAME)))
                    .withArg("reason", String.valueOf(e.getMessage())));
            }
            boolean dead;
            try {
                Thread.sleep(START_SETTLE_MS);
                dead = attempt.failedToStart();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                attempt.close();
                throw Violations.ofForm(Microcopy.of("shell_failed")
                    .withFilter("scope", "violations")
                    .withArg("name", String.valueOf((Object) instance.get(InstanceModel.NAME)))
                    .withArg("reason", "interrupted"));
            } catch (IOException unreachable) {
                // The daemon could not be asked whether it started. Believing it started
                // would be the silent-success shape; refuse and say why.
                attempt.close();
                throw Violations.ofForm(Microcopy.of("shell_failed")
                    .withFilter("scope", "violations")
                    .withArg("name", String.valueOf((Object) instance.get(InstanceModel.NAME)))
                    .withArg("reason", String.valueOf(unreachable.getMessage())));
            }
            if (!dead) {
                return new Started(candidate, attempt);
            }
            attempt.close();
        }
        throw Violations.ofForm(Microcopy.of("shell_missing_in_image")
            .withFilter("scope", "violations")
            .withArg("name", String.valueOf((Object) instance.get(InstanceModel.NAME)))
            .withArg("shell", String.join(", ", candidates)));
    }

    private static @NonNull Violations refusal(@NonNull String key, @NonNull Row instance) {
        return Violations.ofForm(Microcopy.of(key).withFilter("scope", "violations")
            .withArg("name", String.valueOf((Object) instance.get(InstanceModel.NAME))));
    }

    /** The same attribution shape zenit-auth's request resolver produces, off-request. */
    private static @NonNull Accountability accountabilityOf(@NonNull Principal principal) {
        return new Accountability(String.valueOf(principal.id()), principal.displayName(),
            null, null, Accountability.ORIGIN_WEB);
    }

    private static void startSweeper() {
        if (SWEEPING.compareAndSet(false, true)) {
            SWEEPER.scheduleWithFixedDelay(InstanceShell::sweepIdle,
                SWEEP_INTERVAL_MS, SWEEP_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    /** Close abandoned terminals and forget finished ones. */
    static void sweepIdle() {
        long now = System.currentTimeMillis();
        for (Map.Entry<Integer, List<Session>> entry : LIVE.entrySet()) {
            List<Session> sessions = entry.getValue();
            for (Session session : sessions) {
                if (session instanceof LiveSession live && live.isOpen()
                        && now - live.lastInputAt() > IDLE_TIMEOUT_MS) {
                    live.close(EndReason.IDLE);
                }
            }
            sessions.removeIf(session -> !session.isOpen());
            if (sessions.isEmpty()) {
                LIVE.remove(entry.getKey(), sessions);
            }
        }
    }

    /**
     * One live pseudo-terminal plus the bookkeeping around it.
     *
     * AIDEV-NOTE: teardown is EXACTLY ONCE and ALWAYS, and both halves matter. The
     * {@code ended} flag is what makes a second close (the socket's onClose after our own
     * close, an idle sweep racing a shell exit) a no-op instead of a second activity entry
     * and a second onEnd. The pump thread runs {@link #close} when the stream ends, so a
     * shell that exits on its own tears down without anyone asking -- and the framework's
     * WebSocketTeardown runs the socket half even for a close the peer never answers.
     */
    private static final class LiveSession implements Session {

        private final int instanceId;
        private final int runUser;
        private final @NonNull String shell;
        private final PtySupport.@NonNull PtySession pty;
        private final @NonNull Consumer<String> output;
        private final @NonNull Runnable onEnd;
        private final @NonNull Accountability accountability;
        private final @Nullable Datasource datasource;
        private final AtomicBoolean ended = new AtomicBoolean();
        private volatile long lastInputAt = System.currentTimeMillis();

        LiveSession(int instanceId, int runUser, @NonNull String shell,
                    PtySupport.@NonNull PtySession pty, @NonNull Consumer<String> output,
                    @NonNull Runnable onEnd, @NonNull Accountability accountability,
                    @Nullable Datasource datasource) {
            this.instanceId = instanceId;
            this.runUser = runUser;
            this.shell = shell;
            this.pty = pty;
            this.output = output;
            this.onEnd = onEnd;
            this.accountability = accountability;
            this.datasource = datasource;
        }

        void start() {
            JobRunner.startVirtualThread(this::pump);
        }

        long lastInputAt() {
            return this.lastInputAt;
        }

        private void pump() {
            ConsoleStream stream = this.pty.stream();
            ConsoleStream.Chunk chunk;
            try {
                while ((chunk = stream.next()) != null) {
                    String text = new String(chunk.data(), StandardCharsets.UTF_8);
                    if (!text.isEmpty()) {
                        try {
                            this.output.accept(text);
                        } catch (RuntimeException deadViewer) {
                            // A viewer that throws is a viewer that is gone; end the
                            // session rather than spin on a socket nobody reads.
                            break;
                        }
                    }
                }
            } finally {
                // The shell exited (or the daemon went away): tear down without waiting for
                // anyone to ask. A close already in flight makes this a no-op.
                this.close(EndReason.EXITED);
            }
        }

        @Override
        public void write(@NonNull String data) {
            if (this.ended.get() || data.isEmpty()) {
                return;
            }
            this.lastInputAt = System.currentTimeMillis();
            try {
                this.pty.stream().writeStdin(data.getBytes(StandardCharsets.UTF_8));
            } catch (IOException gone) {
                this.close(EndReason.EXITED);
            }
        }

        @Override
        public void resize(int cols, int rows) {
            if (this.ended.get()) {
                return;
            }
            this.lastInputAt = System.currentTimeMillis();
            try {
                this.pty.resize(cols, rows);
            } catch (IOException refused) {
                // Never silent: a terminal stuck at its opening size looks like breakage.
                Blast.log("SHELL: resize of instance", this.instanceId, "refused:",
                    refused.getMessage());
            }
        }

        @Override
        public void close(@NonNull EndReason reason) {
            if (!this.ended.compareAndSet(false, true)) {
                return;
            }
            this.pty.close();
            List<Session> sessions = LIVE.get(this.instanceId);
            if (sessions != null) {
                sessions.remove(this);
            }
            this.record(ACTIVITY_CLOSE, this.shell + " as uid " + this.runUser
                + " (" + reason.name().toLowerCase(java.util.Locale.ROOT) + ")");
            Blast.log("SHELL: closed on instance", this.instanceId, "-", reason);
            try {
                this.onEnd.run();
            } catch (RuntimeException ignored) {
                // The socket is already going away; nothing here may throw past teardown.
            }
        }

        @Override
        public boolean isOpen() {
            return !this.ended.get();
        }

        /**
         * Write the audit entry attributed to whoever opened the session.
         *
         * AIDEV-NOTE: both the datasource scope and the attribution are RE-ENTERED, because
         * this runs on a pump/sweeper thread where neither ThreadLocal survives -- without
         * them an audited act would be recorded as system work, or not at all.
         */
        void record(@NonNull String action, @NonNull String detail) {
            Runnable write = () -> Accountability.runAs(this.accountability, () ->
                ActivityLog.record(Models.get(InstanceModel.class), this.instanceId,
                    action, detail));
            try {
                if (this.datasource == null) {
                    write.run();
                } else {
                    Db.run(this.datasource, write);
                }
            } catch (RuntimeException e) {
                // An audit that cannot be written must be LOUD; it must not kill the
                // session teardown it is part of.
                Blast.log("SHELL: could not record", action, "for instance",
                    this.instanceId, "-", e.getMessage());
            }
        }
    }
}
