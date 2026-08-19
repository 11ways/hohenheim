package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HostPreflightWidget;
import be.elevenways.hohenheim.HostStateWidget;
import be.elevenways.hohenheim.HostTrustWidget;
import be.elevenways.hohenheim.HostWorkloadsWidget;
import be.elevenways.hohenheim.host.HostCapacityView;
import be.elevenways.hohenheim.host.HostFactView;
import be.elevenways.hohenheim.host.HostPreflightReportView;
import be.elevenways.hohenheim.host.KernelIsolationView;
import be.elevenways.hohenheim.host.PostureAcknowledgementView;
import be.elevenways.hohenheim.host.PreflightCheckView;
import be.elevenways.hohenheim.host.TrustLaneView;
import be.elevenways.hohenheim.host.WorkloadView;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.HostTrustSlot;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.server.host.HostKeys;
import be.elevenways.hohenheim.server.host.HostPins;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.incus.IncusEndpoint;
import be.elevenways.hohenheim.server.incus.IncusKernelIsolation;
import be.elevenways.hohenheim.server.host.IncusPreflight;
import be.elevenways.hohenheim.server.incus.IncusTrust;
import be.elevenways.hohenheim.server.instance.InstanceCapacity;
import be.elevenways.hohenheim.server.process.ProcessCapacity;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.MessageResolver;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.render.table.EnumBadgeState;
import be.elevenways.zenit.cms.common.resource.RecordDashboardPage;
import be.elevenways.zenit.cms.common.widget.RecordActionsWidget;
import be.elevenways.zenit.cms.server.render.action.RecordActionBands;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.widget.common.WidgetInstance;
import be.elevenways.zenit.widget.common.WidgetTree;
import be.elevenways.zenit.widget.common.builtin.AlertVariant;
import be.elevenways.zenit.widget.common.builtin.AlertWidget;
import be.elevenways.zenit.widget.common.builtin.FactListWidget;
import be.elevenways.zenit.widget.common.builtin.SectionWidget;
import be.elevenways.zenit.widget.common.builtin.StatusWidget;
import be.elevenways.zenit.widget.common.builtin.UsageBarWidget;
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
import java.util.function.UnaryOperator;

/**
 * Overview tab on a host record, and the record's own front door: the STORED evidence
 * the machinery already keeps -- admission and quarantine, per-lane trust state, the
 * full preflight report with per-check timestamps, capacity bookings and the workloads
 * that hold the host -- rendered structured instead of flattened into form-field
 * sentences.
 *
 * The page IS a widget tree ({@link RecordDashboardPage}) and stays read-only: every
 * mutation on it is one of the resource's own {@code RowAction}s, projected through
 * {@code zenitcms:record_actions} so confirmations, permissions and per-row visibility
 * stay single-sourced.
 */
public final class ServerOverviewPage extends RecordDashboardPage<Row> {

    public static final String SLUG = "overview";

    private final ServerResource resource;

