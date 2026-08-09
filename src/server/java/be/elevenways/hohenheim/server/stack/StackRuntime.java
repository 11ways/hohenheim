package be.elevenways.hohenheim.server.stack;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.StackDeploymentModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.DockerReclaim;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.Accountability;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Lifecycle entry point for managed stacks: explicit deploy/stop/rollback (never
 * save-triggered), one worker per stack so operations on the same stack serialize
 * while different stacks proceed in parallel, and status refresh with alerting on
 * health transitions.
 *
 * AIDEV-NOTE: stack accountability lives HERE, in the one funnel, for the reason
 * {@link be.elevenways.hohenheim.server.instance.InstanceService} states for instance
 * power: the surfaces would each need their own copy, and the status write is a
 * set-based {@code updateAll} that fires no model hooks, so {@code
 * ActivityLog.withAction} has nothing to rename and would record literally nothing.
 * What made the stack tier the LAST silent tier is the thread hop: every operation runs
 * on the stack's own worker and {@code Accountability} is a ThreadLocal, so a row
 * written there is unattributed system work unless the dispatching thread's attribution
 * is snapshotted and re-entered -- which {@link #onWorker} and {@link #submitAsync} now
 * do for every hop, sync and async. WHICH surface acted stays answerable through the
 * activity row's own {@code origin} column: {@code web} for a panel action, {@code
 * system} for the adoption and boot-recovery callers.
 */
public class StackRuntime {

    /** The activity action a SETTLED stack deploy is recorded under. */
    public static final String ACTIVITY_DEPLOY_ACTION = "deployed";

    /** The activity action a SETTLED redeploy of an older snapshot is recorded under. */
    public static final String ACTIVITY_ROLLBACK_ACTION = "rolled_back";

    /** The activity action a SETTLED stop is recorded under. */
    public static final String ACTIVITY_STOP_ACTION = "stopped";

    /** The activity action a SETTLED volume purge is recorded under. */
    public static final String ACTIVITY_PURGE_ACTION = "volumes_purged";

    /** {@link #runDeploy}'s reason for a rollback (the deployment record's own word). */
    private static final String REASON_ROLLBACK = "rollback";

    private static final StackRuntime INSTANCE = new StackRuntime();

    private final ConcurrentHashMap<Integer, ExecutorService> workers = new ConcurrentHashMap<>();

    /** The stack id whose worker the current thread IS, for re-entrant onWorker calls. */
    private static final ThreadLocal<Integer> CURRENT_STACK = new ThreadLocal<>();
    private final Function<String, DockerClient> clientFor;
    private final @Nullable Datasource datasource;

    private StackRuntime() {
        ServerService servers = new ServerService();
        this.clientFor = servers::clientFor;
        this.datasource = null;
    }

    /** Test seam: fixed Docker client and datasource, synchronous helpers below. */
    public StackRuntime(@NonNull DockerClient docker, @NonNull Datasource datasource) {
        this.clientFor = serverName -> docker;
        this.datasource = datasource;
    }

    public static @NonNull StackRuntime get() {
        return INSTANCE;
    }

    // -- async admin operations ----------------------------------------------

    /** Queue a deploy of the stack's CURRENT records. */
    public void deployAsync(int stackId, @NonNull String reason) {
        setStatus(stackId, StackModel.STATUS_DEPLOYING);
        submitAsync(stackId, () -> runDeploy(stackId, reason, null));
    }

    /** Queue a re-deploy of the newest successful deployment's spec snapshot. */
    public void rollbackAsync(int stackId) {
        setStatus(stackId, StackModel.STATUS_DEPLOYING);
        submitAsync(stackId, () -> {
            StackSpec snapshot = scoped(() -> latestSnapshot(stackId));
            if (snapshot == null) {
                Blast.log("STACK: no successful deployment to roll back to for stack", stackId);
                // Nothing was touched, so "failed" would lie about possibly-healthy
                // containers: release the deploying claim and recompute from live state.
                setStatus(stackId, StackModel.STATUS_INACTIVE);
                refreshStatus(stackId);
                return;
            }
            runDeploy(stackId, REASON_ROLLBACK, snapshot);
        });
    }

    /** Queue a stop of every stack container (reverse dependency order). */
    public void stopAsync(int stackId) {
        submitAsync(stackId, () -> {
            try {
                runStop(stackId);
            } catch (Exception e) {
                Blast.log("STACK: stop failed for stack", stackId, "-", e.getMessage());
                refreshStatus(stackId);
            }
        });
    }

