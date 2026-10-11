package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimWidgets;
import be.elevenways.zenit.widget.common.WidgetInstance;
import be.elevenways.zenit.widget.common.WidgetTree;
import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.AttentionSeverity;
import be.elevenways.hohenheim.AttentionSubject;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.OnboardingStage;
import be.elevenways.hohenheim.model.ReconcileFindingModel;
import be.elevenways.hohenheim.server.HohenheimRoles.Role;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.database.ControlPlaneBackups;
import be.elevenways.hohenheim.server.docker.DockerHealth;
import be.elevenways.hohenheim.server.files.HohenheimSftp;
import be.elevenways.hohenheim.server.security.BanService;
import be.elevenways.hohenheim.server.security.SshAuthWatcher;
import be.elevenways.hohenheim.server.task.BackupControlPlane;
import be.elevenways.hohenheim.server.task.VerifyWorkloadIsolation;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.common.typed.CoreTypes;
import be.elevenways.protoblast.common.typed.rule.Condition;
import be.elevenways.protoblast.common.typed.rule.Operand;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.server.page.SettingsPage;
import be.elevenways.zenit.cms.server.task.TaskAdmin;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.orm.query.rules.RuleText;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.task.TaskCatalog;
import be.elevenways.zenit.common.task.TaskDescriptor;
import be.elevenways.zenit.common.task.TaskStatus;
import be.elevenways.zenit.common.task.orm.SystemTaskHistoryModel;
import be.elevenways.zenit.server.task.TaskRunErrors;
import be.elevenways.hohenheim.HohenheimViolations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static be.elevenways.hohenheim.server.cms.AttentionItems.byHost;
import static be.elevenways.hohenheim.server.cms.AttentionItems.item;
import static be.elevenways.hohenheim.server.cms.AttentionItems.literal;
import static be.elevenways.hohenheim.HohenheimSlugs.ADMIN;

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
 * HostAttention, InstanceAttention, StackAttention, DnsAttention and FirewallAttention.
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
public final class AttentionCollector {

    /**
     * Every attention item points into the OPERATOR panel: this widget is an
     * installation-health surface, so its links keep the panel slug they always had.
     */

    /**
     * The settings anchor of the group holding the control-plane backup target (the Backups section's database group).
     */
    static final String CONTROL_PLANE_BACKUP_SECTION =
        HohenheimSettingsSections.BACKUPS.anchorOf(HohenheimSettings.Database.GROUP);

    private AttentionCollector() {}

    /**
     * Every collector is gated on the role that runs the thing it watches: a
     * DNS appliance must not claim to be watching stacks or certificates it
     * does not run. Task history stays ungated -- only role-enabled tasks
     * declare schedules, so its content is already role-shaped.
     */
    public static @NonNull List<AttentionItem> collect() {
        List<AttentionItem> items = new ArrayList<>();
        // What each workload or address keeps from its sites' visitors, read once: a root item says it.
        Map<AttentionSubject, Integer> sitesHeld =
            HohenheimRoles.enabled(Role.PROXY) || HohenheimRoles.enabled(Role.INSTANCES)
                ? AppHealth.sitesHeldBack() : Map.of();
        if (HohenheimRoles.enabled(Role.PROXY)) {
            ProxyAttention.errorCertificates(items);
            ProxyAttention.expiringCertificates(items);
            ProxyAttention.failedProxyListeners(items);
            ProxyAttention.httpsUnavailableWithForceSsl(items);
            ProxyAttention.forcedWithoutCertificate(items);
            ProxyAttention.openProtectedPaths(items);
            ProxyAttention.unhealthySites(items);
            ProxyAttention.routingProblems(items);
            InstanceAttention.failedDeployments(items, sitesHeld);
        }
        items.addAll(databases());
        items.addAll(hosts());
        if (HohenheimRoles.hostWorkloadsEnabled()) {
            // Stored reconciler findings only -- the sweep itself is a scheduled
            // task, never a per-render daemon probe.
            //
            // AIDEV-NOTE: gated on the WORKLOAD tiers, never on PROXY. Findings and
            // parked port claims are produced by what runs ON a host and they link to
            // the Reconcile findings list, which HohenheimPanel puts behind the very
            // same gate -- so a proxy-only node used to render Docker findings whose
            // link 404s and which no enabled role could ever act on.
            dockerFindings(items);
            dockerForeignResources(items);
        }
        failedTasks(items);
        controlPlaneBackupDestination(items);
        sftpServer(items);
        if (HohenheimRoles.enabled(Role.DNS)) {
            DnsAttention.dnsIssues(items);
        }
        if (HohenheimRoles.enabled(Role.STACKS)) {
            StackAttention.unhealthyStacks(items);
        }
        if (HohenheimRoles.enabled(Role.FIREWALL)) {
            FirewallAttention.spamserviceIssue(items);
            AttentionItem budget = FirewallAttention.autoBanBudget(BanService.INSTANCE.autoBanBudget());
            if (budget != null) {
                items.add(budget);
            }
            AttentionItem sshWatch = FirewallAttention.sshWatchIssue(SshAuthWatcher.INSTANCE.snapshot());
            if (sshWatch != null) {
                items.add(sshWatch);
            }
        }
        if (HohenheimRoles.enabled(Role.INSTANCES)) {
            InstanceAttention.crashedInstances(items, sitesHeld);
            InstanceAttention.failedInstanceBackups(items);
            InstanceAttention.staleInstanceBackups(items);
            InstanceAttention.instancesLowOnDisk(items);
        }
        return items;
    }

