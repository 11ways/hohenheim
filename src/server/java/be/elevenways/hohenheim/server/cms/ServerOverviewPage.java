package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.host.HostCapacityView;
import be.elevenways.hohenheim.host.HostFactView;
import be.elevenways.hohenheim.host.KernelIsolationView;
import be.elevenways.hohenheim.host.PostureAcknowledgementView;
import be.elevenways.hohenheim.host.PreflightCheckView;
import be.elevenways.hohenheim.host.ServerActionsView;
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
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.time.RelativeTimeWording;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.render.action.InvokeActionState;
import be.elevenways.zenit.cms.common.render.table.EnumBadgeState;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.server.render.action.ActionStateTranslator;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.server.http.ReturnTarget;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Overview tab on a host record: the STORED evidence the machinery already keeps --
 * admission and quarantine, per-lane trust state, the full preflight report with
 * per-check timestamps, capacity bookings and the workloads that hold the host --
 * rendered structured instead of flattened into form-field sentences.
 *
 * Read-only by design ({@link RecordScopedPage}); every mutation on the page is one of
 * the resource's own {@code RowAction}s, projected through the standard action-state
 * translation so confirmations, permissions and per-row visibility stay single-sourced.
 */
public final class ServerOverviewPage implements RecordScopedPage<Row> {

    public static final String SLUG = "overview";

    private final ServerResource resource;
    private final ActionStateTranslator actions = new ActionStateTranslator();

    ServerOverviewPage(@NonNull ServerResource resource) {
        this.resource = resource;
    }

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "server_overview"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("overview").withFilter("scope", "server"); }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Icon icon() { return Icon.of("gauge"); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row server) {
        String name = server.get(ServerModel.NAME);
        Integer serverId = server.get(ServerModel.ID);
        String panel = CmsSupport.panelSlug(conduit);
        String overviewUrl = CmsRoutes.subpage(panel, "servers", serverId, SLUG).toUrl();

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, "server_overview", name));
        vars.put("serverName", name);
        vars.put("runtimeBadge", EnumBadgeState.of(ServerModel.RUNTIME,
            ServerModel.runtimeOf(server)));
        vars.put("admissionBadge", badgeOf(ServerModel.ADMISSION,
            server.get(ServerModel.ADMISSION)));
        vars.put("postureBadge", badgeOf(ServerModel.POSTURE,
            server.get(ServerModel.POSTURE)));
        vars.put("statusCell", ServerResource.statusCellOf(server));
        vars.put("lastError", blankable(server.get(ServerModel.LAST_ERROR)));
        vars.put("quarantinedAtIso", isoOf(server.get(ServerModel.QUARANTINED_AT)));
        vars.put("quarantineReason", blankable(server.get(ServerModel.QUARANTINE_REASON)));
        vars.put("trustLanes", trustLanes(server));
        vars.put("acknowledgement", acknowledgementViewOf(server));
        vars.put("kernel", kernelIsolationViewOf(server));
        vars.put("preflightChecks", preflightChecks(server));
        vars.put("preflightFacts", preflightFacts(server));
        vars.put("probedAtIso", isoOf(server.get(ServerModel.PROBED_AT)));
        vars.put("preflightOk", Boolean.TRUE.equals(server.get(ServerModel.PREFLIGHT_OK)));
        vars.put("capacity", capacityOf(server, serverId));
        vars.put("workloads", workloadsOf(panel, serverId));
        vars.put("actions", this.actionsOf(server, accessContext, overviewUrl));
        vars.put("timeWording", RelativeTimeWording.resolve(
            conduit.getLocales(), conduit.getMessageResolver()));
        vars.put("recordTabs", recordTabs(conduit));
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/server-overview"), vars);
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

    // -- actions -------------------------------------------------------------------

    /**
     * The resource's row actions for THIS row and viewer, targeting the standard invoke
     * endpoint with {@code _return} pointing back at the overview page.
     */
    private @NonNull ServerActionsView actionsOf(@NonNull Row server,
                                                 @NonNull AccessContext accessContext,
                                                 @NonNull String overviewUrl) {
        Object serverId = server.get(ServerModel.ID);
        // AIDEV-NOTE: RowAction.Url is Uri-typed, hence the render here. The panel slug is
        // the literal "admin" this page already produced -- the server resource is
        // installation administration and the URL must not move; converting the SHAPE is
        // this change, converting the PANEL would be a behaviour change.
        ActionStateTranslator.RowActionPresentation presentation =
            this.actions.translateRowActionsForList(this.resource.rowActions(), server,
                (actionId, row) -> new Uri(ReturnTarget.bind(
                    CmsRoutes.invokeRow("admin", "servers", serverId, actionId),
                    overviewUrl).toUrl()),
                accessContext);
        Map<String, InvokeActionState> byPath = new LinkedHashMap<>();
        for (InvokeActionState state : presentation.inlineInvokes()) {
            byPath.put(state.id().getPath(), state);
        }
        for (InvokeActionState state : presentation.overflowInvokes()) {
            byPath.put(state.id().getPath(), state);
        }
        return new ServerActionsView(
            byPath.get("scan_host_key"), byPath.get("confirm_host_key"),
            byPath.get("repin_host_key"), byPath.get("rotate_host_key"),
            byPath.get("scan_incus_cert"), byPath.get("confirm_incus_cert"),
            byPath.get("repin_incus_cert"), byPath.get("rotate_incus_cert"),
            byPath.get("probe_server"), byPath.get("preflight_server"),
            byPath.get("admit_server"), byPath.get("cordon_server"),
            byPath.get("uncordon_server"), byPath.get("drain_server"),
            byPath.get("reap_controller_objects"), byPath.get("acknowledge_posture"));
    }

    // -- helpers -------------------------------------------------------------------

    private static @Nullable EnumBadgeState badgeOf(@NonNull EnumField field,
                                                    @Nullable Object raw) {
        return raw == null ? null : EnumBadgeState.of(field, raw);
    }

    private static @Nullable String isoOf(@Nullable Instant instant) {
        return instant != null ? instant.toString() : null;
    }

    private static @NonNull String blankable(@Nullable String value) {
        return value != null ? value : "";
    }
}
