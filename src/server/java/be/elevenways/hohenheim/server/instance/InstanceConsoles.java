package be.elevenways.hohenheim.server.instance;

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
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.thread.JobRunner;
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

    /** Flap protection (the ManagedProcessSiteHandler shape): this many crashes ... */
    private static final int FLAP_THRESHOLD = 3;

    /** ... within this window stop the auto-restarts until the next manual deploy. */
    private static final long FLAP_WINDOW_MS = 60_000;

    private static final Map<Integer, InstanceConsoleSession> SESSIONS = new ConcurrentHashMap<>();

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
        String readiness = trimmedOrNull(templateValue(row, InstanceTemplateModel.READINESS_LINE));
        String stopCommand = trimmedOrNull(templateValue(row, InstanceTemplateModel.STOP_COMMAND));
        boolean restartPolicy = InstanceModel.CRASH_RESTART
            .equals(row.get(InstanceModel.CRASH_POLICY));
        if (readiness == null && stopCommand == null && !restartPolicy) {
            return null;
        }
        if (!(resolved.runtime() instanceof ConsoleStreamSupport support)) {
            throw Violations.ofForm(Microcopy.of("console_unsupported")
                .withFilter("scope", "violations")
                .withArg("name", String.valueOf((Object) row.get(InstanceModel.NAME))));
        }
        closeSession(instanceId);
        if (support.attachRequiresRunning()) {
            // Incus shape: the attach happens in arm(), AFTER start; the gap between
            // start and attach is closed by seeding the driver's console backlog.
            return new Watch(null, readiness, stopCommand, support, resolved,
                resolved.serverId(), leases, Db.current(), row.get(InstanceModel.NAME));
        }
        // A fresh deploy is a fresh episode only insofar as the flap window has aged
        // out; rapid deploy-crash cycles stay throttled by design.
        InstanceConsoleSession session = open(support, resolved, instanceId, stopCommand,
            leases, Db.current());
        return new Watch(session, readiness, stopCommand, support, resolved,
            resolved.serverId(), leases, Db.current(), row.get(InstanceModel.NAME));
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
                stampIfStatus(watch.leases(), watch.serverId(), watch.instanceName(),
                    instanceId, watch.initialStatus(), InstanceModel.STATUS_ERROR,
                    "deferred console attach failed");
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
                InstanceModel.STATUS_STARTING, InstanceModel.STATUS_RUNNING,
                "readiness line observed on the console")));
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
                    InstanceModel.STATUS_STARTING, InstanceModel.STATUS_ERROR,
                    "readiness line not observed within " + deadline + "ms"));
        });
    }

    /** One fenced conditional stamp: only writes when the row still holds {@code expected}. */
    private static void stampIfStatus(@NonNull HostLeases leases, int serverId,
                                      @NonNull Object name, int instanceId,
                                      @NonNull String expected, @NonNull String status,
                                      @NonNull String why) {
        try {
            Row current = Models.get(InstanceModel.class).findById(instanceId);
            if (current == null || !expected.equals(current.get(InstanceModel.STATUS))) {
                return;
            }
            long fence = leases.requireFence(serverId);
            InstanceOperationGuard.stamp(leases, instanceId, serverId, fence, status, name);
            Blast.log("CONSOLE: instance", instanceId, "->", status, "(" + why + ")");
        } catch (Violations refused) {
            Blast.log("CONSOLE: instance", instanceId, "status write refused:",
                refused.getMessage());
        }
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
            throw Violations.ofForm(Microcopy.of("console_unsupported")
                .withFilter("scope", "violations")
                .withArg("name", String.valueOf((Object) resolved.row().get(InstanceModel.NAME))));
        }
        if (!resolved.runtime().status(resolved.spec().handle()).running()) {
            throw Violations.ofForm(Microcopy.of("console_not_running")
                .withFilter("scope", "violations")
                .withArg("name", String.valueOf((Object) resolved.row().get(InstanceModel.NAME))));
        }
        String stopCommand = trimmedOrNull(
            templateValue(resolved.row(), InstanceTemplateModel.STOP_COMMAND));
        return open(support, resolved, instanceId, stopCommand,
            service.leases(), Db.current());
    }

    /**
     * The last {@code lines} lines of the workload's captured output -- the one-shot
     * automation-API read beside the streaming console. Works on a stopped container
     * too (the daemon keeps its log); a workload the daemon cannot answer for is a
     * named refusal, never an empty success.
     *
     * @throws Violations {@code console_unsupported}, {@code logs_unavailable}
     */
    public static @NonNull String tail(int instanceId, int lines) {
        InstanceService.Resolved resolved = new InstanceService().resolve(instanceId);
        if (!(resolved.runtime() instanceof ConsoleStreamSupport support)) {
            throw Violations.ofForm(Microcopy.of("console_unsupported")
                .withFilter("scope", "violations")
                .withArg("name", String.valueOf((Object) resolved.row().get(InstanceModel.NAME))));
        }
        try {
            // The one-shot read bypasses the session, so it carries its own redaction --
            // otherwise the automation API would be the wider door the WebSocket is not.
            return ConsoleRedaction.redactWhole(
                support.consoleTail(resolved.spec().handle(), lines), instanceId);
        } catch (IOException e) {
            throw Violations.ofForm(Microcopy.of("logs_unavailable")
                .withFilter("scope", "violations")
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
        InstanceConsoleSession session = ensureSession(instanceId);
        session.subscribe(viewer);
        return () -> session.unsubscribe(viewer);
    }

    /** Detach handle for {@link #subscribe}. */
    @FunctionalInterface
    public interface Subscription extends AutoCloseable {
        @Override void close();
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
            throw Violations.ofForm(Microcopy.of("console_send_failed")
                .withFilter("scope", "violations")
                .withArg("reason", e.getMessage() != null ? e.getMessage() : e.toString()));
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
            throw Violations.ofForm(Microcopy.of("console_open_failed")
                .withFilter("scope", "violations")
                .withArg("name", String.valueOf((Object) resolved.row().get(InstanceModel.NAME)))
                .withArg("reason", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
        int serverId = resolved.serverId();
        Object name = resolved.row().get(InstanceModel.NAME);
        // Built HERE, under the caller's Db scope: the pump thread must never have to read
        // the variable table itself, and an unreadable table must fail loudly at open
        // rather than silently stream a secret later.
        InstanceConsoleSession session = new InstanceConsoleSession(instanceId,
            resolved.spec().handle(), console, stopCommand,
            ConsoleRedaction.redactorFor(instanceId),
            InstanceConsoleLogs.sinkFor(instanceId, resolved.spec().handle(), datasource),
            (endedSession, termination, detail) -> handleStreamEnd(endedSession, instanceId,
                serverId, name, support, leases, datasource, termination, detail));
        SESSIONS.put(instanceId, session);
        return session;
    }

    /**
     * The exit policy, run once per stream end on the pump thread. An observed stop
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
            Row row = Models.get(InstanceModel.class).findById(instanceId);
            if (row == null || row.get(InstanceModel.DELETED_AT) != null) {
                return;
            }
            if (stopObserved) {
                stampIfAnyRunning(leases, serverId, name, instanceId,
                    InstanceModel.STATUS_STOPPED, "observed stop, exit " + exitCode);
                PortLedger.releaseOwnerObserved(InstanceModel.MODEL_ID, instanceId);
                return;
            }
            boolean restart = InstanceModel.CRASH_RESTART
                .equals(row.get(InstanceModel.CRASH_POLICY));
            if (!restart) {
                stampIfAnyRunning(leases, serverId, name, instanceId,
                    exitCode == 0 ? InstanceModel.STATUS_STOPPED : InstanceModel.STATUS_ERROR,
                    "unexpected exit " + exitCode + ", crash policy none");
                PortLedger.releaseOwnerObserved(InstanceModel.MODEL_ID, instanceId);
                return;
            }
            // Clean-exit-as-crash: with the restart policy ANY unobserved exit is a
            // crash, exit code 0 included (game servers "finish" cleanly when they die).
            if (flapExceeded(instanceId)) {
                stampIfAnyRunning(leases, serverId, name, instanceId,
                    InstanceModel.STATUS_ERROR,
                    "crash loop: " + FLAP_THRESHOLD + " crashes inside " + flapWindowMs + "ms");
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
        });
    }

    /** Stamp from starting OR running (whichever the exit interrupted). */
    private static void stampIfAnyRunning(@NonNull HostLeases leases, int serverId,
                                          @NonNull Object name, int instanceId,
                                          @NonNull String status, @NonNull String why) {
        stampIfStatus(leases, serverId, name, instanceId,
            InstanceModel.STATUS_RUNNING, status, why);
        stampIfStatus(leases, serverId, name, instanceId,
            InstanceModel.STATUS_STARTING, status, why);
    }

    private static boolean flapExceeded(int instanceId) {
        long now = System.currentTimeMillis();
        Deque<Long> log = CRASH_LOG.computeIfAbsent(instanceId, id -> new ArrayDeque<>());
        synchronized (log) {
            log.addLast(now);
            while (!log.isEmpty() && now - log.getFirst() > flapWindowMs) {
                log.removeFirst();
            }
            return log.size() >= FLAP_THRESHOLD;
        }
    }

    private static void alertCrashLoop(int instanceId, @NonNull Object name) {
        try {
            Alerts.send(NotificationEvents.INSTANCE_CRASH_LOOP,
                "Crash loop: instance " + name,
                "Instance '" + name + "' (#" + instanceId + ") keeps exiting without an"
                    + " observed stop; automatic restarts are suspended until the next"
                    + " deploy. Check its console output.");
        } catch (Exception e) {
            Blast.log("CONSOLE: could not send crash-loop notification -", e.getMessage());
        }
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