    /**
     * SFTP was turned on but its server is not running: nobody can connect while every Files tab says how to.
     *
     * AIDEV-NOTE: role-free like the server itself (HohenheimSftp); an install that never turned SFTP on gets no
     * row, the same rule as the ssh watcher's item.
     */
    public static void sftpServer(@NonNull List<AttentionItem> items) {
        String failure = HohenheimSftp.failure();
        if (failure == null || !HohenheimSettings.isOn(HohenheimSettings.Sftp.ENABLED)) {
            return;
        }
        items.add(item(AttentionSeverity.WARNING, "folder-tree", HohenheimMicrocopy.ATTENTION_TITLE.of("sftp_server"),
            literal(failure), sftpSettingsTarget(), HohenheimMicrocopy.ATTENTION_ACTION.of("act_open_settings")));
    }

    /** @return the settings page opened on the SFTP group, where the operator turns it on or fixes it */
    public static @NonNull RouteTarget sftpSettingsTarget() {
        return CmsRoutes.settingsAnchor(ADMIN, SettingsPage.DEFAULT_SLUG,
            HohenheimSettingsSections.APPS.anchorOf(HohenheimSettings.Sftp.GROUP));
    }

    /**
     * @return the settings page opened on the security group: per-workload firewall enforcement and the addresses
     *         never blocked
     */
    static @NonNull RouteTarget securitySettingsTarget() {
        return CmsRoutes.settingsAnchor(ADMIN, SettingsPage.DEFAULT_SLUG,
            HohenheimSettingsSections.BLOCKING.anchorOf(HohenheimSettings.Security.GROUP));
    }

    /** The managed-database tier's items, which the Databases list also leads with. */
    public static @NonNull List<AttentionItem> databases() {
        List<AttentionItem> items = new ArrayList<>();
        if (HohenheimRoles.enabled(Role.DATABASES)) {
            DatabaseAttention.failedDatabases(items);
            DatabaseAttention.moveLeftovers(items);
            DatabaseAttention.unavailableAttachedDatabases(items);
        }
        return items;
    }

    /** The host tier's items (the local daemon, admission, parked ports), which the Hosts list also leads with. */
    public static @NonNull List<AttentionItem> hosts() {
        List<AttentionItem> items = new ArrayList<>();
        if (HohenheimRoles.dockerRequired()) {
            AttentionItem daemon = HostAttention.dockerUnreachable(DockerHealth.instance());
            if (daemon != null) {
                items.add(daemon);
            }
        }
        if (HohenheimRoles.hostWorkloadsEnabled()) {
            HostAttention.hostsTakingNoApps(items, AppHealth.heldBackByHost());
            HostAttention.isolationUnenforced(items);
            HostAttention.stuckReleasingPorts(items, Now.instant().minus(HostAttention.RELEASING_STUCK_AFTER));
        }
        return items;
    }

