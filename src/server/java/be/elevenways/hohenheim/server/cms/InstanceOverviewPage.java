package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.instance.InstanceActionsView;
import be.elevenways.hohenheim.instance.InstanceBlockerView;
import be.elevenways.hohenheim.instance.InstanceDiskView;
import be.elevenways.hohenheim.instance.InstanceEndpointView;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.OwnedInstances;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.time.RelativeTimeWording;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.render.action.InvokeActionState;
import be.elevenways.zenit.cms.common.render.action.LinkActionState;
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
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.http.ReturnTarget;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Overview tab on an instance, and the list row's own target: status and power in one
 * place, the STORED disk observation that until now only the attention collector read,
 * and the public endpoint resolved out of the port ledger.
 *
 * Composition, not construction ({@link ServerOverviewPage} is the shape this follows):
 * every button is one of the HOST RESOURCE'S own row actions projected through the
 * standard action-state translation, so a /manage reader gets exactly the shorter list
 * {@link ManageInstanceResource} declares, with each action's per-record capability
 * predicate already applied.
 */
public final class InstanceOverviewPage implements RecordScopedPage<Row> {

    public static final String SLUG = "overview";

    private final InstanceResource resource;
    private final ActionStateTranslator actions = new ActionStateTranslator();

    InstanceOverviewPage(@NonNull InstanceResource resource) {
        this.resource = resource;
    }

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_overview"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("overview").withFilter("scope", "instance"); }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Icon icon() { return Icon.of("gauge"); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row instance) {
        Integer instanceId = instance.get(InstanceModel.ID);
        String name = String.valueOf((Object) instance.get(InstanceModel.NAME));
        int serverId = ServerModel.canonicalServerId(instance.get(InstanceModel.SERVER_ID));
        String panel = CmsSupport.panelSlug(conduit);
        // The delegated projection, applied FIELD BY FIELD rather than by trusting the
        // resource: this page is the SAME class on both panels (ManageInstanceResource
        // registers it verbatim), so the omissions are here or nowhere. See the two
        // AIDEV-NOTEs below for what is dropped and what deliberately is not.
        boolean delegated = CmsSupport.isDelegatedPanel(conduit);
        String overviewUrl = CmsRoutes.subpage(panel, this.resource.slug(), instanceId, SLUG)
            .toUrl();

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, "instance_overview", name));
        vars.put("instanceName", name);
        vars.put("instanceId", instanceId);
        vars.put("statusBadge", EnumBadgeState.of(InstanceModel.STATUS,
            instance.get(InstanceModel.STATUS)));
        vars.put("kindBadge", badgeOf(InstanceModel.KIND, instance.get(InstanceModel.KIND)));
        vars.put("installBadge", badgeOf(InstanceModel.INSTALL_STATE,
            instance.get(InstanceModel.INSTALL_STATE)));
        // AIDEV-NOTE: the host is operator inventory, and BOTH halves leak it -- the name
        // is the machine's identity and the link carries its numeric server id, which is
        // the id every host-scoped admin route is keyed on. A tenant is told WHAT their
        // workload is doing, never WHERE it runs; the endpoint list below is the one
        // address they legitimately get, because it is the address they connect to.
        vars.put("hostName", delegated ? "" : ServerModel.nameOf(serverId));
        vars.put("hostTarget", delegated ? null
            : CmsRoutes.subpage(panel, "servers", serverId, ServerOverviewPage.SLUG));
        // AIDEV-NOTE: install_error is stamped with the daemon's or transport's OWN text
        // (InstanceInstalls stamps describe(IOException) and "exit N" plus the script's
        // output tail), so it names image registries, socket paths, host paths and ssh
        // failures. The install BADGE already tells the tenant the install failed, which
        // is the fact they can act on; the operator reads the reason on /admin.
        vars.put("installError", delegated ? ""
            : blankable(instance.get(InstanceModel.INSTALL_ERROR)));
        vars.put("deployBlocker", deployBlockerOf(conduit, instance, serverId, delegated));
        vars.put("disk", diskOf(instance, serverId));
        vars.put("endpoints", endpointsOf(instanceId));
        vars.put("actions", this.actionsOf(instance, accessContext, overviewUrl, panel));
        vars.put("timeWording", RelativeTimeWording.resolve(
            conduit.getLocales(), conduit.getMessageResolver()));
        vars.put("recordTabs", recordTabs(conduit));
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/instance-overview"), vars);
    }

    // -- deploy blocker --------------------------------------------------------------

    /**
     * Why this instance's next deploy will be refused, or {@link InstanceBlockerView#CLEAR}.
     *
     * AIDEV-NOTE: this asks the deploy lane's OWN gate (HostAdmission.instancePlacementRefusal)
     * behind the deploy lane's OWN predicate (OwnedInstances.isPlacementGated), so the
     * explanation and the refusal cannot drift. It deliberately does NOT call
     * InstanceService.resolve (which builds variables, a DB environment, a spec and a runtime
     * client, and throws refusals of its own) or HostLeases.requireFence (which ACQUIRES a
     * lease). This page explains DECLARED preconditions; controller contention is a runtime
     * event and stays a toast.
     *
     * The catch is not decoration: InstanceMigrations records that ONE unaddressable host
     * used to 500 the whole migrate page, and a bad host record must never kill an instance's
     * landing page.
     */
    private static @NonNull InstanceBlockerView deployBlockerOf(@NonNull Conduit conduit,
                                                                @NonNull Row instance,
                                                                int serverId,
                                                                boolean delegated) {
        try {
            InstanceKindHandler handler = InstanceKinds.getHandler(instance.get(InstanceModel.KIND));

            // An unknown kind is its own story, and resolving it would throw here.
            if (handler == null || !OwnedInstances.isPlacementGated(handler, instance)) {
                return InstanceBlockerView.CLEAR;
            }

            Microcopy refusal = HostAdmission.instancePlacementRefusal(serverId,
                handler.isolation(), instance.get(InstanceModel.QUOTA_BUCKET));

            if (refusal == null) {
                return InstanceBlockerView.CLEAR;
            }

            // The host-free sentence is decided by the SURFACE, exactly as hostName and
            // installError are above: an operator opening /manage must see what a tenant
            // sees there. Every placement refusal interpolates the host's NAME, which is
            // operator inventory this panel blanks everywhere else.
            if (delegated) {
                return new InstanceBlockerView(true,
                    Microcopy.of("deploy_blocked_delegated")
                        .withFilter("scope", "instance_overview")
                        .resolve(conduit.getLocales(), conduit.getMessageResolver()), false);
            }

            return new InstanceBlockerView(true,
                refusal.resolve(conduit.getLocales(), conduit.getMessageResolver()), true);
        } catch (RuntimeException failed) {
            return InstanceBlockerView.CLEAR;
        }
    }

    // -- disk ------------------------------------------------------------------------

    /**
     * The STORED observation {@code ObserveInstanceDisk} stamps, which until this page
     * shipped was read by {@code AttentionCollector} and by nothing else.
     *
     * A null observation is SILENCE, exactly as the collector reads it: Docker enforces
     * no root quota and stamps nothing, so the whole tier is unmeasured by contract and
     * a percentage computed from zeros would be a fabricated reading.
     *
     * AIDEV-NOTE: "by contract" is a recorded DECISION, not an omission awaiting a fix --
     * the reasoning lives on {@code ResourceLimits} and in docs/instance-tier-plan.md
     * beside the runtime-limits gate clause. Read it before changing this branch.
     */
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
    private static @NonNull List<InstanceEndpointView> endpointsOf(int instanceId) {
        List<InstanceEndpointView> endpoints = new ArrayList<>();
        for (Row claim : PortLedger.claimsOf(InstanceModel.MODEL_ID, instanceId)) {
            Integer port = claim.get(PortAllocationModel.PORT);
            if (port == null) {
                continue;
            }
            endpoints.add(new InstanceEndpointView(
                addressOf(claim),
                port,
                blankable(claim.get(PortAllocationModel.PROTOCOL)),
                blankable(claim.get(PortAllocationModel.STATUS)),
                PortLedger.isPreallocated(claim)));
        }
        return endpoints;
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

    // -- actions ---------------------------------------------------------------------

    /**
     * The host resource's row actions for THIS record and viewer, targeting the standard
     * invoke endpoint with {@code _return} pointing back at this page, so a
     * Refresh/Toast result re-renders the page the operator is looking at.
     */
    private @NonNull InstanceActionsView actionsOf(@NonNull Row instance,
                                                   @NonNull AccessContext accessContext,
                                                   @NonNull String overviewUrl,
                                                   @NonNull String panel) {
        Object instanceId = instance.get(InstanceModel.ID);
        // AIDEV-NOTE: RowAction.Url is Uri-typed, so the typed target is rendered here
        // rather than concatenated; _return rides ReturnTarget.bind, never "?_return=" + x.
        ActionStateTranslator.RowActionPresentation presentation =
            this.actions.translateRowActionsForList(this.resource.rowActions(), instance,
                (actionId, row) -> new Uri(ReturnTarget.bind(
                    CmsRoutes.invokeRow(panel, this.resource.slug(), instanceId, actionId),
                    overviewUrl).toUrl()),
                accessContext);
        Map<String, InvokeActionState> invokes = new LinkedHashMap<>();
        for (InvokeActionState state : presentation.inlineInvokes()) {
            invokes.put(state.id().getPath(), state);
        }
        for (InvokeActionState state : presentation.overflowInvokes()) {
            invokes.put(state.id().getPath(), state);
        }
        Map<String, LinkActionState> links = new LinkedHashMap<>();
        for (LinkActionState state : presentation.inlineLinks()) {
            links.put(state.id().getPath(), state);
        }
        for (LinkActionState state : presentation.overflowLinks()) {
            links.put(state.id().getPath(), state);
        }
        return new InstanceActionsView(
            invokes.get("deploy_instance"), invokes.get("stop_instance"),
            invokes.get("restart_instance"), invokes.get("install_instance"),
            invokes.get("reinstall_instance"), invokes.get("app_update_instance"),
            invokes.get("snapshot_instance"), invokes.get("backup_instance"),
            links.get("migrate_instance"));
    }

    // -- helpers ---------------------------------------------------------------------

    private static @Nullable EnumBadgeState badgeOf(@NonNull EnumField field,
                                                    @Nullable Object raw) {
        return raw == null ? null : EnumBadgeState.of(field, raw);
    }

    private static @NonNull String blankable(@Nullable String value) {
        return value != null ? value : "";
    }
}
