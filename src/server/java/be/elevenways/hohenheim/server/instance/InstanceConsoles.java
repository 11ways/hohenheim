package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.instance.ReadinessKind;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.host.HostLeases;
import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.hohenheim.server.runtime.ConsoleStream;
import be.elevenways.hohenheim.server.runtime.ConsoleStreamSupport;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * THE console hub of the instance tier: at most one live console session per instance,
 * shared by every consumer -- the readiness matcher (Phase 5's {@code readiness_line}:
 * starting -> Running on an observed console line), the graceful-stop path
 * ({@code stop_command} over attach stdin), crash detection (an exit with NO observed
 * stop) with per-instance policy and supervisor-style flap protection, and the admin
 * console WebSocket's live output.
 *
 * AIDEV-NOTE: every status this hub stamps rides the SAME fenced write as the
 * synchronous operations ({@link InstanceOperationGuard#stamp}) -- a readiness flip is
 * a runtime outcome, and an async watcher is exactly the kind of stale controller the
 * fence exists to refuse. Callbacks run on virtual threads under the Db scope captured
 * when the watch was armed, so test datasources and the production default both work.
 */
public final class InstanceConsoles {

    /** How long an armed readiness matcher waits before stamping error. */
    private static final long READINESS_TIMEOUT_MS = 180_000;

    /** Flap protection (the deleted process lane's shape): this many crashes ... */
    private static final int FLAP_THRESHOLD = 3;

    /** ... within this window stop the auto-restarts until the next manual deploy. */
    private static final long FLAP_WINDOW_MS = 60_000;

    private static final Map<Integer, InstanceConsoleSession> SESSIONS = new ConcurrentHashMap<>();

    // AIDEV-NOTE: a session's late callbacks (the readiness timer and match, the exit policy) act only while its
    // generation is current, checked under the record's claim: closeSession bumps it for every replacement or end,
    // including a session the pump already dropped from SESSIONS, so a replaced console can never stamp, release
    // ports for or restart the deployment that replaced it (review 14 D02/D03).
    private static final Map<Integer, Long> GENERATIONS = new ConcurrentHashMap<>();

    private static final Map<Integer, Deque<Long>> CRASH_LOG = new ConcurrentHashMap<>();

    // Test seams: shrink the waits so a live test observes the negative halves.
    private static volatile long readinessTimeoutMs = READINESS_TIMEOUT_MS;
    private static volatile long flapWindowMs = FLAP_WINDOW_MS;

    private InstanceConsoles() {}

    /** Test seam; pass null to restore the defaults. */
    public static void overrideTimingsForTest(@Nullable Long readinessMs, @Nullable Long flapMs) {
        readinessTimeoutMs = readinessMs != null ? readinessMs : READINESS_TIMEOUT_MS;
        flapWindowMs = flapMs != null ? flapMs : FLAP_WINDOW_MS;
    }

    /**
     * What deploy must know after preparing the watch. {@code session} is null for a
     * DEFERRED attach (the driver refuses a console on a stopped workload): {@link #arm}
     * opens it after start and seeds the matcher from the driver's console backlog.
     */
    record Watch(@Nullable InstanceConsoleSession session, @Nullable String readinessLine,
                 @Nullable String stopCommand, @NonNull ConsoleStreamSupport support,
                 InstanceService.@NonNull Resolved resolved,
                 int serverId, @NonNull HostLeases leases, @Nullable Datasource datasource,
                 @NonNull Object instanceName) {

        /** The status deploy stamps: starting only when a readiness line will decide. */
        @NonNull String initialStatus() {
            return this.readinessLine != null
                ? InstanceModel.STATUS_STARTING : InstanceModel.STATUS_RUNNING;
        }
    }

    /**
     * Open the console watch for a freshly CREATED (not yet started) workload, so no
     * startup output can be missed. Returns null when nothing needs a console (no
     * matcher data, crash policy none) -- attaching to every instance would be pure
     * overhead. A driver without {@link ConsoleStreamSupport} is a NAMED refusal when
     * the template demands console behaviour; a driver whose console only exists on a
     * RUNNING workload gets a deferred watch instead of a refusal.
     *
     * @throws Violations {@code console_unsupported}
     */
    static @Nullable Watch prepare(InstanceService.@NonNull Resolved resolved, int instanceId,
                                   @NonNull HostLeases leases) {
        Row row = resolved.row();
        // AIDEV-NOTE: the readiness LINE only decides when the template declares
        // readiness_kind=console_line. The other two kinds are a probe after start
        // (InstanceReadiness), and honouring a leftover line beside them would leave a
        // workload stamped `starting` forever while its port probe had already passed.
        String readiness = InstanceReadiness.declaredKind(row) == ReadinessKind.CONSOLE_LINE
            ? trimmedOrNull(templateValue(row, InstanceTemplateModel.READINESS_LINE)) : null;
        String stopCommand = trimmedOrNull(templateValue(row, InstanceTemplateModel.STOP_COMMAND));
        boolean restartPolicy = InstanceModel.CRASH_RESTART
            .equals(row.get(InstanceModel.CRASH_POLICY));
        if (readiness == null && stopCommand == null && !restartPolicy) {
            return null;
        }
        if (!(resolved.runtime() instanceof ConsoleStreamSupport) && readiness == null && stopCommand == null) {
            // AIDEV-NOTE: restart alone never demands a console: without a stream to watch, the status reconciler
            // catches the unobserved exit at sweep cadence and redeploys it. Only a template's console contract
            // (readiness line, stop command) cannot be honoured blind. Restart became the default on 2026-10-07.
            return null;
        }
        if (!(resolved.runtime() instanceof ConsoleStreamSupport support)) {
            throw Violations.ofForm(HohenheimViolations.text("console_unsupported")
                .withArg("name", String.valueOf((Object) row.get(InstanceModel.NAME))));
        }
        closeSession(instanceId);
        if (support.attachRequiresRunning()) {
            // Incus shape: the attach happens in arm(), AFTER start; the gap between
            // start and attach is closed by seeding the driver's console backlog.
            return new Watch(null, readiness, stopCommand, support, resolved,
                resolved.serverId(), leases, Db.currentOrDefault(), row.get(InstanceModel.NAME));
        }
        // A fresh deploy is a fresh episode only insofar as the flap window has aged
        // out; rapid deploy-crash cycles stay throttled by design.
        InstanceConsoleSession session = open(support, resolved, instanceId, stopCommand,
            leases, Db.currentOrDefault());
        return new Watch(session, readiness, stopCommand, support, resolved,
            resolved.serverId(), leases, Db.currentOrDefault(), row.get(InstanceModel.NAME));
    }

    /**
     * Arm the prepared watch AFTER deploy stamped {@link Watch#initialStatus()}: the
     * readiness match flips starting -> Running through the fence, and a timeout
     * stamps error -- an instance whose line never appears must NOT reach Running.
     * A deferred watch attaches HERE (the workload is running now); an attach failure
     * stamps error and refuses the deploy, because a console-dependent contract
     * (readiness, graceful stop, crash detection) cannot be honoured blind.
     *
     * @throws Violations {@code console_open_failed} for a failed deferred attach
     */
    static void arm(@NonNull Watch watch, int instanceId) {
        InstanceConsoleSession session = watch.session();
        if (session == null) {
            try {
                session = open(watch.support(), watch.resolved(), instanceId,
                    watch.stopCommand(), watch.leases(), watch.datasource());
            } catch (Violations refused) {
                // The deploy's own thread, under its claim: no later generation can exist yet.
                stampIfStatus(watch.leases(), watch.serverId(), watch.instanceName(),
                    instanceId, () -> true, watch.initialStatus(), InstanceModel.STATUS_ERROR,
                    "deferred console attach failed", HohenheimActivityAction.WORKLOAD_START_FAILED, null);
                throw refused;
            }
            // Close the start-to-attach gap: what the daemon buffered before the
            // attach seeds the ring, so armReadiness's backlog scan sees it.
            try {
                session.seedBacklog(watch.support()
                    .consoleTail(watch.resolved().spec().handle(), 200));
            } catch (IOException unreadable) {
                Blast.log("CONSOLE: could not seed the console backlog of instance",
                    instanceId, "-", unreadable.getMessage());
            }
        }
        String readiness = watch.readinessLine();
        if (readiness == null) {
            return;
        }
        final InstanceConsoleSession armed = session;
        armed.armReadiness(readiness, () -> withScope(watch.datasource(), () ->
            stampIfStatus(watch.leases(), watch.serverId(), watch.instanceName(), instanceId,
                () -> isCurrent(armed), InstanceModel.STATUS_STARTING, InstanceModel.STATUS_RUNNING,
                "readiness line observed on the console", null, null)));
        long deadline = readinessTimeoutMs;
        JobRunner.startVirtualThread(() -> {
            try {
                Thread.sleep(deadline);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (armed.readinessMatched()) {
                return;
            }
            withScope(watch.datasource(), () ->
                stampIfStatus(watch.leases(), watch.serverId(), watch.instanceName(), instanceId,
                    () -> isCurrent(armed), InstanceModel.STATUS_STARTING, InstanceModel.STATUS_ERROR,
                    "readiness line not observed within " + deadline + "ms",
                    HohenheimActivityAction.WORKLOAD_NEVER_READY, null));
        });
    }

    /**
     * One fenced conditional stamp: only writes when the session's generation is still current and the row still
     * holds {@code expected}, both asked under the record's claim.
     *
     * @param cause  what made {@code status} an error, recorded beside it; null for any other status
     * @param detail the cause's detail as its fact declares it (the exit code), or null
     * @return whether the status was written
     */
    private static boolean stampIfStatus(@NonNull HostLeases leases, int serverId,
                                         @NonNull Object name, int instanceId,
                                         @NonNull BooleanSupplier current,
                                         @NonNull String expected, @NonNull String status,
                                         @NonNull String why, @Nullable HohenheimActivityAction cause,
                                         @Nullable String detail) {
        try {
            // Under the record's claim, queued behind whatever operation holds it; the generation and the status are
            // re-read inside it, so an operation that moved the record meanwhile wins and this observation is dropped.
            return InstanceOperationLock.of(leases).exclusive(instanceId, InstanceOperationLock.Contention.QUEUE,
                () -> {
                    if (!current.getAsBoolean()) {
                        Blast.log("CONSOLE: instance", instanceId, "-> not", status, "(" + why
                            + "): a later console generation replaced the observing one");
                        return false;
                    }
                    Row row = Models.get(InstanceModel.class).findById(instanceId);
                    if (row == null || !expected.equals(row.get(InstanceModel.STATUS))) {
                        return false;
                    }
                    leases.requireFence(serverId);
                    if (cause != null) {
                        InstanceOperationGuard.stampError(leases, instanceId, serverId, name, cause, detail);
                    } else {
                        InstanceOperationGuard.stamp(leases, instanceId, serverId, status, name);
                    }
                    Blast.log("CONSOLE: instance", instanceId, "->", status, "(" + why + ")");
                    return true;
                });
        } catch (Violations refused) {
            Blast.log("CONSOLE: instance", instanceId, "status write refused:",
                refused.getMessage());
            return false;
        }
    }

    /** Whether {@code session} still watches the instance's current deployment: no closeSession came after it. */
    private static boolean isCurrent(@NonNull InstanceConsoleSession session) {
        return GENERATIONS.getOrDefault(session.instanceId(), 0L) == session.generation();
    }

    /**
     * The session of an instance, opening one on its RUNNING workload when none exists
     * (the admin console path). Named refusals for a driver without streaming and for
     * a workload that is not running.
     *
     * @throws Violations {@code console_unsupported}, {@code console_not_running}
     */
    public static @NonNull InstanceConsoleSession ensureSession(int instanceId) {
        InstanceConsoleSession existing = SESSIONS.get(instanceId);
        if (existing != null && !existing.isEnded()) {
            return existing;
        }
        InstanceService service = new InstanceService();
        InstanceService.Resolved resolved = service.resolve(instanceId);
        if (!(resolved.runtime() instanceof ConsoleStreamSupport support)) {
            throw Violations.ofForm(HohenheimViolations.text("console_unsupported")
                .withArg("name", String.valueOf((Object) resolved.row().get(InstanceModel.NAME))));
        }
        if (!resolved.runtime().status(resolved.spec().handle()).running()) {
            throw Violations.ofForm(HohenheimViolations.text("console_not_running")
                .withArg("name", String.valueOf((Object) resolved.row().get(InstanceModel.NAME))));
        }
        String stopCommand = trimmedOrNull(
            templateValue(resolved.row(), InstanceTemplateModel.STOP_COMMAND));
        return open(support, resolved, instanceId, stopCommand,
            service.leases(), Db.currentOrDefault());
    }

    /**
     * The last {@code lines} lines of the workload's captured output -- the one-shot
     * automation-API read beside the streaming console. Works on a stopped container
     * too (the daemon keeps its log); a workload the daemon cannot answer for is a
     * named refusal, never an empty success.
     *
     * AIDEV-NOTE: this asks for CONSOLE, exactly as the streaming socket does at
     * handshake (InstanceConsoleHandler) -- it hands back the SAME captured output. It
     * shipped ungated while /api/v1/instances/{id}/logs resolved its record through
     * InstanceApi's view-only visibility helper, which made the one-shot read a wider
     * door than the stream: a view-only delegate got the console the WebSocket refused
     * them. Redaction below is not a substitute for the gate; it only keeps stored
     * secrets out of output the caller is already entitled to.
     *
     * @throws Violations {@code instance_not_permitted}, {@code console_unsupported},
     *         {@code logs_unavailable}
     */
    public static @NonNull String tail(int instanceId, int lines) {
        HohenheimAccess.requireOperationCapability(instanceId, HohenheimAccess.CONSOLE);
        InstanceService.Resolved resolved = new InstanceService().resolve(instanceId);
        if (!(resolved.runtime() instanceof ConsoleStreamSupport support)) {
            throw Violations.ofForm(HohenheimViolations.text("console_unsupported")
                .withArg("name", String.valueOf((Object) resolved.row().get(InstanceModel.NAME))));
        }
        try {
            // The one-shot read bypasses the session, so it carries its own redaction --
            // otherwise the automation API would be the wider door the WebSocket is not.
            return ConsoleRedaction.redactWhole(
                support.consoleTail(resolved.spec().handle(), lines), instanceId);
        } catch (IOException e) {
            throw Violations.ofForm(HohenheimViolations.text("logs_unavailable")
                .withArg("name", String.valueOf((Object) resolved.row().get(InstanceModel.NAME))));
        }
    }

    /**
     * Attach a viewer to an instance's console: ensures the session, replays its ring and
     * then follows live. The SAME funnel for the WebSocket handler and for anything else
     * that wants console output, so what a viewer sees is defined in exactly one place --
     * redacted text off the session ring, never the driver's raw stream.
     *
     * @return the detach handle; closing it twice is a no-op
     * @throws Violations {@code console_unsupported}, {@code console_not_running}
     */
    public static @NonNull Subscription subscribe(int instanceId,
                                                  @NonNull Consumer<String> viewer) {
        Viewer attached = attach(instanceId);
        attached.follow(viewer);
        return attached;
    }

    /**
     * Attach to an instance's console WITHOUT following it yet: the viewer learns whether
     * the console is interactive first (a pl-terminal renders raw terminal bytes and a
     * line console differently), then {@link Viewer#follow}s. Same funnel and same
     * session as {@link #subscribe}; keystrokes and resize frames ride the same handle.
     *
     * @throws Violations {@code console_unsupported}, {@code console_not_running}
     */
    public static @NonNull Viewer attach(int instanceId) {
        InstanceConsoleSession session = ensureSession(instanceId);
        return new Viewer() {

            private @Nullable Consumer<String> following;

            @Override
            public boolean interactive() {
                return session.interactive();
            }

            @Override
            public void follow(@NonNull Consumer<String> viewer) {
                this.following = viewer;
                session.subscribe(viewer);
            }

            @Override
            public void write(@NonNull String keystrokes) throws IOException {
                session.writeRaw(keystrokes);
            }

            @Override
            public void resize(int cols, int rows) throws IOException {
                session.resize(cols, rows);
            }

            @Override
            public void close() {
                Consumer<String> viewer = this.following;
                this.following = null;
                if (viewer != null) {
                    session.unsubscribe(viewer);
                }
            }
        };
    }

    /** Detach handle for {@link #subscribe}. */
    public interface Subscription extends AutoCloseable {
        @Override void close();
    }

    /**
     * One viewer's handle on a console: output out, and -- for an INTERACTIVE console
     * only -- keystrokes and geometry in. The write side needs no second capability
     * check: {@code console} already gates the attach, and typing into a terminal one
     * may watch is what a terminal is.
     */
    public interface Viewer extends Subscription {

        /** Whether the workload sits behind a pseudo-terminal. */
        boolean interactive();

        /** Replay the session ring, then follow live chunks (redacted text). */
        void follow(@NonNull Consumer<String> viewer);

        /**
         * Raw keystrokes toward the pseudo-terminal.
         *
         * @throws IOException when the console is not interactive or the write is refused
         */
        void write(@NonNull String keystrokes) throws IOException;

        /**
         * The viewer's terminal geometry, handed to the pseudo-terminal.
         *
         * @throws IOException when the console is not interactive or the driver refuses
         */
        void resize(int cols, int rows) throws IOException;
    }

    /** The live session of an instance, or null. */
    public static @Nullable InstanceConsoleSession peek(int instanceId) {
        InstanceConsoleSession session = SESSIONS.get(instanceId);
        return session != null && !session.isEnded() ? session : null;
    }

    /**
     * Send one console command to a running instance (admin console form, schedules
     * later). Routing through the hub is what makes a typed stop command an OBSERVED
     * stop everywhere crash detection looks.
     *
     * @throws Violations {@code console_send_failed} carrying the transport's reason
     */
    public static void sendCommand(int instanceId, @NonNull String command) {
        // THE console-write gate, on the funnel every surface reaches (CMS form,
        // automation API, schedule step) -- a per-handler copy is how the API ends up
        // a wider door than the UI (see HohenheimAccess.requireOperationCapability).
        HohenheimAccess.requireOperationCapability(instanceId, HohenheimAccess.CONSOLE);
        InstanceConsoleSession session = ensureSession(instanceId);
        try {
            session.sendCommand(command);
        } catch (IOException e) {
            throw Violations.ofForm(HohenheimViolations.text("console_send_failed")
                .withArg("reason", HohenheimViolations.reasonOf(e)));
        }
    }

    /**
     * Graceful console stop: when a live session exists, stdin is delivered and the
     * template declares a stop command, send it and wait up to the grace window for
     * the workload to exit on its own.
     *
     * @return true when the workload exited from the console command
     */
    static boolean tryGracefulStop(int instanceId, int graceSeconds) {
        InstanceConsoleSession session = peek(instanceId);
        if (session == null || !session.stdinDelivered() || session.stopCommand() == null) {
            return false;
        }
        session.markStopExpected();
        try {
            session.sendCommand(session.stopCommand());
        } catch (IOException e) {
            Blast.log("CONSOLE: graceful stop of instance", instanceId, "could not send"
                + " the stop command:", e.getMessage());
            return false;
        }
        return session.awaitEnd(graceSeconds * 1000L);
    }

    /** Mark the coming exit operator-intended (stop/destroy); no-op without a session. */
    static void markStopExpected(int instanceId) {
        InstanceConsoleSession session = SESSIONS.get(instanceId);
        if (session != null) {
            session.markStopExpected();
        }
    }

    /**
     * Declare a value secret on an instance whose console is ALREADY streaming: without
     * this, a variable written while an operator watches would ride the open session in the
     * clear until the next attach. Called from the variable write funnel, never by a caller
     * that merely happens to hold a secret string.
     */
    public static void registerSecret(int instanceId, @Nullable String value) {
        InstanceConsoleSession session = peek(instanceId);
        if (session != null && value != null) {
            session.addSecret(value);
        }
    }

    /** Test seam: write the live episode's history row now instead of at the interval. */
    public static void flushLogNow(int instanceId) {
        InstanceConsoleSession session = peek(instanceId);
        if (session != null) {
            session.flushLogNow();
        }
    }

    /** Close and forget an instance's session (destroy, redeploy replacement). */
    static void closeSession(int instanceId) {
        GENERATIONS.merge(instanceId, 1L, Long::sum);
        InstanceConsoleSession session = SESSIONS.remove(instanceId);
        if (session != null) {
            session.close();
        }
    }

    /** Test hook: every live session's count (the leak assertion's subject). */
    public static int liveSessionCount() {
        SESSIONS.values().removeIf(InstanceConsoleSession::isEnded);
        return SESSIONS.size();
    }

    // -- internals ------------------------------------------------------------

    private static @NonNull InstanceConsoleSession open(@NonNull ConsoleStreamSupport support,
                                                        InstanceService.@NonNull Resolved resolved,
                                                        int instanceId,
                                                        @Nullable String stopCommand,
                                                        @NonNull HostLeases leases,
                                                        @Nullable Datasource datasource) {
        ConsoleStreamSupport.Console console;
        try {
            console = support.openConsole(resolved.spec().handle());
        } catch (IOException e) {
            throw Violations.ofForm(HohenheimViolations.text("console_open_failed")
                .withArg("name", String.valueOf((Object) resolved.row().get(InstanceModel.NAME)))
                .withArg("reason", HohenheimViolations.reasonOf(e)));
        }
        int serverId = resolved.serverId();
        Object name = resolved.row().get(InstanceModel.NAME);
        // Built HERE, under the caller's Db scope: the pump thread must never have to read
        // the variable table itself, and an unreadable table must fail loudly at open
        // rather than silently stream a secret later.
        String handle = resolved.spec().handle();
        InstanceConsoleSession session = new InstanceConsoleSession(instanceId,
            handle, console, (cols, rows) -> support.resizeConsole(handle, cols, rows),
            stopCommand, ConsoleRedaction.redactorFor(instanceId),
            InstanceConsoleLogs.sinkFor(instanceId, resolved.spec().handle(), datasource),
            GENERATIONS.getOrDefault(instanceId, 0L),
            (endedSession, termination, detail) -> handleStreamEnd(endedSession, instanceId,
                serverId, name, support, leases, datasource, termination, detail));
        SESSIONS.put(instanceId, session);
        return session;
    }

    /**
     * The exit policy, run once per stream end on the pump thread, which is detached system
     * work (see InstanceConsoleSession), so a crash restart is the system's. An observed stop
     * (console stop command, or an operator stop/destroy) SUPPRESSES crash handling --
     * the same exit is "stopped" with it and "crashed" without it.
     */
    private static void handleStreamEnd(@NonNull InstanceConsoleSession session,
                                        int instanceId, int serverId, @NonNull Object name,
                                        @NonNull ConsoleStreamSupport support,
                                        @NonNull HostLeases leases,
                                        @Nullable Datasource datasource,
                                        ConsoleStream.@NonNull Termination termination,
                                        @NonNull String detail) {
        boolean stopObserved = session.stopObserved();
        // Remove only THIS session: a redeploy may already have replaced it.
        SESSIONS.remove(instanceId, session);
        if (termination == ConsoleStream.Termination.CONSUMER_CLOSED) {
            return;
        }
        if (termination == ConsoleStream.Termination.DAEMON_LOST) {
            // UNREACHABLE doctrine: we observed a transport failure, not a workload
            // outcome, so no status is stamped off it.
            Blast.log("CONSOLE: lost the daemon stream of instance", instanceId,
                detail.isEmpty() ? "" : "(" + detail + ")");
            return;
        }
        withScope(datasource, () -> {
            Integer exitCode;
            try {
                exitCode = support.exitCode(session.handle());
            } catch (IOException e) {
                Blast.log("CONSOLE: instance", instanceId, "stream ended but the daemon"
                    + " could not confirm the exit:", e.getMessage());
                return;
            }
            if (exitCode == null) {
                // Stream ended while the workload still runs (daemon-side hiccup):
                // nothing to stamp; a viewer or the next deploy re-attaches.
                Blast.log("CONSOLE: stream of instance", instanceId,
                    "ended while the container still runs; not treating it as an exit");
                return;
            }
            try {
                // The whole exit policy runs under the record's claim and only for the current generation: an exit
                // observed by a console a redeploy already replaced must not stamp, release the replacement's
                // observed port claims or restart it.
                InstanceOperationLock.of(leases).exclusive(instanceId, InstanceOperationLock.Contention.QUEUE,
                    () -> applyExitPolicy(session, instanceId, serverId, name, leases, stopObserved, exitCode));
            } catch (Violations refused) {
                Blast.log("CONSOLE: exit policy of instance", instanceId, "refused:", refused.getMessage());
            }
        });
    }

    /** The exit policy proper, under the record's claim. */
    private static void applyExitPolicy(@NonNull InstanceConsoleSession session, int instanceId, int serverId,
                                        @NonNull Object name, @NonNull HostLeases leases, boolean stopObserved,
                                        int exitCode) {
        if (!isCurrent(session)) {
            Blast.log("CONSOLE: exit", exitCode, "of a replaced console of instance", instanceId,
                "is not this deployment's; ignored");
            return;
        }
        Row row = Models.get(InstanceModel.class).findById(instanceId);
        if (row == null) {
            return;
        }
        if (stopObserved) {
            if (!stampIfAnyRunning(leases, serverId, name, instanceId,
                InstanceModel.STATUS_STOPPED, "observed stop, exit " + exitCode, null, null)) return;
            PortLedger.releaseOwnerObserved(InstanceModel.MODEL_ID, instanceId);
            return;
        }
        boolean restart = InstanceModel.CRASH_RESTART
            .equals(row.get(InstanceModel.CRASH_POLICY));
        if (!restart) {
            if (!stampIfAnyRunning(leases, serverId, name, instanceId,
                exitCode == 0 ? InstanceModel.STATUS_STOPPED : InstanceModel.STATUS_ERROR,
                "unexpected exit " + exitCode + ", crash policy none",
                exitCode == 0 ? null : HohenheimActivityAction.WORKLOAD_EXITED, String.valueOf(exitCode))) return;
            PortLedger.releaseOwnerObserved(InstanceModel.MODEL_ID, instanceId);
            return;
        }
        // Clean-exit-as-crash: with the restart policy ANY unobserved exit is a
        // crash, exit code 0 included (game servers "finish" cleanly when they die).
        if (flapExceeded(instanceId)) {
            if (!stampIfAnyRunning(leases, serverId, name, instanceId,
                InstanceModel.STATUS_ERROR,
                "crash loop: " + FLAP_THRESHOLD + " crashes inside " + flapWindowMs + "ms",
                HohenheimActivityAction.WORKLOAD_CRASH_LOOPED, null)) return;
            PortLedger.releaseOwnerObserved(InstanceModel.MODEL_ID, instanceId);
            alertCrashLoop(instanceId, name);
            return;
        }
        Blast.log("CONSOLE: instance", instanceId, "crashed (exit " + exitCode
            + ", no observed stop); crash policy restart -> redeploying");
        try {
            new InstanceService(leases, () -> {}).deploy(instanceId);
        } catch (Violations refused) {
            Blast.log("CONSOLE: crash restart of instance", instanceId, "refused:",
                refused.getMessage());
        }
    }

    /** Stamp from starting OR running (whichever the exit interrupted). */
    private static boolean stampIfAnyRunning(@NonNull HostLeases leases, int serverId,
                                          @NonNull Object name, int instanceId,
                                          @NonNull String status, @NonNull String why,
                                          @Nullable HohenheimActivityAction cause, @Nullable String detail) {
        // Called under the claim, after the generation check: the re-entrant stamps need no second one.
        return stampIfStatus(leases, serverId, name, instanceId, () -> true,
            InstanceModel.STATUS_RUNNING, status, why, cause, detail)
            || stampIfStatus(leases, serverId, name, instanceId, () -> true,
            InstanceModel.STATUS_STARTING, status, why, cause, detail);
    }

    /** Shared crash-flap clock: the console watch AND the status reconciler both count
     * their observed crashes here, so a workload flapping across BOTH lanes still trips
     * one threshold instead of two half-full ones. */
    static boolean flapExceeded(int instanceId) {
        long now = Now.millis();
        Deque<Long> log = CRASH_LOG.computeIfAbsent(instanceId, id -> new ArrayDeque<>());
        synchronized (log) {
            log.addLast(now);
            while (!log.isEmpty() && now - log.getFirst() > flapWindowMs) {
                log.removeFirst();
            }
            return log.size() >= FLAP_THRESHOLD;
        }
    }

    static void alertCrashLoop(int instanceId, @NonNull Object name) {
        Alerts.trySend(NotificationEvents.INSTANCE_CRASH_LOOP, Alerts.about(InstanceModel.MODEL_ID, instanceId),
            Alerts.copy("crash_loop_subject").withArg("name", name),
            Alerts.copy("crash_loop_body").withArg("name", name));
    }

    private static void withScope(@Nullable Datasource datasource, @NonNull Runnable body) {
        if (datasource != null) {
            Db.run(datasource, body);
        } else {
            body.run();
        }
    }

    private static @Nullable String templateValue(@NonNull Row instance,
                                                  @NonNull StringField field) {
        Object templateId = instance.get(InstanceModel.TEMPLATE_ID);
        if (!(templateId instanceof Integer id)) {
            return null;
        }
        Row template = Models.get(InstanceTemplateModel.class).findById(id);
        return template == null ? null : template.get(field);
    }

    private static @Nullable String trimmedOrNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
