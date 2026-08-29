package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.database.DatabaseEnvInjection;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
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

/**
 * Databases tab on a Docker-runtime instance: every attached managed database with the
 * variable family it injects, linking into the (nav-hidden) attachment resource forms --
 * the InstanceVolumesPage shape over {@code instance_databases}.
 *
 * AIDEV-NOTE: this is THE page a detach happens on, and it did not exist: the attachment
 * resource declared {@code parent().tab("databases")}, the database in-use refusal said
 * "detach it there first" and two attention items linked here, while the instance record
 * had no such tab (F6, 2026-08-29) -- the only way to an attachment was the nav-hidden
 * list at /admin/instance-databases.
 */
public final class InstanceDatabasesPage implements RecordScopedPage<Row> {

    public static final String SLUG = "databases";

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_databases"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "instance_database"); }
    @Override public boolean secondaryTab() { return true; }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Icon icon() { return Icon.of("database"); }

    /**
     * Only kinds that run on a Docker daemon get the tab: injection rides the link
     * network, which an Incus container or VM has none of, and the attachment form
     * refuses those kinds by name ({@code instance_kind_no_injection}).
     */
    @Override
    public boolean visibleFor(@NonNull Row record) {
        InstanceKindHandler handler = InstanceKinds.getHandler(record.get(InstanceModel.KIND));
        return handler != null && handler.supportedRuntimes().contains(ServerModel.RUNTIME_DOCKER);
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row instance) {
        Integer instanceId = instance.get(InstanceModel.ID);
        String panel = CmsSupport.panelSlug(conduit);

        List<Map<String, Object>> attachments = new ArrayList<>();
        for (Row link : Models.get(InstanceDatabaseModel.class).findByInstanceId(instanceId)) {
            Row database = Models.get(DatabaseModel.class).find()
                .where(DatabaseModel.ID.eq(link.get(InstanceDatabaseModel.DATABASE_ID))).first();
            Map<String, Object> entry = new HashMap<>();
            entry.put("id", link.get(InstanceDatabaseModel.ID));
            entry.put("databaseName", database != null
                ? String.valueOf((Object) database.get(DatabaseModel.NAME)) : "");
            entry.put("engine", database != null
                ? String.valueOf((Object) database.get(DatabaseModel.ENGINE)) : "");
            entry.put("status", database != null
                ? String.valueOf((Object) database.get(DatabaseModel.STATUS)) : "");
            entry.put("prefix", DatabaseEnvInjection.normalizedPrefix(
                link.get(InstanceDatabaseModel.ENV_PREFIX)));
            entry.put("editTarget", CmsRoutes.detail(panel, "instance-databases",
                link.get(InstanceDatabaseModel.ID)));
            entry.put("databaseTarget", database != null
                ? CmsRoutes.detail(panel, "databases", database.get(DatabaseModel.ID)) : null);
            attachments.add(entry);
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, "instance_database",
            instance.get(InstanceModel.NAME)));
        vars.put("instanceId", instanceId);
        vars.put("instanceName", instance.get(InstanceModel.NAME));
        vars.put("attachments", attachments);
        boolean canEdit = HohenheimAccess.isAdmin(accessContext)
            || HohenheimAccess.hasInstanceCapability(
                accessContext, instanceId, HohenheimAccess.CONFIG);
        // Gated on the SAME boolean the template's {% if %} uses: a declared template
        // variable is serialized into the hydration payload whether or not any element
        // renders it (the InstanceDevicesPage lesson).
        vars.put("attachTarget", canEdit ? attachTarget(panel, instanceId) : null);
        vars.put("recordTabs", recordTabs(conduit));
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/instance-databases"), vars);
    }

    /** The attachment create form, opened with its owning instance prefilled. */
    private static @NonNull RouteTarget attachTarget(@NonNull String panel,
                                                     @NonNull Integer instanceId) {
        return CmsEndpoints.CREATE_FORM
            .with(CmsEndpoints.PANEL_PARAM, panel)
            .with(CmsEndpoints.RESOURCE_PARAM, "instance-databases")
            .with(HohenheimParams.INSTANCE_ID_PREFILL, instanceId);
    }
}
