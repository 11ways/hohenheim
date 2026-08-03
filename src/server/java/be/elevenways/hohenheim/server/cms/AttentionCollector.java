package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.DeploymentModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDatabaseModel;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.HohenheimRoles.Role;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.WorkloadIdentity;
import be.elevenways.hohenheim.server.docker.DockerHealth;
import be.elevenways.hohenheim.server.docker.DockerReconciler;
import be.elevenways.hohenheim.server.dns.DnsZoneSnapshot;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.sitetype.SiteHealth;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.spamservice.SpamserviceManager;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.task.TaskStatus;
import be.elevenways.zenit.common.task.orm.SystemTaskHistoryModel;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.xbill.DNS.Type;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gathers the dashboard attention items: certificates in error, sites whose
 * live handler reports DOWN/DEGRADED, failed managed databases, git sites
 * whose LATEST deploy failed, and scheduled tasks whose latest run failed.
 * Server reachability is deliberately NOT probed here (it would SSH/dial
 * every host per dashboard render); the Servers list owns that.
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
public final class AttentionCollector {

    private AttentionCollector() {}

    /**
     * Every collector is gated on the role that runs the thing it watches: a
     * DNS appliance must not claim to be watching stacks or certificates it
     * does not run. Task history stays ungated -- only role-enabled tasks
     * declare schedules, so its content is already role-shaped.
     */
    public static @NonNull List<AttentionItem> collect() {
        List<AttentionItem> items = new ArrayList<>();
        if (HohenheimRoles.enabled(Role.PROXY)) {
            errorCertificates(items);
            unhealthySites(items);
            failedDeployments(items);
        }
        if (HohenheimRoles.enabled(Role.DATABASES)) {
            failedDatabases(items);
            unavailableAttachedDatabases(items);
        }
        if (HohenheimRoles.enabled(Role.STACKS)) {
            dockerUnreachable(items);
        }
        if (HohenheimRoles.anyEnabled(Role.PROXY, Role.DATABASES, Role.STACKS)) {
            // Stored reconciler findings only -- the sweep itself is a scheduled
            // task, never a per-render daemon probe.
            items.addAll(DockerReconciler.attentionItems());
        }
        if (HohenheimRoles.anyEnabled(Role.PROXY, Role.DATABASES, Role.STACKS, Role.PROCESSES)) {
            stuckReleasingPorts(items, Instant.now().minus(RELEASING_STUCK_AFTER));
        }
        failedTasks(items);
        if (HohenheimRoles.enabled(Role.DNS)) {
            dnsIssues(items);
        }
        if (HohenheimRoles.enabled(Role.FIREWALL)) {
            spamserviceIssue(items);
        }
        if (HohenheimRoles.enabled(Role.PROCESSES)) {
            identityIssues(items);
        }
        return items;
    }

    /** How long a claim may sit in {@code releasing} before it is an alarm: two hourly
     *  reconciler sweeps should have observed and freed it by then. */
    private static final Duration RELEASING_STUCK_AFTER = Duration.ofHours(2);

    /**
     * Port claims stuck in {@code releasing} past the age threshold -- the ledger's
     * never-cleared alarm. A row lands there when a teardown could not verify itself
     * (or a host was removed); the reconciler deletes it once it OBSERVES the port
     * free, so one that lingers means the port is genuinely still bound by something
     * we no longer manage, or the host is unobservable. The flip time is updated_at:
     * releasing rows are never re-saved (the park is idempotent). The threshold is a
     * parameter only so a test can prove the projection without forging timestamps.
     */
    public static void stuckReleasingPorts(List<AttentionItem> items, Instant threshold) {
        Map<String, List<String>> stuckByServer = new LinkedHashMap<>();
        List<Row> releasing = Models.get(PortAllocationModel.class).find()
            .where(PortAllocationModel.STATUS.eq(PortAllocationModel.STATUS_RELEASING))
            .all();
        for (Row claim : releasing) {
            Instant parkedAt = claim.get(PortAllocationModel.UPDATED_AT);
            if (parkedAt == null || parkedAt.isAfter(threshold)) {
                continue;
            }
            stuckByServer.computeIfAbsent(serverNameOf(claim.get(PortAllocationModel.SERVER_ID)),
                    k -> new ArrayList<>())
                .add(claim.get(PortAllocationModel.PORT) + "/"
                    + claim.get(PortAllocationModel.PROTOCOL));
        }
        stuckByServer.forEach((server, ports) -> items.add(item("warning", "ethernet",
            copy("ports_releasing", "attention_title", "server", server),
            copy("ports_releasing", "attention_detail",
                "count", ports.size(),
                "hours", RELEASING_STUCK_AFTER.toHours(),
                "ports", String.join(", ", ports)),
            null)));
    }

