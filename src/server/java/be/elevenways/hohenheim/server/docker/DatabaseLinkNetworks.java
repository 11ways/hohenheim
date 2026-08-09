package be.elevenways.hohenheim.server.docker;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
import be.elevenways.hohenheim.server.runtime.DockerInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.server.runtime.WorkloadNetworks;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntPredicate;

/**
 * What the two database link-network lanes share: {@link SiteDatabaseNetworks} joins a
 * site's release container to its databases, {@link InstanceDatabaseNetworks} joins an
 * instance's own container to its databases, and the DAEMON-side mechanics are identical
 * either way -- naming, the stale-network sweep, and the published-port correction a
 * membership change forces.
 *
 * AIDEV-NOTE: this exists because the instance lane arrived second (2026-08-08). Copying
 * the port correction would have been the worse bug: it is subtle (connecting a RUNNING
 * container re-allocates its ephemeral published host port, observed live) and it must
 * rewrite the SAME ledger row the deploy wrote, so two copies drifting apart would leave
 * one engine holding two claims on one port under two owners.
 */
final class DatabaseLinkNetworks {

    private DatabaseLinkNetworks() {
    }

    /** Reads live container status for one handle; both lanes have their own source. */
    interface StatusOf {
        @NonNull InstanceStatus status(@NonNull String handle);
    }

    /**
     * The CONSUMER side of a sweep whose own container is still RUNNING, so its own
     * published port moved with the disconnect exactly as the database's did.
     *
     * AIDEV-NOTE: the site lane passes null and must keep doing so -- it only ever sweeps
     * once its release container is stopped or replaced, so there is no live port to
     * correct. The instance lane sweeps on DETACH, against a container that keeps running,
     * which is the whole reason this parameter exists.
     */
    record Consumer(int instanceId, @NonNull String handle) {}

    /**
     * Remove every link network under {@code handlePrefix} on one server whose owner
     * relationship no longer exists, and correct the published port of every database
     * whose membership the removal changed.
     *
     * @param stillLive answers, for the database id parsed out of a handle, whether the
     *        owning relationship survives; ignored when {@code everything} is true
     * @param consumer the still-running owner whose own published port the disconnect
     *        moved, or null when its container is already stopped or replaced
     * @throws IOException when the daemon cannot be reached or a removal fails
     */
    static void sweepOnServer(@NonNull String handlePrefix, @NonNull String serverName,
                              int serverId, boolean everything,
                              @NonNull IntPredicate stillLive,
                              @Nullable Consumer consumer) throws IOException {
        DockerClient docker = new ServerService().clientFor(serverName);
        DockerInstanceRuntime runtime = new DockerInstanceRuntime(docker,
            WorkloadNetworkPolicy.forServer(serverName));
        boolean removedAny = false;
        for (String network : linkNetworksOf(docker, handlePrefix)) {
            String handle = network.substring(0,
                network.length() - WorkloadNetworks.SUFFIX.length());
            Integer databaseId = trailingInt(handle);
            if (!everything && databaseId != null && stillLive.test(databaseId)) {
                continue;
            }
            runtime.removeLinkNetwork(handle);
            removedAny = true;
            Blast.log("DB-LINK: removed stale link network", network);
            // The disconnect re-allocated the database's published port; re-observe it.
            if (databaseId != null && DatabaseInstances.handleOf(databaseId) instanceof String h) {
                refreshDatabasePort(runtime::status, serverId, databaseId, h);
            }
        }
        if (removedAny && consumer != null) {
            refreshInstancePort(runtime::status, serverId, consumer.instanceId(),
                consumer.handle());
        }
    }

    /** Names of the link networks under one handle prefix, matched on the naming scheme. */
    static @NonNull List<String> linkNetworksOf(@NonNull DockerClient docker,
                                                @NonNull String handlePrefix) throws IOException {
        List<String> names = new ArrayList<>();
        for (Object entry : docker.listNetworks()) {
            if (entry instanceof Map<?, ?> network
                    && network.get("Name") instanceof String name
                    && name.startsWith(handlePrefix) && name.endsWith(WorkloadNetworks.SUFFIX)) {
                names.add(name);
            }
        }
        return names;
    }

    /** The digits after the last dash, or null when the handle is not scheme-shaped. */
    static @Nullable Integer trailingInt(@NonNull String handle) {
        int dash = handle.lastIndexOf('-');
        if (dash < 0 || dash == handle.length() - 1) {
            return null;
        }
        try {
            return Integer.parseInt(handle.substring(dash + 1));
        } catch (NumberFormatException notOurs) {
            return null;
        }
    }

    /**
     * Re-observe a database engine's published loopback port after its network membership
     * changed and refresh the ledger claim when it moved (the GameDomains
     * refreshProxyPort shape). Host processes resolve the port live at spawn, so only
     * the ledger's bookkeeping needs the correction.
     *
     * AIDEV-NOTE: the claim owner is the INSTANCE, not the database record, since the
     * database tier lowered onto the runtime contract -- InstanceService.deploy writes
     * the record-after claim and this correction must rewrite the SAME row, or the
     * engine would end up holding two claims on one port under two different owners.
     */
    static void refreshDatabasePort(@NonNull StatusOf statuses, int serverId, int databaseId,
                                    @NonNull String databaseHandle) {
        Row instance = DatabaseInstances.owned(databaseId);
        if (instance == null) {
            return;
        }
        refreshInstancePort(statuses, serverId, instance.get(InstanceModel.ID), databaseHandle);
    }

    /**
     * {@link #refreshDatabasePort} for any workload: re-observe the published loopback
     * port of one instance's container and refresh its ledger claim when it moved. A
     * container that is not running, or publishes nothing, has nothing to correct.
     */
    static void refreshInstancePort(@NonNull StatusOf statuses, int serverId,
                                    @Nullable Integer instanceId, @NonNull String handle) {
        InstanceStatus status = statuses.status(handle);
        Integer fresh = status.publishedPort();
        if (!status.running() || fresh == null || instanceId == null) {
            return;
        }
        Integer recorded = null;
        for (Row claim : PortLedger.claimsOf(InstanceModel.MODEL_ID, instanceId)) {
            if (!PortLedger.isReleasing(claim)) {
                recorded = claim.get(PortAllocationModel.PORT);
                break;
            }
        }
        if (Objects.equals(recorded, fresh)) {
            return;
        }
        PortLedger.recordObserved(serverId, "127.0.0.1", fresh, "tcp",
            InstanceModel.MODEL_ID, instanceId, null);
    }
}
