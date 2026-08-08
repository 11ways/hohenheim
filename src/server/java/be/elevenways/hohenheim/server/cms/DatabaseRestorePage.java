package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.SiteDatabaseModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.zenit.common.orm.model.Models;
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
 * Restore tab on a managed database: connection details plus the dump-upload
 * form posting to the host-declared restore endpoint.
 */
public final class DatabaseRestorePage implements RecordScopedPage<Row> {

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "database_restore"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("restore").withFilter("scope", "database"); }
    @Override public @NonNull String slug() { return "restore"; }
    @Override public @NonNull Icon icon() { return Icon.of("upload"); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row record) {
        String name = record.get(DatabaseModel.NAME);
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, "database_restore", name));
        vars.put("name", name);
        vars.put("dbUser", record.get(DatabaseModel.DB_USER));
        vars.put("dbPassword", record.get(DatabaseModel.DB_PASSWORD));
        vars.put("dbName", record.get(DatabaseModel.DB_NAME));
        vars.put("engine", record.get(DatabaseModel.ENGINE));
        vars.put("status", record.get(DatabaseModel.STATUS));
        vars.put("restoreUrl", HohenheimEndpoints.DATABASES_RESTORE
            .with(HohenheimEndpoints.DATABASE_NAME, name).toUrl());
        vars.put("recordId", record.get(DatabaseModel.ID));
        String error = conduit.getQueryParam("error");
        vars.put("error", error != null ? error : "");
        vars.put("restored", "1".equals(conduit.getQueryParam("restored")));
        vars.put("usedBy", usedBy(record.get(DatabaseModel.ID)));
        vars.put("recordTabs", recordTabs(conduit));
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/database-restore"), vars);
    }

    /** Live sites this database is attached to (env injection), for the "Used by" card line. */
    private static @NonNull List<Map<String, Object>> usedBy(Integer databaseId) {
        List<Map<String, Object>> sites = new ArrayList<>();
        SiteModel siteModel = Models.get(SiteModel.class);
        for (Row link : Models.get(SiteDatabaseModel.class).findByDatabaseId(databaseId)) {
            Row site = siteModel.find()
                .where(SiteModel.ID.eq(link.get(SiteDatabaseModel.SITE_ID)))
                .first();
            if (site != null && site.get(SiteModel.DELETED_AT) == null) {
                sites.add(Map.of(
                    "name", String.valueOf(site.get(SiteModel.NAME)),
                    "url", "/admin/sites/" + site.get(SiteModel.ID) + "/page/databases"));
            }
        }
        return sites;
    }
}
