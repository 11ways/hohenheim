package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimWidgets;
import be.elevenways.hohenheim.app.AppUsage;
import be.elevenways.hohenheim.instance.InstanceDiskView;
import be.elevenways.hohenheim.instance.InstanceEndpointView;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.MessageResolver;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.resource.RecordOverview;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.text.ByteText;
import be.elevenways.zenit.widget.common.WidgetInstance;
import be.elevenways.zenit.widget.common.WidgetTree;
import be.elevenways.zenit.widget.common.builtin.ActionButtonWidget;
import be.elevenways.zenit.widget.common.builtin.AlertVariant;
import be.elevenways.zenit.widget.common.builtin.AlertWidget;
import be.elevenways.zenit.widget.common.data.NoticeData;
import be.elevenways.zenit.widget.common.data.UsageData;
import be.elevenways.zenit.widget.common.data.WidgetBadge;
import be.elevenways.zenit.widget.common.data.WidgetFact;
import be.elevenways.zenit.widget.common.surface.SurfaceActionOutcome;
import be.elevenways.zenit.widget.common.surface.SurfaceOperation;
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

    /** The one widget-native action on this tab: re-read the stored evidence. */
    static final String REFRESH_ACTION = "refresh";

    private InstanceOverview() {
    }

    /**
     * The tab both instance entries declare. Its refresh re-renders the tree from the record the surface re-loaded
     * through the entry's admitted read, so the answer is a new SSR-truth render.
     */
    static @NonNull RecordOverview<Row> tab() {
        return RecordOverview.<Row>fields(SLUG, Microcopy.of("overview").withFilter("scope", "instance"))
            .withoutFields()
            .widgets(InstanceOverview::widgets)
            .surfaceActions(List.of(SurfaceOperation.of(REFRESH_ACTION, InstanceOperations.REFRESH_OVERVIEW,
                (context, refreshed) -> SurfaceActionOutcome.tree(
                    widgets(context.subjects().get(0), context.access())))));
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
        List<WidgetInstance> top = new ArrayList<>();
        WidgetInstance actions = AppOverview.actions(accessContext, InstanceParts.SLUG, instance);
        if (actions != null) {
            top.add(actions);
        }
        top.add(new WidgetInstance(ActionButtonWidget.ID, Map.of(
            "label", HohenheimWidgetCopy.localized("refresh", "instance_overview"),
            "action", REFRESH_ACTION,
            "variant", "outline")));

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
        main.add(new WidgetInstance(HohenheimWidgets.INSTANCE_ENDPOINTS.id(), Map.of())
            .withData(endpointsOf(instanceId)));
        main.add(AppOverview.resources(List.of(new AppUsage(
            Microcopy.of("disk").withFilter("scope", "instance_overview"),
            diskUsage(instance, serverId, locales, resolver)))));

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
        if (!delegated) {
            facts.add(WidgetFact.link(
                text("host", "instance_overview", locales, resolver),
                ServerModel.nameOf(serverId),
                CmsRoutes.subpage(panelSlug, "servers", serverId, ServerOverviewState.SLUG).toUrl()));
        }
        return facts;
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

    private static @NonNull String text(@NonNull String key, @NonNull String scope,
                                        @NonNull LocaleChain locales,
                                        @Nullable MessageResolver resolver) {
        return Microcopy.of(key).withFilter("scope", scope).resolve(locales, resolver);
    }

    private static @NonNull String blankable(@Nullable String value) {
        return value != null ? value : "";
    }
}
