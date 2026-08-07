package be.elevenways.hohenheim.server.docker;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDatabaseModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.DockerInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.Egress;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.server.runtime.LinkNetworkSupport;
import be.elevenways.hohenheim.server.runtime.WorkloadNetworks;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The network half of site-database attachments for DOCKER sites: one policied LINK
 * network per (site, database) pair, joining exactly the site's release container and
 * the database's container -- the {@link LinkNetworkSupport} shape GameDomains proved
 * for the proxy-to-backend lane. The attachment ROWS are the authority: a pair without
 * a live {@code site_databases} row keeps no network, and a link network never carries
 * a third member, so two sites sharing one database still cannot reach each other.
 *
 * AIDEV-NOTE: link networks carry {@link Egress#NONE}. The database's own network was
 * DECLARED egress-NONE (an engine has no legitimate outbound-initiated traffic) and a
 * second interface must not silently widen that; the site's own egress rides its
 * primary per-workload network, untouched. Members still reach each other because the
 * own-subnet accept precedes the egress drop.
 *
 * AIDEV-NOTE: connecting or disconnecting a RUNNING container re-allocates its
 * ephemeral published host port (observed live: same PID, new HostPort). The database
 * side therefore refreshes its port-ledger claim after every membership change; the
 * SITE side is only ever joined between create and start (no port exists yet) or
 * swept once its container is already stopped/replaced, so its published port never
 * moves under the proxy.
 */
public final class SiteDatabaseNetworks {

    private SiteDatabaseNetworks() {
    }

    /** Link-network handle of one site/database pair. */
    static @NonNull String linkHandle(int siteId, int databaseId) {
        return ControllerScope.handle(ControllerScope.KIND_DBLINK, siteId + "-" + databaseId);
    }

    /**
     * Per-server link-network handles of every attachment row whose site currently has
     * a SERVING release on the same server as the database: the isolation sweep's
     * inventory of site-database link networks whose kernel chains must exist (a host
     * reboot erases them while the containers restart).
     */
    public static @NonNull Map<Integer, List<String>> liveLinkHandles() {
        Map<Integer, List<String>> byServer = new LinkedHashMap<>();
        DatabaseModel databases = Models.get(DatabaseModel.class);
        for (Row link : Models.get(SiteDatabaseModel.class).find().all()) {
            Integer siteId = link.get(SiteDatabaseModel.SITE_ID);
            Integer databaseId = link.get(SiteDatabaseModel.DATABASE_ID);
            if (siteId == null || databaseId == null) {
                continue;
            }
            Row serving = SiteInstances.ownedServing(siteId);
            if (serving == null) {
                continue;   // no release container: the next deploy attaches both sides
            }
            Row database = databases.find().where(DatabaseModel.ID.eq(databaseId)).first();
            if (database == null) {
                continue;
            }
            int serverId = ServerModel.canonicalServerId(serving.get(InstanceModel.SERVER_ID));
            if (ServerModel.canonicalServerId(database.get(DatabaseModel.SERVER_ID))
                    != serverId) {
                continue;   // cross-host pairs are refused at deploy and carry no network
            }
            byServer.computeIfAbsent(serverId, key -> new ArrayList<>())
                .add(linkHandle(siteId, databaseId));
        }
        return byServer;
    }

    /**
     * Between container CREATE and START of a site release container: ensure the link
     * network of every attached database (a deploy recreates the container, which drops
     * every non-primary attachment) and join both members. Runs for candidates too, so
     * the health gate probes the release WITH its database reachable.
     *
     * @throws IOException when a link cannot be enforced, when the daemon cannot answer
     *         for a database container, or -- BY NAME -- when an attached database lives
     *         on a different server than the site deploys to: a cross-host link network
     *         is unsupported, and refusing beats injecting an address that cannot work
     */
    public static void attachLinksBeforeStart(InstanceService.@NonNull Resolved resolved,
                                              int instanceId) throws IOException {
        Row row = resolved.row();
        if (!SiteModel.MODEL_ID.toString().equals(row.get(InstanceModel.GENERATED_FOR_MODEL))) {
            return;
        }
        Integer siteId = row.get(InstanceModel.GENERATED_FOR_ID);
        if (siteId == null) {
            return;
        }
        List<Row> links = Models.get(SiteDatabaseModel.class).findBySiteId(siteId);
        if (links.isEmpty()) {
            return;
        }
        if (!(resolved.runtime() instanceof LinkNetworkSupport support)) {
            throw new IOException("Site " + siteId + " has attached databases but instance "
                + instanceId + " runs on a driver without link networks");
        }
        DatabaseModel databases = Models.get(DatabaseModel.class);
        for (Row link : links) {
            Integer databaseId = link.get(SiteDatabaseModel.DATABASE_ID);
            Row database = databaseId != null
                ? databases.find().where(DatabaseModel.ID.eq(databaseId)).first() : null;
            if (database == null) {
                Blast.slog("hohenheim.db_link.skipped", Map.of(
                    "site_id", String.valueOf(siteId),
                    "database_id", String.valueOf(databaseId),
                    "reason", "record_missing"));
                continue;
            }
            String name = database.get(DatabaseModel.NAME);
            int databaseServer = ServerModel.canonicalServerId(
                database.get(DatabaseModel.SERVER_ID));
            if (databaseServer != resolved.serverId()) {
                throw new IOException("REFUSED to deploy site " + siteId + ": attached database '"
                    + name + "' runs on server '" + ServerModel.nameOf(databaseServer)
                    + "' while the site deploys on server '"
                    + ServerModel.nameOf(resolved.serverId())
                    + "'. Cross-host site-to-database links are not supported; move the site or"
                    + " the database so both run on the same server.");
            }
            String databaseHandle = DatabaseInstances.handleOf(databaseId);
            if (databaseHandle == null) {
                // The database owns no engine instance yet: nothing to join. The link
                // network is still ensured above so the site side is ready, and
                // reattachForDatabase joins the engine the moment it is provisioned.
                Blast.slog("hohenheim.db_link.skipped", Map.of(
                    "site_id", String.valueOf(siteId),
                    "database_id", String.valueOf(databaseId),
                    "reason", "no_engine_instance"));
                continue;
            }
            ContainerState state = resolved.runtime().status(databaseHandle).state();
            if (state == ContainerState.UNREACHABLE) {
                throw new IOException("Cannot verify database container '" + databaseHandle
                    + "' while deploying site " + siteId + ": the daemon is unreachable");
            }
            Integer linkId = link.get(SiteDatabaseModel.ID);
            String handle = linkHandle(siteId, databaseId);
            support.ensureLinkNetwork(handle,
                OwnerLabels.of(SiteDatabaseModel.MODEL_ID, linkId), Egress.NONE);
            support.connectToLinkNetwork(handle, resolved.spec().handle(), List.of());
            if (state == ContainerState.ABSENT) {
                // Nothing to join yet: the database side is (re)connected by
                // reattachForDatabase the moment its container is provisioned.
                Blast.slog("hohenheim.db_link.skipped", Map.of(
                    "site_id", String.valueOf(siteId),
                    "database_id", String.valueOf(databaseId),
                    "reason", "database_container_absent"));
                continue;
            }
            support.connectToLinkNetwork(handle, databaseHandle, List.of());
            refreshDatabasePort(resolved.runtime()::status, resolved.serverId(),
                databaseId, databaseHandle);
        }
    }

    /**
     * After a database container is (re)provisioned: rejoin every link network its
     * attached sites hold, because provisioning REPLACES the container and the fresh one
     * is only attached to its own workload network. Never throws -- provisioning must
     * not die on link plumbing; a pair the deploy lane cannot enforce is refused THERE.
     *
     * @return true when at least one link network membership was (re)ensured, which
     *         also means the database's published port may have moved
     */
    public static boolean reattachForDatabase(int databaseId) {
        boolean touched = false;
        List<Row> links = Models.get(SiteDatabaseModel.class).findByDatabaseId(databaseId);
        for (Row link : links) {
            Integer siteId = link.get(SiteDatabaseModel.SITE_ID);
            if (siteId == null) {
                continue;
            }
            Row serving = SiteInstances.ownedServing(siteId);
            if (serving == null) {
                continue;   // no release container: the next deploy attaches both sides
            }
            try {
                InstanceService.Resolved resolved = new InstanceService()
                    .resolve(serving.get(InstanceModel.ID));
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
                    Blast.slog("hohenheim.db_link.skipped", Map.of(
                        "site_id", String.valueOf(siteId),
                        "database_id", String.valueOf(databaseId),
                        "reason", "cross_host"));
                    continue;
                }
                String handle = linkHandle(siteId, databaseId);
                support.ensureLinkNetwork(handle,
                    OwnerLabels.of(SiteDatabaseModel.MODEL_ID, link.get(SiteDatabaseModel.ID)),
                    Egress.NONE);
                String databaseHandle = DatabaseInstances.handleOf(databaseId);
                if (databaseHandle == null) {
                    continue;   // no engine instance: the next provision joins both sides
                }
                support.connectToLinkNetwork(handle, databaseHandle, List.of());
                support.connectToLinkNetwork(handle, resolved.spec().handle(), List.of());
                refreshDatabasePort(resolved.runtime()::status, resolved.serverId(),
                    databaseId, databaseHandle);
                touched = true;
            } catch (Exception e) {
                Blast.log("DB-LINK: could not rejoin link network for site", siteId,
                    "database", databaseId, "-", e.getMessage());
            }
        }
        return touched;
    }

    /**
     * Remove every link network of the site whose attachment row no longer exists.
     * Runs AFTER a release switch (drain completion) and on site destroy -- never while
     * the members it disconnects still serve traffic, because disconnecting a running
     * container re-allocates its published port.
     *
     * @param everything when true, remove ALL of the site's link networks regardless of
     *        surviving rows (the site-destroy shape)
     */
    static void sweepFor(int siteId, boolean everything) {
        Set<Integer> servers = new LinkedHashSet<>();
        for (Row instance : SiteInstances.ownedInstances(siteId)) {
            servers.add(ServerModel.canonicalServerId(instance.get(InstanceModel.SERVER_ID)));
        }
        if (servers.isEmpty()) {
            servers.add(ServerModel.localServerId());
        }
        sweepFor(siteId, everything, servers);
    }

    /** {@link #sweepFor(int, boolean)} with the servers captured by the caller -- the
     *  destroy path soft-deletes the instance rows first, so it must not re-derive. */
    static void sweepFor(int siteId, boolean everything, Set<Integer> servers) {
        for (Integer serverId : servers) {
            try {
                sweepOnServer(siteId, ServerModel.nameOf(serverId), serverId, everything);
            } catch (Exception e) {
                Blast.log("DB-LINK: link-network sweep failed for site", siteId,
                    "on server", serverId, "-", e.getMessage());
            }
        }
    }

    private static void sweepOnServer(int siteId, String serverName, int serverId,
                                      boolean everything) throws IOException {
        DockerClient docker = new ServerService().clientFor(serverName);
        DockerInstanceRuntime runtime = new DockerInstanceRuntime(docker,
            WorkloadNetworkPolicy.forServer(serverName));
        String prefix = ControllerScope.kindPrefix(ControllerScope.KIND_DBLINK) + siteId + "-";
        SiteDatabaseModel links = Models.get(SiteDatabaseModel.class);
        for (String network : linkNetworksOf(docker, prefix)) {
            String handle = network.substring(0,
                network.length() - WorkloadNetworks.SUFFIX.length());
            Integer databaseId = trailingInt(handle);
            boolean live = false;
            if (!everything && databaseId != null) {
                for (Row link : links.findBySiteId(siteId)) {
                    if (databaseId.equals(link.get(SiteDatabaseModel.DATABASE_ID))) {
                        live = true;
                        break;
                    }
                }
            }
            if (live) {
                continue;
            }
            runtime.removeLinkNetwork(handle);
            Blast.log("DB-LINK: removed stale link network", network, "of site", siteId);
            // The disconnect re-allocated the database's published port; re-observe it.
            if (databaseId != null && DatabaseInstances.handleOf(databaseId) instanceof String h) {
                refreshDatabasePort(runtime::status, serverId, databaseId, h);
            }
        }
    }

    /** Names of this site's link networks at the daemon, matched on the naming scheme. */
    private static List<String> linkNetworksOf(DockerClient docker, String handlePrefix)
            throws IOException {
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
    private static @Nullable Integer trailingInt(String handle) {
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

    private interface StatusOf {
        @NonNull InstanceStatus status(@NonNull String handle);
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
    private static void refreshDatabasePort(StatusOf statuses, int serverId, int databaseId,
                                            String databaseHandle) {
        Row instance = DatabaseInstances.owned(databaseId);
        if (instance == null) {
            return;
        }
        Integer instanceId = instance.get(InstanceModel.ID);
        InstanceStatus status = statuses.status(databaseHandle);
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
