package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.model.InstanceFileModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.resource.Resource;
import be.elevenways.zenit.cms.server.page.ChildListSections;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Provisioning tab on an instance: its template, install state, variables (SECRET
 * VALUES NEVER RENDERED -- key and kind only) and config files.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class InstanceProvisioningPage implements RecordScopedPage<Row> {

    /** The tab's slug, which the variables child list names as its parent tab. */
    public static final String SLUG = "provisioning";

    /** The admin config-file resource's slug; the panel is asked for it, never assumed. */
    private static final String FILE_RESOURCE_SLUG = "instance-files";

    @Override public @NonNull Identifier id() { return HohenheimIds.id("instance_provisioning"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("provisioning").withFilter("scope", "instance"); }
    /**
     * Housekeeping, not an everyday destination: the tab lives in the strip's "More"
     * menu so the visible strip stays the handful of tabs an operator opens daily.
     */
    @Override public boolean secondaryTab() { return true; }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Icon icon() { return Icon.of("wand-magic-sparkles"); }

    /**
     * AIDEV-NOTE: the variables are the framework's child list section ({@link InstanceVariableParts}), embedded over
     * the instance this tab dispatch already admitted; the page keeps its wrapper, install state and files.
     */
    @Override
    @SuppressWarnings("unchecked")
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request, @NonNull Row instance) {
        Conduit conduit = request.conduit();
        Integer instanceId = instance.get(InstanceModel.ID);
        String panel = request.panelSlug();

        Object templateId = instance.get(InstanceModel.TEMPLATE_ID);
        Row template = templateId instanceof Integer id
            ? Models.get(InstanceTemplateModel.class).findById(id) : null;

        // AIDEV-NOTE: the config-file editor is an ADMIN peer, and this page renders under
        // /manage too. Emitting its URLs unconditionally handed every tenant 404 links on
        // their own Provisioning tab. The links are now derived from what the HOSTING PANEL
        // actually offers -- ask the panel, never assume a slug exists -- so /manage renders
        // the same rows read-only and a later decision to delegate the editor lights them up
        // with no change here.
        boolean editable = request.panel().peerBySlug(FILE_RESOURCE_SLUG) != null;

        List<Map<String, Object>> files = new ArrayList<>();
        for (Row file : Models.get(InstanceFileModel.class).findByInstanceId(instanceId)) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("path", file.get(InstanceFileModel.CONTAINER_PATH));
            entry.put("mode", file.get(InstanceFileModel.MODE));
            entry.put("editTarget", editable
                ? CmsRoutes.detail(panel, FILE_RESOURCE_SLUG, file.get(InstanceFileModel.ID)) : null);
            files.add(entry);
        }

        String installState = instance.get(InstanceModel.INSTALL_STATE);
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", instance.get(InstanceModel.NAME));
        vars.put("instanceName", instance.get(InstanceModel.NAME));
        vars.put("templateName", template != null
            ? String.valueOf((Object) template.get(InstanceTemplateModel.NAME)) : "");
        vars.put("installState", installState == null ? InstanceModel.INSTALL_NONE : installState);
        vars.put("installStateLabel", Microcopy.of(
                installState == null ? InstanceModel.INSTALL_NONE : installState)
            .withFilter("scope", "install_state"));
        // AIDEV-NOTE: the SECOND surface of the same leak InstanceOverviewPage's
        // installError note describes -- this tab renders under /manage too, and the
        // stored text is the daemon's or transport's own. The install-state label above
        // carries the fact; the reason stays on the operator panel.
        String installError = ManagePanel.SLUG.equals(panel)
            ? null : instance.get(InstanceModel.INSTALL_ERROR);
        vars.put("installError", installError == null ? "" : installError);
        Resource<Row> parent = (Resource<Row>) request.panel().entryBySlug(HohenheimSlugs.INSTANCES);
        vars.put("sections", ChildListSections.embedded(request, Objects.requireNonNull(parent,
            "the instance entry dispatched this tab"), instance, InstanceVariableParts.PROVISIONING));
        vars.put("panelSlug", panel);
        vars.put("files", files);
        // Create form + prefill query parameter: composed off CmsEndpoints, since
        // CmsRoutes.create returns the RouteTarget interface (no with(...)).
        RouteTarget addFileTarget = editable ? CmsEndpoints.CREATE_FORM
            .with(CmsEndpoints.PANEL_PARAM, panel)
            .with(CmsEndpoints.RESOURCE_PARAM, FILE_RESOURCE_SLUG)
            .with(HohenheimParams.INSTANCE_ID_PREFILL, instanceId) : null;
        vars.put("addFileTarget", addFileTarget);
        vars.put("canAddFile", addFileTarget != null);
        vars.put("recordTabs", recordTabs(conduit));
        return new RenderTemplateResult(HohenheimTemplateIds.INSTANCE_PROVISIONING, vars);
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit, @NonNull AccessContext accessContext,
                                           @NonNull Row instance) {
        throw new UnsupportedOperationException("The provisioning tab renders through its PanelRequest");
    }
}