    // A releasing claim can outlive its servers row (host removal parks claims and
    // deletes nothing), so a dangling id must still render, not throw.
    private static String serverNameOf(@Nullable Integer serverId) {
        try {
            return ServerModel.nameOf(serverId);
        } catch (IllegalArgumentException gone) {
            return "removed host #" + serverId;
        }
    }

    /** A stacks node whose boot probe found no Docker daemon: a red item, not silence. */
    private static void dockerUnreachable(List<AttentionItem> items) {
        DockerHealth health = DockerHealth.instance();
        if (health.status() != DockerHealth.Status.UNREACHABLE) {
            return;
        }
        items.add(item("error", "cubes",
            copy("docker_unreachable", "attention_title"),
            literal(health.problem()),
            "/admin/settings"));
    }

    /**
     * Managed-process sites without their own exclusively-claimed system user. An
     * "error" means the site faults (enforcement applies to it right now); a
     * "warning" means it still runs leniently but blocks enabling
     * process.require_dedicated_user.
     */
    private static void identityIssues(List<AttentionItem> items) {
        for (WorkloadIdentity.Finding finding : WorkloadIdentity.auditAll()) {
            boolean enforced = WorkloadIdentity.enforcementRequired(finding.siteId());
            items.add(item(enforced ? "error" : "warning", "user-lock",
                copy("workload_identity", "attention_title", "name",
                    finding.siteName() != null ? finding.siteName() : finding.siteId()),
                literal(finding.problem()),
                "/admin/sites/" + finding.siteId()));
        }
    }

    /** Surfaces an enabled managed Spamservice that is not currently ready. */
    private static void spamserviceIssue(List<AttentionItem> items) {
        SpamserviceManager.Snapshot snapshot = SpamserviceManager.get().snapshot();
        if (snapshot.configured() && snapshot.enabled() && "ready".equals(snapshot.state())) {
            return;
        }
        items.add(item("warning", "shield",
            copy("not_ready", "spamservice"),
            literal(snapshot.lastError() != null ? snapshot.lastError()
                : snapshot.state()), null));
    }

    /** DNS listeners that failed to bind, and enabled zones a resolver cannot delegate to. */
    private static void dnsIssues(List<AttentionItem> items) {
        Boolean enabled = HohenheimSettings.VALUES.getValue(HohenheimSettings.Dns.ENABLED);
        var dnsServer = ServerMain.getDnsServer();
        if (Boolean.TRUE.equals(enabled) && (dnsServer == null || !dnsServer.isRunning())) {
            String reason = dnsServer != null ? dnsServer.getStartupError() : null;
            items.add(item("error", "sitemap",
                copy("dns_listener", "attention_title"),
                literal(reason),
                "/admin/settings"));
        }
        for (DnsZoneSnapshot zone : DnsZoneStore.INSTANCE.zones()) {
            if (zone.getRrset(zone.getOrigin(), Type.NS) == null) {
                items.add(item("warning", "sitemap",
                    copy("dns_zone_no_ns", "attention_title", "origin", zone.getOriginString()),
                    copy("dns_zone_no_ns", "attention_detail"),
                    "/admin/dns-zones/" + zone.getZoneId() + "/page/records"));
            }
        }
    }

