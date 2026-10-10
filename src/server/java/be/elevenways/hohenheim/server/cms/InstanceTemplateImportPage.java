package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.server.instance.CommunityScripts;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.resource.PanelPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.HashMap;
import java.util.Map;

/**
 * Template import (paste an exported JSON document). The POST is the host-declared
 * INSTANCE_TEMPLATES_IMPORT endpoint; the imported template lands UNAPPROVED, always.
 */
public final class InstanceTemplateImportPage extends PanelPage {

    @Override public @NonNull Identifier id() { return HohenheimIds.id("instance_templates_import"); }
    @Override public @NonNull Microcopy label() { return HohenheimMicrocopy.INSTANCE_TEMPLATE.of("import"); }
    @Override public @NonNull String slug() { return HohenheimSlugs.INSTANCE_TEMPLATES_IMPORT; }
    @Override public @NonNull Icon icon() { return Icon.of("file-import"); }
    @Override public boolean showInNav() { return false; }
    @Override public @NonNull String standsUnder() { return HohenheimSlugs.INSTANCE_TEMPLATES; }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request) {
        Conduit conduit = request.conduit();
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", HohenheimMicrocopy.INSTANCE_TEMPLATE.of("import")
            .resolve(conduit.getLocales(), conduit.getMessageResolver()));
        vars.put("catalogApps", CommunityScripts.catalogApps());
        vars.put("catalogRevision", CommunityScripts.catalogRevision());
        vars.put("templatesTarget", CmsRoutes.list(HohenheimSlugs.ADMIN, HohenheimSlugs.INSTANCE_TEMPLATES));
        return new RenderTemplateResult(HohenheimTemplateIds.TEMPLATE_IMPORT, vars);
    }
}
