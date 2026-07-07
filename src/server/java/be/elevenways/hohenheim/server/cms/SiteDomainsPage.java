package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Domains tab on a site: the site's hostnames, linking into the (nav-hidden)
 * domain resource forms.
 */
public final class SiteDomainsPage implements RecordScopedPage<Row> {

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "site_domains"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("hohenheim.site.domains"); }
    @Override public @NonNull String slug() { return "domains"; }
    @Override public @NonNull Icon icon() { return Icon.of("at"); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row site) {
        Integer siteId = site.get(SiteModel.ID);
        List<Map<String, Object>> domains = new ArrayList<>();
        for (Row domain : Models.get(SiteDomainModel.class).findBySiteId(siteId)) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("id", domain.get(SiteDomainModel.ID));
            entry.put("hostname", domain.get(SiteDomainModel.HOSTNAME));
            entry.put("matchType", domain.get(SiteDomainModel.MATCH_TYPE));
            entry.put("forceSsl", Boolean.TRUE.equals(domain.get(SiteDomainModel.FORCE_SSL)));
            entry.put("editUrl", "/admin/domains/" + domain.get(SiteDomainModel.ID));
            domains.add(entry);
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", site.get(SiteModel.NAME) + " - Domains");
        vars.put("siteId", siteId);
        vars.put("siteName", site.get(SiteModel.NAME));
        vars.put("domains", domains);
        vars.put("recordTabs", CmsSupport.siteTabs(siteId, "domains"));
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/site-domains"), vars);
    }
}