    // -- synchronous operations ----------------------------------------------
    // Every synchronous operation ALSO runs on the stack's worker: a caller-thread
    // Docker mutation racing a queued deploy is exactly the interleaving the
    // one-worker-per-stack design exists to prevent.

    /** Deploy synchronously (tests, scripted use); throws on failure. */
    public void deploy(int stackId, @NonNull String reason) throws IOException {
        IOException failure = onWorker(stackId, () -> runDeploy(stackId, reason, null));
        if (failure != null) {
            throw failure;
        }
    }

    /** Roll back synchronously to the newest successful snapshot; throws on failure. */
    public void rollback(int stackId) throws IOException {
        StackSpec snapshot = scoped(() -> latestSnapshot(stackId));
        if (snapshot == null) {
            throw new IOException("No successful deployment to roll back to");
        }
        IOException failure = onWorker(stackId, () -> runDeploy(stackId, REASON_ROLLBACK, snapshot));
        if (failure != null) {
            throw failure;
        }
    }

    /** Stop synchronously; throws on failure. */
    public void stop(int stackId) throws IOException {
        onWorker(stackId, () -> {
            if (!runStop(stackId)) {
                throw new IOException("Stack " + stackId + " does not exist");
            }
            return null;
        });
    }

    /**
     * The stop ITSELF, shared by both surfaces so neither can be the audited one.
     *
     * @return false when the stack does not exist (the async caller's silent no-op)
     */
    private boolean runStop(int stackId) throws IOException {
        StackSpec spec = scoped(() -> teardownSpec(stackId));
        if (spec == null) {
            return false;
        }
        stopServices(spec);
        setStatus(stackId, StackModel.STATUS_STOPPED);
        recordSettled(stackId, ACTIVITY_STOP_ACTION, spec.name());
        return true;
    }

    /**
     * Remove every owned container and the network; owned volumes only when
     * {@code removeVolumes}. Used by record deletion. Serializes behind any
     * queued deploy so the queued work cannot recreate containers after this
     * removed them.
     *
     * The worker is deliberately NOT retired: removing it from the map opens a
     * window where a concurrent submission mints a SECOND executor (two threads
     * mutating the same Docker resources) or hits RejectedExecutionException
     * after a queue-time status claim (a permanently wedged "deploying" row). A
     * parked virtual thread per ever-touched stack id is the cheaper bug.
     */
    public void destroy(int stackId, boolean removeVolumes) throws IOException {
        onWorker(stackId, () -> {
            // Unordered on purpose: a broken dependency graph must not make a stack
            // undeletable, and teardown does not need the order.
            StackSpec spec = scoped(() -> teardownSpec(stackId));
            if (spec == null) {
                return null;
            }
            destroyServices(spec, removeVolumes);
            setStatus(stackId, StackModel.STATUS_INACTIVE);
            return null;
        });
    }

    /**
     * Boot sweep: a deploy interrupted by a crash or restart must not own the status
     * forever -- {@code refreshStatus} defers to a "running" deploy, so a stale
     * {@code deploying} row would disable monitoring and alerting permanently.
     */
    public void resetInterruptedDeploys() {
        List<Row> stuck = scoped(() -> Models.get(StackModel.class)
            .find().where(StackModel.STATUS.eq(StackModel.STATUS_DEPLOYING)).all());
        for (Row stack : stuck) {
            Integer stackId = stack.get(StackModel.ID);
            Blast.log("STACK: deploy of stack", stackId,
                "was interrupted by a restart; recomputing status from live containers");
            setStatus(stackId, StackModel.STATUS_FAILED);
            refreshStatus(stackId);
        }
    }

