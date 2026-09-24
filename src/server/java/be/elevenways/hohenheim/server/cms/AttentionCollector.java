package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.AttentionSeverity;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.dns.DelegationVerdict;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.DnsZonePeerModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.ReconcileFindingModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.server.HohenheimRoles.Role;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.database.ControlPlaneBackups;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.docker.DockerHealth;
import be.elevenways.hohenheim.server.docker.DockerReconciler;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.security.SshAuthWatcher;
import be.elevenways.hohenheim.server.task.BackupControlPlane;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.query.rules.Rule;
import be.elevenways.zenit.common.orm.query.rules.RuleGroup;
import be.elevenways.zenit.common.orm.query.rules.RuleOperator;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.server.page.SettingsPage;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.orm.query.rules.RuleText;
import be.elevenways.zenit.common.routing.ParameterDefinition;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.task.TaskCatalog;
import be.elevenways.zenit.common.task.TaskDescriptor;
import be.elevenways.zenit.common.task.TaskStatus;
import be.elevenways.zenit.common.task.orm.SystemTaskHistoryModel;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static be.elevenways.hohenheim.server.cms.AttentionItems.copy;
import static be.elevenways.hohenheim.server.cms.AttentionItems.item;

/**
 * THE entry point of the dashboard attention items: gates each role's collector on the role
 * that runs what it watches, and owns the role-free ones (task runs, the control-plane backup)
 * and the foreign-resource row.
 *
 * AIDEV-NOTE: NOTHING reached from here dials a daemon or a host. Every projection reads
 * stored rows, a boot probe's recorded answer or in-memory runtime state, so rendering the
 * dashboard costs queries and never an SSH/HTTPS round trip per workload; reachability and
 * liveness are observed by their own scheduled sweeps (InstanceStatusReconciler, the host
 * probe) and read back here. The per-role collectors are ProxyAttention, DatabaseAttention,
 * HostAttention, InstanceAttention, DnsAttention and FirewallAttention.
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
public final class AttentionCollector {

    /**
     * Every attention item points into the OPERATOR panel: this widget is an
     * installation-health surface, so its links keep the panel slug they always had.
     */
    private static final String ADMIN = HohenheimSlugs.ADMIN;

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
            ProxyAttention.errorCertificates(items);
            ProxyAttention.failedProxyListeners(items);
            ProxyAttention.httpsUnavailableWithForceSsl(items);
            ProxyAttention.unhealthySites(items);
            ProxyAttention.routingProblems(items);
            InstanceAttention.failedDeployments(items);
        }
        if (HohenheimRoles.enabled(Role.DATABASES)) {
            DatabaseAttention.failedDatabases(items);
            DatabaseAttention.unavailableAttachedDatabases(items);
        }
        if (HohenheimRoles.dockerRequired()) {
            AttentionItem daemon = HostAttention.dockerUnreachable(DockerHealth.instance());
            if (daemon != null) {
                items.add(daemon);
            }
        }
        if (HohenheimRoles.hostWorkloadsEnabled()) {
            // Stored reconciler findings only -- the sweep itself is a scheduled
            // task, never a per-render daemon probe.
            //
            // AIDEV-NOTE: gated on the WORKLOAD tiers, never on PROXY. Findings and
            // parked port claims are produced by what runs ON a host and they link to
            // the Reconcile findings list, which HohenheimPanel puts behind the very
            // same gate -- so a proxy-only node used to render Docker findings whose
            // link 404s and which no enabled role could ever act on.
            items.addAll(DockerReconciler.attentionItems());
            dockerForeignResources(items);
            HostAttention.stuckReleasingPorts(items,
                Now.instant().minus(HostAttention.RELEASING_STUCK_AFTER));
        }
        failedTasks(items);
        controlPlaneBackupDestination(items);
        if (HohenheimRoles.enabled(Role.DNS)) {
            DnsAttention.dnsIssues(items);
        }
        if (HohenheimRoles.enabled(Role.FIREWALL)) {
            FirewallAttention.spamserviceIssue(items);
            AttentionItem sshWatch = FirewallAttention.sshWatchIssue(SshAuthWatcher.INSTANCE.snapshot());
            if (sshWatch != null) {
                items.add(sshWatch);
            }
        }
        if (HohenheimRoles.hostWorkloadsEnabled()) {
            HostAttention.hostsNotAdmitted(items);
        }
        if (HohenheimRoles.enabled(Role.INSTANCES)) {
            InstanceAttention.crashedInstances(items);
            InstanceAttention.failedInstanceBackups(items);
            InstanceAttention.staleInstanceBackups(items);
            InstanceAttention.instancesLowOnDisk(items);
        }
        return items;
    }

    /**
     * Resources on a host that nobody here created, as ONE informational row per host.
     *
     * AIDEV-NOTE: the orphan and collision buckets are warnings and stay the
     * reconciler's; this row exists because a host carrying twenty foreign volumes
     * showed "All clear" while the findings list said otherwise. It is
     * information, never a warning: the reconciler leaves foreign resources alone.
     */
    public static void dockerForeignResources(List<AttentionItem> items) {
        Map<String, Integer> countByServer = new LinkedHashMap<>();
        for (Row row : Models.get(ReconcileFindingModel.class).find()
                .where(ReconcileFindingModel.BUCKET.in(FOREIGN_BUCKETS))
                .all()) {
            countByServer.merge(row.get(ReconcileFindingModel.SERVER_NAME), 1, Integer::sum);
        }
        countByServer.forEach((server, count) -> items.add(item(AttentionSeverity.INFO, "cubes",
            copy("docker_foreign", "attention_title", "server", server),
            copy("docker_foreign", "attention_detail",
                "count", count, "page", ReconcileFindingResource.LABEL),
            foreignFindingsOf(server))));
    }

    /**
     * The buckets the row above counts: the two that mean "not ours, and left alone".
     * {@code foreign_colliding} is deliberately absent -- it is the reconciler's own warning.
     */
    private static final List<String> FOREIGN_BUCKETS = List.of(
        ReconcileFindingModel.BUCKET_FOREIGN_KNOWN, ReconcileFindingModel.BUCKET_FOREIGN_UNRELATED);

    /**
     * The list page's TEXTUAL filter parameter (zenit-cms's {@code q} tier).
     *
     * AIDEV-NOTE: the tier is chosen for what it can EXPRESS. A chip param carries one
     * value per select filter, so two buckets are inexpressible there; {@code adv} carries
     * a whole tree but as opaque base64 the operator cannot read or edit. {@code q} carries
     * the tree as the query builder's own text, which the list page then shows in its query
     * box -- which is why the resource offers that box (see its listChrome).
     */
    private static final ParameterDefinition<String> LIST_QUERY = ParameterDefinition
        .builder(String.class).name("q").stringResolver(value -> value).build();

    /**
     * The findings list narrowed to exactly the rows the item counted.
     *
     * AIDEV-NOTE: the link used to be the bare list, which shows EVERY bucket of EVERY
     * host -- including the owned rows the detail sentence says are not there, and the
     * orphaned ones carrying a DESTRUCTIVE remove action -- so the number on the dashboard
     * was never the number the operator landed on. The tree is built TYPED and printed by
     * RuleText, so the expression is the framework's own grammar rather than a
     * hand-spelled string, and nothing here concatenates a URL.
     */
    private static @NonNull RouteTarget foreignFindingsOf(String server) {
        RuleGroup tree = RuleGroup.and(
            Rule.of(ReconcileFindingModel.SERVER_NAME.getName(), RuleOperator.EQUALS, server),
            Rule.list(ReconcileFindingModel.BUCKET.getName(), RuleOperator.IN, FOREIGN_BUCKETS));
        return CmsRoutes.list(ADMIN, "reconcile-findings").with(LIST_QUERY, RuleText.print(tree));
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
            items.add(item(AttentionSeverity.ERROR, "box-archive",
                copy("control_plane_backup", "attention_title"),
                copy("control_plane_backup", "attention_detail"),
                CmsRoutes.list(ADMIN, SettingsPage.DEFAULT_SLUG)));
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
                || successAt.isBefore(Now.instant().minus(CONTROL_PLANE_BACKUP_STALE_AFTER))) {
            items.add(item(AttentionSeverity.ERROR, "box-archive",
                copy("control_plane_backup_stale", "attention_title"),
                copy("control_plane_backup_stale", "attention_detail",
                    "hours", CONTROL_PLANE_BACKUP_STALE_AFTER.toHours()),
                CmsRoutes.list(ADMIN, SettingsPage.DEFAULT_SLUG)));
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
                items.add(item(AttentionSeverity.WARNING, "clock",
                    copy("task", "attention_title", "name", descriptor.typePath()),
                    copy("last_run_failed", "attention_detail"),
                    null));
            }
        }
    }
}
