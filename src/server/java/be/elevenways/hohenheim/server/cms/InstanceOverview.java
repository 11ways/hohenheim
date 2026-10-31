package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.i18n.wording.ByteUnit;
import be.elevenways.zenit.common.text.Texts;
import be.elevenways.hohenheim.server.instance.InstanceQuota;
import be.elevenways.hohenheim.server.quota.ChargedDimension.Charge;
import be.elevenways.hohenheim.model.InstanceQuotaModel;
import be.elevenways.hohenheim.server.quota.OwnerBudget;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.app.PutOnlineReach;
import be.elevenways.hohenheim.ports.PortPurpose;
import be.elevenways.hohenheim.ports.PublishedPort;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.runtime.WorkloadLane;
import be.elevenways.hohenheim.HohenheimWidgets;
import be.elevenways.hohenheim.instance.InstanceDiskView;
import be.elevenways.hohenheim.instance.InstanceEndpointView;
import be.elevenways.hohenheim.instance.InstanceLiveUsageView;
import be.elevenways.hohenheim.instance.LiveFigureView;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceStatus;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceCapacity;
import be.elevenways.hohenheim.server.instance.InstanceFigures;
import be.elevenways.hohenheim.server.instance.InstanceStats;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.MessageResolver;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.i18n.wording.Reader;
import be.elevenways.protoblast.common.i18n.wording.Wording;
import be.elevenways.protoblast.common.util.BlastString;
import be.elevenways.zenit.auth.server.GrantAdministration;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.render.table.HealthCellState;
import be.elevenways.zenit.cms.common.resource.RecordHealth;
import be.elevenways.zenit.cms.common.resource.RecordOverview;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.KnownCapabilities;
import be.elevenways.zenit.common.security.KnownCapability;
import be.elevenways.zenit.widget.common.WidgetInstance;
import be.elevenways.zenit.widget.common.WidgetTree;
import be.elevenways.zenit.widget.common.builtin.AlertVariant;
import be.elevenways.zenit.widget.common.builtin.AlertWidget;
import be.elevenways.zenit.widget.common.builtin.CardWidget;
import be.elevenways.zenit.widget.common.data.NoticeData;
import be.elevenways.zenit.widget.common.data.UsageData;
import be.elevenways.hohenheim.model.InstanceVolumeModel;
import be.elevenways.hohenheim.server.instance.InstanceVolumes;
import be.elevenways.zenit.widget.common.data.WidgetBadge;
import be.elevenways.zenit.widget.common.data.WidgetFact;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    private InstanceOverview() {
    }

    /** The tab both instance entries declare; loading it reads the stored evidence afresh. */
    static @NonNull RecordOverview<Row> tab() {
        return RecordOverview.<Row>fields(RecordOverview.SLUG, HohenheimMicrocopy.INSTANCE.of("overview"))
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
                .withData(NoticeData.of(HohenheimMicrocopy.INSTANCE_OVERVIEW.of("install_error")
                    .resolve(locales, resolver), installError)));
        }

        // A kind a site can serve answers on the names of the sites that serve it (the sites.instance_id reverse lookup
        // the column exists for). A machine no site can serve is reached on its own network instead: its Network card
        // says how it is connected and the addresses its guest holds. Both read the kind's and its driver's facts.
        List<WidgetInstance> main = new ArrayList<>();
        // The ports it answers on directly: the Network card says the outside reaches it on them.
        List<InstanceEndpointView> ports = endpointsOf(instanceId, delegated);
        InstanceKindHandler kind = InstanceKinds.handlerOf(instance);
        PutOnlineReach reach = InstanceKinds.reachOf(instance);
        if (kind != null && reach.asksAddress()) {
            List<Row> sites = Models.get(SiteModel.class).find().where(SiteModel.INSTANCE_ID.eq(instanceId)).all();
            main.add(AppOverview.addresses(sites, accessContext));
            WidgetInstance protection = AppOverview.protection(sites, accessContext);
            if (protection != null) {
                main.add(protection);
            }
        } else if (InstanceKinds.offers(instance, WorkloadLane.GUEST_ADDRESSES)) {
            main.addAll(InstanceNetwork.cards(instance, !ports.isEmpty(), locales, resolver));
        }
        // Only where it holds a port is its Connect to card drawn: no card is drawn for an app
        // reached through its addresses alone, where "holds no port claim" was ledger words about nothing.
        if (!ports.isEmpty()) {
            // A service is reached on its ports: the card says where to connect, and names the game address first;
            // its lead speaks to players only when one of its ports is theirs (PortPurpose.reachedByPlayers).
            boolean service = reach.asksPorts();
            String gameAddress = instance.get(InstanceModel.GAME_ADDRESS);
            WidgetInstance card = portsCard(HohenheimMicrocopy.INSTANCE_OVERVIEW.of(service ? "connect" : "endpoint"),
                service && gameAddress != null
                    ? HohenheimMicrocopy.INSTANCE_OVERVIEW.of("connect_name").withArg("address", gameAddress)
                    : service ? PortPurpose.connectLead(namedPorts(InstanceModel.settingsOf(instance)).stream()
                        .map(PublishedPort::purpose).toList())
                    : HohenheimMicrocopy.INSTANCE_OVERVIEW.of("endpoint_hint"), ports, liveFigure(instance));
            // The game address is the operator's entry on the Configuration (InstanceParts.gameAddressAccess): the card
            // that names it leads there, so a game server is given a name from its own page.
            String linkKey = gameAddress != null ? "game_address_change" : "game_address_set";
            main.add(service && !delegated ? CardWidget.withLink(card, HohenheimMicrocopy.INSTANCE_OVERVIEW.of(linkKey),
                CmsRoutes.detail(panelSlug, HohenheimSlugs.INSTANCES, instanceId).toUrl(), "pen") : card);
        }
        // Memory, CPU, disk. A running workload's memory and CPU are LIVE: the gauges' element holds the stats hub's
        // feed while this page is open (seeded from what the hub holds now), never a stream opened by this render.
        // An application's three are its serving release's, the container that runs (ApplicationReleases.runtimeRows).
        Row runtime = ApplicationReleases.runtimeRowOf(instance);
        List<WidgetInstance> gauges = new ArrayList<>(liveGauges(runtime, locales, resolver));
        gauges.add(AppOverview.gauge(HohenheimMicrocopy.INSTANCE_OVERVIEW.of("disk"),
            diskUsage(runtime, ServerModel.canonicalServerId(runtime.get(InstanceModel.SERVER_ID)), locales,
                resolver)));
        // Each declared volume with a size limit, measured against it ("uploads: 3 GB of 10 GB"), one read.
        for (Row volume : InstanceVolumes.declaredFor(instanceId)) {
            UsageData usage = VolumeParts.usageData(volume);
            if (usage != null) {
                gauges.add(AppOverview.gauge(HohenheimMicrocopy.INSTANCE_VOLUME.of("volume_gauge")
                    .withArg("name", String.valueOf((Object) volume.get(InstanceVolumeModel.NAME))), usage));
            }
        }
        main.add(AppOverview.resources(gauges));

        List<WidgetInstance> side = new ArrayList<>();
        side.add(AppOverview.details(facts(instance, runtime, serverId, panelSlug, delegated, accessContext)));
        // AIDEV-NOTE: the per-record RECENT ACTIVITY card, read through core's per-record history
        // (AdminActivityResource.recentFor over History.inContext), narrowed by the shared
        // `zenit:activity` source's rules.
        // AIDEV-NOTE: OPERATOR-ONLY, and deliberately by omission rather than by an empty
        // list. The shared source is registered with HohenheimSources.ADMIN_ACCESS
        // (HohenheimSources.register -> ActivitySources.register("admin", ADMIN_ACCESS)), so
        // a tenant fails the source's own gate and would be shown a card that is always empty --
        // a censoring that looks like a bug. Widening the audience is a decision about the AUDIT
        // LOG, not about this page: the log carries every operator's actions on every record.
        if (!delegated) {
            side.add(AppOverview.recent(Models.get(InstanceModel.class), instanceId, accessContext));
        } else {
            side.add(yourPart(instanceId, accessContext, locales, resolver));
        }
        return AppOverview.compose(top, main, side);
    }

    /**
     * The Details card: the app's verdict as its Status with how old the stored status is, the kind, and (for the
     * operator) the host.
     *
     * AIDEV-NOTE: the Status is the band's verdict (AppHealth), never the stored status token: a shop once read "Cannot
     * start yet" in the band beside a red "Error" here, an older failed start the host's refusal had since overtaken.
     *
     * AIDEV-NOTE: the host is operator inventory, and BOTH halves leak it -- the name is the machine's identity and
     * the link carries its numeric server id, which is the id every host-scoped admin route is keyed on. A tenant is
     * told WHAT their workload is doing, never WHERE it runs; the censoring is the fact simply NOT BEING ADDED.
     *
     * @param runtime the row whose status speaks for its runtime: an application's serving release, else itself
     */
    private static @NonNull List<WidgetFact> facts(@NonNull Row instance, @NonNull Row runtime, int serverId,
                                                   @NonNull String panelSlug, boolean delegated,
                                                   @NonNull AccessContext viewer) {
        LocaleChain locales = viewer.conduit().getLocales();
        MessageResolver resolver = viewer.conduit().getMessageResolver();
        List<WidgetFact> facts = new ArrayList<>();
        RecordHealth reading = AppHealth.instanceReading(instance, delegated, viewer).health();
        HealthCellState verdict = HealthCellState.of(reading, viewer);
        // The verdict's one word ("Working"), never its headline: the band above says the sentence ("Live at
        // https://..."), which as a pill outgrew this narrow card. An unknown tone has no word of its own, so its
        // short headline ("Not deployed yet") speaks, agreeing with the band (AppParts.stateWord).
        facts.add(WidgetFact.badge(AppOverview.text("state", locales, resolver),
            new WidgetBadge(AppParts.stateWord(reading).resolve(locales, resolver), verdict.variant(), null,
                verdict.icon(), true)));
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
        // The verdict above reads the STORED status, and a stored column is a claim about
        // the past. This says how old that claim is, from the only write that means "a
        // runtime actually answered" (InstanceStatusReconciler). It is a stored fact too,
        // deliberately: reading it costs nothing, while dialling the daemon per render is
        // the thing the disk gauge already refuses to do.
        facts.add(statusConfirmation(runtime, locales, resolver));
        // How long it has run ("Running for 2 hours"): since the last start of what runs, stamped on its record
        // (InstanceModel.STARTED_AT); a failed start since clears it, so an old start never shows beside it.
        Instant started = InstanceModel.STATUS_RUNNING.equals(runtime.get(InstanceModel.STATUS))
            ? runtime.get(InstanceModel.STARTED_AT) : null;
        if (started != null) {
            facts.add(WidgetFact.instant(HohenheimMicrocopy.INSTANCE_OVERVIEW.of("started")
                .resolve(locales, resolver), started.toString()));
        }
        if (!delegated) {
            facts.add(WidgetFact.link(
                HohenheimMicrocopy.INSTANCE_OVERVIEW.of("host").resolve(locales, resolver),
                ServerModel.nameOf(serverId),
                CmsRoutes.subpage(panelSlug, HohenheimSlugs.SERVERS, serverId, RecordOverview.SLUG).toUrl()));
        }
        if (!delegated) {
            WidgetFact quota = quotaFact(instance, panelSlug, locales, resolver);
            if (quota != null) {
                facts.add(quota);
            }
        }
        facts.addAll(databaseFacts(instance.get(InstanceModel.ID), panelSlug, delegated, locales, resolver));
        facts.add(backupFact(instance, locales, resolver));
        return facts;
    }

    /**
     * The owner budget the record is charged to (InstanceQuota.COUNT's own answer, a stampless row the operator's), as
     * "2 of 5 workloads, 4096 of 8192 MB memory", linked to that owner's quota when one is set and to the quotas
     * otherwise: every live workload, a game server included, counts there.
     *
     * @return null for a record the count charges nothing
     */
    private static @Nullable WidgetFact quotaFact(@NonNull Row instance, @NonNull String panelSlug,
                                                  @NonNull LocaleChain locales, @NonNull MessageResolver resolver) {
        Charge charge = InstanceQuota.COUNT.held(instance);
        if (charge == null) {
            return null;
        }
        String owner = OwnerBudget.INSTANCES.packOf(charge.bucket());
        String words = Texts.factRun(
            usage(OwnerBudget.INSTANCES, owner, "quota_workloads").resolve(locales, resolver),
            usage(OwnerBudget.OWNER_MEMORY, owner, "quota_memory").resolve(locales, resolver));
        Row override = OwnerBudget.INSTANCES.overrideIn(charge.bucket());
        String url = override != null
            ? CmsRoutes.open(panelSlug, HohenheimSlugs.INSTANCE_QUOTAS, override.get(InstanceQuotaModel.ID)).toUrl()
            : CmsRoutes.list(panelSlug, HohenheimSlugs.INSTANCE_QUOTAS).toUrl();
        return WidgetFact.link(HohenheimMicrocopy.INSTANCE_OVERVIEW.of("owner_quota").resolve(locales, resolver),
            words, url);
    }

    /** @return one budget's spend for the owner, "2 of 5 workloads", or "2 workloads, no cap" where none is set */
    private static @NonNull Microcopy usage(@NonNull OwnerBudget budget, @NonNull String owner, @NonNull String key) {
        Integer limit = budget.limitFor(owner);
        Microcopy words = HohenheimMicrocopy.INSTANCE_OVERVIEW.of(limit != null ? key : key + "_open")
            .withArg("used", budget.worded(budget.usedBy(owner)));
        return limit != null ? words.withArg("limit", budget.worded(limit)) : words;
    }

    /**
     * One Database line per managed database the workload uses ("shop (MySQL)"), with the
     * database's own state when it does not serve ({@link DatabaseVerdict}), linked to its page for the operator.
     */
    private static @NonNull List<WidgetFact> databaseFacts(int instanceId, @NonNull String panelSlug,
                                                           boolean delegated, @NonNull LocaleChain locales,
                                                           @Nullable MessageResolver resolver) {
        List<WidgetFact> facts = new ArrayList<>();
        String label = HohenheimMicrocopy.INSTANCE_OVERVIEW.of("database").resolve(locales, resolver);
        for (Row link : Models.get(InstanceDatabaseModel.class).find()
                .where(InstanceDatabaseModel.INSTANCE_ID.eq(instanceId)).all()) {
            Integer databaseId = link.get(InstanceDatabaseModel.DATABASE_ID);
            Row database = Models.get(DatabaseModel.class).findById(databaseId);
            if (database == null) {
                continue;
            }
            DatabaseVerdict verdict = DatabaseVerdict.ofDatabase(database);
            Microcopy state = verdict.state().label().withFilter("case", "sentence");
            Microcopy value = (verdict.state().serves()
                    ? HohenheimMicrocopy.INSTANCE_OVERVIEW.of("database_value")
                    : HohenheimMicrocopy.INSTANCE_OVERVIEW.of("database_value_state")
                        .withArg("state", state))
                .withArg("name", database.get(DatabaseModel.NAME))
                .withArg("engine", WidgetBadge.of(DatabaseModel.ENGINE, database.get(DatabaseModel.ENGINE), locales,
                    resolver).label());
            String words = value.resolve(locales, resolver);
            facts.add(delegated ? WidgetFact.of(label, words)
                : WidgetFact.link(label, words, CmsRoutes.open(panelSlug, HohenheimSlugs.DATABASES, databaseId)
                .toUrl()));
        }
        return facts;
    }

    /**
     * The Backups line ("last one 03:00, 212 MB"): its newest backup, failed or made, or that
     * none is made because it has no backup target.
     */
    private static @NonNull WidgetFact backupFact(@NonNull Row instance, @NonNull LocaleChain locales,
                                                  @Nullable MessageResolver resolver) {
        String label = HohenheimMicrocopy.INSTANCE_OVERVIEW.of("backups").resolve(locales, resolver);
        if (instance.get(InstanceModel.BACKUP_TARGET_ID) == null) {
            return WidgetFact.of(label, HohenheimMicrocopy.INSTANCE_OVERVIEW.of("backups_no_target")
                .resolve(locales, resolver));
        }
        Row newest = Models.get(InstanceBackupModel.class).newestOf(instance.get(InstanceModel.ID));
        Instant at = newest == null ? null : newest.get(InstanceBackupModel.CREATED_AT);
        if (newest == null || at == null) {
            return WidgetFact.of(label, HohenheimMicrocopy.INSTANCE_OVERVIEW.of("backups_none_yet")
                .resolve(locales, resolver));
        }
        boolean made = InstanceBackupModel.STATUS_COMPLETE.equals(newest.get(InstanceBackupModel.STATUS));
        Long size = newest.get(InstanceBackupModel.SIZE_BYTES);
        Microcopy words = !made ? HohenheimMicrocopy.INSTANCE_OVERVIEW.of("backups_last_failed")
            : size == null ? HohenheimMicrocopy.INSTANCE_OVERVIEW.of("backups_last_unsized")
            : HohenheimMicrocopy.INSTANCE_OVERVIEW.of("backups_last").withArg("size", size);
        return WidgetFact.of(label, words.withArg("ago", Wording.relative(Reader.of(locales, resolver), at, resolver))
            .resolve(locales, resolver));
    }

    // -- live usage ------------------------------------------------------------------

    /**
     * The memory and CPU gauges: live for a running workload whose driver reads live stats (the
     * {@link HohenheimWidgets#INSTANCE_LIVE_USAGE} element holds the hub's feed while the overview is open, seeded with
     * the hub's newest two readings), otherwise both not measured for the one reason that applies.
     *
     * AIDEV-NOTE: the render itself never opens a stream (InstanceStats.lastMemoryMb's rule); the element's channel
     * link does, as a viewer of the ONE hub session per instance the Metrics tab also joins. Memory is measured
     * against the runtime's cap, else what the host booked for it.
     *
     * @return the gauge widgets, memory first
     */
    static @NonNull List<WidgetInstance> liveGauges(@NonNull Row instance, @NonNull LocaleChain locales,
                                                   @Nullable MessageResolver resolver) {
        Microcopy memoryLabel = HohenheimMicrocopy.INSTANCE_OVERVIEW.of("memory");
        Microcopy cpuLabel = HohenheimMicrocopy.INSTANCE_OVERVIEW.of("cpu");
        Microcopy unmeasured = !InstanceKinds.offers(instance, WorkloadLane.LIVE_STATS)
            ? HohenheimMicrocopy.INSTANCE_OVERVIEW.of("live_no_lane")
            : !InstanceModel.STATUS_RUNNING.equals(instance.get(InstanceModel.STATUS))
            ? HohenheimMicrocopy.INSTANCE_OVERVIEW.of("live_not_running") : null;
        if (unmeasured != null) {
            UsageData none = UsageData.unmeasured(unmeasured.resolve(locales, resolver));
            return List.of(AppOverview.gauge(memoryLabel, none), AppOverview.gauge(cpuLabel, none));
        }
        int instanceId = instance.get(InstanceModel.ID);
        List<InstanceStats.Sample> held = InstanceStats.history(instanceId);
        long booked = ByteUnit.MEGABYTE.bytes(InstanceCapacity.bookedMbOf(instance));
        InstanceLiveUsageView view = new InstanceLiveUsageView(instanceId,
            held.isEmpty() ? null : held.get(held.size() - 1).toMap(),
            held.size() < 2 ? null : held.get(held.size() - 2).toMap(),
            booked > 0 ? booked : null,
            AppOverview.gauge(memoryLabel, null), AppOverview.gauge(cpuLabel, null));
        return List.of(new WidgetInstance(HohenheimWidgets.INSTANCE_LIVE_USAGE.id(), Map.of()).withData(view));
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
        String label = HohenheimMicrocopy.INSTANCE_OVERVIEW.of("status_confirmed").resolve(locales, resolver);
        Instant observedAt = instance.get(InstanceModel.STATUS_OBSERVED_AT);
        if (observedAt == null) {
            return WidgetFact.of(label,
                HohenheimMicrocopy.INSTANCE_OVERVIEW.of("status_never_confirmed").resolve(locales, resolver));
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
     * the reasoning lives on {@code ResourceLimits}.
     * Read it before changing this branch.
     */
    private static @NonNull UsageData diskUsage(@NonNull Row instance, int serverId,
                                                @NonNull LocaleChain locales,
                                                @Nullable MessageResolver resolver) {
        InstanceDiskView disk = diskOf(instance, serverId);
        if (!disk.measured() || !disk.enforced()) {
            return UsageData.unmeasured(
                HohenheimMicrocopy.INSTANCE_OVERVIEW.of("not_measured_body").resolve(locales, resolver));
        }
        return UsageData.bytes(disk.usedBytes(), disk.limitBytes(), disk.observedAtIso());
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

    /**
     * Every port claim this instance holds, joined to its host's declared address.
     *
     * @param delegated whether the reader is a tenant, who reads why a port has no address without the host's
     *                  internals
     */
    static @NonNull List<InstanceEndpointView> endpointsOf(int instanceId, boolean delegated) {
        Row instance = Models.get(InstanceModel.class).findById(instanceId);
        List<PublishedPort> named = instance == null ? List.of() : namedPorts(InstanceModel.settingsOf(instance));
        InstanceStatus status = instance == null ? null : InstanceStatus.forToken(instance.get(InstanceModel.STATUS));
        return endpointsOf(InstanceModel.MODEL_ID, instanceId, named, status != null && status.servable(), delegated);
    }

    /** The card listing where clients connect: each port an owner holds, its protocol, purpose and state. */
    static @NonNull WidgetInstance portsCard(@NonNull Microcopy title, @NonNull Microcopy lead,
                                             @NonNull List<InstanceEndpointView> ports) {
        return portsCard(title, lead, ports, null);
    }

    /** {@link #portsCard(Microcopy, Microcopy, List)} led by the service's live figure, when it declares one. */
    static @NonNull WidgetInstance portsCard(@NonNull Microcopy title, @NonNull Microcopy lead,
                                             @NonNull List<InstanceEndpointView> ports,
                                             @Nullable WidgetInstance figure) {
        List<WidgetInstance> body = new ArrayList<>();
        if (figure != null) {
            body.add(figure);
        }
        body.add(new WidgetInstance(HohenheimWidgets.INSTANCE_ENDPOINTS.id(), Map.of()).withData(ports));
        return new WidgetInstance(CardWidget.ID, Map.of("title", title, "lead", lead), new WidgetTree(body));
    }

    /**
     * The service's live figure ("8 of 20 players online"): its element holds the live figure link while the overview
     * is open, seeded from the row and the hub's memory ({@link InstanceFigures#viewOf}); the render never asks.
     *
     * @return null for an instance that declares no status probe
     */
    private static @Nullable WidgetInstance liveFigure(@NonNull Row instance) {
        LiveFigureView view = InstanceFigures.viewOf(instance);
        return view == null ? null
            : new WidgetInstance(HohenheimWidgets.INSTANCE_LIVE_FIGURE.id(), Map.of()).withData(view);
    }

    /**
     * Every port one owner holds in the ledger, as the Connect to card lists it: a workload's published ports, a
     * stream's public port.
     *
     * @param live whether what holds them serves right now, which tells In use from Reserved
     */
    static @NonNull List<InstanceEndpointView> endpointsOf(@NonNull Identifier ownerModel, int ownerId,
                                                           @NonNull List<PublishedPort> named, boolean live,
                                                           boolean delegated) {
        List<InstanceEndpointView> endpoints = new ArrayList<>();
        boolean workloadLive = live;
        for (Row claim : PortLedger.claimsOf(ownerModel, ownerId)) {
            Integer port = claim.get(PortAllocationModel.PORT);
            if (port == null) {
                continue;
            }
            PortState state = PortState.of(claim, workloadLive);
            String address = addressOf(claim);
            String protocol = Objects.toString(claim.get(PortAllocationModel.PROTOCOL), "");
            // A loopback port is reached by its host alone: no public address would change that, so none is asked.
            boolean loopback = PublishedPort.LOOPBACK.equals(claim.get(PortAllocationModel.HOST_IP));
            boolean asksAddress = address.isBlank() && !loopback;
            endpoints.add(new InstanceEndpointView(
                loopback ? "" : address,
                port,
                protocol,
                state.key(),
                state.words(),
                PortLedger.isPreallocated(claim),
                loopback ? HohenheimMicrocopy.INSTANCE_OVERVIEW.of("loopback_only").withArg("port", port)
                    : !asksAddress ? null
                    : delegated ? HohenheimMicrocopy.INSTANCE_OVERVIEW.of("no_public_address_delegated")
                    : HohenheimMicrocopy.INSTANCE_OVERVIEW.of("no_public_address").withArg("port", port)
                        .withArg("host", ServerModel.labelOf(claim.get(PortAllocationModel.SERVER_ID))),
                purposeOf(named, port, protocol),
                !asksAddress || delegated ? null : CmsRoutes.detail(HohenheimSlugs.ADMIN,
                    HohenheimSlugs.SERVERS, claim.get(PortAllocationModel.SERVER_ID)).toUrl()));
        }
        return endpoints;
    }

    /** @return what the published port on this host port and protocol is for, or null when none names it */
    private static @Nullable Microcopy purposeOf(@NonNull List<PublishedPort> named, int port,
                                                 @NonNull String protocol) {
        for (PublishedPort published : named) {
            if (Integer.valueOf(port).equals(published.hostPort()) && published.protocol().equals(protocol)) {
                PortPurpose purpose = published.purpose();
                return purpose == PortPurpose.OTHER ? Microcopy.literal(published.name()) : purpose.label();
            }
        }
        return null;
    }

    /** @return the published ports the settings name, none when their spelling is refused (the deploy says why) */
    private static @NonNull List<PublishedPort> namedPorts(@NonNull Map<String, Object> settings) {
        try {
            return PublishedPort.of(settings);
        } catch (Violations unspelled) {
            return List.of();
        }
    }

    /** What a claim means to an operator, as a stable hook and in words; never the ledger's own status word. */
    private record PortState(@NonNull String key, @NonNull Microcopy words) {

        /**
         * @param workloadLive whether the owning record leaves a workload that holds its ports; every claim outlives a
         *                     stop (the owner's number for life), so only this says whether anything listens on it
         */
        static @NonNull PortState of(@NonNull Row claim, boolean workloadLive) {
            String status = claim.get(PortAllocationModel.STATUS);
            if (PortAllocationModel.STATUS_RELEASING.equals(status)) {
                return new PortState("port_freeing",
                    HohenheimMicrocopy.INSTANCE_OVERVIEW.of("port_freeing"));
            }
            if (!workloadLive) {
                return new PortState("reserved",
                    HohenheimMicrocopy.INSTANCE_OVERVIEW.of("reserved"));
            }
            if (PortAllocationModel.STATUS_HELD.equals(status)) {
                return new PortState("port_in_use",
                    HohenheimMicrocopy.INSTANCE_OVERVIEW.of("port_in_use"));
            }
            return new PortState("port_unknown",
                HohenheimMicrocopy.INSTANCE_OVERVIEW.of("port_unknown"));
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
     * What this delegate may do here, in what the instance vocabulary says each held capability
     * allows, and what stays the operator's. It reads the same capability walk every tab and action is gated by, so
     * it cannot promise a door that refuses.
     *
     * AIDEV-NOTE: sharing is the Access tab's own gate ({@link GrantAdministration#mayAdministerRecordAccess}), never
     * MANAGE: every instance capability is delegable, so a VIEW holder may already hand VIEW on, and a card saying
     * "who has access is up to the operator" sat beside the tab that lets them do it. Removing the app is the
     * destroy gate's answer, the one that makes the /manage entry's Delete live (the destroy operation's
     * availability), so a listed Destroy always has its door.
     */
    private static @NonNull WidgetInstance yourPart(int instanceId, @NonNull AccessContext access,
                                                    @NonNull LocaleChain locales, @Nullable MessageResolver resolver) {
        List<KnownCapability> holds = new ArrayList<>();
        for (KnownCapability capability : KnownCapabilities.forModel(InstanceModel.MODEL_ID)) {
            if (capability.label() != null && !HohenheimCapabilities.VIEW.equals(capability.capability())
                && HohenheimAccess.hasInstanceCapability(access, instanceId, capability.capability())) {
                holds.add(capability);
            }
        }
        // Each held capability in what it allows (its description, else its sentence label), leaving out one a
        // broader held capability implies: a tenant reads "start and stop it, its console, settings and removal",
        // never the umbrella's verbs again as a raw list ("Console, power, configure, destroy").
        List<String> held = new ArrayList<>();
        for (KnownCapability capability : holds) {
            if (holds.stream().anyMatch(other -> capability.impliedBy().contains(other.capability()))) {
                continue;
            }
            Microcopy words = capability.description() != null ? capability.description()
                : Objects.requireNonNull(capability.label()).withFilter("case", "sentence");
            held.add(words.resolve(locales, resolver));
        }
        boolean shares = GrantAdministration.mayAdministerRecordAccess(access, InstanceModel.MODEL_ID, instanceId);
        String can;
        if (held.isEmpty()) {
            can = HohenheimMicrocopy.INSTANCE_OVERVIEW.of(shares ? "you_can_look_share" : "you_can_look")
                .resolve(locales, resolver);
        } else {
            if (shares) {
                held.add(HohenheimMicrocopy.INSTANCE_OVERVIEW.of("you_can_share")
                    .withFilter("case", "sentence").resolve(locales, resolver));
            }
            String sentence = String.join(", ", held);
            can = BlastString.upperFirst(sentence);
        }
        boolean removes = HohenheimAccess.destroyUnavailableReason(access, instanceId) == null;
        List<WidgetFact> facts = new ArrayList<>();
        facts.add(WidgetFact.of(HohenheimMicrocopy.INSTANCE_OVERVIEW.of("you_can").resolve(locales, resolver), can));
        String operators = removes && shares ? null
            : removes ? "operator_decides_access" : shares ? "operator_decides_removal" : "operator_decides_detail";
        if (operators != null) {
            facts.add(WidgetFact.of(HohenheimMicrocopy.INSTANCE_OVERVIEW.of("operator_decides")
                .resolve(locales, resolver),
                HohenheimMicrocopy.INSTANCE_OVERVIEW.of(operators).resolve(locales, resolver)));
        }
        return CardWidget.ofFacts(HohenheimMicrocopy.INSTANCE_OVERVIEW.of("your_part"), facts);
    }

    // -- helpers ---------------------------------------------------------------------
}
