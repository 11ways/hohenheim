package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.http.ReturnTarget;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Protected paths tab on a site: which folders demand an extra access list, linking into
 * the (nav-hidden) protected-path resource forms.
 */
public final class SiteProtectedPathsPage implements RecordScopedPage<Row> {

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "site_protected_paths"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("protected_paths").withFilter("scope", "site"); }
    @Override public @NonNull String slug() { return "protected-paths"; }
    @Override public @NonNull Icon icon() { return Icon.of("lock"); }

    /** An HTTP concern: encrypted passthrough traffic offers nothing to guard per path. */
    @Override
    public boolean visibleFor(@NonNull Row site) {
        return !SiteModel.UPSTREAM_TLS_PASSTHROUGH.equals(site.get(SiteModel.UPSTREAM_KIND));
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row site) {
        Integer siteId = site.get(SiteModel.ID);
        String panel = CmsSupport.panelSlug(conduit);
        // Per-row write authority is the RESOURCE's answer, never a second hand-rolled
        // one -- the SiteDomainsPage seam.
        ProtectedPathResource resource = pathResource(panel);
        boolean canAdd = resource != null && resource.creatable()
            && HohenheimAccess.reachesRecord(accessContext, SiteModel.MODEL_ID, siteId,
                HohenheimAccess.MANAGE);
        boolean anyRowActions = false;
        List<Map<String, Object>> paths = new ArrayList<>();
        for (Row guarded : Models.get(ProtectedPathModel.class).findBySiteId(siteId)) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("id", guarded.get(ProtectedPathModel.ID));
            entry.put("path", guarded.get(ProtectedPathModel.PATH));
            entry.put("listName", listNameOf(guarded));
            boolean canEditRow = resource != null && resource.updatable()
                && resource.updatableBy(guarded, accessContext);
            entry.put("canEdit", canEditRow);
            if (canEditRow) {
                entry.put("editTarget", CmsRoutes.detail(panel, "protected-paths",
                    guarded.get(ProtectedPathModel.ID)));
            }
            boolean canRemoveRow = resource != null && resource.deletable()
                && resource.deletableBy(guarded, accessContext);
            entry.put("canRemove", canRemoveRow);
            if (canRemoveRow) {
                entry.put("deleteTarget", ReturnTarget.bind(
                    CmsRoutes.delete(panel, "protected-paths",
                        guarded.get(ProtectedPathModel.ID)), conduit));
            }
            anyRowActions |= canEditRow || canRemoveRow;
            paths.add(entry);
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, "site_protected_paths",
            site.get(SiteModel.NAME)));
        vars.put("siteId", siteId);
        vars.put("siteName", site.get(SiteModel.NAME));
        vars.put("paths", paths);
        vars.put("hasRowActions", anyRowActions);
        vars.put("canAdd", canAdd);
        vars.put("addTarget", canAdd ? CmsEndpoints.CREATE_FORM
            .with(CmsEndpoints.PANEL_PARAM, panel)
            .with(CmsEndpoints.RESOURCE_PARAM, "protected-paths")
            .with(HohenheimParams.SITE_ID_PREFILL, siteId) : null);
        vars.put("recordTabs", recordTabs(conduit));
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/site-protected-paths"), vars);
    }

    /** The list's display name; a dangling reference reads as its id, never as unguarded. */
    private static @NonNull String listNameOf(@NonNull Row guarded) {
        Integer listId = guarded.get(ProtectedPathModel.ACCESS_LIST_ID);
        Row list = listId != null ? Models.get(AccessListModel.class).findById(listId) : null;
        String name = list != null ? list.get(AccessListModel.NAME) : null;
        return name != null && !name.isBlank() ? name : "#" + listId;
    }

    /**
     * The protected-path resource of the panel this tab renders under, whose write
     * predicates decide every affordance here.
     */
    private static @Nullable ProtectedPathResource pathResource(@NonNull String panelSlug) {
        Panel panel = PanelRegistry.getBySlug(panelSlug);
        return panel != null
            && panel.peerBySlug("protected-paths") instanceof ProtectedPathResource peer
            ? peer : null;
    }
}
