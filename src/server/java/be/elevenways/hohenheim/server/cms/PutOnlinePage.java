package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.app.PutOnlineGroup;
import be.elevenways.hohenheim.app.PutOnlineReach;
import be.elevenways.hohenheim.instance.InstanceKindInfo;
import be.elevenways.hohenheim.instance.InstanceKindRegistry;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceDevices;
import be.elevenways.hohenheim.upstream.UpstreamKindInfo;
import be.elevenways.hohenheim.upstream.UpstreamKinds;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.resource.PanelPage;
import be.elevenways.zenit.cms.server.page.PageActions;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * "Put something online": first what, then the wizard for it, then its run page.
 *
 * AIDEV-NOTE: the chooser is the step "What". A template opens {@link PutOnline#PUT_ONLINE} over that template
 * ({@code ?template=}); an address kind opens {@link PutOnline#PUT_ADDRESS_ONLINE} with the kind preset
 * ({@code ?kind=}); for an operator, an instance kind opens the flow its declarations put it online by
 * ({@code ?workload=}, the kind preset): {@link PutOnline#PUT_WORKLOAD_ONLINE} for one that serves a website (a Git
 * app, a container image), {@link PutOnline#PUT_WORKLOAD_SERVICE_ONLINE} for one reached on ports it publishes (a
 * container image of a game server, {@code &reach=ports}), {@link PutOnline#PUT_MACHINE_ONLINE} for one that does not
 * (a virtual machine, a Linux container). A kind offered more than one way ({@code InstanceKindHandler
 * .putOnlineReaches}) gets one card per way. All are the framework's stepped PAGE document, and all run in the
 * background, so a submit lands on the run page ("Going live"). Every kind for a tenant still opens the instance
 * create form.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class PutOnlinePage extends PanelPage {

    /** The chooser's "Point an address somewhere else" group, as a fragment a link can scroll to. */
    public static final String ADDRESSES_ANCHOR = "put-online-" + PutOnlineGroup.ADDRESS.token();

    /** Every put-online run's page also offers starting over at the chooser (board Online-4). */
    private static final Microcopy PUT_SOMETHING_ELSE = HohenheimMicrocopy.PUT_ONLINE.of("put_something_else");

    /** A template, put online with its address and certificate. */
    public static final PanelAction<Row> FROM_TEMPLATE = PanelAction.<Row, Integer>placesRun(PutOnline.PUT_ONLINE,
            ActionPlacement.PAGE)
        .confirmation(ConfirmationSpec.generic(HohenheimMicrocopy.PUT_ONLINE.of("finish"), false))
        .selectedBy(HohenheimParams.FROM_TEMPLATE_TEMPLATE.getName())
        .fixed((template, request) -> fixed(request.access()))
        .offersAnotherRun(PUT_SOMETHING_ELSE, PutOnlinePage::chooser)
        .build();

    /** A template reached on its ports (a game server), put online with its ports and its game address. */
    public static final PanelAction<Row> FROM_SERVICE_TEMPLATE = PanelAction.<Row, Integer>placesRun(
            PutOnline.PUT_SERVICE_ONLINE, ActionPlacement.PAGE)
        .confirmation(ConfirmationSpec.generic(HohenheimMicrocopy.PUT_ONLINE.of("finish"), false))
        .selectedBy(HohenheimParams.FROM_TEMPLATE_TEMPLATE.getName())
        .fixed((template, request) -> serviceFixed(request.access()))
        .offersAnotherRun(PUT_SOMETHING_ELSE, PutOnlinePage::chooser)
        .build();

    /** An address that needs no workload: a redirect, an existing service, static files, TLS passthrough. */
    public static final PanelAction<Object> ADDRESS = PanelAction.<Object, Integer>placesRun(
            PutOnline.PUT_ADDRESS_ONLINE, ActionPlacement.PAGE)
        .confirmation(ConfirmationSpec.generic(HohenheimMicrocopy.PUT_ONLINE.of("finish"), false))
        // The chooser already answered what it serves: the kind rides the form as transport, never asked again.
        .transport(SiteModel.UPSTREAM_KIND.getName())
        .offersAnotherRun(PUT_SOMETHING_ELSE, PutOnlinePage::chooser)
        .build();

    /** A TCP/UDP stream: a public port of its own on the proxy's host, forwarded to an address; nothing runs for it. */
    public static final PanelAction<Object> STREAM = PanelAction.<Object, Integer>placesRun(
            PutOnline.PUT_STREAM_ONLINE, ActionPlacement.PAGE)
        .confirmation(ConfirmationSpec.generic(HohenheimMicrocopy.PUT_ONLINE.of("finish"), false))
        .offersAnotherRun(PUT_SOMETHING_ELSE, PutOnlinePage::chooser)
        .build();

    /** A workload of one kind, put online from that kind's own settings with its address and certificate. */
    public static final PanelAction<Object> WORKLOAD = PanelAction.<Object, Integer>placesRun(
            PutOnline.PUT_WORKLOAD_ONLINE, ActionPlacement.PAGE)
        .confirmation(ConfirmationSpec.generic(HohenheimMicrocopy.PUT_ONLINE.of("finish"), false))
        // The chooser already answered which kind: it rides the form as transport, never asked again.
        .transport(InstanceModel.KIND.getName())
        .offersAnotherRun(PUT_SOMETHING_ELSE, PutOnlinePage::chooser)
        .build();

    /** A service of one kind, put online from that kind's own settings and reached on the ports it publishes. */
    public static final PanelAction<Object> WORKLOAD_SERVICE = PanelAction.<Object, Integer>placesRun(
            PutOnline.PUT_WORKLOAD_SERVICE_ONLINE, ActionPlacement.PAGE)
        .confirmation(ConfirmationSpec.generic(HohenheimMicrocopy.PUT_ONLINE.of("finish"), false))
        .transport(InstanceModel.KIND.getName())
        .offersAnotherRun(PUT_SOMETHING_ELSE, PutOnlinePage::chooser)
        .build();

    /**
     * A machine of one kind, put online from that kind's own settings, with no address to give. A machine created
     * with install media opens on its screen once its run finished, where its installer is waiting; any other on its
     * front door, the overview.
     */
    public static final PanelAction<Object> MACHINE = PanelAction.<Object, Integer>placesRun(
            PutOnline.PUT_MACHINE_ONLINE, ActionPlacement.PAGE)
        .finishedRunOpens(Row.class, machine -> InstanceDevices.hasInstallMedia(machine.get(InstanceModel.ID))
            ? HohenheimSlugs.Tab.FRAMEBUFFER : null)
        .confirmation(ConfirmationSpec.generic(HohenheimMicrocopy.PUT_ONLINE.of("finish"), false))
        .transport(InstanceModel.KIND.getName())
        .offersAnotherRun(PUT_SOMETHING_ELSE, PutOnlinePage::chooser)
        .build();

    /** The journey's steps on other pages: the chooser before the wizard, the run page after it. */
    private static final List<Microcopy> BEFORE_WIZARD = PutOnline.BEFORE_FORM;
    private static final List<Microcopy> AFTER_WIZARD = List.of(PutOnline.STEP_LIVE);

    @Override public @NonNull Identifier id() { return HohenheimIds.id("put_online"); }
    @Override public @NonNull Microcopy label() { return HohenheimMicrocopy.PUT_ONLINE.of("put_online"); }
    @Override public @NonNull String slug() { return HohenheimSlugs.PUT_ONLINE; }
    @Override public @NonNull Icon icon() { return Icon.of("rocket"); }
    @Override public boolean showInNav() { return false; }
    @Override public @NonNull String standsUnder() { return HohenheimSlugs.APPS; }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public @NonNull List<PanelAction<Row>> actions() {
        return List.of(FROM_TEMPLATE, FROM_SERVICE_TEMPLATE, (PanelAction) ADDRESS, (PanelAction) WORKLOAD,
            (PanelAction) WORKLOAD_SERVICE, (PanelAction) MACHINE, (PanelAction) STREAM);
    }

    /** @return the chooser, where putting something else online starts */
    private static @NonNull RouteTarget chooser(@NonNull PanelRequest request) {
        return CmsRoutes.list(request.panelSlug(), HohenheimSlugs.PUT_ONLINE);
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", resolve(label(), request));
        vars.put("lead", HohenheimMicrocopy.PUT_ONLINE.of("lead"));
        vars.put("cancelTarget", CmsRoutes.list(request.panelSlug(), HohenheimSlugs.APPS));
        String kind = request.conduit().getQueryParam(HohenheimParams.PUT_ONLINE_KIND.getName());
        String template = request.conduit().getQueryParam(HohenheimParams.FROM_TEMPLATE_TEMPLATE.getName());
        String workload = request.conduit().getQueryParam(HohenheimParams.PUT_ONLINE_WORKLOAD.getName());
        PutOnlineReach reach = askedReach(workload,
            request.conduit().getQueryParam(HohenheimParams.PUT_ONLINE_REACH.getName()));
        PanelAction<Object> workloadAction = reach == null ? null : kindAction(workload, reach);
        if (template != null && !template.isBlank()) {
            PanelAction<Row> templateAction = templateAction(template);
            PageActions.Opened opened = PageActions.open(request, this, templateAction.id());
            if (opened instanceof PageActions.Form form) {
                Row chosen = (Row) form.subject();
                if (templateAction == FROM_SERVICE_TEMPLATE) {
                    vars.put("lead", HohenheimMicrocopy.PUT_ONLINE.of("service_lead"));
                }
                vars.put("document", form.state().withJourney(BEFORE_WIZARD, AFTER_WIZARD));
                vars.put("whatIcon", TemplateFamily.iconOf(chosen).name());
                vars.put("what", String.valueOf((Object) chosen.get(InstanceTemplateModel.NAME)));
                Object description = chosen.get(InstanceTemplateModel.DESCRIPTION);
                vars.put("whatDescription", description == null ? "" : String.valueOf(description));
                vars.put("versions", otherVersions(request, chosen));
            }
        } else if (kind != null && addressAction(kind) != null && HohenheimAccess.isAdmin(request.access())) {
            PanelAction<Object> addressAction = Objects.requireNonNull(addressAction(kind));
            // The chooser already answered what an address serves; a stream's flow is that kind's alone.
            PageActions.Opened opened = addressAction == ADDRESS
                ? PageActions.open(request, this, ADDRESS.id(), Map.of(SiteModel.UPSTREAM_KIND.getName(), kind))
                : PageActions.open(request, this, addressAction.id());
            if (opened instanceof PageActions.Form form) {
                UpstreamKindInfo info = UpstreamKinds.REGISTRY.get(Identifier.tryParse(kind));
                vars.put("document", form.state().withJourney(BEFORE_WIZARD, AFTER_WIZARD));
                if (addressAction == STREAM) {
                    vars.put("lead", HohenheimMicrocopy.PUT_ONLINE.of("stream_lead"));
                }
                vars.put("whatIcon", info == null ? "globe" : info.getIcon().name());
                vars.put("what", info == null ? kind : resolve(info.getLabel(), request));
                vars.put("whatDescription", info == null ? "" : resolve(info.getDescription(), request));
            }
        } else if (workload != null && workloadAction != null && HohenheimAccess.isAdmin(request.access())) {
            PageActions.Opened opened = PageActions.open(request, this, workloadAction.id(),
                Map.of(InstanceModel.KIND.getName(), workload));
            InstanceKindInfo info = InstanceKindRegistry.REGISTRY.get(Identifier.tryParse(workload));
            if (opened instanceof PageActions.Form form && info != null) {
                vars.put("document", form.state().withJourney(BEFORE_WIZARD, AFTER_WIZARD));
                // Only a website is led in by the app flow's promise of an address and a certificate.
                switch (reach) {
                    case WEBSITE -> { }
                    case PORTS -> vars.put("lead", HohenheimMicrocopy.PUT_ONLINE.of("service_lead"));
                    case NONE -> vars.put("lead", HohenheimMicrocopy.PUT_ONLINE.of("machine_lead"));
                }
                vars.put("whatIcon", info.getIcon().name());
                // A kind put online another way than its own card's is named as the chooser's card for that way.
                boolean ownWay = reach == PutOnline.reachesOf(workload).getFirst();
                vars.put("what", resolve(PutOnline.namedAs(workload, info.getLabel(), reach), request));
                vars.put("whatDescription", resolve(ownWay ? info.getDescription() : reach.cardDescription(),
                    request));
            }
        }
        if (!vars.containsKey("document")) {
            Map<String, RouteTarget> targets = new LinkedHashMap<>();
            List<Map<String, Object>> groups = groups(request, targets);
            // The chooser posts its pick back here as ?choice=: a known value opens its flow, anything else redraws.
            String choice = request.conduit().getQueryParam(HohenheimParams.PUT_ONLINE_CHOICE.getName());
            RouteTarget chosen = choice == null ? null : targets.get(choice);
            if (chosen != null) {
                return request.conduit().softRedirect(chosen);
            }
            vars.put("groups", groups);
            List<String> steps = new ArrayList<>();
            PutOnline.sharedSteps().forEach(step -> steps.add(resolve(step, request)));
            vars.put("steps", steps);
            vars.put("chooseAction", CmsRoutes.list(request.panelSlug(), HohenheimSlugs.PUT_ONLINE).toUrl());
        }
        return new RenderTemplateResult(HohenheimTemplateIds.PUT_ONLINE, vars);
    }

    /**
     * The chooser's groups in {@link PutOnlineGroup} order, each with its cards; every card's flow is recorded in
     * {@code targets} under the card's value, so the pick is resolved from the same list it was drawn from.
     */
    private static @NonNull List<Map<String, Object>> groups(@NonNull PanelRequest request,
                                                             @NonNull Map<String, RouteTarget> targets) {
        Map<PutOnlineGroup, List<Map<String, Object>>> cards = new EnumMap<>(PutOnlineGroup.class);
        for (TemplateFamily family : TemplateFamily.of(request)) {
            Row template = family.current();
            card(cards, targets, PutOnlineGroup.TEMPLATE, "template:" + family.name(), family.icon().name(),
                family.name(),
                resolve(family.cardLine(), request),
                CmsRoutes.list(request.panelSlug(), HohenheimSlugs.PUT_ONLINE)
                    .with(HohenheimParams.FROM_TEMPLATE_TEMPLATE, template.get(InstanceTemplateModel.ID)));
        }
        card(cards, targets, PutOnlineGroup.TEMPLATE, "catalog", "folder-open",
            resolve(HohenheimMicrocopy.PUT_ONLINE.of("catalog"), request),
                resolve(HohenheimMicrocopy.PUT_ONLINE.of("catalog_help"), request),
            CmsRoutes.list(request.panelSlug(), HohenheimSlugs.INSTANCE_TEMPLATES));
        boolean admin = HohenheimAccess.isAdmin(request.access());
        for (InstanceKindInfo info : InstanceKindRegistry.REGISTRY) {
            PutOnlineGroup group = info.putOnlineGroup();
            if (group == null) {
                continue;
            }
            String kind = info.typeId().toString();
            List<PutOnlineReach> reaches = PutOnline.reachesOf(kind);
            String label = resolve(info.getLabel(), request);
            RouteTarget workload = CmsRoutes.list(request.panelSlug(), HohenheimSlugs.PUT_ONLINE)
                .with(HohenheimParams.PUT_ONLINE_WORKLOAD, kind);
            card(cards, targets, group, "instance:" + info.typeId(), info.getIcon().name(), label,
                resolve(info.getDescription(), request),
                admin && !reaches.isEmpty() ? workload
                    : CmsRoutes.create(request.panelSlug(), HohenheimSlugs.INSTANCES)
                        .with(HohenheimParams.PUT_ONLINE_KIND, kind));
            // The kind's other ways are operator flows of their own; a tenant has the create form behind its card.
            for (PutOnlineReach reach : reaches) {
                if (admin && reach != reaches.getFirst()) {
                    card(cards, targets, group, "instance:" + info.typeId() + "@" + reach.token(),
                        info.getIcon().name(), resolve(reach.cardLabel().withArg("kind", label), request),
                        resolve(reach.cardDescription(), request),
                        workload.with(HohenheimParams.PUT_ONLINE_REACH, reach.token()));
                }
            }
        }
        if (admin) {
            for (UpstreamKindInfo info : UpstreamKinds.REGISTRY) {
                PutOnlineGroup group = info.putOnlineGroup();
                if (group != null) {
                    card(cards, targets, group, "kind:" + info.typeId(), info.getIcon().name(),
                        resolve(info.getLabel(), request), resolve(info.getDescription(), request),
                        CmsRoutes.list(request.panelSlug(), HohenheimSlugs.PUT_ONLINE)
                            .with(HohenheimParams.PUT_ONLINE_KIND, info.typeId().toString()));
                }
            }
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map.Entry<PutOnlineGroup, List<Map<String, Object>>> entry : cards.entrySet()) {
            Map<String, Object> group = new HashMap<>();
            group.put("token", entry.getKey().token());
            group.put("anchor", "put-online-" + entry.getKey().token());
            group.put("heading", resolve(entry.getKey().label(), request));
            group.put("choices", entry.getValue());
            groups.add(group);
        }
        return groups;
    }

    private static void card(@NonNull Map<PutOnlineGroup, List<Map<String, Object>>> cards,
                             @NonNull Map<String, RouteTarget> targets, @NonNull PutOnlineGroup group,
                             @NonNull String value, @NonNull String icon, @NonNull String label,
                             @NonNull String description, @NonNull RouteTarget target) {
        Map<String, Object> card = new HashMap<>();
        card.put("value", value);
        card.put("icon", icon);
        card.put("label", label);
        card.put("description", description);
        cards.computeIfAbsent(group, ignored -> new ArrayList<>()).add(card);
        targets.put(value, target);
    }

    /**
     * @return the way a kind is asked to be put online: the route's reach, else the kind's own card's; null for a
     *         reach the vocabulary does not know or a kind not offered
     */
    private static @Nullable PutOnlineReach askedReach(@Nullable String kind, @Nullable String reach) {
        if (reach != null) {
            return PutOnlineReach.ofToken(reach);
        }
        List<PutOnlineReach> reaches = PutOnline.reachesOf(kind);
        return reaches.isEmpty() ? null : reaches.getFirst();
    }

    /** @return the flow an address of the upstream kind with this stored id is put online by, null when none is */
    private static @Nullable PanelAction<Object> addressAction(@NonNull String kind) {
        PutOnlineReach reach = PutOnline.offeredAsApp(kind) ? PutOnline.addressReachOf(kind) : null;
        if (reach == null) {
            return null;
        }
        return switch (reach) {
            case WEBSITE -> ADDRESS;
            case PORTS -> STREAM;
            case NONE -> null;
        };
    }

    /**
     * @return the flow an instance kind with this stored id is put online by when reached this way, or null when the
     *         kind is not offered that way (it opens the create form)
     */
    private static @Nullable PanelAction<Object> kindAction(@Nullable String kind, @NonNull PutOnlineReach reach) {
        if (!PutOnline.offeredWith(kind, reach)) {
            return null;
        }
        return switch (reach) {
            case WEBSITE -> WORKLOAD;
            case PORTS -> WORKLOAD_SERVICE;
            case NONE -> MACHINE;
        };
    }

    /**
     * The flow a template is put online by, from how its workload is reached ({@link PutOnline#reachOf}): a service
     * gets its Ports step; a website, and a machine whose address the operator may still give, the address flow.
     */
    private static @NonNull PanelAction<Row> templateAction(@NonNull String templateId) {
        Integer id = RawValues.parsedInt(templateId);
        Row template = id == null ? null : Models.get(InstanceTemplateModel.class).findById(id);
        if (template == null) {
            return FROM_TEMPLATE;
        }
        return switch (PutOnline.reachOf(template)) {
            case PORTS -> FROM_SERVICE_TEMPLATE;
            case WEBSITE, NONE -> FROM_TEMPLATE;
        };
    }

    /** The chosen template's other versions (its family's other members), each opening its own document. */
    private static @NonNull List<Map<String, Object>> otherVersions(@NonNull PanelRequest request,
            @NonNull Row chosen) {
        List<Map<String, Object>> versions = new ArrayList<>();
        Object chosenId = chosen.get(InstanceTemplateModel.ID);
        for (TemplateFamily family : TemplateFamily.of(request)) {
            if (!family.contains(chosenId)) {
                continue;
            }
            for (Row member : family.members()) {
                Integer id = member.get(InstanceTemplateModel.ID);
                if (!id.equals(chosenId)) {
                    Map<String, Object> version = new HashMap<>();
                    version.put("name", String.valueOf((Object) member.get(InstanceTemplateModel.NAME)));
                    version.put("target", CmsRoutes.list(request.panelSlug(), HohenheimSlugs.PUT_ONLINE)
                        .with(HohenheimParams.FROM_TEMPLATE_TEMPLATE, id));
                    versions.add(version);
                }
            }
        }
        return versions;
    }

    /**
     * The template form's own fixed entries ({@link InstanceFromTemplatePage#fixed}), plus the address, its HTTPS and
     * its access list for everyone but an operator, since claiming an address is an operator act.
     */
    private static @NonNull Map<String, @Nullable Object> fixed(@NonNull AccessContext access) {
        Map<String, @Nullable Object> fixed = new LinkedHashMap<>(InstanceFromTemplatePage.fixed(access));
        if (!HohenheimAccess.isAdmin(access)) {
            fixed.put(PutOnline.HOSTNAME.getName(), null);
            fixed.put(PutOnline.HTTPS.getName(), PutOnline.HTTPS_LATER);
            fixed.put(SiteModel.ACCESS_LIST_ID.getName(), null);
        }
        return fixed;
    }

    /** The template form's own fixed entries, plus the game address for everyone but an operator. */
    private static @NonNull Map<String, @Nullable Object> serviceFixed(@NonNull AccessContext access) {
        Map<String, @Nullable Object> fixed = new LinkedHashMap<>(InstanceFromTemplatePage.fixed(access));
        if (!HohenheimAccess.isAdmin(access)) {
            fixed.put(PutOnline.GAME_ADDRESS.getName(), null);
        }
        return fixed;
    }

    private static @NonNull String resolve(@NonNull Microcopy copy, @NonNull PanelRequest request) {
        return copy.asString(request.conduit());
    }
}
