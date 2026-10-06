package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimTemplateIds;
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
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Put something online" (boards Online-1 to Online-4): first what, then the wizard for it, then its run page.
 *
 * AIDEV-NOTE: the chooser is the board's step "What". A template opens {@link PutOnline#PUT_ONLINE} over that template
 * ({@code ?template=}); an address kind opens {@link PutOnline#PUT_ADDRESS_ONLINE} with the kind preset
 * ({@code ?kind=}). Both forms are the framework's stepped PAGE document, and both operations run in the background,
 * so a submit lands on the run page ("Going live"). Workloads without a template (a Git app, a container image, a Linux
 * container, a virtual machine, a workspace) still open the instance create form until they get a flow of their own.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class PutOnlinePage extends PanelPage {

    /** The page's slug; the dashboard's and the Apps list's primary action lead here. */
    public static final String SLUG = "put-online";

    /** A template, put online with its address and certificate. */
    public static final PanelAction<Row> FROM_TEMPLATE = PanelAction.<Row, Integer>places(PutOnline.PUT_ONLINE,
            ActionPlacement.PAGE,
            (request, result) -> CmsActionResult.redirect(new Uri(CmsRoutes.subpage(request.request().panelSlug(),
                HohenheimSlugs.INSTANCES, result.value(), InstanceOverview.SLUG).toUrl())))
        .confirmation(ConfirmationSpec.generic(PutOnline.copy("finish"), false))
        .selectedBy(HohenheimParams.FROM_TEMPLATE_TEMPLATE.getName())
        .fixed((template, request) -> fixed(request.access()))
        .build();

    /** An address that needs no workload: a redirect, an existing service, static files, TLS passthrough. */
    public static final PanelAction<Object> ADDRESS = PanelAction.<Object, Integer>places(
            PutOnline.PUT_ADDRESS_ONLINE, ActionPlacement.PAGE,
            (request, result) -> CmsActionResult.redirect(new Uri(CmsRoutes.subpage(request.request().panelSlug(),
                HohenheimSlugs.SITES, result.value(), "overview").toUrl())))
        .confirmation(ConfirmationSpec.generic(PutOnline.copy("finish"), false))
        .build();

    @Override public @NonNull Identifier id() { return HohenheimIds.id("put_online"); }
    @Override public @NonNull Microcopy label() { return PutOnline.copy("put_online"); }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Icon icon() { return Icon.of("rocket"); }
    @Override public boolean showInNav() { return false; }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public @NonNull List<PanelAction<Row>> actions() {
        return List.of(FROM_TEMPLATE, (PanelAction) ADDRESS);
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", resolve(PutOnline.copy("put_online"), request));
        vars.put("cancelTarget", CmsRoutes.list(request.panelSlug(), AppParts.SLUG));
        String kind = request.conduit().getQueryParam(HohenheimParams.PUT_ONLINE_KIND.getName());
        String template = request.conduit().getQueryParam(HohenheimParams.FROM_TEMPLATE_TEMPLATE.getName());
        if (template != null && !template.isBlank()) {
            PageActions.Opened opened = PageActions.open(request, this, FROM_TEMPLATE.id());
            if (opened instanceof PageActions.Form form) {
                Row chosen = (Row) form.subject();
                vars.put("document", form.state());
                vars.put("what", String.valueOf((Object) chosen.get(InstanceTemplateModel.NAME)));
                Object description = chosen.get(InstanceTemplateModel.DESCRIPTION);
                vars.put("whatDescription", description == null ? "" : String.valueOf(description));
            }
        } else if (kind != null && PutOnline.offeredAsApp(kind) && HohenheimAccess.isAdmin(request.access())) {
            PageActions.Opened opened = PageActions.open(request, this, ADDRESS.id(),
                Map.of(SiteModel.UPSTREAM_KIND.getName(), kind));
            if (opened instanceof PageActions.Form form) {
                UpstreamKindInfo info = UpstreamKinds.REGISTRY.get(Identifier.tryParse(kind));
                vars.put("document", form.state());
                vars.put("what", info == null ? kind : resolve(info.getLabel(), request));
                vars.put("whatDescription", info == null ? "" : resolve(info.getDescription(), request));
            }
        }
        if (!vars.containsKey("document")) {
            vars.put("templates", InstanceFromTemplatePage.choices(request, SLUG));
            vars.put("addresses", HohenheimAccess.isAdmin(request.access()) ? addresses(request) : List.of());
            vars.put("otherTarget", CmsRoutes.create(request.panelSlug(), HohenheimSlugs.INSTANCES));
        }
        return new RenderTemplateResult(HohenheimTemplateIds.PUT_ONLINE, vars);
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

    /** The address kinds put online on their own, in the registry's order. */
    private static @NonNull List<Map<String, Object>> addresses(@NonNull PanelRequest request) {
        List<Map<String, Object>> choices = new ArrayList<>();
        for (UpstreamKindInfo info : UpstreamKinds.REGISTRY) {
            if (!info.offeredAsApp()) {
                continue;
            }
            Map<String, Object> choice = new HashMap<>();
            choice.put("name", resolve(info.getLabel(), request));
            choice.put("description", resolve(info.getDescription(), request));
            choice.put("target", CmsRoutes.list(request.panelSlug(), SLUG)
                .with(HohenheimParams.PUT_ONLINE_KIND, info.typeId().toString()));
            choices.add(choice);
        }
        return choices;
    }

    private static @NonNull String resolve(@NonNull Microcopy copy, @NonNull PanelRequest request) {
        return copy.resolve(request.conduit().getLocales(), request.conduit().getMessageResolver());
    }
}
