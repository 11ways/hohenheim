package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDatabaseModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.Secrets;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.InstanceDatabaseLinks;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.FieldAccess;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Docker-provisioned managed databases. Create provisions the container in
 * the background; records are immutable afterwards (all fields read-only on
 * edit) with backup/restore/destroy as actions.
 */
public class DatabaseResource extends RowResource {

    private final DatabaseService databaseService = new DatabaseService();

    private final FormSpec formSpec = FormSpec.builder()
        .add(DatabaseModel.NAME)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(DatabaseModel.ENGINE))
        .add(DatabaseModel.DB_NAME)
        .add(DatabaseModel.DB_USER)
        .add(DatabaseModel.DB_PASSWORD)
        .add(DatabaseModel.IMAGE)
        .add(DatabaseModel.EPHEMERAL)
        .add(DatabaseModel.MEMORY_LIMIT_MB)
        .add(DatabaseModel.CPU_LIMIT)
        .add(RelationPick.of(DatabaseModel.SERVER_ID, ServerModel.MODEL_ID).build())
        .add(DatabaseModel.STATUS)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(DatabaseModel.NAME).filterable().build())
        .column(ColumnSpec.fromField(DatabaseModel.ENGINE).filterable().build())
        .column(ColumnSpec.fromField(DatabaseModel.DB_NAME).filterable().build())
        .column(ColumnSpec.fromField(DatabaseModel.SERVER_ID)
            .relation(RelationPick.of(DatabaseModel.SERVER_ID, ServerModel.MODEL_ID).build()).build())
        .column(ColumnSpec.fromField(DatabaseModel.EPHEMERAL).filterable().build())
        .column(ColumnSpec.fromField(DatabaseModel.STATUS).filterable().build())
        .filter(FilterSpec.forField(DatabaseModel.NAME, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(DatabaseModel.NAME)).build())
        .filter(FilterSpec.forField(DatabaseModel.ENGINE, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(DatabaseModel.ENGINE)).build())
        .filter(FilterSpec.forField(DatabaseModel.DB_NAME, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(DatabaseModel.DB_NAME)).build())
        .filter(FilterSpec.forField(DatabaseModel.EPHEMERAL, FilterSpec.Kind.BOOLEAN)
            .label(FieldLabels.labelFor(DatabaseModel.EPHEMERAL)).build())
        .filter(FilterSpec.forField(DatabaseModel.STATUS, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(DatabaseModel.STATUS)).build())
        .build();

    /** The server pick defaults to the local daemon (ensuring its row exists for the picker). */
    @Override
    public @NonNull Map<String, Object> createValues(@NonNull Conduit conduit) {
        Map<String, Object> values = CmsSupport.mutable(formSpec().defaultValues());
        values.put("server_id", ServerModel.localServerId());
        return Map.copyOf(values);
    }

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "database"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "database"); }
    @Override public @NonNull String slug() { return "databases"; }
    @Override public @NonNull Model model() { return Models.get(DatabaseModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.INFRA_GROUP; }
    @Override public int navOrder() { return 30; }

    @Override
    public @Nullable Microcopy description() {
        return Microcopy.of("nav_hint").withFilter("scope", "database");
    }
    @Override public @NonNull Icon icon() { return Icon.of("database"); }
    @Override public boolean updatable() { return false; }


    /** Records are provisioned containers: no field is editable after create. */
    @Override
    public @NonNull List<ResourceFieldBinding> fieldBindings() {
        // STATUS is service-owned even on create.
        return List.of(ResourceFieldBinding.of(DatabaseModel.STATUS.getName(), FieldAccess.alwaysReadonly()));
    }

    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        String name = trimmed(coerced.get("name"));
        if (!name.matches("[a-z0-9][a-z0-9-]*")) {
            throw Violations.ofField("name", name, CmsSupport.violationText("name_format"));
        }
        String engineToken = trimmed(coerced.get("engine")).toLowerCase(Locale.ROOT);
        ManagedDatabase.Engine engine;
        try {
            engine = ManagedDatabase.Engine.valueOf(engineToken.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw Violations.ofField("engine", engineToken,
                CmsSupport.violationText("unknown_engine").withArg("engine", engineToken));
        }
        String database = trimmed(coerced.get("db_name"));
        if (database.isEmpty()) {
            throw Violations.ofField("db_name", database,
                CmsSupport.violationText("database_name_required"));
        }
        String user = trimmed(coerced.get("db_user"));
        if (user.isEmpty()) {
            user = "appuser";
        }
        String password = trimmed(coerced.get("db_password"));
        if (password.isEmpty()) {
            password = Secrets.generatePassword();
        }
        String image = trimmed(coerced.get("image"));
        boolean ephemeral = Boolean.TRUE.equals(coerced.get("ephemeral"));
        // The FK is canonical; the service API still speaks the (unique) server name.
        String server = ServerModel.nameOf(
            coerced.get("server_id") instanceof Integer serverId ? serverId : null);

        ResourceLimits limits = ResourceLimits.of(
            coerced.get("memory_limit_mb") instanceof Integer mb ? mb : null,
            coerced.get("cpu_limit") instanceof Double cpus ? cpus : null);

        // The service persists the record itself (status=provisioning) and provisions the
        // container in the background. It REFUSES a name that is already taken
        // (database_name_taken) rather than converging onto the existing record.
        //
        // AIDEV-NOTE: the returned row is the one the service inserted, not the answer to
        // a re-query by name. The old code looked the name back up and called whatever it
        // found "created" -- which, on the pre-fix upsert path, was how a colliding create
        // reported success while it had actually overwritten someone else's database and
        // handed its id back as the new record's.
        return rowKey(this.databaseService.createAsync(name, engine,
            image.isEmpty() ? null : image, user, password, database, ephemeral, server, limits));
    }

    /**
     * A database DELETE demands {@code destroy} on the record (the model's
     * before-remove hook in {@code TenantWrites}), so the synthesized Delete affordance
     * is offered on exactly that answer -- the {@link InstanceDeviceResource} shape:
     * {@link ManageDatabaseResource} reads by the wider {@code view}, and without this
     * a view-only delegate was shown a destroy button the pipeline could only refuse.
     * No updatableBy twin: {@link #updatable()} is false, the record is immutable.
     */
    @Override
    public boolean deletableBy(@NonNull Row record, @NonNull AccessContext accessContext) {
        Integer id = record.get(DatabaseModel.ID);
        return super.deletableBy(record, accessContext) && id != null
            && HohenheimAccess.hasDatabaseCapability(accessContext, id,
                HohenheimAccess.DESTROY);
    }

    /**
     * Refuses while any live WORKLOAD still depends on the database's injected credentials.
     *
     * AIDEV-NOTE: both tiers, and the instance half is not decoration. Since 2026-08-08 a
     * database can be attached to an instance, and a refusal that only counted SITES would
     * have let a tenant destroy the engine out from under their own running game server --
     * the workload keeps its derived environment until it next resolves and then simply
     * cannot connect, with nothing anywhere saying why.
     */
    @Override
    public void deleteRow(@NonNull Row existing, @NonNull AccessContext accessContext) {
        String name = existing.get(DatabaseModel.NAME);
        Integer id = existing.get(DatabaseModel.ID);
        SiteDatabaseModel links = Models.get(SiteDatabaseModel.class);
        SiteModel sites = Models.get(SiteModel.class);
        List<String> attachedTo = new ArrayList<>();
        for (Row link : links.findByDatabaseId(id)) {
            Row site = sites.find().where(SiteModel.ID.eq(link.get(SiteDatabaseModel.SITE_ID))).first();
            if (site != null && site.get(SiteModel.DELETED_AT) == null) {
                attachedTo.add(String.valueOf(site.get(SiteModel.NAME)));
            }
        }
        attachedTo.addAll(InstanceDatabaseLinks.liveInstanceNames(id));
        if (!attachedTo.isEmpty()) {
            throw Violations.ofForm(CmsSupport.violationText("database_in_use")
                .withArg("name", name)
                .withArg("workloads", String.join(", ", attachedTo)));
        }
        try {
            this.databaseService.destroy(name, true);
        } catch (IOException e) {
            // A NAMED refusal, not a 500: the record is kept (status destroy_failed), the
            // port claim is parked, and the force-destroy action is the recorded way out.
            throw Violations.ofForm(CmsSupport.violationText("database_destroy_failed")
                .withArg("name", name)
                .withArg("reason", e.getMessage()));
        }
        // Links to soft-deleted owners are debris once the database is gone.
        links.find().where(SiteDatabaseModel.DATABASE_ID.eq(id)).delete();
        InstanceDatabaseLinks.deleteForDatabase(id);
    }

    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        List<RowAction<Row>> actions = new ArrayList<>(super.rowActions());
        actions.add(RowAction.Url.<Row>builder(Identifier.of("hohenheim", "backup_database"))
            .label(Microcopy.of("backup").withFilter("scope", "database"))
            .icon(Icon.of("download"))
            .url(row -> new Uri(HohenheimEndpoints.DATABASES_BACKUP
                .with(HohenheimEndpoints.DATABASE_NAME, row.get(DatabaseModel.NAME)).toUrl()))
            .build());
        actions.add(this.forceDeleteAction());
        return actions;
    }

    /**
     * The recorded escape hatch for a genuinely unreachable host: visible ONLY once a
     * normal destroy already failed (status {@code destroy_failed}), typed-confirmed
     * with the database's own name, and ActivityLog-recorded. The container and volume
     * may survive on the host; the reconciler reports them as orphans, and the port
     * claim stays parked in {@code releasing} via the model's remove hooks.
     */
    private @NonNull RowAction<Row> forceDeleteAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "force_delete_database"))
            .label(Microcopy.of("force_delete").withFilter("scope", "database"))
            .description(Microcopy.of("force_delete_hint").withFilter("scope", "database"))
            .icon(Icon.of("triangle-exclamation"))
            .style(ActionStyle.DESTRUCTIVE)
            .inlineInRow(false)
            .visibleFor((row, ctx) ->
                DatabaseModel.STATUS_DESTROY_FAILED.equals(row.get(DatabaseModel.STATUS)))
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("force_delete").withFilter("scope", "database"))
                .body(Microcopy.of("force_delete_confirm_generic").withFilter("scope", "database"))
                .confirmLabel(Microcopy.of("force_delete_ok").withFilter("scope", "database"))
                .style(ActionStyle.DESTRUCTIVE)
                .build())
            .dynamicConfirmation(row -> ConfirmationSpec.builder()
                .title(Microcopy.of("force_delete").withFilter("scope", "database"))
                .body(Microcopy.of("force_delete_confirm").withFilter("scope", "database")
                    .withArg("name", row.get(DatabaseModel.NAME)))
                .confirmLabel(Microcopy.of("force_delete_ok").withFilter("scope", "database"))
                .style(ActionStyle.DESTRUCTIVE)
                .requireTypedConfirmation(row.get(DatabaseModel.NAME))
                .build())
            .handler((row, ctx) -> {
                String name = row.get(DatabaseModel.NAME);
                Integer id = row.get(DatabaseModel.ID);
                ActivityLog.withAction(ActivityLog.ACTION_DELETE, "force-destroy", () -> {
                    this.databaseService.forceDestroyRecord(name);
                    Models.get(SiteDatabaseModel.class).find()
                        .where(SiteDatabaseModel.DATABASE_ID.eq(id)).delete();
                    InstanceDatabaseLinks.deleteForDatabase(id);
                });
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("force_delete_done").withFilter("scope", "database")
                        .withArg("name", name));
            })
            .build();
    }

    @Override
    public @NonNull List<RecordScopedPage<Row>> subpages() {
        return List.of(new DatabaseRestorePage());
    }

    private static @NonNull String trimmed(@Nullable Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }
}
