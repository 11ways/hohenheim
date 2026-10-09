package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimWidgets;
import be.elevenways.hohenheim.instance.InstanceDiskView;
import be.elevenways.hohenheim.instance.InstanceEndpointView;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceCapacity;
import be.elevenways.hohenheim.server.instance.InstanceStats;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.MessageResolver;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.time.RelativeTime;
import be.elevenways.protoblast.common.time.RelativeTimeWording;
import be.elevenways.zenit.auth.server.GrantAdministration;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.resource.RecordOverview;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.KnownCapabilities;
import be.elevenways.zenit.common.security.KnownCapability;
import be.elevenways.zenit.common.text.ByteText;
import be.elevenways.zenit.widget.common.WidgetInstance;
import be.elevenways.zenit.widget.common.WidgetTree;
import be.elevenways.zenit.widget.common.builtin.AlertVariant;
import be.elevenways.zenit.widget.common.builtin.AlertWidget;
import be.elevenways.zenit.widget.common.builtin.CardWidget;
import be.elevenways.zenit.widget.common.builtin.FactListWidget;
import be.elevenways.zenit.widget.common.data.NoticeData;
import be.elevenways.zenit.widget.common.data.UsageData;
import be.elevenways.zenit.widget.common.data.WidgetBadge;
import be.elevenways.zenit.widget.common.data.WidgetFact;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Overview tab on an instance, and the record's own front door: the app composition ({@link AppOverview}) over the
 * workload, with the STORED disk observation and the public endpoint resolved out of the port ledger. The verdict
 * above it (running, cannot start, stopped after an error) is the resource's health part ({@link AppHealth}).
 *
 * AIDEV-NOTE: the delegated projection is applied FIELD BY FIELD in {@link #widgets},
 * not by trusting the entry: this is the SAME tab on both panels (both instance entries
 * declare it, {@link InstanceParts}), so the omissions are here or nowhere. See the
 * AIDEV-NOTEs at each censored card for what is dropped and why.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class InstanceOverview {

    public static final String SLUG = RecordOverview.SLUG;

    private InstanceOverview() {
    }

    /** The tab both instance entries declare; loading it reads the stored evidence afresh. */
    static @NonNull RecordOverview<Row> tab() {
        return RecordOverview.<Row>fields(SLUG, Microcopy.of("overview").withFilter("scope", "instance"))
            .withoutFields()
            .widgets(InstanceOverview::widgets);
    }

    private static @NonNull WidgetTree widgets(@NonNull Row instance, @NonNull AccessContext accessContext) {
        Conduit conduit = accessContext.conduit();
        Integer instanceId = instance.get(InstanceModel.ID);
        int serverId = ServerModel.canonicalServerId(instance.get(InstanceModel.SERVER_ID));
        String panelSlug = CmsSupport.panelSlug(conduit);
        boolean delegated = CmsSupport.isDelegatedPanel(conduit);
        LocaleChain locales = conduit.getLocales();
        MessageResolver resolver = conduit.getMessageResolver();

        // Why the instance cannot start, whether it runs and what it is live at are the resource's health verdict
        // (AppHealth), drawn by the framework as the page's first band; this tree is what follows it.
        // The record's actions are its heading's (zenitcms:record-head).
        List<WidgetInstance> top = new ArrayList<>();

        // AIDEV-NOTE: install_error is stamped with the daemon's or transport's OWN text
        // (InstanceInstalls stamps describe(IOException) and "exit N" plus the script's
        // output tail), so it names image registries, socket paths, host paths and ssh
        // failures. The health band already tells the tenant the install failed, which
        // is the fact they can act on; the operator reads the reason here.
        String installError = instance.get(InstanceModel.INSTALL_ERROR);
        if (!delegated && installError != null && !installError.isBlank()) {
            top.add(new WidgetInstance(AlertWidget.ID, Map.of("variant", AlertVariant.DESTRUCTIVE.token()))
                .withData(NoticeData.of(text("install_error", "instance_overview", locales, resolver), installError)));
        }

        // The sites whose hostname serves THIS instance (the sites.instance_id reverse lookup the column exists
        // for): their names are the addresses the workload answers on.
        List<Row> sites = Models.get(SiteModel.class).find().where(SiteModel.INSTANCE_ID.eq(instanceId)).all();
        List<WidgetInstance> main = new ArrayList<>();
        main.add(AppOverview.addresses(sites, accessContext));
        WidgetInstance protection = AppOverview.protection(sites, accessContext);
        if (protection != null) {
            main.add(protection);
        }
        main.add(new WidgetInstance(CardWidget.ID, Map.of(
                "title", Microcopy.of("endpoint").withFilter("scope", "instance_overview"),
                "lead", Microcopy.of("endpoint_hint").withFilter("scope", "instance_overview")),
            new WidgetTree(List.of(new WidgetInstance(HohenheimWidgets.INSTANCE_ENDPOINTS.id(), Map.of())
                .withData(endpointsOf(instanceId))))));
        // Board App-Overview: memory, disk, CPU. Memory and CPU are the live stats hub's held samples, read while
        // someone watches the Metrics tab; never a stream opened by this render.
        List<UsageData> live = liveUsage(instance, InstanceStats.history(instanceId), locales, resolver);
        main.add(AppOverview.resources(List.of(
            AppOverview.gauge(Microcopy.of("memory").withFilter("scope", "instance_overview"), live.get(0)),
            AppOverview.gauge(Microcopy.of("disk").withFilter("scope", "instance_overview"),
                diskUsage(instance, serverId, locales, resolver)),
            AppOverview.gauge(Microcopy.of("cpu").withFilter("scope", "instance_overview"), live.get(1)))));

        List<WidgetInstance> side = new ArrayList<>();
        side.add(AppOverview.details(facts(instance, serverId, panelSlug, delegated, locales, resolver)));
        // AIDEV-NOTE: the per-record RECENT ACTIVITY card. It was blocked until zenit-cms's
        // `zenit.activity` source started PROJECTING record_id: a source's rule vocabulary is
        // derived from its projection, so `ActivityRules.forRecord` failed validation with
        // `unknown_variable: record_id` and every render 500'd.
        //
        // AIDEV-NOTE: OPERATOR-ONLY, and deliberately by omission rather than by an empty
        // list. The shared source is registered with HohenheimSources.ADMIN_ACCESS
        // (HohenheimSources.register -> ActivitySources.register("admin", ADMIN_ACCESS)), so
        // a tenant fails the source's own gate and would be shown a card that is always empty --
        // a censoring that looks like a bug. Widening the audience is a decision about the AUDIT
        // LOG, not about this page: the log carries every operator's actions on every record.
        if (!delegated) {
            side.add(AppOverview.recent(Models.get(InstanceModel.class), instanceId));
        } else {
            side.add(yourPart(instanceId, accessContext, locales, resolver));
        }
        return AppOverview.compose(top, main, side);
    }

    /**
     * The Details card: the stored status with how old that claim is, the kind, and (for the operator) the host.
     *
     * AIDEV-NOTE: the host is operator inventory, and BOTH halves leak it -- the name is the machine's identity and
     * the link carries its numeric server id, which is the id every host-scoped admin route is keyed on. A tenant is
     * told WHAT their workload is doing, never WHERE it runs; the censoring is the fact simply NOT BEING ADDED.
     */
    private static @NonNull List<WidgetFact> facts(@NonNull Row instance, int serverId, @NonNull String panelSlug,
                                                   boolean delegated, @NonNull LocaleChain locales,
                                                   @Nullable MessageResolver resolver) {
        List<WidgetFact> facts = new ArrayList<>();
        facts.add(WidgetFact.badge(AppOverview.text("state", locales, resolver),
            WidgetBadge.of(InstanceModel.STATUS, instance.get(InstanceModel.STATUS), locales, resolver)));
        Object kind = instance.get(InstanceModel.KIND);
        if (kind != null) {
            facts.add(WidgetFact.badge(AppOverview.text("kind", locales, resolver),
                WidgetBadge.of(InstanceModel.KIND, kind, locales, resolver)));
        }
        // The install line only while there IS an install lifecycle (InstanceModel.isNotableInstallState); "no
        // install step" on every record is the clutter this card exists to avoid.
        Object installState = instance.get(InstanceModel.INSTALL_STATE);
        if (InstanceModel.isNotableInstallState(installState)) {
            facts.add(WidgetFact.badge(AppOverview.text("install", locales, resolver),
                WidgetBadge.of(InstanceModel.INSTALL_STATE, installState, locales, resolver)));
        }
        // The badge above renders a STORED column, and a stored column is a claim about
        // the past. This says how old that claim is, from the only write that means "a
        // runtime actually answered" (InstanceStatusReconciler). It is a stored fact too,
        // deliberately: reading it costs nothing, while dialling the daemon per render is
        // the thing the disk gauge already refuses to do.
        facts.add(statusConfirmation(instance, locales, resolver));
        // How long it has run (board App-Overview's "Running for 2 hours"): since its last start.
        Instant started = InstanceModel.STATUS_RUNNING.equals(instance.get(InstanceModel.STATUS))
            ? lastStartOf(instance.get(InstanceModel.ID)) : null;
        if (started != null) {
            facts.add(WidgetFact.instant(text("started", "instance_overview", locales, resolver), started.toString()));
        }
        if (!delegated) {
            facts.add(WidgetFact.link(
                text("host", "instance_overview", locales, resolver),
                ServerModel.nameOf(serverId),
                CmsRoutes.subpage(panelSlug, "servers", serverId, ServerOverviewState.SLUG).toUrl()));
        }
        facts.addAll(databaseFacts(instance.get(InstanceModel.ID), panelSlug, delegated, locales, resolver));
        facts.add(backupFact(instance, locales, resolver));
        return facts;
    }

    /**
     * When this workload last started: the newest row of {@link HohenheimActivityAction#DEPLOYED}, which every start of
     * a container records (InstanceService's deploy, an application's release), unless a start that failed came after
     * it; null when the activity log holds none (switched off, pruned), and then the card says only that it runs.
     *
     * AIDEV-NOTE: a restart the container runtime does on its own (Docker's restart policy) writes no row, so this is
     * the last start Hohenheim made; no stored column or daemon call says more, and this page dials no daemon. A newer
     * failed start (a cause whose {@link HohenheimActivityAction#errorPhase()} is START) means the start this row
     * records is not what runs now, so the card names no start rather than an old one beside "could not be started"
     * (D13b's shop read "Started 2 hours ago" under a minute-old failed start).
     */
    static @Nullable Instant lastStartOf(int instanceId) {
        if (Models.get(ActivityModel.MODEL_ID) == null) {
            return null;
        }
        List<String> outcomes = new ArrayList<>();
        for (HohenheimActivityAction action : HohenheimActivityAction.values()) {
            if (action == HohenheimActivityAction.DEPLOYED || (action.errorCause().isCause()
                    && action.errorPhase() == HohenheimActivityAction.ErrorPhase.START)) {
                outcomes.add(action.id().toString());
            }
        }
        Row row = Models.get(ActivityModel.class).find()
            .where(ActivityModel.MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(ActivityModel.RECORD_ID.eq(String.valueOf(instanceId)))
            .where(ActivityModel.ACTION.in(outcomes))
            .orderBy(ActivityModel.ID, SortOrder.DESC)
            .first();
        return row == null || !HohenheimActivityAction.DEPLOYED.id().toString().equals(row.get(ActivityModel.ACTION))
            ? null : row.get(ActivityModel.CREATED_AT);
    }

    /**
     * One Database line per managed database the workload uses (board App-Overview: "shop (MySQL)"), with the
     * database's own state when it does not serve ({@link DatabaseVerdict}), linked to its page for the operator.
     */
    private static @NonNull List<WidgetFact> databaseFacts(int instanceId, @NonNull String panelSlug,
                                                           boolean delegated, @NonNull LocaleChain locales,
                                                           @Nullable MessageResolver resolver) {
        List<WidgetFact> facts = new ArrayList<>();
        String label = text("database", "instance_overview", locales, resolver);
        for (Row link : Models.get(InstanceDatabaseModel.class).find()
                .where(InstanceDatabaseModel.INSTANCE_ID.eq(instanceId)).all()) {
            Integer databaseId = link.get(InstanceDatabaseModel.DATABASE_ID);
            Row database = databaseId == null ? null : Models.get(DatabaseModel.class).findById(databaseId);
            if (database == null) {
                continue;
            }
            DatabaseVerdict verdict = DatabaseVerdict.ofDatabase(database);
            Microcopy state = verdict.state().label().withFilter("case", "sentence");
            Microcopy value = (verdict.state().serves()
                    ? Microcopy.of("database_value").withFilter("scope", "instance_overview")
                    : Microcopy.of("database_value_state").withFilter("scope", "instance_overview")
                        .withArg("state", state))
                .withArg("name", database.get(DatabaseModel.NAME))
                .withArg("engine", WidgetBadge.of(DatabaseModel.ENGINE, database.get(DatabaseModel.ENGINE), locales,
                    resolver).label());
            String words = value.resolve(locales, resolver);
            facts.add(delegated ? WidgetFact.of(label, words)
                : WidgetFact.link(label, words, CmsRoutes.open(panelSlug, DatabaseParts.SLUG, databaseId).toUrl()));
        }
        return facts;
    }

    /**
     * The Backups line (board App-Overview: "last one 03:00, 212 MB"): its newest backup, failed or made, or that
     * none is made because it has no backup target.
     */
    private static @NonNull WidgetFact backupFact(@NonNull Row instance, @NonNull LocaleChain locales,
                                                  @Nullable MessageResolver resolver) {
        String label = text("backups", "instance_overview", locales, resolver);
        if (instance.get(InstanceModel.BACKUP_TARGET_ID) == null) {
            return WidgetFact.of(label, text("backups_no_target", "instance_overview", locales, resolver));
        }
        Row newest = Models.get(InstanceBackupModel.class).newestOf(instance.get(InstanceModel.ID));
        Instant at = newest == null ? null : newest.get(InstanceBackupModel.CREATED_AT);
        if (newest == null || at == null) {
            return WidgetFact.of(label, text("backups_none_yet", "instance_overview", locales, resolver));
        }
        RelativeTimeWording wording = resolver == null ? null : RelativeTimeWording.resolve(locales, resolver);
        boolean made = InstanceBackupModel.STATUS_COMPLETE.equals(newest.get(InstanceBackupModel.STATUS));
        Long size = newest.get(InstanceBackupModel.SIZE_BYTES);
        Microcopy words = made
            ? Microcopy.of("backups_last").withFilter("scope", "instance_overview")
                .withArg("size", size == null ? "-" : ByteText.human(size))
            : Microcopy.of("backups_last_failed").withFilter("scope", "instance_overview");
        return WidgetFact.of(label, words.withArg("ago", RelativeTime.ago(at, wording)).resolve(locales, resolver));
    }

    // -- live usage ------------------------------------------------------------------

    /**
     * Memory and CPU from the samples the live stats hub holds ({@link InstanceStats#history}), which exist only while
     * someone watches the workload's Metrics tab: a reading when there is one, NOT MEASURED in words when there is
     * not, never a zero.
     *
     * AIDEV-NOTE: never opens a stream (InstanceStats.lastMemoryMb's rule): a render that started a daemon stats
     * stream would be the per-render daemon call this page refuses. Memory is measured against the container's own
     * limit, else what the host booked for it; CPU is the samples' mean (the first, which has no CPU figure, left out)
     * against the cores the daemon reports.
     *
     * @return memory, then CPU
     */
    static @NonNull List<UsageData> liveUsage(@NonNull Row instance, @NonNull List<InstanceStats.Sample> samples,
                                              @NonNull LocaleChain locales, @Nullable MessageResolver resolver) {
        if (!InstanceModel.STATUS_RUNNING.equals(instance.get(InstanceModel.STATUS))) {
            UsageData idle = UsageData.unmeasured(text("live_not_running", "instance_overview", locales, resolver));
            return List.of(idle, idle);
        }
        if (samples.isEmpty()) {
            UsageData unwatched = UsageData.unmeasured(text("live_unwatched", "instance_overview", locales, resolver));
            return List.of(unwatched, unwatched);
        }
        InstanceStats.Sample last = samples.get(samples.size() - 1);
        String observed = Instant.ofEpochMilli(last.at()).toString();
        long limit = last.memoryLimit() > 0 ? last.memoryLimit()
            : InstanceCapacity.bookedMbOf(instance) * 1024L * 1024L;
        UsageData memory = limit > 0
            ? UsageData.measured(last.memoryBytes(), limit, ByteText.human(last.memoryBytes()), ByteText.human(limit),
                observed)
            : UsageData.unmeasured(Microcopy.of("memory_no_limit").withFilter("scope", "instance_overview")
                .withArg("used", ByteText.human(last.memoryBytes())).resolve(locales, resolver));
        if (samples.size() < 2) {
            return List.of(memory,
                UsageData.unmeasured(text("cpu_first_reading", "instance_overview", locales, resolver)));
        }
        double total = 0;
        for (InstanceStats.Sample sample : samples.subList(1, samples.size())) {
            total += sample.cpuPercent();
        }
        long mean = Math.round(total / (samples.size() - 1));
        UsageData cpu = UsageData.measured(mean, 100L * last.cores(), mean + "%",
            Microcopy.of("cores").withFilter("scope", "instance_overview").withArg("count", last.cores())
                .resolve(locales, resolver), observed);
        return List.of(memory, cpu);
    }

    // -- status ----------------------------------------------------------------------

    /**
     * When a runtime last CONFIRMED the badge above, or the honest admission that none
     * ever has.
     *
     * AIDEV-NOTE: null is NOT rendered as "just now" and not hidden either -- a record
     * deployed before this column existed, one on a host that has been unreachable since
     * boot, and one nothing has swept yet are all the same answer: nobody has checked.
     * Hiding the fact would leave the badge looking freshly verified, which is exactly
     * the lie the reconciler exists to stop telling.
     */
    private static @NonNull WidgetFact statusConfirmation(@NonNull Row instance,
                                                          @NonNull LocaleChain locales,
                                                          @Nullable MessageResolver resolver) {
        String label = text("status_confirmed", "instance_overview", locales, resolver);
        Instant observedAt = instance.get(InstanceModel.STATUS_OBSERVED_AT);
        if (observedAt == null) {
            return WidgetFact.of(label,
                text("status_never_confirmed", "instance_overview", locales, resolver));
        }
        return WidgetFact.instant(label, observedAt.toString());
    }

    // -- disk ------------------------------------------------------------------------

    /**
     * The STORED observation {@code ObserveInstanceDisk} stamps, as the usage widget's
     * own NOT-MEASURED-is-an-answer shape.
     *
     * A null observation is SILENCE, exactly as {@code AttentionCollector} reads it:
     * Docker enforces no root quota and stamps nothing, so the whole tier is unmeasured
     * by contract and a percentage computed from zeros would be a fabricated reading.
     *
     * AIDEV-NOTE: "by contract" is a recorded DECISION, not an omission awaiting a fix --
     * the reasoning lives on {@code ResourceLimits} and in docs/instance-tier-plan.md
     * beside the runtime-limits gate clause. Read it before changing this branch.
     */
    private static @NonNull UsageData diskUsage(@NonNull Row instance, int serverId,
                                                @NonNull LocaleChain locales,
                                                @Nullable MessageResolver resolver) {
        InstanceDiskView disk = diskOf(instance, serverId);
        if (!disk.measured() || !disk.enforced()) {
            return UsageData.unmeasured(
                Microcopy.of("not_measured_body").withFilter("scope", "instance_overview")
                    .withArg("runtime", disk.runtime())
                    .resolve(locales, resolver));
        }
        return UsageData.measured(disk.usedBytes(), disk.limitBytes(),
            ByteText.human(disk.usedBytes()), ByteText.human(disk.limitBytes()),
            disk.observedAtIso());
    }

    /** The stored disk observation as a view, for the attention collector and this page. */
    static @NonNull InstanceDiskView diskViewOf(@NonNull Row instance) {
        return diskOf(instance,
            ServerModel.canonicalServerId(instance.get(InstanceModel.SERVER_ID)));
    }

    private static @NonNull InstanceDiskView diskOf(@NonNull Row instance, int serverId) {
        Long used = instance.get(InstanceModel.DISK_USED_BYTES);
        Long limit = instance.get(InstanceModel.DISK_LIMIT_BYTES);
        Instant observedAt = instance.get(InstanceModel.DISK_OBSERVED_AT);
        Row server = Models.get(ServerModel.class).findById(serverId);
        String runtime = server != null ? ServerModel.runtimeOf(server)
            : ServerModel.RUNTIME_DOCKER;
        boolean measured = observedAt != null && used != null;
        return new InstanceDiskView(
            measured,
            measured && limit != null && limit > 0,
            used != null ? used : 0L,
            limit != null ? limit : 0L,
            observedAt != null ? observedAt.toString() : null,
            runtime);
    }

    // -- endpoints -------------------------------------------------------------------

    /** Every port claim this instance holds, joined to its host's declared address. */
    static @NonNull List<InstanceEndpointView> endpointsOf(int instanceId) {
        List<InstanceEndpointView> endpoints = new ArrayList<>();
        for (Row claim : PortLedger.claimsOf(InstanceModel.MODEL_ID, instanceId)) {
            Integer port = claim.get(PortAllocationModel.PORT);
            if (port == null) {
                continue;
            }
            PortState state = PortState.of(claim);
            endpoints.add(new InstanceEndpointView(
                addressOf(claim),
                port,
                blankable(claim.get(PortAllocationModel.PROTOCOL)),
                state.key(),
                state.words(),
                PortLedger.isPreallocated(claim)));
        }
        return endpoints;
    }

    /** What a claim means to an operator, as a stable hook and in words; never the ledger's own status word. */
    private record PortState(@NonNull String key, @NonNull Microcopy words) {

        static @NonNull PortState of(@NonNull Row claim) {
            if (PortLedger.isPreallocated(claim)) {
                return new PortState("reserved",
                    Microcopy.of("reserved").withFilter("scope", "instance_overview"));
            }
            String status = claim.get(PortAllocationModel.STATUS);
            if (PortAllocationModel.STATUS_HELD.equals(status)) {
                return new PortState("port_in_use",
                    Microcopy.of("port_in_use").withFilter("scope", "instance_overview"));
            }
            if (PortAllocationModel.STATUS_RELEASING.equals(status)) {
                return new PortState("port_freeing",
                    Microcopy.of("port_freeing").withFilter("scope", "instance_overview"));
            }
            return new PortState("port_unknown",
                Microcopy.of("port_unknown").withFilter("scope", "instance_overview"));
        }
    }

    /**
     * The address the claim is reachable at: the host's declared public IPv4, then its
     * IPv6, then BLANK. Never a fabricated {@code localhost} -- on a remote host that
     * would be a reachable-looking lie, and the template says so out loud instead.
     */
    private static @NonNull String addressOf(@NonNull Row claim) {
        Row server = Models.get(ServerModel.class)
            .findById(claim.get(PortAllocationModel.SERVER_ID));
        if (server == null) {
            return "";
        }
        String v4 = server.get(ServerModel.PUBLIC_IPV4);
        if (v4 != null && !v4.isBlank()) {
            return v4;
        }
        String v6 = server.get(ServerModel.PUBLIC_IPV6);
        return v6 != null && !v6.isBlank() ? v6 : "";
    }

    /**
     * What this delegate may do here (board Manage-App), in the instance vocabulary's own labels, and what stays the
     * operator's. It reads the same capability walk every tab and action is gated by, so it cannot promise a door
     * that refuses.
     *
     * AIDEV-NOTE: sharing is the Access tab's own gate ({@link GrantAdministration#mayAdministerRecordAccess}), never
     * MANAGE: every instance capability is delegable, so a VIEW holder may already hand VIEW on, and a card saying
     * "who has access is up to the operator" sat beside the tab that lets them do it. Removing the app is the
     * destroy gate's answer, the one that makes the /manage entry's Delete live (the destroy operation's
     * availability), so a listed Destroy always has its door.
     */
    private static @NonNull WidgetInstance yourPart(int instanceId, @NonNull AccessContext access,
                                                    @NonNull LocaleChain locales, @Nullable MessageResolver resolver) {
        List<String> held = new ArrayList<>();
        for (KnownCapability capability : KnownCapabilities.forModel(InstanceModel.MODEL_ID)) {
            if (capability.label() != null && !HohenheimAccess.VIEW.equals(capability.capability())
                && HohenheimAccess.hasInstanceCapability(access, instanceId, capability.capability())) {
                // The first as declared, the rest in their sentence spelling ("Console, power, configure").
                held.add((held.isEmpty() ? capability.label() : capability.label().withFilter("case", "sentence"))
                    .resolve(locales, resolver));
            }
        }
        boolean shares = GrantAdministration.mayAdministerRecordAccess(access, InstanceModel.MODEL_ID, instanceId);
        String can;
        if (held.isEmpty()) {
            can = text(shares ? "you_can_look_share" : "you_can_look", "instance_overview", locales, resolver);
        } else {
            if (shares) {
                held.add(Microcopy.of("you_can_share").withFilter("scope", "instance_overview")
                    .withFilter("case", "sentence").resolve(locales, resolver));
            }
            can = String.join(", ", held);
        }
        boolean removes = HohenheimAccess.destroyUnavailableReason(access, instanceId) == null;
        List<WidgetFact> facts = new ArrayList<>();
        facts.add(WidgetFact.of(text("you_can", "instance_overview", locales, resolver), can));
        String operators = removes && shares ? null
            : removes ? "operator_decides_access" : shares ? "operator_decides_removal" : "operator_decides_detail";
        if (operators != null) {
            facts.add(WidgetFact.of(text("operator_decides", "instance_overview", locales, resolver),
                text(operators, "instance_overview", locales, resolver)));
        }
        return CardWidget.of(Microcopy.of("your_part").withFilter("scope", "instance_overview"),
            new WidgetTree(List.of(new WidgetInstance(FactListWidget.ID, Map.of()).withData(facts))));
    }

    // -- helpers ---------------------------------------------------------------------

    private static @NonNull String text(@NonNull String key, @NonNull String scope,
                                        @NonNull LocaleChain locales,
                                        @Nullable MessageResolver resolver) {
        return Microcopy.of(key).withFilter("scope", scope).resolve(locales, resolver);
    }

    private static @NonNull String blankable(@Nullable String value) {
        return value != null ? value : "";
    }
}