    /** One list's own attention band, the dashboard's widget over that tier's items; nothing when all is well. */
    public static @NonNull WidgetTree band(@NonNull List<AttentionItem> items) {
        return items.isEmpty() ? new WidgetTree(List.of())
            : new WidgetTree(List.of(AdminDashboard.section(
                new WidgetInstance(HohenheimWidgets.ATTENTION.id(), Map.of()).withData(items))));
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
            HohenheimMicrocopy.ATTENTION_TITLE.of("docker_foreign").withArg("server", server),
            HohenheimMicrocopy.ATTENTION_DETAIL.of("docker_foreign").withArg("count", count)
                .withArg("page", ReconcileFindingParts.LABEL),
            findingsOf(server, FOREIGN_BUCKETS),
            HohenheimMicrocopy.ATTENTION_ACTION.of("act_review_findings"))));
    }

    /** How many resource names a findings item spells out before eliding. */
    private static final int FINDING_NAME_CAP = 3;

    /**
     * The reconciler's stored warnings, per host: one item for orphaned resources (attributed to us, record gone:
     * volumes here are unreclaimed data) and one for name collisions (a same-named foreign resource is what the
     * legacy replace paths would destroy), each leading to exactly the findings it counts. Foreign-known and owned
     * rows never surface here.
     */
    public static void dockerFindings(@NonNull List<AttentionItem> items) {
        dockerBucket(items, ReconcileFindingModel.BUCKET_ORPHANED, "docker_orphans");
        dockerBucket(items, ReconcileFindingModel.BUCKET_FOREIGN_COLLIDING, "docker_colliding");
    }

    private static void dockerBucket(@NonNull List<AttentionItem> items, @NonNull String bucket, @NonNull String key) {
        byHost(Models.get(ReconcileFindingModel.class).find().where(ReconcileFindingModel.BUCKET.eq(bucket)).all(),
            row -> row.get(ReconcileFindingModel.SERVER_NAME),
            row -> row.get(ReconcileFindingModel.KIND) + " " + row.get(ReconcileFindingModel.RESOURCE_NAME))
            .forEach((server, names) -> {
                String listed = String.join(", ", names.subList(0, Math.min(names.size(), FINDING_NAME_CAP)))
                    + (names.size() > FINDING_NAME_CAP ? ", ..." : "");
                items.add(item(AttentionSeverity.WARNING, "cubes",
                    HohenheimMicrocopy.ATTENTION_TITLE.of(key).withArg("server", server),
                    HohenheimMicrocopy.ATTENTION_DETAIL.of(key).withArg("count", names.size()).withArg("names", listed),
                    findingsOf(server, List.of(bucket)),
                    HohenheimMicrocopy.ATTENTION_ACTION.of("act_review_findings")));
            });
    }

    /**
     * The buckets the row above counts: the two that mean "not ours, and left alone".
     * {@code foreign_colliding} is deliberately absent -- it is the reconciler's own warning.
     */
    private static final List<String> FOREIGN_BUCKETS = List.of(
        ReconcileFindingModel.BUCKET_FOREIGN_KNOWN, ReconcileFindingModel.BUCKET_FOREIGN_UNRELATED);

    /**
     * The findings list narrowed to exactly the rows the item counted.
     *
     * AIDEV-NOTE: the link used to be the bare list, which shows EVERY bucket of EVERY
     * host -- including the owned rows the detail sentence says are not there, and the
     * orphaned ones carrying a DESTRUCTIVE remove action -- so the number on the dashboard
     * was never the number the operator landed on. The tree is built TYPED and printed by
     * RuleText, so the expression is the framework's own grammar rather than a
     * hand-spelled string, and nothing here concatenates a URL. It rides the list state's
     * TYPED rule text (zenit-cms's query box): two buckets are one {@code IN} test there,
     * readable and editable by the operator.
     */
    private static @NonNull RouteTarget findingsOf(@NonNull String server, @NonNull List<String> buckets) {
        Condition tree = Condition.all(
            Condition.test(ReconcileFindingModel.SERVER_NAME.getName(), CoreTypes.EQUALS, Operand.of(server)),
            Condition.test(ReconcileFindingModel.BUCKET.getName(), CoreTypes.IN, Operand.of(buckets)));
        return CmsRoutes.list(ADMIN, HohenheimSlugs.RECONCILE_FINDINGS)
            .with(CmsEndpoints.LIST_QUERY_PARAM, RuleText.print(tree));
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
                HohenheimMicrocopy.ATTENTION_TITLE.of("control_plane_backup"),
                HohenheimMicrocopy.ATTENTION_DETAIL.of("control_plane_backup"),
                controlPlaneBackupTarget(),
                HohenheimMicrocopy.ATTENTION_ACTION.of("act_choose_backup_target")).forStage(OnboardingStage.BACKUPS));
            return;
        }
        controlPlaneBackupFreshness(items);
    }

    /** Where the control-plane backup's destination is chosen: its settings group, scrolled to. */
    static @NonNull RouteTarget controlPlaneBackupTarget() {
        return CmsRoutes.settingsSection(ADMIN, SettingsPage.DEFAULT_SLUG, CONTROL_PLANE_BACKUP_SECTION)
            .withFragment(CONTROL_PLANE_BACKUP_SECTION);
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
        String typePath = BackupControlPlane.ID.toString();
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
                HohenheimMicrocopy.ATTENTION_TITLE.of("control_plane_backup_stale"),
                HohenheimMicrocopy.ATTENTION_DETAIL.of("control_plane_backup_stale")
                    .withArg("hours", CONTROL_PLANE_BACKUP_STALE_AFTER.toHours()),
                CmsRoutes.list(ADMIN, SettingsPage.DEFAULT_SLUG),
                HohenheimMicrocopy.ATTENTION_ACTION.of("act_open_settings")));
        }
    }

    /**
     * Latest history row per DECLARED task type; a failed one surfaces by the task's worded name, with why it failed
     * and the way to that run, whose page offers Run now. Public for the same reason the instance collectors are: a
     * test proves the projection.
     */
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
            Row run = latest.get(0);
            if (TaskStatus.FAILED.name().equals(run.get(SystemTaskHistoryModel.STATUS))) {
                Microcopy reason = failureOf(run.get(SystemTaskHistoryModel.ERROR));
                // The isolation sweep cannot check a host whose firewall rules are switched off: that host's item
                // is the root and this run's failure folds under it.
                items.add(item(AttentionSeverity.WARNING, "clock",
                    HohenheimMicrocopy.ATTENTION_TITLE.of("task_failed").withArg("task", descriptor.label()),
                    reason != null ? reason : HohenheimMicrocopy.ATTENTION_DETAIL.of("last_run_failed"),
                    CmsRoutes.open(ADMIN, TaskAdmin.RUNS_SLUG, run.get(SystemTaskHistoryModel.ID)),
                    HohenheimMicrocopy.ATTENTION_ACTION.of("act_show_run"))
                    .causedBy(VerifyWorkloadIsolation.ID.toString().equals(descriptor.typePath())
                        && HohenheimRoles.hostWorkloadsEnabled() ? HostAttention.isolationRootOfSweep() : null));
            }
        }
    }

    /**
     * Why a run failed, in the words its failure carried: core's message without the exception or stack trace
     * ({@link TaskRunErrors#message}), a stored refusal read back in its own words.
     *
     * @return the reason, null when the run stored none
     */
    private static @Nullable Microcopy failureOf(@Nullable String stored) {
        String message = TaskRunErrors.message(stored);
        return message == null ? null : Microcopy.literal(HohenheimViolations.storedText(message));
    }
}