    /** Run a stack operation on its worker and wait, unwrapping the checked failure.
     *  Re-entrant: a call from within the same stack's worker runs inline, so worker
     *  tasks (a rollback's status refresh) never deadlock the single lane. */
    private <T> T onWorker(int stackId, @NonNull Callable<T> body) throws IOException {
        if (Integer.valueOf(stackId).equals(CURRENT_STACK.get())) {
            try {
                return body.call();
            } catch (IOException | RuntimeException | Error direct) {
                throw direct;
            } catch (Exception other) {
                throw new IOException(String.valueOf(other), other);
            }
        }
        Accountability caller = Accountability.current();
        try {
            return workerFor(stackId).submit(() -> {
                CURRENT_STACK.set(stackId);
                try {
                    return asCaller(caller, body);
                } finally {
                    CURRENT_STACK.remove();
                }
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while operating on stack " + stackId, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException(String.valueOf(cause), cause);
        }
    }

    /**
     * How many containers currently carry the stack's ownership label, decided on
     * its worker (the rename gate must not interleave with a running operation).
     *
     * @throws IOException when the stack is unknown or Docker cannot answer
     */
    public int ownedContainerCount(int stackId) throws IOException {
        return onWorker(stackId, () -> {
            StackSpec spec = scoped(() -> teardownSpec(stackId));
            if (spec == null) {
                throw new IOException("Stack " + stackId + " does not exist");
            }
            return liveWorkloadCount(spec);
        });
    }

    /** Live per-service states, best-effort; empty when the stack is unknown. */
    public @NonNull Map<String, String> serviceStates(int stackId) {
        try {
            return onWorker(stackId, () -> {
                // Unordered: an admin page must still show live state for a stack whose
                // dependency graph is currently invalid.
                StackSpec spec = scoped(() -> teardownSpec(stackId));
                if (spec == null) {
                    return Map.of();
                }
                try {
                    return serviceStates(spec);
                } catch (Exception e) {
                    return Map.of();
                }
            });
        } catch (IOException e) {
            return Map.of();
        }
    }

    /**
     * Recompute and persist the stack's aggregate status from live container states,
     * alerting on a transition into degraded/failed. Runs ON the stack's worker: a
     * monitor tick that read "active" just before a stop or deploy must never stomp
     * the operation's own status with a stale aggregate mid-mutation.
     *
     * @return the persisted status
     */
    public @NonNull String refreshStatus(int stackId) {
        try {
            return onWorker(stackId, () -> refreshStatusOnWorker(stackId));
        } catch (IOException e) {
            Blast.log("STACK: status refresh failed for stack", stackId, "-", e.getMessage());
            return StackModel.STATUS_INACTIVE;
        }
    }

    private @NonNull String refreshStatusOnWorker(int stackId) {
        Row stack = scoped(() -> Models.get(StackModel.class).findById(stackId));
        if (stack == null) {
            return StackModel.STATUS_INACTIVE;
        }
        String previous = stack.get(StackModel.STATUS);
        if (StackModel.STATUS_DEPLOYING.equals(previous)) {
            return previous;   // a running deploy owns the status until it finishes
        }

        StackSpec spec = scoped(() -> StackSpec.fromRecordsUnordered(stack));
        Map<String, String> states;
        try {
            // Even a spec with ZERO services runs the live status: the label sweep can
            // still surface orphaned containers of deleted services, and reading such a
            // stack as "inactive" would unlock renaming and orphan them permanently.
            states = serviceStates(spec);
        } catch (Exception e) {
            Blast.log("STACK: status refresh failed for stack", stackId, "-", e.getMessage());
            return previous != null ? previous : StackModel.STATUS_INACTIVE;
        }
        String next = states.isEmpty() ? StackModel.STATUS_INACTIVE : aggregate(states, previous);

        if (!next.equals(previous)) {
            setStatus(stackId, next);
            boolean turnedBad = (StackModel.STATUS_DEGRADED.equals(next) || StackModel.STATUS_FAILED.equals(next))
                && StackModel.STATUS_ACTIVE.equals(previous);
            if (turnedBad) {
                // The states that TRIGGERED the transition, not a fresh Docker round-trip
                // that may already show something else.
                Alerts.send(NotificationEvents.STACK_HEALTH,
                    "Stack '" + spec.name() + "' is " + next,
                    "Service states: " + states);
            }
        }
        return next;
    }

    /** Refresh every enabled stack's status (the scheduled monitor's body). */
    public void refreshAllStatuses() {
        List<Row> stacks = scoped(() -> Models.get(StackModel.class)
            .find().where(StackModel.ENABLED.eq(true)).all());
        for (Row stack : stacks) {
            refreshStatus(stack.get(StackModel.ID));
        }
    }

    // -- volume purge ---------------------------------------------------------

    /**
     * Tear the stack down and destroy every volume it owns: the operator's way to
     * reclaim the disk a stack's DATA occupies. The containers must GO, not merely
     * stop -- Docker refuses to remove a volume attached to any container, stopped
     * ones included, so a stop-only purge would fail on every mounted volume. The
     * next deploy recreates everything from the records, minus the data.
     *
     * External (adopted) volumes never carry our ownership label and survive.
     *
     * @throws IOException when the stack does not exist, or Docker refuses
     */
    public void purgeVolumes(int stackId) throws IOException {
        onWorker(stackId, () -> {
            StackSpec spec = scoped(() -> teardownSpec(stackId));
            if (spec == null) {
                throw new IOException("Stack " + stackId + " does not exist");
            }
            destroyServices(spec, true);
            setStatus(stackId, StackModel.STATUS_INACTIVE);
            recordSettled(stackId, ACTIVITY_PURGE_ACTION, spec.name());
            return null;
        });
    }

    /** Queue a stop-and-purge of the stack's owned volumes. */
    public void purgeVolumesAsync(int stackId) {
        submitAsync(stackId, () -> {
            try {
                purgeVolumes(stackId);
            } catch (Exception e) {
                Blast.log("STACK: volume purge failed for stack", stackId, "-", e.getMessage());
                refreshStatus(stackId);
            }
        });
    }

    // -- disk reclaim ---------------------------------------------------------

    /**
     * Reclaim superseded and dangling images on every daemon that hosts a stack.
     * {@link DockerReclaim} owns the rules; this owns the WIRING -- which image
     * references count as declared, which is every image any stack service names on
     * that server, enabled or not (a disabled stack's image must stay pinned so
     * re-enabling it does not have to re-pull).
     *
     * Deliberately NOT on a stack worker: image storage is per DAEMON, not per
     * stack, so there is no stack whose queue this belongs in. Deploys are kept
     * safe by the in-flight check instead: a server with a DEPLOYING stack is
     * skipped, and the sweep re-checks before every removal (a deploy claims its
     * status before it pulls, so a mid-pull image can never be swept).
     *
     * @param minimumAge images younger than this are kept
     * @param includeUnattributed also remove images carrying no reference at all
     * @return per-server outcomes, keyed by server name; a server whose daemon
     *         cannot be reached is logged and absent rather than failing the sweep
     */
    public @NonNull Map<String, DockerReclaim.Outcome> reclaimImages(@NonNull Duration minimumAge,
                                                                    boolean includeUnattributed) {
        Map<String, Set<String>> referencesByServer = scoped(this::declaredImagesByServer);
        Map<String, DockerReclaim.Outcome> outcomes = new LinkedHashMap<>();
        Instant now = Instant.now();

        for (Map.Entry<String, Set<String>> entry : referencesByServer.entrySet()) {
            String serverName = entry.getKey();
            if (deployInFlightOn(serverName)) {
                Blast.log("STACK: image reclaim skipped on server", serverName, "- deploy in flight");
                continue;
            }
            try {
                DockerReclaim reclaim = new DockerReclaim(
                    this.clientFor.apply(serverName), minimumAge, includeUnattributed,
                    () -> deployInFlightOn(serverName));
                outcomes.put(serverName, reclaim.reclaimImages(entry.getValue(), now));
            } catch (IOException e) {
                Blast.log("STACK: image reclaim failed on server", serverName, "-", e.getMessage());
            }
        }
        return outcomes;
    }

    /** Whether any stack on this server is currently deploying (or rolling back). */
    private boolean deployInFlightOn(@NonNull String serverName) {
        return scoped(() -> !Models.get(StackModel.class).find()
            .where(StackModel.SERVER_ID.eq(ServerModel.canonicalServerId(serverName)))
            .where(StackModel.STATUS.eq(StackModel.STATUS_DEPLOYING))
            .all().isEmpty());
    }

    /** Every image reference declared by a stack service, grouped by the stack's server. */
    private @NonNull Map<String, Set<String>> declaredImagesByServer() {
        Map<String, Set<String>> byServer = new LinkedHashMap<>();
        for (Row stack : Models.get(StackModel.class).find().all()) {
            // AIDEV-NOTE: the pre-FK version keyed on the raw server_name STRING and
            // SKIPPED blank/null ones, so a "local by omission" stack's image references
            // never entered the keep set beside an explicit "local" stack's -- the
            // two-spellings bug class. The canonical FK folds them into one host.
            String serverName = ServerModel.nameOf(stack.get(StackModel.SERVER_ID));
            Set<String> references = byServer.computeIfAbsent(serverName, key -> new LinkedHashSet<>());
            for (Row service : Models.get(StackServiceModel.class)
                    .findByStackId(stack.get(StackModel.ID))) {
                String image = service.get(StackServiceModel.IMAGE);
                if (image != null && !image.isBlank()) {
                    references.add(image.trim());
                }
            }
        }
        return byServer;
    }

    /**
     * Every service good = active; none running = failed (or stays stopped after an
     * explicit stop); a mix = degraded. "starting" counts as good to avoid flapping
     * while a healthcheck warms up. "orphaned" entries (owned containers whose service
     * left the records) are never good: records and reality disagree, and the operator
     * must see that as degraded, not active.
     */
    private static @NonNull String aggregate(@NonNull Map<String, String> states, @Nullable String previous) {
        int good = 0;
        for (String state : states.values()) {
            if ("healthy".equals(state) || "running".equals(state) || "starting".equals(state)) {
                good++;
            }
        }
        if (good == states.size()) {
            return StackModel.STATUS_ACTIVE;
        }
        if (good == 0) {
            return StackModel.STATUS_STOPPED.equals(previous)
                ? StackModel.STATUS_STOPPED : StackModel.STATUS_FAILED;
        }
        return StackModel.STATUS_DEGRADED;
    }

    // -- internals -------------------------------------------------------------

    /**
     * Resolve records (or use the given snapshot), deploy, persist history and the
     * final status.
     *
     * @return the failure, or null on success (async callers log it, sync rethrow)
     */
    private @Nullable IOException runDeploy(int stackId, String reason, @Nullable StackSpec snapshot) {
        // Claim the status HERE, not only at queue time: a deploy queued behind another
        // one must re-claim it when it actually starts, or the monitor sees the first
        // deploy's "active" while containers are mid-replace and fires a false alert.
        setStatus(stackId, StackModel.STATUS_DEPLOYING);
        StringBuilder log = new StringBuilder();
        Integer recordId = scoped(() -> StackDeploymentRecords.started(stackId, reason));
        try {
            StackSpec spec = snapshot != null ? snapshot : scoped(() -> currentSpec(stackId));
            if (spec == null) {
                throw new IOException("Stack " + stackId + " does not exist");
            }

            runDeployOnWorker(spec, line -> log.append(line).append('\n'));

            String specSnapshot = Zenit.DRY.stringify(spec.toMap());
            scoped(() -> {
                StackDeploymentRecords.finished(recordId, true, null, log.toString(), specSnapshot);
                return null;
            });
            setStatus(stackId, StackModel.STATUS_ACTIVE);
            // The SETTLED operation, never the queued intent: a deploy that failed is
            // answered by its failed status and its deployment record, not by an
            // activity row claiming it happened (the instance tier's rule).
            recordSettled(stackId, REASON_ROLLBACK.equals(reason)
                ? ACTIVITY_ROLLBACK_ACTION : ACTIVITY_DEPLOY_ACTION, reason);
            return null;
        } catch (Throwable e) {
            // Throwable, not Exception: an Error thrown on the worker would otherwise
            // vanish into the executor's unread future and leave the status stuck.
            String failure = e.getMessage() != null ? e.getMessage() : e.toString();
            log.append("FAILED: ").append(failure).append('\n');
            scoped(() -> {
                StackDeploymentRecords.finished(recordId, false, failure, log.toString(), null);
                return null;
            });
            setStatus(stackId, StackModel.STATUS_FAILED);
            Blast.log("STACK: deploy failed for stack", stackId, "-", failure);
            if (e instanceof Error error) {
                throw error;
            }
            return e instanceof IOException io ? io : new IOException(failure, e);
        }
    }


    // -- the orchestration half (what did NOT lower) ---------------------------

    /**
     * How long a dependency may take to reach its declared condition. Compose semantics,
     * kept in the PRODUCT tier: an instance answers "is this workload running/healthy",
     * while "may this workload start yet" is a statement about OTHER records.
     */
    private static final long CONDITION_POLL_MS = 500;
    private static final long STARTED_WAIT_CAP_MS = 30_000;
    private static final long HEALTHY_WAIT_BASE_MS = 120_000;

    /**
     * Deploy every enabled service as an owned instance, in dependency order, gating each
     * on its declared conditions -- and destroy the workloads of services that left the
     * records first, so a renamed service's old container cannot hold the host port the
     * new one is about to claim.
     */
    private void runDeployOnWorker(@NonNull StackSpec spec, @NonNull Consumer<String> log)
            throws IOException {
        log.accept("Deploying stack '" + spec.name() + "' (" + spec.services().size()
            + " services)");
        scoped(() -> {
            pruneOrphanedWorkloads(spec, log);
            return null;
        });

        for (StackSpec.ServiceSpec service : spec.services()) {
            awaitDependencies(spec, service, log);
            log.accept("Deploying service '" + service.name() + "' (" + service.image() + ")");
            IOException failure = scopedThrowing(() -> StackInstances.deploy(spec, service));
            if (failure != null) {
                throw failure;
            }
            log.accept("Started " + service.name());
        }

        // Only once every service owns an instance on its own network is the pre-lowering
        // shared network certainly unused; retiring it earlier would cut a still-running
        // legacy container off from its siblings.
        scoped(() -> {
            StackInstances.retireLegacyNetwork(spec);
            return null;
        });
        log.accept("Stack '" + spec.name() + "' deployed");
    }

    /**
     * Destroy the workloads of services this stack no longer declares: disabled ones, and
     * -- by ATTRIBUTION, across every stack -- ones whose record was deleted, which is the
     * only evidence left once the row is gone.
     */
    private void pruneOrphanedWorkloads(@NonNull StackSpec spec, @NonNull Consumer<String> log) {
        Set<Integer> wanted = new LinkedHashSet<>();
        for (StackSpec.ServiceSpec service : spec.services()) {
            wanted.add(service.serviceId());
        }
        for (Row service : Models.get(StackServiceModel.class).findByStackId(spec.stackId())) {
            Integer serviceId = service.get(StackServiceModel.ID);
            if (serviceId == null || wanted.contains(serviceId)
                    || StackInstances.owned(serviceId) == null) {
                continue;
            }
            log.accept("Removing the workload of disabled service '"
                + service.get(StackServiceModel.NAME) + "'");
            try {
                StackInstances.destroyFor(serviceId);
            } catch (IOException e) {
                log.accept("Could not remove it: " + e.getMessage());
            }
        }
        for (Row instance : Models.get(InstanceModel.class).find()
                .where(InstanceModel.GENERATED_FOR_MODEL.eq(StackServiceModel.MODEL_ID.toString()))
                .where(InstanceModel.DELETED_AT.isNull()).all()) {
            Integer serviceId = instance.get(InstanceModel.GENERATED_FOR_ID);
            if (serviceId == null
                    || Models.get(StackServiceModel.class).findById(serviceId) != null) {
                continue;
            }
            log.accept("Removing the workload of deleted service record " + serviceId);
            try {
                StackInstances.destroyFor(serviceId);
            } catch (IOException e) {
                log.accept("Could not remove it: " + e.getMessage());
            }
        }
    }

    /**
     * Block until every declared dependency of {@code service} reaches its condition.
     *
     * @throws IOException naming the dependency when it turns unhealthy or times out --
     *         a service whose dependency never came up must not start pretending it did
     */
    private void awaitDependencies(@NonNull StackSpec spec, StackSpec.@NonNull ServiceSpec service,
                                   @NonNull Consumer<String> log) throws IOException {
        for (StackSpec.DependsSpec dependency : service.dependsOn()) {
            StackSpec.ServiceSpec target = null;
            for (StackSpec.ServiceSpec candidate : spec.services()) {
                if (candidate.name().equals(dependency.service())) {
                    target = candidate;
                    break;
                }
            }
            if (target == null) {
                throw new IOException("Service '" + service.name() + "' depends on unknown"
                    + " or disabled service '" + dependency.service() + "'");
            }
            boolean needHealthy = StackServiceModel.CONDITION_HEALTHY.equals(dependency.condition());
            if (needHealthy && target.healthCmd() == null) {
                // A dependency gated on health whose target declares no check would wait
                // out its whole deadline and then fail: say so immediately, by name.
                throw new IOException("Service '" + service.name() + "' waits for '"
                    + dependency.service() + "' to be healthy, but that service declares"
                    + " no health check");
            }
            long deadline = System.currentTimeMillis() + (needHealthy
                ? HEALTHY_WAIT_BASE_MS + target.healthStartPeriodSeconds() * 1000L
                : STARTED_WAIT_CAP_MS);
            log.accept("Waiting for '" + dependency.service() + "' to be "
                + (needHealthy ? "healthy" : "running"));

            int targetId = target.serviceId();
            while (true) {
                InstanceStatus status = scoped(() -> StackInstances.liveStatus(targetId));
                if (needHealthy
                    ? status.health() == InstanceStatus.HealthState.HEALTHY
                    : status.running()) {
                    break;
                }
                if (needHealthy && status.health() == InstanceStatus.HealthState.UNHEALTHY) {
                    throw new IOException("Dependency '" + dependency.service()
                        + "' became unhealthy");
                }
                if (System.currentTimeMillis() > deadline) {
                    throw new IOException("Timed out waiting for dependency '"
                        + dependency.service() + "' to become "
                        + (needHealthy ? "healthy" : "running") + " (state: "
                        + stateToken(status) + ")");
                }
                try {
                    Thread.sleep(CONDITION_POLL_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for dependency '"
                        + dependency.service() + "'");
                }
            }
        }
    }

    /** Stop every service's workload, dependents first (reverse dependency order). */
    private void stopServices(@NonNull StackSpec spec) throws IOException {
        List<StackSpec.ServiceSpec> reversed = new ArrayList<>(spec.services());
        java.util.Collections.reverse(reversed);
        for (StackSpec.ServiceSpec service : reversed) {
            IOException failure = scopedThrowing(() -> {
                StackInstances.stop(service.serviceId());
                return null;
            });
            if (failure != null) {
                throw failure;
            }
        }
    }

    /**
     * Verified teardown of the whole stack: every owned workload (services still declared
     * AND ones whose row was disabled since), then the shared network, then -- only when
     * asked -- the volumes the stack's name scopes.
     */
    private void destroyServices(@NonNull StackSpec spec, boolean removeVolumes)
            throws IOException {
        List<StackSpec.ServiceSpec> reversed = new ArrayList<>(spec.services());
        java.util.Collections.reverse(reversed);
        Set<Integer> destroyed = new LinkedHashSet<>();
        for (StackSpec.ServiceSpec service : reversed) {
            IOException failure = scopedThrowing(() -> {
                StackInstances.destroyFor(service.serviceId());
                return null;
            });
            if (failure != null) {
                throw failure;
            }
            destroyed.add(service.serviceId());
        }
        // Services disabled or removed since the last deploy still own a workload; "destroy
        // the stack" must mean the whole stack, not just what the current spec declares.
        for (Map.Entry<Integer, Row> owned : scoped(() ->
                StackInstances.ownedByStack(spec.stackId())).entrySet()) {
            if (destroyed.contains(owned.getKey())) {
                continue;
            }
            IOException failure = scopedThrowing(() -> {
                StackInstances.destroyFor(owned.getKey());
                return null;
            });
            if (failure != null) {
                throw failure;
            }
        }
        StackInstances.removeNetwork(spec.serverName(), spec.name());
        if (removeVolumes) {
            StackInstances.removeOwnedVolumes(spec.serverName(), spec.name());
        }
    }

    /** How many of the stack's owned workloads the daemon reports as present. */
    private int liveWorkloadCount(@NonNull StackSpec spec) {
        int live = 0;
        for (Integer serviceId : scoped(() ->
                StackInstances.ownedByStack(spec.stackId())).keySet()) {
            ContainerState state = scoped(() -> StackInstances.liveStatus(serviceId)).state();
            if (state != ContainerState.ABSENT) {
                live++;
            }
        }
        return live;
    }

    /**
     * Live per-service state, best-effort. Services whose record was disabled but whose
     * workload still exists are reported too, keyed by service id with state "orphaned":
     * records and reality disagree, and the operator must see that as degraded, not
     * active (which would also unlock renaming and orphan the workload for good).
     */
    private @NonNull Map<String, String> serviceStates(@NonNull StackSpec spec) {
        Map<String, String> states = new LinkedHashMap<>();
        Set<Integer> declared = new LinkedHashSet<>();
        for (StackSpec.ServiceSpec service : spec.services()) {
            declared.add(service.serviceId());
            states.put(service.name(),
                stateToken(scoped(() -> StackInstances.liveStatus(service.serviceId()))));
        }
        for (Map.Entry<Integer, Row> owned : scoped(() ->
                StackInstances.ownedByStack(spec.stackId())).entrySet()) {
            if (!declared.contains(owned.getKey())) {
                states.put(String.valueOf(owned.getValue().get(InstanceModel.NAME)), "orphaned");
            }
        }
        return states;
    }

    /** The UI's state vocabulary, from the typed status the driver reports. */
    private static @NonNull String stateToken(@NonNull InstanceStatus status) {
        return switch (status.state()) {
            case ABSENT -> "missing";
            case UNREACHABLE -> "unreachable";
            case STOPPED -> "stopped";
            case RUNNING -> switch (status.health()) {
                case HEALTHY -> "healthy";
                case UNHEALTHY -> "unhealthy";
                case STARTING -> "starting";
                case NONE -> "running";
            };
        };
    }

    private @Nullable StackSpec currentSpec(int stackId) {
        Row stack = Models.get(StackModel.class).findById(stackId);
        return stack != null ? StackSpec.fromRecords(stack) : null;
    }

    /** Spec for stop/destroy: resolved without dependency ordering, so a broken graph
     *  can still be torn down. */
    private @Nullable StackSpec teardownSpec(int stackId) {
        Row stack = Models.get(StackModel.class).findById(stackId);
        return stack != null ? StackSpec.fromRecordsUnordered(stack) : null;
    }

    @SuppressWarnings("unchecked")
    private @Nullable StackSpec latestSnapshot(int stackId) {
        Row deployment = Models.get(StackDeploymentModel.class).findLatestSuccessful(stackId);
        if (deployment == null) {
            return null;
        }
        Object parsed = Zenit.DRY.parse(deployment.get(StackDeploymentModel.SPEC));
        if (!(parsed instanceof Map<?, ?> map)) {
            return null;
        }
        return StackSpec.fromMap((Map<String, Object>) map);
    }

    private void setStatus(int stackId, String status) {
        // ONE-column atomic write: a load-mutate-save here writes the whole stale row
        // back and silently reverts an admin's concurrent form save.
        scoped(() -> {
            Models.get(StackModel.class).find()
                .where(StackModel.ID.eq(stackId))
                .assign(StackModel.STATUS, status)
                .updateAll();
            return null;
        });
    }

    /** Queue fire-and-forget work on the stack's lane, stamped for re-entrant onWorker calls. */
    private void submitAsync(int stackId, @NonNull Runnable body) {
        Accountability caller = Accountability.current();
        workerFor(stackId).submit(() -> {
            CURRENT_STACK.set(stackId);
            try {
                Accountability.runAs(caller, body);
            } finally {
                CURRENT_STACK.remove();
            }
        });
    }

    /**
     * Run worker work under the DISPATCHING thread's attribution.
     *
     * AIDEV-NOTE: the snapshot is taken on the caller's thread and re-entered here
     * because {@code Accountability} resolves through a ThreadLocal and a request-scoped
     * resolver, neither of which exists on a stack worker. Without it every stack
     * activity row -- and every deployment-history row the worker saves through the
     * model hooks -- is unattributed {@code system} work, which is exactly what an
     * accountability-shaped no-op looks like.
     */
    private static <T> T asCaller(@NonNull Accountability caller,
                                  @NonNull Callable<T> body) throws Exception {
        Object[] result = new Object[1];
        Exception[] failure = new Exception[1];
        Accountability.runAs(caller, () -> {
            try {
                result[0] = body.call();
            } catch (Exception thrown) {
                failure[0] = thrown;
            }
        });
        if (failure[0] != null) {
            throw failure[0];
        }
        @SuppressWarnings("unchecked")
        T typed = (T) result[0];
        return typed;
    }

    /** Record a SETTLED stack operation on the stack record, on the worker's datasource. */
    private void recordSettled(int stackId, @NonNull String action, @Nullable String detail) {
        scoped(() -> {
            ActivityLog.record(Models.get(StackModel.class), stackId, action, detail);
            return null;
        });
    }

    private ExecutorService workerFor(int stackId) {
        // Virtual threads: lanes are never retired (see destroy), so a parked lane per
        // ever-touched stack id must cost next to nothing.
        return workers.computeIfAbsent(stackId, id -> Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("stack-" + id).factory()));
    }

    /** A scoped body that may fail the way the daemon work it wraps fails. */
    @FunctionalInterface
    private interface ThrowingWork<T> {
        T run() throws IOException;
    }

    /**
     * Run daemon work inside the datasource scope and hand back its failure instead of
     * throwing through {@link #scoped}, whose Db.run body cannot carry a checked exception.
     */
    private <T> @Nullable IOException scopedThrowing(@NonNull ThrowingWork<T> body) {
        return scoped(() -> {
            try {
                body.run();
                return null;
            } catch (IOException failure) {
                return failure;
            }
        });
    }

    /** Run model access on the injected datasource when one is set (tests). */
    private <T> T scoped(@NonNull Supplier<T> body) {
        if (this.datasource == null) {
            return body.get();
        }
        Object[] result = new Object[1];
        Db.run(this.datasource, () -> result[0] = body.get());
        @SuppressWarnings("unchecked")
        T typed = (T) result[0];
        return typed;
    }
}