    ServerOverviewPage(@NonNull ServerResource resource) {
        this.resource = resource;
    }

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "server_overview"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("overview").withFilter("scope", "server"); }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Icon icon() { return Icon.of("gauge"); }

    @Override
    public @NonNull WidgetTree widgets(@NonNull Row server, @NonNull AccessContext accessContext) {
        Conduit conduit = accessContext.conduit();
        Integer serverId = server.get(ServerModel.ID);
        String panelSlug = CmsSupport.panelSlug(conduit);
        LocaleChain locales = conduit.getLocales();
        MessageResolver resolver = conduit.getMessageResolver();

        List<WidgetInstance> bands = new ArrayList<>();

        // Quarantine is LOUD and leads: the repin ceremony that clears it renders in the
        // action band below, as the resource's own confirmed row action.
        Instant quarantinedAt = server.get(ServerModel.QUARANTINED_AT);
        if (quarantinedAt != null) {
            String reason = blankable(server.get(ServerModel.QUARANTINE_REASON));
            String body = reason.isBlank()
                ? text("quarantine_clears_by_repin", locales, resolver)
                : reason + " " + text("quarantine_clears_by_repin", locales, resolver);
            bands.add(band(new WidgetTree(List.of(
                alert(AlertVariant.DESTRUCTIVE,
                    NoticeData.of(text("quarantined_title", locales, resolver), body))))));
        }

        List<WidgetInstance> state = new ArrayList<>();
        state.add(new WidgetInstance(StatusWidget.ID,
            Map.of("label", HohenheimWidgetCopy.localized("state", "server_overview")))
            .withData(stateBadges(server, locales, resolver)));
        state.add(new WidgetInstance(HostStateWidget.ID, Map.of())
            .withData(ServerResource.statusCellOf(server)));

        String lastError = blankable(server.get(ServerModel.LAST_ERROR));
        if (!lastError.isBlank()) {
            state.add(alert(AlertVariant.DESTRUCTIVE,
                NoticeData.of(text("last_error", locales, resolver), lastError)));
        }

        PostureAcknowledgementView acknowledgement = acknowledgementViewOf(server);
        if (acknowledgement.needed()) {
            state.add(new WidgetInstance(FactListWidget.ID, Map.of())
                .withData(List.of(WidgetFact.badge(
                    text("acknowledgement", locales, resolver),
                    acknowledgementBadge(acknowledgement, locales, resolver)))));
        }

        Panel panel = PanelRegistry.getBySlug(panelSlug);
        if (panel != null) {
            state.add(new WidgetInstance(RecordActionsWidget.ID, Map.of())
                .withData(RecordActionBands.forRecord(panel, this.resource, server,
                    accessContext, conduit)));
        }
        bands.add(band(new WidgetTree(state)));

        List<TrustLaneView> lanes = trustLanes(server);
        if (!lanes.isEmpty()) {
            bands.add(band(new WidgetTree(List.of(
                new WidgetInstance(HostTrustWidget.ID, Map.of()).withData(lanes)))));
        }

        bands.add(band(new WidgetTree(List.of(
            new WidgetInstance(HostPreflightWidget.ID, Map.of()).withData(preflightReport(server))))));

        HostCapacityView capacity = capacityOf(server, serverId);
        bands.add(band(new WidgetTree(List.of(
            new WidgetInstance(UsageBarWidget.ID,
                Map.of("label", HohenheimWidgetCopy.localized("capacity", "server_overview")))
                .withData(capacityUsage(capacity, locales, resolver)),
            new WidgetInstance(FactListWidget.ID, Map.of())
                .withData(capacityFacts(capacity, locales, resolver))))));

        bands.add(band(new WidgetTree(List.of(
            new WidgetInstance(HostWorkloadsWidget.ID, Map.of())
                .withData(workloadsOf(panelSlug, serverId))))));

        // AIDEV-NOTE: no per-record RECENT ACTIVITY band -- see the same note on
        // InstanceOverviewPage: the shared `zenit.activity` source does not project
        // record_id, so ActivityRules.forRecord is not expressible against its
        // vocabulary yet.

        return new WidgetTree(List.of(new WidgetInstance(SectionWidget.ID,
            Map.of("css_class", "hh-server-overview"), new WidgetTree(bands))));
    }

    // -- state ---------------------------------------------------------------------

    private static @NonNull List<WidgetBadge> stateBadges(@NonNull Row server,
                                                          @NonNull LocaleChain locales,
                                                          @Nullable MessageResolver resolver) {
        List<WidgetBadge> badges = new ArrayList<>();
        badges.add(WidgetBadge.of(ServerModel.RUNTIME, ServerModel.runtimeOf(server),
            locales, resolver));
        addBadge(badges, ServerModel.ADMISSION, server.get(ServerModel.ADMISSION), locales, resolver);
        addBadge(badges, ServerModel.POSTURE, server.get(ServerModel.POSTURE), locales, resolver);
        return badges;
    }

    private static void addBadge(@NonNull List<WidgetBadge> badges, @NonNull EnumField field,
                                 @Nullable Object raw, @NonNull LocaleChain locales,
                                 @Nullable MessageResolver resolver) {
        if (raw != null) {
            badges.add(WidgetBadge.of(field, raw, locales, resolver));
        }
    }

    /** The acknowledgement state as ONE pill: current, out of date, or never given. */
    private static @NonNull WidgetBadge acknowledgementBadge(
            @NonNull PostureAcknowledgementView acknowledgement,
            @NonNull LocaleChain locales, @Nullable MessageResolver resolver) {
        if (acknowledgement.current()) {
            return WidgetBadge.of(Microcopy.of("ack_current").withFilter("scope", "server_overview")
                .withArg("actor", acknowledgement.actorLabel())
                .withArg("version", String.valueOf(acknowledgement.version()))
                .resolve(locales, resolver), "success", null);
        }
        if (acknowledgement.stale()) {
            return WidgetBadge.of(Microcopy.of("ack_stale").withFilter("scope", "server_overview")
                .withArg("version", String.valueOf(acknowledgement.requiredVersion()))
                .resolve(locales, resolver), "destructive", null);
        }
        return WidgetBadge.of(text("ack_missing", locales, resolver), "destructive", null);
    }

    // -- trust ---------------------------------------------------------------------

    /** The lanes this record declares, in transport-first order. */
    private static @NonNull List<TrustLaneView> trustLanes(@NonNull Row server) {
        List<TrustLaneView> lanes = new ArrayList<>();
        if (ServerModel.isIncusHttps(server)) {
            lanes.add(laneView(server, "incus_cert", HostTrustSlot.INCUS_TLS,
                IncusTrust::fingerprintOf));
        }
        if (ServerModel.hasSshLane(server)) {
            lanes.add(laneView(server, "host_key", HostTrustSlot.SSH,
                HostKeys::fingerprintOf));
        }
        return lanes;
    }

    private static @NonNull TrustLaneView laneView(@NonNull Row server, @NonNull String laneId,
                                                   @NonNull HostTrustSlot slot,
                                                   @NonNull UnaryOperator<String> digest) {
        String fingerprint = server.get(slot.fingerprint());
        String offered = slot.offeredOf(server);
        String client = server.get(slot.clientPublic());
        Instant pinnedAt = server.get(slot.pinnedAt());
        return new TrustLaneView(
            laneId,
            slot.isPinned(server),
            fingerprint != null ? fingerprint : "",
            Boolean.TRUE.equals(server.get(slot.verified())),
            pinnedAt != null ? pinnedAt.toString() : null,
            offered.isBlank() ? "" : digest.apply(offered),
            HostPins.isQuarantined(server, slot),
            client != null ? client : "");
    }

    /**
     * The posture acknowledgement as data: what is stored, and whether it still answers.
     * Rendered for every host, including ones whose posture needs none -- "not required"
     * is a state an operator has to be able to read too.
     */
    public static @NonNull PostureAcknowledgementView acknowledgementViewOf(@NonNull Row server) {
        Instant at = server.get(ServerModel.ACKNOWLEDGED_AT);
        String label = server.get(ServerModel.ACKNOWLEDGED_BY_LABEL);
        return new PostureAcknowledgementView(
            ServerModel.postureNeedsAcknowledgement(server),
            ServerModel.postureAcknowledged(server),
            server.get(ServerModel.ACKNOWLEDGED_POSTURE),
            server.get(ServerModel.ACKNOWLEDGED_WARNING_VERSION),
            ServerModel.POSTURE_WARNING_VERSION,
            at != null ? at.toString() : null,
            label != null ? label : "");
    }

    /**
     * Kernel-truth isolation as data, or null for a non-Incus host. Names the ENDPOINT
     * the verdict is about: a blank {@code incus_url} means the controller's own socket,
     * so a record named after a remote machine cannot silently green-light the wrong host.
     */
    public static @Nullable KernelIsolationView kernelIsolationViewOf(@NonNull Row server) {
        if (!ServerModel.isIncus(server)) {
            return null;
        }
        Instant checkedAt = HostPreflight.storedCheckAt(server, IncusPreflight.KERNEL_LANE_CHECK);
        return new KernelIsolationView(
            ServerModel.acceptsTenantWorkloads(server),
            IncusKernelIsolation.laneAvailable(server),
            HostPreflight.storedCheckStatus(server, IncusPreflight.KERNEL_LANE_CHECK),
            checkedAt != null ? checkedAt.toString() : null,
            ServerModel.isIncusHttps(server) ? "" : IncusEndpoint.of(server).describe());
    }

    // -- preflight -----------------------------------------------------------------

    /** The whole stored report as one payload: kernel verdict, checks, facts and stamp. */
    private static @NonNull HostPreflightReportView preflightReport(@NonNull Row server) {
        Instant probedAt = server.get(ServerModel.PROBED_AT);
        return new HostPreflightReportView(
            kernelIsolationViewOf(server),
            preflightChecks(server),
            preflightFacts(server),
            probedAt != null ? probedAt.toString() : null,
            Boolean.TRUE.equals(server.get(ServerModel.PREFLIGHT_OK)));
    }

    /** Every stored check with its own status/required/detail/timestamp. */
    private static @NonNull List<PreflightCheckView> preflightChecks(@NonNull Row server) {
        List<PreflightCheckView> checks = new ArrayList<>();
        if (!(server.get(ServerModel.CAPABILITIES) instanceof Map<?, ?> capabilities)
                || !(capabilities.get(HostPreflight.CHECKS_KEY) instanceof Map<?, ?> stored)) {
            return checks;
        }
        for (Map.Entry<?, ?> entry : stored.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> check)) {
                continue;
            }
            checks.add(PreflightCheckView.of(
                String.valueOf(entry.getKey()),
                String.valueOf(check.get("status")),
                Boolean.TRUE.equals(check.get("required")),
                check.get("detail") != null ? String.valueOf(check.get("detail")) : "",
                check.get("at") != null ? String.valueOf(check.get("at")) : null));
        }
        return checks;
    }

    /** Every stored fact with its own measurement stamp. */
    private static @NonNull List<HostFactView> preflightFacts(@NonNull Row server) {
        List<HostFactView> facts = new ArrayList<>();
        if (!(server.get(ServerModel.CAPABILITIES) instanceof Map<?, ?> capabilities)) {
            return facts;
        }
        for (Map.Entry<?, ?> entry : capabilities.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (HostPreflight.CHECKS_KEY.equals(key) || HostPreflight.FACTS_AT_KEY.equals(key)) {
                continue;
            }
            Instant measuredAt = HostPreflight.factMeasuredAt(server, key);
            facts.add(new HostFactView(key, String.valueOf(entry.getValue()),
                measuredAt != null ? measuredAt.toString() : null));
        }
        return facts;
    }

    // -- capacity ------------------------------------------------------------------

    private static @NonNull HostCapacityView capacityOf(@NonNull Row server, int serverId) {
        Long budget = InstanceCapacity.budgetMbOf(server);
        boolean hasReading = server.get(ServerModel.CAPABILITIES) instanceof Map<?, ?> map
            && map.get(HostPreflight.MEM_TOTAL_FACT) instanceof Number;
        Instant measuredAt = HostPreflight.factMeasuredAt(server, HostPreflight.MEM_TOTAL_FACT);
        if (measuredAt == null && hasReading) {
            measuredAt = server.get(ServerModel.PROBED_AT);
        }
        Integer maxAge = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Capacity.FACTS_MAX_AGE_HOURS);
        long booked = InstanceCapacity.bookedMbOn(serverId);
        return new HostCapacityView(
            budget != null,
            budget == null && hasReading,
            budget != null ? clampInt(budget) : 0,
            clampInt(booked),
            budget != null ? clampInt(InstanceCapacity.bookableMbOn(serverId, budget)) : 0,
            clampInt(ProcessCapacity.bookedMbOn(serverId)),
            measuredAt != null ? measuredAt.toString() : null,
            maxAge != null ? maxAge : 0);
    }

    /**
     * The booking bar, with UNMEASURED as a first-class answer: no usable memory reading
     * means this host has no placement budget at all, and a zero bar there would read as
     * an empty host.
     */
    private static @NonNull UsageData capacityUsage(@NonNull HostCapacityView capacity,
                                                    @NonNull LocaleChain locales,
                                                    @Nullable MessageResolver resolver) {
        if (!capacity.measured()) {
            String reason = capacity.stale()
                ? Microcopy.of("evidence_stale").withFilter("scope", "server_overview")
                    .withArg("hours", String.valueOf(capacity.maxAgeHours()))
                    .resolve(locales, resolver)
                : text("unmeasured_body", locales, resolver);
            return UsageData.unmeasured(reason);
        }
        return UsageData.measured(capacity.bookedMb(), capacity.budgetMb(),
            megabytes(capacity.bookedMb()), megabytes(capacity.budgetMb()),
            capacity.measuredAtIso());
    }

    /** The numbers the bar itself cannot show: what is still bookable, and by whom. */
    private static @NonNull List<WidgetFact> capacityFacts(@NonNull HostCapacityView capacity,
                                                           @NonNull LocaleChain locales,
                                                           @Nullable MessageResolver resolver) {
        List<WidgetFact> facts = new ArrayList<>();
        if (!capacity.measured()) {
            return facts;
        }
        facts.add(WidgetFact.of(text("booked", locales, resolver), megabytes(capacity.bookedMb())));
        facts.add(WidgetFact.of(text("budget", locales, resolver), megabytes(capacity.budgetMb())));
        facts.add(WidgetFact.of(text("bookable", locales, resolver),
            megabytes(capacity.bookableMb())));
        if (capacity.processBookedMb() > 0) {
            facts.add(WidgetFact.of(text("process_booked", locales, resolver),
                megabytes(capacity.processBookedMb())));
        }
        return facts;
    }

    private static @NonNull String megabytes(int value) {
        return value + " MB";
    }

    private static int clampInt(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, value));
    }

    // -- workloads -----------------------------------------------------------------

    /**
     * The SAME three populations {@link ServerModel#refuseRemovalWhileOwned} counts:
     * live instances, stacks and managed databases referencing this host.
     */
    private static @NonNull List<WorkloadView> workloadsOf(@NonNull String panel,
                                                           int serverId) {
        List<WorkloadView> workloads = new ArrayList<>();
        for (Row instance : Models.get(InstanceModel.class).find()
                .where(InstanceModel.SERVER_ID.eq(serverId))
                .where(InstanceModel.DELETED_AT.isNull()).all()) {
            workloads.add(new WorkloadView(
                String.valueOf((Object) instance.get(InstanceModel.NAME)),
                "instance",
                badgeOf(InstanceModel.STATUS, instance.get(InstanceModel.STATUS)),
                instance.get(InstanceModel.CAPACITY_MB),
                CmsRoutes.detail(panel, "instances", instance.get(InstanceModel.ID))));
        }
        for (Row stack : Models.get(StackModel.class).find()
                .where(StackModel.SERVER_ID.eq(serverId)).all()) {
            workloads.add(new WorkloadView(
                String.valueOf((Object) stack.get(StackModel.NAME)),
                "stack",
                badgeOf(StackModel.STATUS, stack.get(StackModel.STATUS)),
                null,
                CmsRoutes.detail(panel, "stacks", stack.get(StackModel.ID))));
        }
        for (Row database : Models.get(DatabaseModel.class).find()
                .where(DatabaseModel.SERVER_ID.eq(serverId)).all()) {
            workloads.add(new WorkloadView(
                String.valueOf((Object) database.get(DatabaseModel.NAME)),
                "database",
                badgeOf(DatabaseModel.STATUS, database.get(DatabaseModel.STATUS)),
                database.get(DatabaseModel.MEMORY_LIMIT_MB),
                CmsRoutes.detail(panel, "databases", database.get(DatabaseModel.ID))));
        }
        return workloads;
    }

    // -- helpers -------------------------------------------------------------------

    private static @Nullable EnumBadgeState badgeOf(@NonNull EnumField field,
                                                    @Nullable Object raw) {
        return raw == null ? null : EnumBadgeState.of(field, raw);
    }

    private static @NonNull WidgetInstance alert(@NonNull AlertVariant variant,
                                                 @NonNull NoticeData notice) {
        return new WidgetInstance(AlertWidget.ID, Map.of("variant", variant.token()))
            .withData(notice);
    }

    private static @NonNull WidgetInstance band(@NonNull WidgetTree children) {
        return new WidgetInstance(SectionWidget.ID,
            Map.of("css_class", "hh-overview-band"), children);
    }

    private static @NonNull String text(@NonNull String key, @NonNull LocaleChain locales,
                                        @Nullable MessageResolver resolver) {
        return Microcopy.of(key).withFilter("scope", "server_overview").resolve(locales, resolver);
    }

    private static @NonNull String blankable(@Nullable String value) {
        return value != null ? value : "";
    }
}
