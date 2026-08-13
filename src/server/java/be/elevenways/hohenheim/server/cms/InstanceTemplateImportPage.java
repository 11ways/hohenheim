package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.server.instance.CommunityScripts;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.resource.PanelPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.HashMap;
import java.util.Map;

/**
 * Template import (paste an exported JSON document). The POST is the host-declared
 * INSTANCE_TEMPLATES_IMPORT endpoint; the imported template lands UNAPPROVED, always.
 */
public final class InstanceTemplateImportPage extends PanelPage {

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_templates_import"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("import").withFilter("scope", "instance_template"); }
    @Override public @NonNull String slug() { return "instance-templates-import"; }
    @Override public @NonNull Icon icon() { return Icon.of("file-import"); }
    @Override public boolean showInNav() { return false; }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", Microcopy.of("import").withFilter("scope", "instance_template")
            .resolve(conduit.getLocales(), conduit.getMessageResolver()));
        vars.put("catalogApps", CommunityScripts.catalogApps());
        vars.put("catalogRevision", CommunityScripts.catalogRevision());
        vars.put("templatesTarget", CmsRoutes.list("admin", "instance-templates"));
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/template-import"), vars);
    }
}