    private static void errorCertificates(List<AttentionItem> items) {
        List<Row> rows = Models.get(CertificateModel.class).find()
            .where(CertificateModel.STATUS.eq(CertificateModel.STATUS_ERROR))
            .all();
        for (Row row : rows) {
            items.add(item("error", "certificate",
                copy("certificate", "attention_title", "name", row.get(CertificateModel.NICE_NAME)),
                literal(row.get(CertificateModel.RENEWAL_ERROR)),
                "/admin/certificates/" + row.get(CertificateModel.ID)));
        }
    }

    private static void unhealthySites(List<AttentionItem> items) {
        var proxy = ServerMain.getProxyServer();
        if (proxy == null) {
            return;
        }
        List<Row> sites = Models.get(SiteModel.class).find()
            .where(SiteModel.ENABLED.eq(true))
            .where(SiteModel.DELETED_AT.isNull())
            .all();
        for (Row site : sites) {
            Integer siteId = site.get(SiteModel.ID);
            if (siteId == null) {
                continue;
            }
            SiteRequestHandler handler = proxy.getDispatcher().findHandlerBySiteId(siteId);
            SiteHealth health = handler != null ? handler.getHealth() : null;
            if (health == SiteHealth.DOWN || health == SiteHealth.DEGRADED) {
                items.add(item(health == SiteHealth.DOWN ? "error" : "warning", "globe",
                    copy("site", "attention_title", "name", site.get(SiteModel.NAME)),
                    copy(health == SiteHealth.DOWN ? "down" : "degraded", "attention_detail"),
                    "/admin/sites/" + siteId));
            }
        }
    }

    private static void failedDatabases(List<AttentionItem> items) {
        List<Row> rows = Models.get(DatabaseModel.class).find()
            .where(DatabaseModel.STATUS.eq(DatabaseModel.STATUS_FAILED))
            .all();
        for (Row row : rows) {
            items.add(item("error", "database",
                copy("database", "attention_title", "name", row.get(DatabaseModel.NAME)),
                copy("provisioning_failed", "attention_detail"),
                "/admin/databases/" + row.get(DatabaseModel.ID)));
        }
    }

    /**
     * Sites whose ATTACHED database can't serve its injected credentials right now
     * (record not active, or container not running). Attached databases are local by
     * the link-time rule, so the probe is a cheap local docker inspect -- and only
     * linked databases of live enabled sites are probed. Failed-record databases
     * already surface above; this frames the SITE impact of a stopped container.
     */
    private static void unavailableAttachedDatabases(List<AttentionItem> items) {
        var linkModel = Models.get(SiteDatabaseModel.class);
        if (linkModel == null) {
            return;
        }
        List<Row> links = linkModel.find().all();
        if (links.isEmpty()) {
            return;
        }
        var siteModel = Models.get(SiteModel.class);
        var databaseModel = Models.get(DatabaseModel.class);
        DatabaseService databases = new DatabaseService();
        for (Row link : links) {
            Row site = siteModel.find()
                .where(SiteModel.ID.eq(link.get(SiteDatabaseModel.SITE_ID)))
                .first();
            if (site == null || site.get(SiteModel.DELETED_AT) != null
                || !Boolean.TRUE.equals(site.get(SiteModel.ENABLED))) {
                continue;
            }
            Row database = databaseModel.find()
                .where(DatabaseModel.ID.eq(link.get(SiteDatabaseModel.DATABASE_ID)))
                .first();
            if (database == null) {
                continue;   // dangling link; the tab shows it as (deleted)
            }
            String status = database.get(DatabaseModel.STATUS);
            boolean unavailable;
            Microcopy detail;
            if (!DatabaseModel.STATUS_ACTIVE.equals(status)) {
                unavailable = true;
                detail = copy("database_status", "attention_detail",
                    "name", database.get(DatabaseModel.NAME), "status", status);
            } else {
                var live = safeDetail(databases, database);
                unavailable = live == null || !live.running();
                boolean unreachable = live == null
                    || live.containerState() == ContainerState.UNREACHABLE;
                // "Gone/stopped" and "the daemon could not be asked" are different
                // operator problems; conflating them was the C6 status defect.
                detail = copy(unreachable ? "database_unreachable" : "database_not_running",
                    "attention_detail", "name", database.get(DatabaseModel.NAME));
            }
            if (unavailable) {
                items.add(item("warning", "database",
                    copy("site", "attention_title", "name", site.get(SiteModel.NAME)),
                    detail,
                    "/admin/sites/" + site.get(SiteModel.ID) + "/page/databases"));
            }
        }
    }

