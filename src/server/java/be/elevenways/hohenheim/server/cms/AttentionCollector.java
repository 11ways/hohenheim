package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.HohenheimRoles.Role;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.docker.DockerHealth;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.docker.DockerReconciler;
import be.elevenways.hohenheim.server.dns.DnsZoneSnapshot;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.database.ControlPlaneBackups;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.sitetype.SiteHealth;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.spamservice.SpamserviceManager;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.hohenheim.server.task.BackupControlPlane;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.task.TaskCatalog;
import be.elevenways.zenit.common.task.TaskDescriptor;
import be.elevenways.zenit.common.task.TaskStatus;
import be.elevenways.zenit.common.task.orm.SystemTaskHistoryModel;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.xbill.DNS.Type;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * Every attention item points into the OPERATOR panel: this widget is an
     * installation-health surface, so its links keep the panel slug they always had.
     */
    private static final String ADMIN = "admin";

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
            failedProxyListeners(items);
            httpsUnavailableWithForceSsl(items);
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
        if (HohenheimRoles.anyEnabled(Role.PROXY, Role.DATABASES, Role.STACKS)) {
            stuckReleasingPorts(items, Instant.now().minus(RELEASING_STUCK_AFTER));
        }
        failedTasks(items);
        controlPlaneBackupDestination(items);
        if (HohenheimRoles.enabled(Role.DNS)) {
            dnsIssues(items);
        }
        if (HohenheimRoles.enabled(Role.FIREWALL)) {
            spamserviceIssue(items);
        }
        if (HohenheimRoles.anyEnabled(Role.STACKS, Role.DATABASES, Role.INSTANCES)) {
            hostsNotAdmitted(items);
        }
        if (HohenheimRoles.enabled(Role.INSTANCES)) {
            crashedInstances(items);
            failedInstanceBackups(items);
            staleInstanceBackups(items);
            instancesLowOnDisk(items);
        }
        return items;
    }

    /**
     * Enrolled hosts that are not admitted for placement.
     *
     * AIDEV-NOTE: this list used to say "All clear" directly beneath the onboarding card
     * naming a host the deploy lane would refuse -- the checklist watched admission and
     * this collector did not, so the dashboard contradicted itself on one screen. Gated
     * on the same roles that put the Servers list in the panel, so the link always exists.
     * CORDONED is deliberately absent: an operator drained that host on purpose, and a
     * permanent warning over a deliberate state is how a warning stops being read.
     */
    public static void hostsNotAdmitted(List<AttentionItem> items) {
        for (Row server : Models.get(ServerModel.class).find()
                .where(ServerModel.ADMISSION.eq(ServerModel.ADMISSION_BLOCKED))
                .all()) {
            items.add(item("warning", "server",
                copy("host_not_admitted", "attention_title",
                    "name", server.get(ServerModel.NAME)),
                copy("host_not_admitted", "attention_detail"),
                CmsRoutes.detail(ADMIN, "servers", server.get(ServerModel.ID))));
        }
    }

    // AIDEV-NOTE: the three instance collectors are PUBLIC for the same reason
    // stuckReleasingPorts is -- a test proves each projection directly, positive and
    // negative, instead of asserting against whatever the whole dashboard happens to hold.

    /**
     * Instances the runtime gave up on: the status CRASH DETECTION already stamped
     * (an unobserved exit under crash policy none, or a crash loop that tripped flap
     * protection). A stored fact, deliberately -- asking every daemon whether each
     * workload is alive is what the class-level rule about per-render probes forbids.
     */
    public static void crashedInstances(List<AttentionItem> items) {
        for (Row instance : Models.get(InstanceModel.class).find()
                .where(InstanceModel.DELETED_AT.isNull())
                .where(InstanceModel.STATUS.eq(InstanceModel.STATUS_ERROR))
                .all()) {
            items.add(item("error", "box",
                copy("instance_crashed", "attention_title",
                    "name", instance.get(InstanceModel.NAME)),
                copy("instance_crashed", "attention_detail"),
                CmsRoutes.subpage(ADMIN, "instances", instance.get(InstanceModel.ID), "console")));
        }
    }

    /**
     * Instances whose LATEST backup failed -- the failedDeployments shape: only the most
     * recent attempt per instance speaks, so one old failure followed by successes is not
     * an alarm and a currently-failing schedule is.
     */
    public static void failedInstanceBackups(List<AttentionItem> items) {
        var backups = Models.get(InstanceBackupModel.class);
        for (Row instance : Models.get(InstanceModel.class).find()
                .where(InstanceModel.DELETED_AT.isNull())
                .all()) {
            Integer id = instance.get(InstanceModel.ID);
            if (id == null) {
                continue;
            }
            Row latest = backups.find()
                .where(InstanceBackupModel.INSTANCE_ID.eq(id))
                .orderBy(InstanceBackupModel.ID, SortOrder.DESC)
                .first();
            if (latest == null || !InstanceBackupModel.STATUS_FAILED
                    .equals(latest.get(InstanceBackupModel.STATUS))) {
                continue;
            }
            items.add(item("error", "box-archive",
                copy("instance_backup", "attention_title",
                    "name", instance.get(InstanceModel.NAME)),
                literal(latest.get(InstanceBackupModel.ERROR)),
                CmsRoutes.subpage(ADMIN, "instances", id, "backups")));
        }
    }

    /**
     * Instances whose backup signal has degraded to SILENCE: a backup target is declared
     * on the record (the operator's statement that this instance is supposed to be backed
     * up) but no COMPLETE backup exists, or the newest one is older than
     * {@code backup.stale_after_days}. The latest-FAILED collector above answers "is it
     * failing right now"; this one answers the question that collector structurally
     * cannot -- "when did it last SUCCEED" -- so an instance never backed up, or failing
     * so long its failures predate its rows, stops reading as green. Both may fire for
     * one instance (failing nightly AND stale); that is escalation, not duplication.
     */
    public static void staleInstanceBackups(List<AttentionItem> items) {
        Integer days = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Backup.STALE_AFTER_DAYS);
        if (days == null || days <= 0) {
            return;
        }
        Instant threshold = Instant.now().minus(Duration.ofDays(days));
        var backups = Models.get(InstanceBackupModel.class);
        for (Row instance : Models.get(InstanceModel.class).find()
                .where(InstanceModel.DELETED_AT.isNull())
                .where(InstanceModel.BACKUP_TARGET_ID.isNotNull())
                .all()) {
            Integer id = instance.get(InstanceModel.ID);
            if (id == null) {
                continue;
            }
            Row newestComplete = backups.find()
                .where(InstanceBackupModel.INSTANCE_ID.eq(id))
                .where(InstanceBackupModel.STATUS.eq(InstanceBackupModel.STATUS_COMPLETE))
                .orderBy(InstanceBackupModel.ID, SortOrder.DESC)
                .first();
            if (newestComplete == null) {
                items.add(item("warning", "box-archive",
                    copy("instance_backup_never", "attention_title",
                        "name", instance.get(InstanceModel.NAME)),
                    copy("instance_backup_never", "attention_detail"),
                    CmsRoutes.subpage(ADMIN, "instances", id, "backups")));
                continue;
            }
            Instant completedAt = newestComplete.get(InstanceBackupModel.CREATED_AT);
            if (completedAt == null || completedAt.isBefore(threshold)) {
                long age = completedAt == null
                    ? -1 : Duration.between(completedAt, Instant.now()).toDays();
                items.add(item("warning", "box-archive",
                    copy("instance_backup_stale", "attention_title",
                        "name", instance.get(InstanceModel.NAME)),
                    copy("instance_backup_stale", "attention_detail", "days", age),
                    CmsRoutes.subpage(ADMIN, "instances", id, "backups")));
            }
        }
    }

    /** Above this fraction of an ENFORCED root-disk ceiling an instance needs attention. */
    private static final double DISK_HIGH = 0.85;

    /** ... and above this it is about to break rather than merely worth watching. */
    private static final double DISK_CRITICAL = 0.95;

    /**
     * Instances close to filling their root disk, from the STORED observation
     * ({@code ObserveInstanceDisk}).
     *
     * AIDEV-NOTE: a null observation is silence, never zero, and a zero LIMIT is silence
     * too. Both mean "nothing is rationing this disk, or nothing measured it" -- Docker's
     * whole tier is in that state by design, because it enforces no root quota at all. An
     * item here therefore always names a real ceiling a real number is approaching.
     */
    public static void instancesLowOnDisk(List<AttentionItem> items) {
        for (Row instance : Models.get(InstanceModel.class).find()
                .where(InstanceModel.DELETED_AT.isNull())
                .where(InstanceModel.DISK_OBSERVED_AT.isNotNull())
                .all()) {
            Long used = instance.get(InstanceModel.DISK_USED_BYTES);
            Long limit = instance.get(InstanceModel.DISK_LIMIT_BYTES);
            if (used == null || limit == null || limit <= 0) {
                continue;
            }
            double fraction = (double) used / limit;
            if (fraction < DISK_HIGH) {
                continue;
            }
            items.add(item(fraction >= DISK_CRITICAL ? "error" : "warning", "hard-drive",
                copy("instance_disk", "attention_title",
                    "name", instance.get(InstanceModel.NAME)),
                copy("instance_disk", "attention_detail",
                    "percent", Math.round(fraction * 100),
                    "limit", Math.round(limit / (1024.0 * 1024 * 1024))),
                CmsRoutes.detail(ADMIN, "instances", instance.get(InstanceModel.ID))));
        }
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

    /**
     * No off-host destination for the control-plane recovery archive.
     *
     * Role-FREE, like the task itself: every node has a control-plane database. Surfaced here
     * rather than left to the nightly task's failure, because 02:30 is a poor moment to learn
     * that the one backup covering this host's own database and keyring was never configured.
     */
    public static void controlPlaneBackupDestination(List<AttentionItem> items) {
        if (ControlPlaneBackups.configuredDestinationName() == null) {
            items.add(item("error", "box-archive",
                copy("control_plane_backup", "attention_title"),
                copy("control_plane_backup", "attention_detail"),
                CmsRoutes.list(ADMIN, "settings")));
            return;
        }
        controlPlaneBackupFreshness(items);
    }

    /** The nightly task runs at 02:30; two missed nights is an alarm, not scheduling jitter. */
    private static final Duration CONTROL_PLANE_BACKUP_STALE_AFTER = Duration.ofHours(48);

    /**
     * A configured destination is a CAPABILITY; this is the OBSERVATION: the newest
     * COMPLETED {@code BackupControlPlane} run must be recent. The per-type failedTasks
     * item says "the last run failed"; this one catches what that cannot -- a scheduler
     * that stopped running the task at all, or a failure streak old enough that "last run
     * failed" understates it. LIMITATION, stated: a brand-new install with no history row
     * for the task yet stays silent until the first nightly run seeds one.
     */
    public static void controlPlaneBackupFreshness(List<AttentionItem> items) {
        if (Models.get(SystemTaskHistoryModel.MODEL_ID) == null) {
            return;
        }
        var history = Models.get(SystemTaskHistoryModel.class);
        String typePath = BackupControlPlane.class.getName();
        if (history.findRecentForType(typePath, 1).isEmpty()) {
            return;
        }
        Row newestSuccess = history.find()
            .where(SystemTaskHistoryModel.TASK_TYPE.eq(typePath))
            .where(SystemTaskHistoryModel.STATUS.eq(TaskStatus.COMPLETED.name()))
            .orderBy(SystemTaskHistoryModel.STARTED_AT, SortOrder.DESC)
            .first();
        Instant successAt = newestSuccess != null
            ? newestSuccess.get(SystemTaskHistoryModel.STARTED_AT) : null;
        if (successAt == null
                || successAt.isBefore(Instant.now().minus(CONTROL_PLANE_BACKUP_STALE_AFTER))) {
            items.add(item("error", "box-archive",
                copy("control_plane_backup_stale", "attention_title"),
                copy("control_plane_backup_stale", "attention_detail",
                    "hours", CONTROL_PLANE_BACKUP_STALE_AFTER.toHours()),
                CmsRoutes.list(ADMIN, "settings")));
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
            CmsRoutes.list(ADMIN, "settings")));
    }

    /** Surfaces an enabled managed Spamservice that is not currently ready. */
    private static void spamserviceIssue(List<AttentionItem> items) {
        AttentionItem issue = spamserviceIssue(SpamserviceManager.get().snapshot());
        if (issue != null) {
            items.add(issue);
        }
    }

    /**
     * The decision behind the spamservice item, on a snapshot so it is testable.
     * An unconfigured or deliberately disabled service is a CHOICE, never a warning;
     * only an enabled one that is not ready gets an item, and that item explains
     * itself (the last error when there is one, else a localized state sentence)
     * and links to the settings mount where the service is administered.
     */
    static @Nullable AttentionItem spamserviceIssue(SpamserviceManager.Snapshot snapshot) {
        if (!snapshot.configured() || !snapshot.enabled() || "ready".equals(snapshot.state())) {
            return null;
        }
        Microcopy detail = snapshot.lastError() != null
            ? literal(snapshot.lastError())
            : copy("spamservice_not_ready", "attention_detail", "state", snapshot.state());
        return item("warning", "shield",
            copy("not_ready", "spamservice"), detail,
            CmsRoutes.list(ADMIN, "settings"));
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
                CmsRoutes.list(ADMIN, "settings")));
        }
        for (DnsZoneSnapshot zone : DnsZoneStore.INSTANCE.zones()) {
            if (zone.getRrset(zone.getOrigin(), Type.NS) == null) {
                items.add(item("warning", "sitemap",
                    copy("dns_zone_no_ns", "attention_title", "origin", zone.getOriginString()),
                    copy("dns_zone_no_ns", "attention_detail"),
                    CmsRoutes.subpage(ADMIN, "dns-zones", zone.getZoneId(), "records")));
            }
        }
    }

    /**
     * A dead or degraded proxy listener, REGARDLESS of force_ssl population. The Aug 04
     * 2026 port-443 outage stayed invisible for six days partly because the only listener
     * attention item required force_ssl sites; this one fires on listener state alone.
     * The force-SSL twin below stays because it names the affected sites.
     * Public so a test can prove the projection directly, like the instance collectors.
     */
    public static void failedProxyListeners(List<AttentionItem> items) {
        var proxy = ServerMain.getProxyServer();
        if (proxy == null) return;
        if (proxy.getHttpState() == ProxyServer.State.FAILED) {
            items.add(item("error", "sitemap",
                copy("proxy_http_listener", "attention_title"),
                literal(proxy.getHttpFailureReason()),
                CmsRoutes.list(ADMIN, "settings")));
        }
        if (proxy.getHttpsState() == ProxyServer.State.FAILED) {
            items.add(item("error", "certificate",
                copy("proxy_https_listener", "attention_title"),
                literal(proxy.getHttpsFailureReason()),
                CmsRoutes.list(ADMIN, "certificates")));
        } else if (proxy.getHttpsState() == ProxyServer.State.RUNNING
                && proxy.getHttpsFailureReason() != null) {
            // Partial mode: passthrough listens but termination failed, so the listener
            // reads healthy while every force_ssl vhost answers 503.
            items.add(item("error", "certificate",
                copy("proxy_https_degraded", "attention_title"),
                literal(proxy.getHttpsFailureReason()),
                CmsRoutes.list(ADMIN, "certificates")));
        }
    }

    /**
     * HTTPS termination is down while force-SSL routes exist: those sites answer plain
     * HTTP with a 503 (the fail-closed force_ssl gate in SiteDispatcher), so the operator
     * must SEE the inert control instead of a checkbox that silently stopped mattering.
     * Public so a test can prove the projection directly, like the instance collectors.
     */
    public static void httpsUnavailableWithForceSsl(List<AttentionItem> items) {
        var proxy = ServerMain.getProxyServer();
        if (proxy == null || proxy.isHttpsTerminationAvailable()
                || proxy.getHttpState() != ProxyServer.State.RUNNING) {
            return;
        }
        List<String> sites = proxy.getDispatcher().forceSslSiteNames();
        boolean globalForce = Boolean.TRUE.equals(
            HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.FORCE_HTTPS));
        boolean anyRoutes = proxy.getDispatcher().getExactRouteCount()
            + proxy.getDispatcher().getWildcardRouteCount()
            + proxy.getDispatcher().getRegexRouteCount() > 0;
        if (sites.isEmpty() && !(globalForce && anyRoutes)) {
            return;
        }
        items.add(item("error", "certificate",
            copy("https_unavailable", "attention_title"),
            copy("https_unavailable", "attention_detail",
                "sites", sites.isEmpty() ? "-" : String.join(", ", sites)),
            CmsRoutes.list(ADMIN, "certificates")));
    }

    /**
     * Certificates whose last renewal failed, linked to their detail page.
     *
     * PUBLIC for the reason the note above gives: a test proves this projection directly,
     * positive and negative, instead of loading the whole dashboard twice to watch an item
     * appear and then disappear.
     */
    public static void errorCertificates(List<AttentionItem> items) {
        List<Row> rows = Models.get(CertificateModel.class).find()
            .where(CertificateModel.STATUS.eq(CertificateModel.STATUS_ERROR))
            .all();
        for (Row row : rows) {
            items.add(item("error", "certificate",
                copy("certificate", "attention_title", "name", row.get(CertificateModel.NICE_NAME)),
                literal(row.get(CertificateModel.RENEWAL_ERROR)),
                CmsRoutes.detail(ADMIN, "certificates", row.get(CertificateModel.ID))));
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
                    CmsRoutes.detail(ADMIN, "sites", siteId)));
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
                CmsRoutes.detail(ADMIN, "databases", row.get(DatabaseModel.ID))));
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
        var linkModel = Models.get(InstanceDatabaseModel.class);
        if (linkModel == null) {
            return;
        }
        List<Row> links = linkModel.find().all();
        if (links.isEmpty()) {
            return;
        }
        var instanceModel = Models.get(InstanceModel.class);
        var databaseModel = Models.get(DatabaseModel.class);
        DatabaseService databases = new DatabaseService();
        for (Row link : links) {
            Row instance = instanceModel.find()
                .where(InstanceModel.ID.eq(link.get(InstanceDatabaseModel.INSTANCE_ID)))
                .where(InstanceModel.DELETED_AT.isNull())
                .first();
            if (instance == null) {
                continue;
            }
            Row database = databaseModel.find()
                .where(DatabaseModel.ID.eq(link.get(InstanceDatabaseModel.DATABASE_ID)))
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
                boolean workloadDead = live != null && live.workloadDead();
                unavailable = live == null || !live.running() || workloadDead;
                boolean unreachable = live == null
                    || live.containerState() == ContainerState.UNREACHABLE;
                // "Gone/stopped", "the daemon could not be asked" and "the container runs
                // but the engine inside it was OOM-killed" are three different operator
                // problems; conflating the first two was the C6 status defect, and
                // reporting the third as healthy was its instance-tier twin.
                String key = workloadDead ? "database_workload_dead"
                    : unreachable ? "database_unreachable" : "database_not_running";
                detail = copy(key, "attention_detail",
                    "name", database.get(DatabaseModel.NAME));
            }
            if (unavailable) {
                items.add(item("warning", "database",
                    copy("instance", "attention_title",
                        "name", instance.get(InstanceModel.NAME)),
                    detail,
                    CmsRoutes.subpage(ADMIN, "instances", instance.get(InstanceModel.ID),
                        "databases")));
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

    /**
     * The newest release operation of every application, when it FAILED.
     *
     * AIDEV-NOTE: this used to read the {@code deployments} table of the deleted host-slot
     * lane. The release engine's own {@code release_operations} row IS the deploy history
     * now -- one record of what was attempted, with its step log -- so there is no second
     * table to keep in step with it.
     */
    private static void failedDeployments(List<AttentionItem> items) {
        var instanceModel = Models.get(InstanceModel.class);
        var operations = Models.get(ReleaseOperationModel.class);
        if (instanceModel == null || operations == null) {
            return;
        }
        for (Row application : instanceModel.find()
                .where(InstanceModel.KIND.eq(ApplicationKind.ID.toString()))
                .where(InstanceModel.DELETED_AT.isNull())
                .all()) {
            Integer applicationId = application.get(InstanceModel.ID);
            if (applicationId == null) {
                continue;
            }
            List<Row> latest = operations.findForOwner(InstanceModel.MODEL_ID.toString(),
                applicationId, 1);
            if (latest.isEmpty()) {
                continue;
            }
            Row operation = latest.get(0);
            if (ReleaseOperationModel.STATUS_FAILED.equals(
                    operation.get(ReleaseOperationModel.STATUS))) {
                items.add(item("error", "rocket",
                    copy("deploy", "attention_title",
                        "name", application.get(InstanceModel.NAME)),
                    literal(operation.get(ReleaseOperationModel.FAILURE_REASON)),
                    CmsRoutes.subpage(ADMIN, "instances", applicationId, "deployments")));
            }
        }
    }

    /** Latest history row per DECLARED task type; failed ones surface (no task UI yet, so no url).
     *  Public for the same reason the instance collectors are: a test proves the projection. */
    public static void failedTasks(List<AttentionItem> items) {
        // The task system registers its datasource-scoped model at its own boot
        // stage; a boot without it (tests, tools) simply has no task news.
        if (Models.get(SystemTaskHistoryModel.MODEL_ID) == null) {
            return;
        }
        // AIDEV-NOTE: per-type newest via the catalog, never one recent-N window across
        // ALL types -- with several frequent cron tasks a 200-row window was a few HOURS
        // deep, so a nightly task's 02:30 failure had scrolled out of it by mid-morning
        // and the dashboard went green while the task stayed broken. A type is judged by
        // ITS OWN newest row; a type with no history yet has no news.
        var history = Models.get(SystemTaskHistoryModel.class);
        for (TaskDescriptor descriptor : TaskCatalog.all()) {
            List<Row> latest = history.findRecentForType(descriptor.typePath(), 1);
            if (latest.isEmpty()) {
                continue;
            }
            if (TaskStatus.FAILED.name().equals(
                    latest.get(0).get(SystemTaskHistoryModel.STATUS))) {
                items.add(item("warning", "clock",
                    copy("task", "attention_title", "name", descriptor.typePath()),
                    copy("last_run_failed", "attention_detail"),
                    null));
            }
        }
    }

    private static @NonNull AttentionItem item(String severity, String icon, Microcopy title,
                                               @Nullable Microcopy detail,
                                               @Nullable RouteTarget target) {
        return new AttentionItem(severity, icon, title, detail, target);
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
