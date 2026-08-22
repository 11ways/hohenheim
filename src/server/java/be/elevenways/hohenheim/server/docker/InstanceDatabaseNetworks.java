package be.elevenways.hohenheim.server.docker;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.Egress;
import be.elevenways.hohenheim.server.runtime.LinkNetworkSupport;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The network half of INSTANCE-database attachments: one policied LINK network per
 * (instance, database) pair, joining exactly that instance's container and the database's
 * container. The attachment ROWS are the authority -- a pair without a live
 * {@code instance_databases} row keeps no network, and a link network never carries a
 * third member, so two instances sharing one database still cannot reach each other.
 *
 * AIDEV-NOTE: OWNER and CONSUMER are not always the same record, and phase-0 brief 7 folded
 * the deleted site lane's asymmetry in here rather than keeping a second copy of this class.
 * An ordinary instance owns its links and consumes them itself; an APPLICATION owns them
 * while whichever release is currently serving consumes them. The link network is therefore
 * named after the OWNER (it must outlive every release) and joined by the CONSUMER's
 * container -- naming it after the consumer would mint a fresh, empty network on every
 * deploy, exactly the defect owner-keyed volumes exist to avoid.
 *
 * AIDEV-NOTE: link networks carry {@link Egress#NONE} -- the database's own network was DECLARED egress-NONE and a second interface must
 * not silently widen that. The instance's own egress rides its primary per-workload
 * network, untouched; members still reach each other because the own-subnet accept
 * precedes the egress drop.
 *
 */
public final class InstanceDatabaseNetworks {

    private InstanceDatabaseNetworks() {
    }

    /** Link-network handle of one (link owner, database) pair. */
    static @NonNull String linkHandle(int instanceId, int databaseId) {
        return ControllerScope.handle(ControllerScope.KIND_INSTANCE_DBLINK,
            instanceId + "-" + databaseId);
    }

    /**
     * Per-server link-network handles of every attachment row whose instance and database
     * both live on the same server: the isolation sweep's inventory of instance-database
     * link networks whose kernel chains must exist (a host reboot erases them while the
     * containers restart).
     */
    public static @NonNull Map<Integer, List<String>> liveLinkHandles() {
        Map<Integer, List<String>> byServer = new LinkedHashMap<>();
        DatabaseModel databases = Models.get(DatabaseModel.class);
        InstanceModel instances = Models.get(InstanceModel.class);
        for (Row link : Models.get(InstanceDatabaseModel.class).find().all()) {
            Integer instanceId = link.get(InstanceDatabaseModel.INSTANCE_ID);
            Integer databaseId = link.get(InstanceDatabaseModel.DATABASE_ID);
            if (instanceId == null || databaseId == null) {
                continue;
            }
            Row instance = instances.find()
                .where(InstanceModel.ID.eq(instanceId))
                .where(InstanceModel.DELETED_AT.isNull())
                .first();
            Row database = databases.find().where(DatabaseModel.ID.eq(databaseId)).first();
            if (instance == null || database == null) {
                continue;
            }
            int serverId = ServerModel.canonicalServerId(instance.get(InstanceModel.SERVER_ID));
            if (ServerModel.canonicalServerId(database.get(DatabaseModel.SERVER_ID)) != serverId) {
                continue;   // cross-host pairs are refused at deploy and carry no network
            }
            byServer.computeIfAbsent(serverId, key -> new ArrayList<>())
                .add(linkHandle(instanceId, databaseId));
        }
        return byServer;
    }

    /**
     * Between container CREATE and START: ensure the link network of every attached
     * database (a deploy recreates the container, which drops every non-primary
     * attachment) and join both members.
     *
     * @throws IOException when a link cannot be enforced, when the driver cannot do link
     *         networks at all, when the daemon cannot answer for a database container, or
     *         -- BY NAME -- when an attached database lives on another server than the
     *         instance: a cross-host link network is unsupported, and refusing beats
     *         starting a workload whose injected address cannot resolve
     */
    public static void attachLinksBeforeStart(InstanceService.@NonNull Resolved resolved,
                                              int instanceId) throws IOException {
        // A release's links live on its APPLICATION; every other instance owns its own.
        int ownerId = ApplicationReleases.linkOwnerOf(resolved.row());
        List<Row> links = Models.get(InstanceDatabaseModel.class).findByInstanceId(ownerId);
        if (links.isEmpty()) {
            return;
        }
        if (!(resolved.runtime() instanceof LinkNetworkSupport support)) {
            throw new IOException("Instance " + instanceId + " has attached databases but runs"
                + " on a driver without link networks; detach them or move the workload to a"
                + " Docker host.");
        }
        DatabaseModel databases = Models.get(DatabaseModel.class);
        for (Row link : links) {
            Integer databaseId = link.get(InstanceDatabaseModel.DATABASE_ID);
            Row database = databaseId != null
                ? databases.find().where(DatabaseModel.ID.eq(databaseId)).first() : null;
            if (database == null) {
                skipped(instanceId, databaseId, "record_missing");
                continue;
            }
            String name = database.get(DatabaseModel.NAME);
            int databaseServer = ServerModel.canonicalServerId(
                database.get(DatabaseModel.SERVER_ID));
            if (databaseServer != resolved.serverId()) {
                throw new IOException("REFUSED to deploy instance " + instanceId
                    + ": attached database '" + name + "' runs on server '"
                    + ServerModel.nameOf(databaseServer) + "' while the instance deploys on"
                    + " server '" + ServerModel.nameOf(resolved.serverId())
                    + "'. Cross-host workload-to-database links are not supported; move the"
                    + " instance or the database so both run on the same server.");
            }
            String databaseHandle = DatabaseInstances.handleOf(databaseId);
            String handle = linkHandle(ownerId, databaseId);
            support.ensureLinkNetwork(handle,
                OwnerLabels.of(InstanceDatabaseModel.MODEL_ID, link.get(InstanceDatabaseModel.ID)),
                Egress.NONE);
            support.connectToLinkNetwork(handle, resolved.spec().handle(), List.of());
            if (databaseHandle == null) {
                // No engine instance yet: the network is ensured and the instance side
                // joined, and reattachForDatabase joins the engine when it is provisioned.
                skipped(instanceId, databaseId, "no_engine_instance");
                continue;
            }
            ContainerState state = resolved.runtime().status(databaseHandle).state();
            if (state == ContainerState.UNREACHABLE) {
                throw new IOException("Cannot verify database container '" + databaseHandle
                    + "' while deploying instance " + instanceId + ": the daemon is unreachable");
            }
            if (state == ContainerState.ABSENT) {
                skipped(instanceId, databaseId, "database_container_absent");
                continue;
            }
            support.connectToLinkNetwork(handle, databaseHandle, List.of());
            DatabaseLinkNetworks.refreshDatabasePort(resolved.runtime()::status,
                resolved.serverId(), databaseId, databaseHandle);
        }
    }

    /**
     * After a database container is (re)provisioned: rejoin every link network its
     * attached instances hold, because provisioning REPLACES the container and the fresh
     * one is only attached to its own workload network. Never throws -- provisioning must
     * not die on link plumbing; a pair the deploy lane cannot enforce is refused THERE.
     *
     * @return true when at least one membership was (re)ensured, which also means the
     *         database's published port may have moved
     */
    public static boolean reattachForDatabase(int databaseId) {
        boolean touched = false;
        for (Row link : Models.get(InstanceDatabaseModel.class).findByDatabaseId(databaseId)) {
            Integer ownerId = link.get(InstanceDatabaseModel.INSTANCE_ID);
            if (ownerId == null) {
                continue;
            }
            // The container to rejoin is the owner's CONSUMER: an application has none
            // until a release serves, and there is nothing to rejoin until then.
            Integer instanceId = ApplicationReleases.consumerInstanceOf(ownerId);
            if (instanceId == null) {
                continue;
            }
            try {
                InstanceService.Resolved resolved = new InstanceService().resolve(instanceId);
                if (!(resolved.runtime() instanceof LinkNetworkSupport support)) {
                    continue;
                }
                Row database = Models.get(DatabaseModel.class).find()
                    .where(DatabaseModel.ID.eq(databaseId)).first();
                if (database == null) {
                    continue;
                }
                if (ServerModel.canonicalServerId(database.get(DatabaseModel.SERVER_ID))
                        != resolved.serverId()) {
                    skipped(instanceId, databaseId, "cross_host");
                    continue;
                }
                String handle = linkHandle(ownerId, databaseId);
                support.ensureLinkNetwork(handle,
                    OwnerLabels.of(InstanceDatabaseModel.MODEL_ID,
                        link.get(InstanceDatabaseModel.ID)), Egress.NONE);
                String databaseHandle = DatabaseInstances.handleOf(databaseId);
                if (databaseHandle == null) {
                    continue;   // no engine instance: the next provision joins both sides
                }
                support.connectToLinkNetwork(handle, databaseHandle, List.of());
                support.connectToLinkNetwork(handle, resolved.spec().handle(), List.of());
                DatabaseLinkNetworks.refreshDatabasePort(resolved.runtime()::status,
                    resolved.serverId(), databaseId, databaseHandle);
                touched = true;
            } catch (Exception e) {
                Blast.log("DB-LINK: could not rejoin link network for instance", instanceId,
                    "database", databaseId, "-", e.getMessage());
            }
        }
        return touched;
    }

    /**
     * Remove every link network of the instance whose attachment row no longer exists.
     *
     * Unlike the site lane this DOES run against a still-running container -- a detach
     * has no release switch to ride, and deferring the disconnect would leave the
     * workload dialling a database whose authorization row is already gone. Both
     * ephemeral published ports the disconnect moves (the database's and the instance's
     * own) are re-observed by the shared sweep.
     *
     * @param everything when true, remove ALL of the instance's link networks regardless
     *        of surviving rows (the instance-destroy shape)
     */
    public static void sweepFor(int ownerId, boolean everything) {
        Row owner = Models.get(InstanceModel.class).findById(ownerId);
        int serverId = owner != null
            ? ServerModel.canonicalServerId(owner.get(InstanceModel.SERVER_ID))
            : ServerModel.localServerId();
        sweepFor(ownerId, everything, Set.of(serverId));
    }

    /**
     * The multi-host form: an application's releases may have been placed on more than one
     * host over their lifetime, so a destroy sweeps every host any of them ran on.
     *
     * @param servers the hosts to sweep, captured BEFORE the rows that name them are trashed
     */
    public static void sweepFor(int ownerId, boolean everything, @NonNull Set<Integer> servers) {
        InstanceDatabaseModel links = Models.get(InstanceDatabaseModel.class);
        String prefix = ControllerScope.kindPrefix(ControllerScope.KIND_INSTANCE_DBLINK)
            + ownerId + "-";
        Integer consumerId = ApplicationReleases.consumerInstanceOf(ownerId);
        DatabaseLinkNetworks.Consumer consumer = consumerId == null ? null
            : new DatabaseLinkNetworks.Consumer(consumerId,
                ControllerScope.handle(ControllerScope.KIND_INSTANCE, consumerId));
        for (Integer serverId : servers) {
            if (serverId == null) {
                continue;
            }
            try {
                DatabaseLinkNetworks.sweepOnServer(prefix, ServerModel.nameOf(serverId), serverId,
                    everything, databaseId -> {
                        for (Row link : links.findByInstanceId(ownerId)) {
                            if (Integer.valueOf(databaseId)
                                    .equals(link.get(InstanceDatabaseModel.DATABASE_ID))) {
                                return true;
                            }
                        }
                        return false;
                    }, consumer);
            } catch (Exception e) {
                Blast.log("DB-LINK: link-network sweep failed for instance", ownerId,
                    "on server", serverId, "-", e.getMessage());
            }
        }
    }

    private static void skipped(int instanceId, Integer databaseId, String reason) {
        Blast.slog("hohenheim.db_link.skipped", Map.of(
            "instance_id", String.valueOf(instanceId),
            "database_id", String.valueOf(databaseId),
            "reason", reason));
    }
}
