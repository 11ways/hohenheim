package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HostTrustLane;
import be.elevenways.hohenheim.WorkloadTier;
import be.elevenways.hohenheim.HohenheimWidgets;
import be.elevenways.hohenheim.host.HostCapacityView;
import be.elevenways.hohenheim.host.HostFactView;
import be.elevenways.hohenheim.host.HostPreflightReportView;
import be.elevenways.hohenheim.host.KernelIsolationView;
import be.elevenways.hohenheim.host.PostureAcknowledgementView;
import be.elevenways.hohenheim.host.PreflightCheckView;
import be.elevenways.hohenheim.host.TrustLaneView;
import be.elevenways.hohenheim.host.VolumeBackend;
import be.elevenways.hohenheim.host.WorkloadView;
import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.HostTrustSlot;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.server.host.HostFact;
import be.elevenways.hohenheim.server.host.HostKeys;
import be.elevenways.hohenheim.server.host.HostPins;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.host.HostProbe;
import be.elevenways.hohenheim.server.host.IncusPreflight;
import be.elevenways.hohenheim.server.host.PreflightFinding;
import be.elevenways.hohenheim.server.incus.IncusEndpoint;
import be.elevenways.hohenheim.server.incus.IncusKernelIsolation;
import be.elevenways.hohenheim.server.incus.IncusTrust;
import be.elevenways.hohenheim.server.instance.InstanceCapacity;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.MessageResolver;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.time.RelativeTime;
import be.elevenways.protoblast.common.time.RelativeTimeWording;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.render.table.EnumBadgeState;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.BadgeVariant;
import be.elevenways.zenit.widget.common.WidgetInstance;
import be.elevenways.zenit.widget.common.WidgetTree;
import be.elevenways.zenit.widget.common.builtin.AlertVariant;
import be.elevenways.zenit.widget.common.builtin.AlertWidget;
import be.elevenways.zenit.widget.common.builtin.FactListWidget;
import be.elevenways.zenit.widget.common.builtin.RecordsWidget;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Overview tab on a host record, and the record's own front door: the STORED evidence
 * the machinery already keeps -- admission and quarantine, per-lane trust state, the
 * full preflight report with per-check timestamps, capacity bookings and the workloads
 * that hold the host -- rendered structured instead of flattened into form-field
 * sentences.
 *
 * The RecordOverview mounts this widget tree and stays read-only: every
 * mutation on it is one of the resource's own placed operations, projected through
 * {@code zenit:record_actions} so confirmations, permissions and per-row visibility
 * stay single-sourced.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class ServerOverviewState {

    private ServerOverviewState() {}

    public static @NonNull WidgetTree widgets(@NonNull Row server, @NonNull AccessContext accessContext) {
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
            String reason = Objects.toString(server.get(ServerModel.QUARANTINE_REASON), "");
            String body = reason.isBlank()
                ? HohenheimMicrocopy.SERVER_OVERVIEW.of("quarantine_clears_by_repin").resolve(locales, resolver)
                : reason + " " + HohenheimMicrocopy.SERVER_OVERVIEW.of("quarantine_clears_by_repin")
                    .resolve(locales, resolver);
            bands.add(band(new WidgetTree(List.of(
                alert(AlertVariant.DESTRUCTIVE,
                    NoticeData.of(HohenheimMicrocopy.SERVER_OVERVIEW.of("quarantined_title")
                        .resolve(locales, resolver), body))))));
        }

        HostVerdict verdict = HostVerdict.of(server);
        List<WidgetInstance> state = new ArrayList<>();
        state.add(new WidgetInstance(StatusWidget.ID,
            Map.of("label", HohenheimWidgetCopy.localized(HohenheimMicrocopy.SERVER_OVERVIEW.of("state"))))
            .withData(stateBadges(server, verdict, locales, resolver)));
        state.add(new WidgetInstance(HohenheimWidgets.HOST_STATE.id(), Map.of())
            .withData(ServerParts.statusCellOf(server)));
        // Why a host takes no new apps, in the words the Hosts list and the attention band use (one verdict).
        if (verdict.standing().severity() != null && verdict.reason() != null) {
            state.add(alert(AlertVariant.WARNING, NoticeData.of(verdict.standing().label().resolve(locales, resolver),
                verdict.reason().resolve(locales, resolver))));
        }

        // The last failure in words (its kind's label, "Docker not found"), the transport's own text after it as the
        // technical line: a host once read "Last error" over raw ssh English.
        String lastError = Objects.toString(server.get(ServerModel.LAST_ERROR), "");
        if (!lastError.isBlank()) {
            String kind = Objects.toString(server.get(ServerModel.LAST_ERROR_KIND), "");
            String title = kind.isBlank() ? HohenheimMicrocopy.SERVER_OVERVIEW.of("last_error")
                .resolve(locales, resolver)
                : HostProbe.FailureKind.labelOf(kind).resolve(locales, resolver);
            state.add(alert(AlertVariant.DESTRUCTIVE,
                NoticeData.of(title, WorkloadErrors.technically(lastError).resolve(locales, resolver))));
        }

        // The volume-backend FINDING and its consequence, beside admission and posture:
        // what the preflight probe measured on the data root, and -- when it measured
        // nothing usable -- what that refuses and how to fix it, in the operator's words.
        VolumeBackend volumeBackend = ServerModel.volumeBackendOf(server);
        state.add(new WidgetInstance(FactListWidget.ID, Map.of())
            .withData(List.of(WidgetFact.badge(
                HohenheimMicrocopy.SERVER_OVERVIEW.of("volume_backend").resolve(locales, resolver),
                WidgetBadge.of(volumeBackend.label().resolve(locales, resolver),
                    volumeBackend.color(), volumeBackend.icon())))));
        if (!volumeBackend.supportsQuota() && volumeBackend.filesystemEnforcesQuota()) {
            // The filesystem COULD enforce a quota; this build has no operations for it.
            // Telling the operator to mount something else here would be a lie in the
            // other direction -- they already mounted a quota-capable filesystem.
            state.add(alert(AlertVariant.WARNING, NoticeData.of(
                HohenheimMicrocopy.SERVER_OVERVIEW.of("volume_backend_unsupported_title").resolve(locales, resolver),
                HohenheimMicrocopy.SERVER_OVERVIEW.of("volume_backend_unsupported_body")
                    .withArg("backend", volumeBackend.label())
                    .resolve(locales, resolver))));
        } else if (!volumeBackend.supportsQuota()) {
            state.add(alert(AlertVariant.WARNING, NoticeData.of(
                HohenheimMicrocopy.SERVER_OVERVIEW.of("volume_backend_none_title").resolve(locales, resolver),
                HohenheimMicrocopy.SERVER_OVERVIEW.of("volume_backend_none_body").resolve(locales, resolver))));
        } else if (!volumeBackend.supportsSnapshot()) {
            state.add(alert(AlertVariant.WARNING, NoticeData.of(
                HohenheimMicrocopy.SERVER_OVERVIEW.of("volume_backend_no_snapshot_title").resolve(locales, resolver),
                HohenheimMicrocopy.SERVER_OVERVIEW.of("volume_backend_no_snapshot_body").resolve(locales, resolver))));
        }

        PostureAcknowledgementView acknowledgement = acknowledgementViewOf(server);
        if (acknowledgement.needed()) {
            state.add(new WidgetInstance(FactListWidget.ID, Map.of())
                .withData(List.of(WidgetFact.badge(
                    HohenheimMicrocopy.SERVER_OVERVIEW.of("acknowledgement").resolve(locales, resolver),
                    acknowledgementBadge(acknowledgement, locales, resolver)))));
        }

        // The host's actions are its record heading's (zenitcms:record-head), never a second row here.
        bands.add(band(new WidgetTree(state)));

        List<TrustLaneView> lanes = trustLanes(server);
        if (!lanes.isEmpty()) {
            bands.add(band(new WidgetTree(List.of(
                new WidgetInstance(HohenheimWidgets.HOST_TRUST.id(), Map.of()).withData(lanes)))));
        }

        bands.add(band(new WidgetTree(List.of(
            new WidgetInstance(HohenheimWidgets.HOST_PREFLIGHT.id(), Map.of()).withData(preflightReport(server))))));

        HostCapacityView capacity = capacityOf(server, serverId);
        List<WidgetInstance> capacityBand = new ArrayList<>();
        capacityBand.add(new WidgetInstance(UsageBarWidget.ID,
                Map.of("label", HohenheimWidgetCopy.localized(HohenheimMicrocopy.SERVER_OVERVIEW.of("capacity"))))
            .withData(capacityUsage(capacity, locales, resolver)));
        // Without a usable reading there is nothing booked against a budget to list: no empty "Nothing to show".
        if (capacity.measured()) {
            capacityBand.add(new WidgetInstance(FactListWidget.ID, Map.of())
                .withData(capacityFacts(capacity, locales, resolver)));
        }
        bands.add(band(new WidgetTree(capacityBand)));

        bands.add(band(new WidgetTree(List.of(
            new WidgetInstance(HohenheimWidgets.HOST_WORKLOADS.id(), Map.of())
                .withData(workloadsOf(panelSlug, serverId))))));

        // AIDEV-NOTE: the per-record RECENT ACTIVITY band -- what an operator did to THIS
        // host, in the order it happened. Unconditional here, unlike the instance page's
        // copy: no delegated resource registers this page (ManagePanel projects no host
        // inventory at all), so there is no tenant audience to censor for. If one ever
        // appears it must take the instance page's !delegated branch, because the shared
        // `zenit.activity` source is gated on ADMIN_ACCESS and would render empty.
        bands.add(band(new WidgetTree(List.of(
            new WidgetInstance(RecordsWidget.ID, Map.of(
                "title", HohenheimWidgetCopy.localized(HohenheimMicrocopy.SERVER_OVERVIEW.of("recent_activity")),
                "source", CmsSupport.ACTIVITY_SOURCE,
                "rules", AdminActivityResource.peopleOnlyFor(Models.get(ServerModel.class), serverId),
                "sort", ActivityModel.CREATED_AT.getName(),
                "descending", true,
                "limit", 10))))));

        return new WidgetTree(List.of(new WidgetInstance(SectionWidget.ID,
            Map.of("css_class", "hh-server-overview"), new WidgetTree(bands))));
    }

    // -- state ---------------------------------------------------------------------

    /** The runtime, what the host takes (its verdict, never the stored admission token alone) and who may run here. */
    private static @NonNull List<WidgetBadge> stateBadges(@NonNull Row server, @NonNull HostVerdict verdict,
                                                          @NonNull LocaleChain locales,
                                                          @Nullable MessageResolver resolver) {
        List<WidgetBadge> badges = new ArrayList<>();
        badges.add(WidgetBadge.of(ServerModel.RUNTIME, ServerModel.runtimeOf(server),
            locales, resolver));
        badges.add(WidgetBadge.of(verdict.standing().label().resolve(locales, resolver), verdict.standing().variant(),
            verdict.standing().icon()));
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
            // Who accepted it; the warning's version is the record's bookkeeping, never words.
            return WidgetBadge.of(HohenheimMicrocopy.SERVER_OVERVIEW.of("ack_current")
                .withArg("actor", acknowledgement.actorLabel())
                .resolve(locales, resolver), BadgeVariant.SUCCESS, null);
        }
        if (acknowledgement.stale()) {
            return WidgetBadge.of(HohenheimMicrocopy.SERVER_OVERVIEW.of("ack_stale")
                .resolve(locales, resolver), BadgeVariant.DESTRUCTIVE, null);
        }
        return WidgetBadge.of(HohenheimMicrocopy.SERVER_OVERVIEW.of("ack_missing")
            .resolve(locales, resolver), BadgeVariant.DESTRUCTIVE, null);
    }

    // -- trust ---------------------------------------------------------------------

    /** The lanes this record declares, in transport-first order. */
    private static @NonNull List<TrustLaneView> trustLanes(@NonNull Row server) {
        List<TrustLaneView> lanes = new ArrayList<>();
        if (ServerModel.isIncusHttps(server)) {
            lanes.add(laneView(server, HostTrustLane.INCUS, HostTrustSlot.INCUS_TLS,
                IncusTrust::fingerprintOf));
        }
        if (ServerModel.hasSshLane(server)) {
            lanes.add(laneView(server, HostTrustLane.SSH, HostTrustSlot.SSH,
                HostKeys::fingerprintOf));
        }
        return lanes;
    }

    private static @NonNull TrustLaneView laneView(@NonNull Row server, @NonNull HostTrustLane lane,
                                                   @NonNull HostTrustSlot slot,
                                                   @NonNull UnaryOperator<String> digest) {
        String fingerprint = server.get(slot.fingerprint());
        String offered = slot.offeredOf(server);
        String client = server.get(slot.clientPublic());
        Instant pinnedAt = server.get(slot.pinnedAt());
        return new TrustLaneView(
            lane,
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
    static @NonNull HostPreflightReportView preflightReport(@NonNull Row server) {
        Instant probedAt = server.get(ServerModel.PROBED_AT);
        List<PreflightCheckView> mustPass = new ArrayList<>();
        List<PreflightCheckView> advice = new ArrayList<>();
        for (PreflightCheckView check : preflightChecks(server)) {
            (check.required() ? mustPass : advice).add(check.withLabel(HostPreflight.checkLabel(check.name()))
                    .withFix(fixFor(check)));
        }
        // What blocks comes first; within each half the stored order stays.
        Comparator<PreflightCheckView> failingFirst = Comparator.comparing(check -> !check.notPassing());
        mustPass.sort(failingFirst);
        advice.sort(failingFirst);
        return new HostPreflightReportView(
            kernelIsolationViewOf(server),
            mustPass,
            advice,
            preflightFacts(server),
            probedAt != null ? probedAt.toString() : null,
            Boolean.TRUE.equals(server.get(ServerModel.PREFLIGHT_OK)));
    }

    /**
     * What an operator does about a check that did not pass, or null.
     *
     * AIDEV-NOTE: the check names are the batteries' own declarations (HostPreflight.DOCKER_BATTERY,
     * IncusPreflight.BATTERY); HostCheckAndAdmitJourneyTest asserts every one of them has this copy and its
     * {@link HostPreflight#checkLabel} in both shipped catalogs, so a new check fails the build until it is named and
     * says how to fix it.
     */
    static @Nullable Microcopy fixFor(@NonNull PreflightCheckView check) {
        if (!check.notPassing() || !HostPreflight.declaredCheck(check.name())) {
            return null;
        }
        return fixCopy(check.name());
    }

    /** @return the how-to-fix sentence of one declared check */
    static @NonNull Microcopy fixCopy(@NonNull String checkName) {
        return HohenheimMicrocopy.SERVER_OVERVIEW.of("fix_" + checkName);
    }

    /** Every stored check with its own status/required/timestamp and what it found in words. */
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
            String detail = check.get("detail") != null ? String.valueOf(check.get("detail")) : "";
            checks.add(PreflightCheckView.of(
                String.valueOf(entry.getKey()),
                String.valueOf(check.get("status")),
                Boolean.TRUE.equals(check.get("required")),
                detail,
                check.get("at") != null ? String.valueOf(check.get("at")) : null)
                .withDetail(PreflightFinding.wordsOf(check.get(PreflightFinding.TOKEN_KEY),
                    check.get(PreflightFinding.ARGS_KEY), detail)));
        }
        return checks;
    }

    /** Every stored fact in words with its unit and its own measurement stamp; an undeclared one as stored. */
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
            HostFact fact = HostFact.ofToken(key);
            facts.add(new HostFactView(key,
                fact != null ? fact.label() : Microcopy.literal(key),
                fact != null ? fact.valueText(entry.getValue()) : String.valueOf(entry.getValue()),
                measuredAt != null ? measuredAt.toString() : null));
        }
        return facts;
    }

    // -- capacity ------------------------------------------------------------------

    /** The ledger itself lives with the booking; this page and the hosts API read the same one. */
    private static @NonNull HostCapacityView capacityOf(@NonNull Row server, int serverId) {
        return InstanceCapacity.viewOf(server, serverId);
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
            // The reason carries when it was last measured: the unmeasured bar has no time slot of its own.
            String reason = capacity.stale() && capacity.measuredAtIso() != null
                ? HohenheimMicrocopy.SERVER_OVERVIEW.of("evidence_stale")
                    .withArg("ago", RelativeTime.ago(Instant.parse(capacity.measuredAtIso()),
                        RelativeTimeWording.resolve(locales, resolver)))
                    .resolve(locales, resolver)
                : HohenheimMicrocopy.SERVER_OVERVIEW.of("unmeasured_body").resolve(locales, resolver);
            return UsageData.unmeasured(reason);
        }
        return UsageData.measured(capacity.bookedMb(), capacity.budgetMb(),
            ServerParts.sizeOfMegabytes(capacity.bookedMb()), ServerParts.sizeOfMegabytes(capacity.budgetMb()),
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
        facts.add(WidgetFact.of(HohenheimMicrocopy.SERVER_OVERVIEW.of("booked")
            .resolve(locales, resolver), ServerParts.sizeOfMegabytes(capacity.bookedMb())));
        facts.add(WidgetFact.of(HohenheimMicrocopy.SERVER_OVERVIEW.of("budget")
            .resolve(locales, resolver), ServerParts.sizeOfMegabytes(capacity.budgetMb())));
        facts.add(WidgetFact.of(HohenheimMicrocopy.SERVER_OVERVIEW.of("bookable").resolve(locales, resolver),
            ServerParts.sizeOfMegabytes(capacity.bookableMb())));
        return facts;
    }

    // -- workloads -----------------------------------------------------------------

    /**
     * What runs on this host, once each: its instances, stacks, managed databases and database engines, the records
     * {@link ServerModel#refuseRemovalWhileOwned} counts, with the memory each books in the capacity ledger.
     *
     * AIDEV-NOTE: a database engine, and a dedicated database, runs as an instance it owns (generated_for); the ledger
     * books that instance once. The owner row is the one an operator recognises and the one that blocks removal, so it
     * stands for the booking and its owned instance is not listed again (once: dbengine-mongo-local and mongo-local,
     * 512 MB each, one engine). An owned instance whose owner is not on this host (a move in flight) stays listed, so
     * nothing booked here goes missing.
     */
    static @NonNull List<WorkloadView> workloadsOf(@NonNull String panel, int serverId) {
        List<Row> databases = Models.get(DatabaseModel.class).find()
            .where(DatabaseModel.SERVER_ID.eq(serverId)).all();
        List<Row> engines = Models.get(DatabaseEngineModel.class).find()
            .where(DatabaseEngineModel.SERVER_ID.eq(serverId)).all();
        Set<String> owners = new HashSet<>();
        for (Row database : databases) {
            owners.add(ownerKey(DatabaseModel.MODEL_ID, database.get(DatabaseModel.ID)));
        }
        for (Row engine : engines) {
            owners.add(ownerKey(DatabaseEngineModel.MODEL_ID, engine.get(DatabaseEngineModel.ID)));
        }
        Map<String, Row> owned = new HashMap<>();
        List<WorkloadView> workloads = new ArrayList<>();
        for (Row instance : Models.get(InstanceModel.class).find()
                .where(InstanceModel.SERVER_ID.eq(serverId))
                .all()) {
            String owner = ownerKey(instance.get(InstanceModel.GENERATED_FOR_MODEL),
                instance.get(InstanceModel.GENERATED_FOR_ID));
            if (owners.contains(owner)) {
                owned.put(owner, instance);
                continue;
            }
            workloads.add(new WorkloadView(
                String.valueOf((Object) instance.get(InstanceModel.NAME)),
                WorkloadTier.INSTANCE,
                EnumBadgeState.ofNullable(InstanceModel.STATUS, instance.get(InstanceModel.STATUS)),
                instance.get(InstanceModel.CAPACITY_MB),
                // A release row is not served by the instance list; the route sends it to
                // its application's Deploys tab instead of a 404.
                InstanceParts.recordRoute(panel, instance, null)));
        }
        for (Row stack : Models.get(StackModel.class).find()
                .where(StackModel.SERVER_ID.eq(serverId)).all()) {
            workloads.add(new WorkloadView(
                String.valueOf((Object) stack.get(StackModel.NAME)),
                WorkloadTier.STACK,
                EnumBadgeState.ofNullable(StackModel.STATUS, stack.get(StackModel.STATUS)),
                null,
                CmsRoutes.detail(panel, HohenheimSlugs.STACKS, stack.get(StackModel.ID))));
        }
        for (Row database : databases) {
            Row instance = owned.get(ownerKey(DatabaseModel.MODEL_ID, database.get(DatabaseModel.ID)));
            workloads.add(new WorkloadView(
                String.valueOf((Object) database.get(DatabaseModel.NAME)),
                WorkloadTier.DATABASE,
                // What it does, never the stored "active" (DatabaseVerdict): the list and the attention band agree.
                DatabaseVerdict.ofDatabase(database).badge(),
                // A shared database books nothing of its own: its engine does.
                instance == null ? null : instance.get(InstanceModel.CAPACITY_MB),
                CmsRoutes.detail(panel, HohenheimSlugs.DATABASES, database.get(DatabaseModel.ID))));
        }
        for (Row engine : engines) {
            Row instance = owned.get(ownerKey(DatabaseEngineModel.MODEL_ID, engine.get(DatabaseEngineModel.ID)));
            workloads.add(new WorkloadView(
                String.valueOf((Object) engine.get(DatabaseEngineModel.NAME)),
                WorkloadTier.DATABASE_ENGINE,
                DatabaseVerdict.ofEngine(engine).badge(),
                instance == null ? null : instance.get(InstanceModel.CAPACITY_MB),
                CmsRoutes.detail(panel, HohenheimSlugs.DATABASE_ENGINES,
                    engine.get(DatabaseEngineModel.ID))));
        }
        return workloads;
    }

    /** @return the owning record of a generated instance as one key; never matches for an instance nothing owns */
    private static @NonNull String ownerKey(@Nullable Object model, @Nullable Object id) {
        return model == null || id == null ? "" : model + "#" + id;
    }

    // -- helpers -------------------------------------------------------------------

    private static @NonNull WidgetInstance alert(@NonNull AlertVariant variant,
                                                 @NonNull NoticeData notice) {
        return new WidgetInstance(AlertWidget.ID, Map.of("variant", variant.token()))
            .withData(notice);
    }

    private static @NonNull WidgetInstance band(@NonNull WidgetTree children) {
        return new WidgetInstance(SectionWidget.ID,
            Map.of("css_class", "hh-overview-band"), children);
    }
}
