package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.instance.InstanceActionsView;
import be.elevenways.hohenheim.instance.InstanceDiskView;
import be.elevenways.hohenheim.instance.InstanceEndpointView;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.time.RelativeTimeWording;
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
        String base = CmsSupport.panelBase(conduit);
        String overviewUrl = base + "/" + this.resource.slug() + "/" + instanceId
            + "/page/" + SLUG;

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, "instance_overview", name));
        vars.put("instanceName", name);
        vars.put("instanceId", instanceId);
        vars.put("statusBadge", EnumBadgeState.of(InstanceModel.STATUS,
            instance.get(InstanceModel.STATUS)));
        vars.put("kindBadge", badgeOf(InstanceModel.KIND, instance.get(InstanceModel.KIND)));
        vars.put("installBadge", badgeOf(InstanceModel.INSTALL_STATE,
            instance.get(InstanceModel.INSTALL_STATE)));
        vars.put("hostName", ServerModel.nameOf(serverId));
        vars.put("hostUrl", base + "/servers/" + serverId + "/page/" + ServerOverviewPage.SLUG);
        vars.put("installError", blankable(instance.get(InstanceModel.INSTALL_ERROR)));
        vars.put("disk", diskOf(instance, serverId));
        vars.put("endpoints", endpointsOf(instanceId));
        vars.put("actions", this.actionsOf(instance, accessContext, overviewUrl, base));
        vars.put("timeWording", RelativeTimeWording.resolve(
            conduit.getLocales(), conduit.getMessageResolver()));
        vars.put("recordTabs", recordTabs(conduit));
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/instance-overview"), vars);
    }

    // -- disk ------------------------------------------------------------------------

    /**
     * The STORED observation {@code ObserveInstanceDisk} stamps, which until this page
     * shipped was read by {@code AttentionCollector} and by nothing else.
     *
     * A null observation is SILENCE, exactly as the collector reads it: Docker enforces
     * no root quota and stamps nothing, so the whole tier is unmeasured by contract and
     * a percentage computed from zeros would be a fabricated reading.
     */
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
                                                   @NonNull String base) {
        Object instanceId = instance.get(InstanceModel.ID);
        String suffix = "?_return=" + Uri.encodeComponent(overviewUrl);
        String actionBase = base + "/" + this.resource.slug() + "/" + instanceId + "/action/";
        ActionStateTranslator.RowActionPresentation presentation =
            this.actions.translateRowActionsForList(this.resource.rowActions(), instance,
                (actionId, row) -> new Uri(actionBase + actionId.getPath() + suffix),
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
