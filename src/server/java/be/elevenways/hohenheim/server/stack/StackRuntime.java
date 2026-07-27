package be.elevenways.hohenheim.server.stack;

import be.elevenways.hohenheim.model.StackDeploymentModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.server.docker.DockerClient;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
        workerFor(stackId).submit(() -> runDeploy(stackId, reason, null));
    }

    /** Queue a re-deploy of the newest successful deployment's spec snapshot. */
    public void rollbackAsync(int stackId) {
        setStatus(stackId, StackModel.STATUS_DEPLOYING);
        workerFor(stackId).submit(() -> {
            StackSpec snapshot = scoped(() -> latestSnapshot(stackId));
            if (snapshot == null) {
                Blast.log("STACK: no successful deployment to roll back to for stack", stackId);
                setStatus(stackId, StackModel.STATUS_FAILED);
                return;
            }
            runDeploy(stackId, "rollback", snapshot);
        });
    }

    /** Queue a stop of every stack container (reverse dependency order). */
    public void stopAsync(int stackId) {
        workerFor(stackId).submit(() -> {
            try {
                StackSpec spec = scoped(() -> currentSpec(stackId));
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

    /** Deploy synchronously (tests, scripted use); throws on failure. */
    public void deploy(int stackId, @NonNull String reason) throws IOException {
        IOException failure = runDeploy(stackId, reason, null);
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
        IOException failure = runDeploy(stackId, "rollback", snapshot);
        if (failure != null) {
            throw failure;
        }
    }

    /** Stop synchronously; throws on failure. */
    public void stop(int stackId) throws IOException {
        StackSpec spec = scoped(() -> currentSpec(stackId));
        if (spec == null) {
            throw new IOException("Stack " + stackId + " does not exist");
        }
        new StackDeployer(clientFor.apply(spec.serverName()), null).stop(spec);
        setStatus(stackId, StackModel.STATUS_STOPPED);
    }

    /**
     * Remove every owned container and the network; owned volumes only when
     * {@code removeVolumes}. Used by the destroy action and record deletion.
     */
    public void destroy(int stackId, boolean removeVolumes) throws IOException {
        StackSpec spec = scoped(() -> currentSpec(stackId));
        if (spec == null) {
            return;
        }
        new StackDeployer(clientFor.apply(spec.serverName()), null).destroy(spec, removeVolumes);
        setStatus(stackId, StackModel.STATUS_INACTIVE);
    }

    /** Live per-service states, best-effort; empty when the stack is unknown. */
    public @NonNull Map<String, String> serviceStates(int stackId) {
        StackSpec spec = scoped(() -> currentSpec(stackId));
        if (spec == null) {
            return Map.of();
        }
        try {
            return new StackDeployer(clientFor.apply(spec.serverName()), null).status(spec);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * Recompute and persist the stack's aggregate status from live container states,
     * alerting on a transition into degraded/failed.
     *
     * @return the persisted status
     */
    public @NonNull String refreshStatus(int stackId) {
        Row stack = scoped(() -> Models.get(StackModel.class).findById(stackId));
        if (stack == null) {
            return StackModel.STATUS_INACTIVE;
        }
        String previous = stack.get(StackModel.STATUS);
        if (StackModel.STATUS_DEPLOYING.equals(previous)) {
            return previous;   // a running deploy owns the status until it finishes
        }

        StackSpec spec = scoped(() -> StackSpec.fromRecords(stack));
        String next;
        if (spec.services().isEmpty()) {
            next = StackModel.STATUS_INACTIVE;
        } else {
            Map<String, String> states;
            try {
                states = new StackDeployer(clientFor.apply(spec.serverName()), null).status(spec);
            } catch (Exception e) {
                Blast.log("STACK: status refresh failed for stack", stackId, "-", e.getMessage());
                return previous != null ? previous : StackModel.STATUS_INACTIVE;
            }
            next = aggregate(states, previous);
        }

        if (!next.equals(previous)) {
            setStatus(stackId, next);
            boolean turnedBad = (StackModel.STATUS_DEGRADED.equals(next) || StackModel.STATUS_FAILED.equals(next))
                && StackModel.STATUS_ACTIVE.equals(previous);
            if (turnedBad) {
                Alerts.send("stack_health", "Stack '" + spec.name() + "' is " + next,
                    "Service states: " + serviceStates(stackId));
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

    /**
     * Every service good = active; none running = failed (or stays stopped after an
     * explicit stop); a mix = degraded. "starting" counts as good to avoid flapping
     * while a healthcheck warms up.
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
        } catch (Exception e) {
            log.append("FAILED: ").append(e.getMessage()).append('\n');
            scoped(() -> {
                StackDeploymentRecords.finished(recordId, false, e.getMessage(), log.toString(), null);
                return null;
            });
            setStatus(stackId, StackModel.STATUS_FAILED);
            Blast.log("STACK: deploy failed for stack", stackId, "-", e.getMessage());
            return e instanceof IOException io ? io : new IOException(e.getMessage(), e);
        }
    }

    private @Nullable StackSpec currentSpec(int stackId) {
        Row stack = Models.get(StackModel.class).findById(stackId);
        return stack != null ? StackSpec.fromRecords(stack) : null;
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
        scoped(() -> {
            StackModel model = Models.get(StackModel.class);
            Row row = model.findById(stackId);
            if (row != null) {
                row.set(StackModel.STATUS, status);
                model.save(row);
            }
            return null;
        });
    }

    private ExecutorService workerFor(int stackId) {
        return workers.computeIfAbsent(stackId, id -> Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "stack-" + id);
            thread.setDaemon(true);
            return thread;
        }));
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
