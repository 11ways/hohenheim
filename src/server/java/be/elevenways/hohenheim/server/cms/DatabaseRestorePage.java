package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.resource.RecordTab;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
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
public final class DatabaseRestorePage implements RecordTab.Rendered<Row> {

    @Override public @NonNull Identifier id() { return HohenheimIds.id("database_restore"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("restore").withFilter("scope", "database"); }
    @Override public @NonNull String slug() { return "restore"; }
    @Override public @NonNull Icon icon() { return Icon.of("upload"); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request, @NonNull Row record) {
        Conduit conduit = request.conduit();
        String name = record.get(DatabaseModel.NAME);
        Map<String, Object> vars = new HashMap<>(DatabaseConnectionCard.facts(record));
        vars.put("title", CmsSupport.pageTitle(conduit, "database_restore", name));
        vars.put("restoreUrl", HohenheimEndpoints.DATABASES_RESTORE
            .with(HohenheimEndpoints.DATABASE_NAME, name).toUrl());
        vars.put("recordId", record.get(DatabaseModel.ID));
        vars.put("usedBy", usedBy(record.get(DatabaseModel.ID)));
        vars.put("head", recordHead(conduit));
        return new RenderTemplateResult(HohenheimTemplateIds.DATABASE_RESTORE, vars);
    }

    /** Live INSTANCES this database is attached to (env injection), for the "Used by" line. */
    private static @NonNull List<Map<String, Object>> usedBy(Integer databaseId) {
        List<Map<String, Object>> instances = new ArrayList<>();
        InstanceModel instanceModel = Models.get(InstanceModel.class);
        for (Row link : Models.get(InstanceDatabaseModel.class).findByDatabaseId(databaseId)) {
            Row instance = instanceModel.find()
                .where(InstanceModel.ID.eq(link.get(InstanceDatabaseModel.INSTANCE_ID)))
                .first();
            if (instance != null) {
                instances.add(Map.of(
                    "name", String.valueOf(instance.get(InstanceModel.NAME)),
                    "target", CmsRoutes.subpage(HohenheimSlugs.ADMIN, HohenheimSlugs.INSTANCES,
                        instance.get(InstanceModel.ID), "databases")));
            }
        }
        return instances;
    }
}
