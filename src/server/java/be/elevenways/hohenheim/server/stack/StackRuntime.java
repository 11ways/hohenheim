package be.elevenways.hohenheim.server.stack;

import be.elevenways.hohenheim.model.StackDeploymentModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.DockerReclaim;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
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
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Lifecycle entry point for managed stacks: explicit deploy/stop/rollback (never
 * save-triggered), one worker per stack so operations on the same stack serialize
 * while different stacks proceed in parallel, and status refresh with alerting on
 * health transitions.
 */
public class StackRuntime {

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
            runDeploy(stackId, "rollback", snapshot);
        });
    }

    /** Queue a stop of every stack container (reverse dependency order). */
    public void stopAsync(int stackId) {
        submitAsync(stackId, () -> {
            try {
                StackSpec spec = scoped(() -> teardownSpec(stackId));
                if (spec == null) {
                    return;
                }
                new StackDeployer(clientFor.apply(spec.serverName()), null).stop(spec);
                setStatus(stackId, StackModel.STATUS_STOPPED);
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
        IOException failure = onWorker(stackId, () -> runDeploy(stackId, "rollback", snapshot));
        if (failure != null) {
            throw failure;
        }
    }

    /** Stop synchronously; throws on failure. */
    public void stop(int stackId) throws IOException {
        onWorker(stackId, () -> {
            StackSpec spec = scoped(() -> teardownSpec(stackId));
            if (spec == null) {
                throw new IOException("Stack " + stackId + " does not exist");
            }
            new StackDeployer(clientFor.apply(spec.serverName()), null).stop(spec);
            setStatus(stackId, StackModel.STATUS_STOPPED);
            return null;
        });
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
            new StackDeployer(clientFor.apply(spec.serverName()), null).destroy(spec, removeVolumes);
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
        try {
            return workerFor(stackId).submit(() -> {
                CURRENT_STACK.set(stackId);
                try {
                    return body.call();
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
            return new StackDeployer(clientFor.apply(spec.serverName()), null).countOwnedContainers(spec);
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
                    return new StackDeployer(clientFor.apply(spec.serverName()), null).status(spec);
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
            states = new StackDeployer(clientFor.apply(spec.serverName()), null).status(spec);
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
                Alerts.send("stack_health", "Stack '" + spec.name() + "' is " + next,
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
            StackDeployer deployer = new StackDeployer(clientFor.apply(spec.serverName()), null);
            deployer.destroy(spec, true);
            setStatus(stackId, StackModel.STATUS_INACTIVE);
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
            .where(StackModel.SERVER_NAME.eq(serverName))
            .where(StackModel.STATUS.eq(StackModel.STATUS_DEPLOYING))
            .all().isEmpty());
    }

    /** Every image reference declared by a stack service, grouped by the stack's server. */
    private @NonNull Map<String, Set<String>> declaredImagesByServer() {
        Map<String, Set<String>> byServer = new LinkedHashMap<>();
        for (Row stack : Models.get(StackModel.class).find().all()) {
            String serverName = stack.get(StackModel.SERVER_NAME);
            if (serverName == null || serverName.isBlank()) {
                continue;
            }
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

            StackDeployer deployer = new StackDeployer(clientFor.apply(spec.serverName()),
                line -> log.append(line).append('\n'));
            deployer.deploy(spec);

            String specSnapshot = Zenit.DRY.stringify(spec.toMap());
            scoped(() -> {
                StackDeploymentRecords.finished(recordId, true, null, log.toString(), specSnapshot);
                return null;
            });
            setStatus(stackId, StackModel.STATUS_ACTIVE);
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
        workerFor(stackId).submit(() -> {
            CURRENT_STACK.set(stackId);
            try {
                body.run();
            } finally {
                CURRENT_STACK.remove();
            }
        });
    }

    private ExecutorService workerFor(int stackId) {
        // Virtual threads: lanes are never retired (see destroy), so a parked lane per
        // ever-touched stack id must cost next to nothing.
        return workers.computeIfAbsent(stackId, id -> Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("stack-" + id).factory()));
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
