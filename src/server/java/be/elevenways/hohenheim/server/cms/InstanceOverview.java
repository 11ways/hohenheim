package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimWidgets;
import be.elevenways.hohenheim.instance.InstanceDiskView;
import be.elevenways.hohenheim.instance.InstanceEndpointView;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.MessageResolver;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.auth.server.GrantAdministration;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.resource.RecordOverview;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
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
        main.add(AppOverview.resources(List.of(AppOverview.gauge(
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
                held.add(capability.label().resolve(locales, resolver));
            }
        }
        boolean shares = GrantAdministration.mayAdministerRecordAccess(access, InstanceModel.MODEL_ID, instanceId);
        String can;
        if (held.isEmpty()) {
            can = text(shares ? "you_can_look_share" : "you_can_look", "instance_overview", locales, resolver);
        } else {
            if (shares) {
                held.add(text("you_can_share", "instance_overview", locales, resolver));
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