    private static DatabaseService.@Nullable Detail safeDetail(DatabaseService databases, Row database) {
        try {
            return databases.detailOf(database);
        } catch (Exception e) {
            return null;   // unresolvable engine/host: treat as unavailable
        }
    }

    private static void failedDeployments(List<AttentionItem> items) {
        var siteModel = Models.get(SiteModel.class);
        var deployModel = Models.get(DeploymentModel.class);
        List<Row> gitSites = siteModel.find()
            .where(SiteModel.ENABLED.eq(true))
            .where(SiteModel.DELETED_AT.isNull())
            .where(SiteModel.SOURCE.eq(SiteModel.SOURCE_GIT))
            .all();
        for (Row site : gitSites) {
            Integer siteId = site.get(SiteModel.ID);
            if (siteId == null) {
                continue;
            }
            List<Row> latest = deployModel.findBySiteId(siteId, 1);
            if (latest.isEmpty()) {
                continue;
            }
            Row deploy = latest.get(0);
            if (DeploymentModel.STATUS_FAILED.equals(deploy.get(DeploymentModel.STATUS))) {
                items.add(item("error", "rocket",
                    copy("deploy", "attention_title", "name", site.get(SiteModel.NAME)),
                    literal(deploy.get(DeploymentModel.ERROR)),
                    "/admin/sites/" + siteId + "/page/deployments"));
            }
        }
    }

    /** Latest history row per task type; failed ones surface (no task UI yet, so no url). */
    private static void failedTasks(List<AttentionItem> items) {
        // The task system registers its datasource-scoped model at its own boot
        // stage; a boot without it (tests, tools) simply has no task news.
        if (Models.get(SystemTaskHistoryModel.MODEL_ID) == null) {
            return;
        }
        List<Row> recent = Models.get(SystemTaskHistoryModel.class).find()
            .orderBy(SystemTaskHistoryModel.STARTED_AT, be.elevenways.zenit.common.orm.query.SortOrder.DESC)
            .limit(200)
            .all();
        Map<String, Row> latestPerType = new LinkedHashMap<>();
        for (Row row : recent) {
            String type = row.get(SystemTaskHistoryModel.TASK_TYPE);
            if (type != null) {
                latestPerType.putIfAbsent(type, row);
            }
        }
        Set<String> failed = new LinkedHashSet<>();
        for (Map.Entry<String, Row> entry : latestPerType.entrySet()) {
            if (TaskStatus.FAILED.name().equals(entry.getValue().get(SystemTaskHistoryModel.STATUS))) {
                failed.add(entry.getKey());
            }
        }
        for (String type : failed) {
            items.add(item("warning", "clock",
                copy("task", "attention_title", "name", type),
                copy("last_run_failed", "attention_detail"),
                null));
        }
    }

    private static @NonNull AttentionItem item(String severity, String icon, Microcopy title,
                                               @Nullable Microcopy detail, @Nullable String url) {
        return new AttentionItem(severity, icon, title, detail, url != null ? url : "");
    }

    private static @Nullable Microcopy literal(@Nullable Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return Microcopy.literal(String.valueOf(value));
    }

    private static @NonNull Microcopy copy(String key, String scope, Object... args) {
        Microcopy copy = Microcopy.of(key).withFilter("scope", scope);
        for (int i = 0; i + 1 < args.length; i += 2) {
            copy = copy.withArg(String.valueOf(args[i]), args[i + 1]);
        }
        return copy;
    }
}
