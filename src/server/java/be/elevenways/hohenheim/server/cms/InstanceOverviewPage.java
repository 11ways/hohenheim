package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.InstanceEndpointsWidget;
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
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.MessageResolver;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.resource.RecordDashboardPage;
import be.elevenways.zenit.cms.common.widget.RecordActionsWidget;
import be.elevenways.zenit.cms.server.render.action.RecordActionBands;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.orm.activity.ActivityRules;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.text.ByteText;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.widget.common.WidgetInstance;
import be.elevenways.zenit.widget.common.WidgetTree;
import be.elevenways.zenit.widget.common.builtin.ActionButtonWidget;
import be.elevenways.zenit.widget.common.builtin.AlertVariant;
import be.elevenways.zenit.widget.common.builtin.AlertWidget;
import be.elevenways.zenit.widget.common.builtin.FactWidget;
import be.elevenways.zenit.widget.common.builtin.RecordsWidget;
import be.elevenways.zenit.widget.common.builtin.SectionWidget;
import be.elevenways.zenit.widget.common.builtin.StatusWidget;
import be.elevenways.zenit.widget.common.builtin.UsageBarWidget;
import be.elevenways.zenit.widget.common.data.NoticeData;
import be.elevenways.zenit.widget.common.data.UsageData;
import be.elevenways.zenit.widget.common.data.WidgetBadge;
import be.elevenways.zenit.widget.common.data.WidgetFact;
import be.elevenways.zenit.widget.common.surface.SurfaceActionOutcome;
import be.elevenways.zenit.widget.common.surface.SurfaceActionRequest;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Overview tab on an instance, and the record's own front door: status and power in one
 * place, the STORED disk observation that until this page shipped only the attention
 * collector read, and the public endpoint resolved out of the port ledger.
 *
 * The page IS a widget tree ({@link RecordDashboardPage}), so the action row is the
 * resource's own row actions through {@code zenitcms:record_actions}, and the bespoke
 * endpoint table is an app-local widget type rather than a hand-rendered template.
 *
 * AIDEV-NOTE: the delegated projection is applied FIELD BY FIELD in {@link #widgets},
 * not by trusting the resource: this is the SAME page class on both panels
 * ({@link ManageInstanceResource} registers it verbatim), so the omissions are here or
 * nowhere. See the AIDEV-NOTEs at each censored band for what is dropped and why.
 */
public final class InstanceOverviewPage extends RecordDashboardPage<Row> {

    public static final String SLUG = "overview";

    /** The one widget-native action on this page: re-read the stored evidence. */
    static final String REFRESH_ACTION = "refresh";

    private final InstanceResource resource;

    InstanceOverviewPage(@NonNull InstanceResource resource) {
        this.resource = resource;
    }

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_overview"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("overview").withFilter("scope", "instance"); }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Icon icon() { return Icon.of("gauge"); }

    @Override
    public @NonNull WidgetTree widgets(@NonNull Row instance, @NonNull AccessContext accessContext) {
        Conduit conduit = accessContext.conduit();
        Integer instanceId = instance.get(InstanceModel.ID);
        int serverId = ServerModel.canonicalServerId(instance.get(InstanceModel.SERVER_ID));
        String panelSlug = CmsSupport.panelSlug(conduit);
        boolean delegated = CmsSupport.isDelegatedPanel(conduit);
        LocaleChain locales = conduit.getLocales();
        MessageResolver resolver = conduit.getMessageResolver();

        List<WidgetInstance> state = new ArrayList<>();
        state.add(new WidgetInstance(StatusWidget.ID,
            Map.of("label", HohenheimWidgetCopy.localized("state", "instance_overview")))
            .withData(statusBadges(instance, locales, resolver)));

        // AIDEV-NOTE: the host is operator inventory, and BOTH halves leak it -- the name
        // is the machine's identity and the link carries its numeric server id, which is
        // the id every host-scoped admin route is keyed on. A tenant is told WHAT their
        // workload is doing, never WHERE it runs; the endpoint list below is the one
        // address they legitimately get, because it is the address they connect to. The
        // censoring is the fact simply NOT BEING ADDED to the delegated tree.
        if (!delegated) {
            state.add(new WidgetInstance(FactWidget.ID, Map.of())
                .withData(WidgetFact.link(
                    text("host", "instance_overview", locales, resolver),
                    ServerModel.nameOf(serverId),
                    CmsRoutes.subpage(panelSlug, "servers", serverId, ServerOverviewPage.SLUG)
                        .toUrl())));
        }

        // AIDEV-NOTE: install_error is stamped with the daemon's or transport's OWN text
        // (InstanceInstalls stamps describe(IOException) and "exit N" plus the script's
        // output tail), so it names image registries, socket paths, host paths and ssh
        // failures. The install BADGE already tells the tenant the install failed, which
        // is the fact they can act on; the operator reads the reason on /admin.
        String installError = instance.get(InstanceModel.INSTALL_ERROR);
        if (!delegated && installError != null && !installError.isBlank()) {
            state.add(alert(AlertVariant.DESTRUCTIVE, NoticeData.of(
                text("install_error", "instance_overview", locales, resolver), installError)));
        }

        // The DECLARED precondition that will refuse the next deploy, stated where the
        // operator already is. Deploy stays offered on purpose: the button is what proves
        // the explanation is about a host, not about their own authority.
        InstanceBlockerView blocker = deployBlockerOf(conduit, instance, serverId, delegated);
        if (blocker.blocked()) {
            String hostUrl = blocker.hostLinkable() && !delegated
                ? CmsRoutes.subpage(panelSlug, "servers", serverId, ServerOverviewPage.SLUG).toUrl()
                : null;
            NoticeData notice = hostUrl == null
                ? NoticeData.of(text("deploy_blocked", "instance_overview", locales, resolver),
                    blocker.reason())
                : NoticeData.link(text("deploy_blocked", "instance_overview", locales, resolver),
                    blocker.reason(), hostUrl,
                    Microcopy.of("deploy_blocked_fix").withFilter("scope", "instance_overview")
                        .withArg("host", ServerModel.nameOf(serverId))
                        .resolve(locales, resolver));
            state.add(alert(AlertVariant.WARNING, notice));
        }

        Panel panel = PanelRegistry.getBySlug(panelSlug);
        if (panel != null) {
            state.add(new WidgetInstance(RecordActionsWidget.ID, Map.of())
                .withData(RecordActionBands.forRecord(panel, this.resource, instance,
                    accessContext, conduit)));
        }
        state.add(new WidgetInstance(ActionButtonWidget.ID, Map.of(
            "label", HohenheimWidgetCopy.localized("refresh", "instance_overview"),
            "action", REFRESH_ACTION,
            "variant", "outline")));

        List<WidgetInstance> bands = new ArrayList<>();
        bands.add(band(new WidgetTree(state)));
        bands.add(band(new WidgetTree(List.of(
            new WidgetInstance(UsageBarWidget.ID,
                Map.of("label", HohenheimWidgetCopy.localized("disk", "instance_overview")))
                .withData(diskUsage(instance, serverId, locales, resolver))))));
        bands.add(band(new WidgetTree(List.of(
            new WidgetInstance(InstanceEndpointsWidget.ID, Map.of())
                .withData(endpointsOf(instanceId))))));

        // AIDEV-NOTE: the per-record RECENT ACTIVITY band. It was blocked until zenit-cms's
        // `zenit.activity` source started PROJECTING record_id: a source's rule vocabulary is
        // derived from its projection, so `ActivityRules.forRecord` failed validation with
        // `unknown_variable: record_id` and every render 500'd. The pairing itself stays
        // ActivityRules' -- never a hand-spelled model token plus a stringified id here.
        //
        // AIDEV-NOTE: OPERATOR-ONLY, and deliberately by omission rather than by an empty
        // list. The shared source is registered with HohenheimSources.ADMIN_ACCESS
        // (HohenheimSources.register -> ActivitySources.register("admin", ADMIN_ACCESS)), so
        // a tenant fails the source's own gate and would be shown a band that is always empty --
        // a censoring that looks like a bug. This matches the host fact and the install error
        // above: the delegated tree simply never grows the widget. Widening the audience is a
        // decision about the AUDIT LOG, not about this page: the log carries every operator's
        // actions on every record, including admin-only detail text.
        if (!delegated) {
            bands.add(band(new WidgetTree(List.of(
                new WidgetInstance(RecordsWidget.ID, Map.of(
                    "title", HohenheimWidgetCopy.localized("recent_activity", "instance_overview"),
                    "source", CmsSupport.ACTIVITY_SOURCE,
                    "rules", ActivityRules.forRecord(this.resource.model(), instanceId),
                    "sort", ActivityModel.CREATED_AT.getName(),
                    "descending", true,
                    "limit", 10))))));
        }

        return new WidgetTree(List.of(new WidgetInstance(SectionWidget.ID,
            Map.of("css_class", "hh-instance-overview"), new WidgetTree(bands))));
    }

    /**
     * The one widget-native action: re-render this record's tree from freshly read
     * evidence. The adapter re-loads the record through the resource's access scope
     * before this runs, so the tree it answers with is a new SSR-truth render.
     */
    @Override
    public @NonNull SurfaceActionOutcome onSurfaceAction(@NonNull SurfaceActionRequest request,
                                                         @NonNull Row instance,
                                                         @NonNull AccessContext accessContext) {
        if (REFRESH_ACTION.equals(request.action())) {
            return SurfaceActionOutcome.tree(this.widgets(instance, accessContext));
        }
        return super.onSurfaceAction(request, instance, accessContext);
    }

    // -- status ----------------------------------------------------------------------

    private static @NonNull List<WidgetBadge> statusBadges(@NonNull Row instance,
                                                           @NonNull LocaleChain locales,
                                                           @Nullable MessageResolver resolver) {
        List<WidgetBadge> badges = new ArrayList<>();
        badges.add(WidgetBadge.of(InstanceModel.STATUS, instance.get(InstanceModel.STATUS),
            locales, resolver));
        addBadge(badges, InstanceModel.KIND, instance.get(InstanceModel.KIND), locales, resolver);
        addBadge(badges, InstanceModel.INSTALL_STATE, instance.get(InstanceModel.INSTALL_STATE),
            locales, resolver);
        return badges;
    }

    private static void addBadge(@NonNull List<WidgetBadge> badges, @NonNull EnumField field,
                                 @Nullable Object raw, @NonNull LocaleChain locales,
                                 @Nullable MessageResolver resolver) {
        if (raw != null) {
            badges.add(WidgetBadge.of(field, raw, locales, resolver));
        }
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

            // The host-free sentence is decided by the SURFACE, exactly as the host fact and
            // the install error are above: an operator opening /manage must see what a tenant
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

    // -- helpers ---------------------------------------------------------------------

    private static @NonNull WidgetInstance alert(@NonNull AlertVariant variant,
                                                 @NonNull NoticeData notice) {
        return new WidgetInstance(AlertWidget.ID, Map.of("variant", variant.token()))
            .withData(notice);
    }

    private static @NonNull WidgetInstance band(@NonNull WidgetTree children) {
        return new WidgetInstance(SectionWidget.ID,
            Map.of("css_class", "hh-overview-band"), children);
    }

    private static @NonNull String text(@NonNull String key, @NonNull String scope,
                                        @NonNull LocaleChain locales,
                                        @Nullable MessageResolver resolver) {
        return Microcopy.of(key).withFilter("scope", scope).resolve(locales, resolver);
    }

    private static @NonNull String blankable(@Nullable String value) {
        return value != null ? value : "";
    }
}
