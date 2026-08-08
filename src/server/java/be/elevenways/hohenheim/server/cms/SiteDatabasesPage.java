package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.SiteDatabaseModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.database.DatabaseEnvInjection;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.hohenheim.sitetype.SiteTypeInfo;
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
 * Databases tab on a site: the managed databases attached to it and the
 * environment variables their processes receive, linking into the
 * (nav-hidden) attachment resource forms. Only offered on site types that
 * support env injection.
 */
public final class SiteDatabasesPage implements RecordScopedPage<Row> {

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "site_databases_page"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("databases").withFilter("scope", "site"); }
    @Override public @NonNull String slug() { return "databases"; }
    @Override public @NonNull Icon icon() { return Icon.of("database"); }

    @Override
    public boolean visibleFor(@NonNull Row site) {
        SiteTypeInfo type = SiteTypes.getHandler(site.get(SiteModel.SITE_TYPE));
        return type != null && type.supportsEnvInjection();
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row site) {
        Integer siteId = site.get(SiteModel.ID);
        DatabaseModel databaseModel = Models.get(DatabaseModel.class);

        List<Map<String, Object>> links = new ArrayList<>();
        boolean primary = true;
        for (Row link : Models.get(SiteDatabaseModel.class).findBySiteId(siteId)) {
            Map<String, Object> entry = new HashMap<>();
            String prefix = DatabaseEnvInjection.normalizedPrefix(link.get(SiteDatabaseModel.ENV_PREFIX));
            entry.put("id", link.get(SiteDatabaseModel.ID));
            entry.put("prefix", prefix);
            entry.put("primary", primary);
            entry.put("editUrl", "/admin/site-databases/" + link.get(SiteDatabaseModel.ID));
            entry.put("varNames", prefix + "_HOST, _PORT, _USER, _PASSWORD, _NAME, _URL"
                + (primary ? " + DATABASE_URL" : ""));
            primary = false;

            Row database = databaseModel.find()
                .where(DatabaseModel.ID.eq(link.get(SiteDatabaseModel.DATABASE_ID)))
                .first();
            if (database != null) {
                entry.put("dbName", database.get(DatabaseModel.NAME));
                entry.put("engine", database.get(DatabaseModel.ENGINE));
                entry.put("status", String.valueOf(database.get(DatabaseModel.STATUS)));
                entry.put("dbUrl", "/admin/databases/" + database.get(DatabaseModel.ID));
            } else {
                entry.put("dbName", "(deleted)");
                entry.put("engine", "");
                entry.put("status", "missing");
                entry.put("dbUrl", "");
            }
            links.add(entry);
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, "site_databases",
            site.get(SiteModel.NAME)));
        vars.put("siteId", siteId);
        vars.put("siteName", site.get(SiteModel.NAME));
        vars.put("links", links);
        vars.put("recordTabs", recordTabs(conduit));
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/site-databases"), vars);
    }
}
