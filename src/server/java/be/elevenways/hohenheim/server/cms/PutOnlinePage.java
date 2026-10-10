package be.elevenways.hohenheim.server.cms;

import be.elevenways.zenit.cms.common.resource.RecordOverview;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.app.PutOnlineGroup;
import be.elevenways.hohenheim.instance.InstanceKindInfo;
import be.elevenways.hohenheim.instance.InstanceKindRegistry;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.upstream.UpstreamKindInfo;
import be.elevenways.hohenheim.upstream.UpstreamKinds;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
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

/**
 * "Put something online": first what, then the wizard for it, then its run page.
 *
 * AIDEV-NOTE: the chooser is the step "What". A template opens {@link PutOnline#PUT_ONLINE} over that template
 * ({@code ?template=}); an address kind opens {@link PutOnline#PUT_ADDRESS_ONLINE} with the kind preset
 * ({@code ?kind=}). Both forms are the framework's stepped PAGE document, and both operations run in the background,
 * so a submit lands on the run page ("Going live"). Workloads without a template (a Git app, a container image, a Linux
 * container, a virtual machine, a workspace) still open the instance create form until they get a flow of their own.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class PutOnlinePage extends PanelPage {

    /** The chooser's "Point an address somewhere else" group, as a fragment a link can scroll to. */
    public static final String ADDRESSES_ANCHOR = "put-online-" + PutOnlineGroup.ADDRESS.token();

    /** A template, put online with its address and certificate. */
    public static final PanelAction<Row> FROM_TEMPLATE = PanelAction.<Row, Integer>places(PutOnline.PUT_ONLINE,
            ActionPlacement.PAGE,
            (request, result) -> CmsActionResult.redirect(new Uri(CmsRoutes.subpage(request.request().panelSlug(),
                HohenheimSlugs.INSTANCES, result.value(), RecordOverview.SLUG).toUrl())))
        .confirmation(ConfirmationSpec.generic(HohenheimMicrocopy.PUT_ONLINE.of("finish"), false))
        .selectedBy(HohenheimParams.FROM_TEMPLATE_TEMPLATE.getName())
        .fixed((template, request) -> fixed(request.access()))
        .build();

    /** An address that needs no workload: a redirect, an existing service, static files, TLS passthrough. */
    public static final PanelAction<Object> ADDRESS = PanelAction.<Object, Integer>places(
            PutOnline.PUT_ADDRESS_ONLINE, ActionPlacement.PAGE,
            (request, result) -> CmsActionResult.redirect(new Uri(CmsRoutes.subpage(request.request().panelSlug(),
                HohenheimSlugs.SITES, result.value(), "overview").toUrl())))
        .confirmation(ConfirmationSpec.generic(HohenheimMicrocopy.PUT_ONLINE.of("finish"), false))
        // The chooser already answered what it serves: the kind rides the form as transport, never asked again.
        .transport(SiteModel.UPSTREAM_KIND.getName())
        .build();

    /** The journey's steps on other pages: the chooser before the wizard, the run page after it. */
    private static final List<Microcopy> BEFORE_WIZARD = List.of(PutOnline.STEP_WHAT);
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
        return List.of(FROM_TEMPLATE, (PanelAction) ADDRESS);
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", resolve(label(), request));
        vars.put("lead", HohenheimMicrocopy.PUT_ONLINE.of("lead"));
        vars.put("cancelTarget", CmsRoutes.list(request.panelSlug(), HohenheimSlugs.APPS));
        String kind = request.conduit().getQueryParam(HohenheimParams.PUT_ONLINE_KIND.getName());
        String template = request.conduit().getQueryParam(HohenheimParams.FROM_TEMPLATE_TEMPLATE.getName());
        if (template != null && !template.isBlank()) {
            PageActions.Opened opened = PageActions.open(request, this, FROM_TEMPLATE.id());
            if (opened instanceof PageActions.Form form) {
                Row chosen = (Row) form.subject();
                InstanceKindInfo kindInfo = InstanceKindRegistry.REGISTRY.get(
                    Identifier.tryParse(String.valueOf((Object) chosen.get(InstanceTemplateModel.KIND))));
                vars.put("document", form.state().withJourney(BEFORE_WIZARD, AFTER_WIZARD));
                vars.put("whatIcon", kindInfo == null ? "cube" : kindInfo.getIcon().name());
                vars.put("what", String.valueOf((Object) chosen.get(InstanceTemplateModel.NAME)));
                Object description = chosen.get(InstanceTemplateModel.DESCRIPTION);
                vars.put("whatDescription", description == null ? "" : String.valueOf(description));
                vars.put("versions", otherVersions(request, chosen));
            }
        } else if (kind != null && PutOnline.offeredAsApp(kind) && HohenheimAccess.isAdmin(request.access())) {
            PageActions.Opened opened = PageActions.open(request, this, ADDRESS.id(),
                Map.of(SiteModel.UPSTREAM_KIND.getName(), kind));
            if (opened instanceof PageActions.Form form) {
                UpstreamKindInfo info = UpstreamKinds.REGISTRY.get(Identifier.tryParse(kind));
                vars.put("document", form.state().withJourney(BEFORE_WIZARD, AFTER_WIZARD));
                vars.put("whatIcon", info == null ? "globe" : info.getIcon().name());
                vars.put("what", info == null ? kind : resolve(info.getLabel(), request));
                vars.put("whatDescription", info == null ? "" : resolve(info.getDescription(), request));
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
            InstanceKindInfo kindInfo = InstanceKindRegistry.REGISTRY.get(
                Identifier.tryParse(String.valueOf((Object) template.get(InstanceTemplateModel.KIND))));
            card(cards, targets, PutOnlineGroup.TEMPLATE, "template:" + family.name(),
                kindInfo == null ? "cube" : kindInfo.getIcon().name(), family.name(),
                resolve(family.cardLine(), request),
                CmsRoutes.list(request.panelSlug(), HohenheimSlugs.PUT_ONLINE)
                    .with(HohenheimParams.FROM_TEMPLATE_TEMPLATE, template.get(InstanceTemplateModel.ID)));
        }
        card(cards, targets, PutOnlineGroup.TEMPLATE, "catalog", "folder-open",
            resolve(HohenheimMicrocopy.PUT_ONLINE.of("catalog"), request),
                resolve(HohenheimMicrocopy.PUT_ONLINE.of("catalog_help"), request),
            CmsRoutes.list(request.panelSlug(), HohenheimSlugs.INSTANCE_TEMPLATES));
        for (InstanceKindInfo info : InstanceKindRegistry.REGISTRY) {
            PutOnlineGroup group = info.putOnlineGroup();
            if (group != null) {
                card(cards, targets, group, "instance:" + info.typeId(), info.getIcon().name(),
                    resolve(info.getLabel(), request), resolve(info.getDescription(), request),
                    CmsRoutes.create(request.panelSlug(), HohenheimSlugs.INSTANCES)
                        .with(HohenheimParams.PUT_ONLINE_KIND, info.typeId().toString()));
            }
        }
        if (HohenheimAccess.isAdmin(request.access())) {
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

    /** The chosen template's other versions (its family's other members), each opening its own document. */
    private static @NonNull List<Map<String, Object>> otherVersions(@NonNull PanelRequest request, @NonNull Row chosen) {
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
     * The template form's own fixed entries ({@link InstanceFromTemplatePage#fixed}), plus the address and its HTTPS
     * for everyone but an operator, since claiming an address is an operator act.
     */
    private static @NonNull Map<String, @Nullable Object> fixed(@NonNull AccessContext access) {
        Map<String, @Nullable Object> fixed = new LinkedHashMap<>(InstanceFromTemplatePage.fixed(access));
        if (!HohenheimAccess.isAdmin(access)) {
            fixed.put(PutOnline.HOSTNAME.getName(), null);
            fixed.put(PutOnline.HTTPS.getName(), PutOnline.HTTPS_LATER);
        }
        return fixed;
    }

    private static @NonNull String resolve(@NonNull Microcopy copy, @NonNull PanelRequest request) {
        return copy.resolve(request.conduit().getLocales(), request.conduit().getMessageResolver());
    }
}
