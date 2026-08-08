package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.devtunnel.DevLease;
import be.elevenways.hohenheim.server.devtunnel.DevLeases;
import be.elevenways.hohenheim.server.sitetype.types.DevNamespaceSiteType;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
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
 * Dev sessions tab on a dev-namespace site: the live tunnel registrations
 * currently claiming subdomains.
 */
public final class SiteDevSessionsPage implements RecordScopedPage<Row> {

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "site_dev_sessions"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("dev_sessions").withFilter("scope", "site"); }
    @Override public @NonNull String slug() { return "dev-sessions"; }
    @Override public @NonNull Icon icon() { return Icon.of("flask"); }

    @Override
    public boolean visibleFor(@NonNull Row site) {
        return DevNamespaceSiteType.ID.toString().equals(site.get(SiteModel.SITE_TYPE));
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row site) {
        Integer siteId = site.get(SiteModel.ID);

        List<Map<String, Object>> sessions = new ArrayList<>();
        for (DevLease lease : DevLeases.forSite(siteId)) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("name", lease.getName());
            entry.put("origin", lease.getOrigin());
            entry.put("registeredAt", String.valueOf(lease.getRegisteredAt()));
            entry.put("streams", lease.getActiveStreams());
            sessions.add(entry);
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, "dev_sessions",
            site.get(SiteModel.NAME)));
        vars.put("siteName", site.get(SiteModel.NAME));
        vars.put("sessions", sessions);
        vars.put("recordTabs", recordTabs(conduit));

        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/site-dev-sessions"), vars);
    }
}
