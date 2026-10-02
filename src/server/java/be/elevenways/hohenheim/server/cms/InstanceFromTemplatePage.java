package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.instance.InstanceTemplateOperations;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceTemplates;
import be.elevenways.hohenheim.server.project.Projects;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.resource.PanelPage;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.server.page.PageActions;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
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
 * "Create instance from template": the PAGE wizard over one selected template, on both panels.
 *
 * AIDEV-NOTE: the template is chosen FIRST (DECIDED D4-B06): without {@code ?template=} the page shows the chooser,
 * and a key the caller may not select answers as missing. The document then asks the details (name, the host for an
 * operator, the project) and the template's own variables as one form, submitted once through the hosting panel's
 * invoke route; the result opens the new instance in the same panel. A host or project control that would offer
 * nothing is fixed to null instead of drawn (InstancePlacement decides the host for everyone else).
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class InstanceFromTemplatePage extends PanelPage {

    /** The page's slug; the template catalogs' create links lead here. */
    public static final String SLUG = "instances-from-template";

    /** The wizard: the create operation over the template its request selects. */
    public static final PanelAction<Row> CREATE = PanelAction.<Row, Integer>places(
            InstanceTemplateOperations.CREATE_INSTANCE_FROM_TEMPLATE, ActionPlacement.PAGE,
            (request, result) -> CmsActionResult.redirect(new Uri(CmsRoutes.detail(request.request().panelSlug(),
                HohenheimSlugs.INSTANCES, result.value()).toUrl())))
        .confirmation(ConfirmationSpec.generic(Microcopy.of("create").withFilter("scope", "instance_from_template"),
            false))
        .selectedBy(HohenheimParams.FROM_TEMPLATE_TEMPLATE.getName())
        .fixed((template, request) -> fixed(request.access()))
        .build();

    @Override public @NonNull Identifier id() { return HohenheimIds.id("instances_from_template"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("create_instance").withFilter("scope", "instance_template"); }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Icon icon() { return Icon.of("plus"); }
    @Override public boolean showInNav() { return false; }
    @Override public @NonNull List<PanelAction<Row>> actions() { return List.of(CREATE); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", Microcopy.of("create_instance").withFilter("scope", "instance_template")
            .resolve(request.conduit().getLocales(), request.conduit().getMessageResolver()));
        PageActions.Opened opened = PageActions.open(request, this, CREATE.id());
        if (opened instanceof PageActions.Form form) {
            Row template = (Row) form.subject();
            vars.put("document", form.state());
            vars.put("templateName", String.valueOf((Object) template.get(InstanceTemplateModel.NAME)));
            Object description = template.get(InstanceTemplateModel.DESCRIPTION);
            vars.put("templateDescription", description == null ? "" : String.valueOf(description));
            vars.put("approved", template.get(InstanceTemplateModel.APPROVED_AT) != null);
            vars.put("hasInstall", InstanceTemplates.hasInstallStep(template));
        } else {
            vars.put("choices", choices(request));
        }
        vars.put("cancelTarget", CmsRoutes.list(request.panelSlug(), HohenheimSlugs.INSTANCE_TEMPLATES));
        return new RenderTemplateResult(HohenheimTemplateIds.INSTANCE_FROM_TEMPLATE, vars);
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit, @NonNull AccessContext accessContext) {
        throw new UnsupportedOperationException("The from-template wizard renders through its PanelRequest");
    }

    /**
     * The entries this caller is never asked: no environment picker; the host for everyone but an operator; the
     * project when the caller may create into none.
     */
    private static @NonNull Map<String, @Nullable Object> fixed(@NonNull AccessContext access) {
        Map<String, @Nullable Object> fixed = new LinkedHashMap<>();
        fixed.put(InstanceTemplateOperations.ENVIRONMENT_ID.getName(), null);
        if (!HohenheimAccess.isAdmin(access)) {
            fixed.put(InstanceTemplateOperations.SERVER_ID.getName(), null);
        }
        if (Projects.visibleTo(access).isEmpty()) {
            fixed.put(InstanceTemplateOperations.PROJECT_ID.getName(), null);
        }
        return fixed;
    }

    /** The templates this caller may create from, each linking to its own document. */
    private static @NonNull List<Map<String, Object>> choices(@NonNull PanelRequest request) {
        List<Map<String, Object>> choices = new ArrayList<>();
        Criteria scope = TenantScopes.INSTANCE_TEMPLATES.criteria(request.access());
        QueryBuilder<Row> query = Models.get(InstanceTemplateModel.class).find();
        for (Row template : (scope == null ? query : query.where(scope))
                .orderBy(InstanceTemplateModel.NAME, SortOrder.ASC).all()) {
            Map<String, Object> choice = new HashMap<>();
            choice.put("name", String.valueOf((Object) template.get(InstanceTemplateModel.NAME)));
            choice.put("target", CmsRoutes.list(request.panelSlug(), SLUG)
                .with(HohenheimParams.FROM_TEMPLATE_TEMPLATE, template.get(InstanceTemplateModel.ID)));
            choices.add(choice);
        }
        return choices;
    }
}
