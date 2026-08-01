package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimEndpoints;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.SiteDatabaseModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.Secrets;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldAccess;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Docker-provisioned managed databases. Create provisions the container in
 * the background; records are immutable afterwards (all fields read-only on
 * edit) with backup/restore/destroy as actions.
 */
public final class DatabaseResource extends RowResource {

    private final DatabaseService databaseService = new DatabaseService();
    private final ServerService serverService = new ServerService();

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
        .add(Select.of(DatabaseModel.SERVER_NAME)
            .options(OptionSource.dynamic(ctx -> serverOptions()))
            .build())
        .add(DatabaseModel.STATUS)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(DatabaseModel.NAME).filterable().build())
        .column(ColumnSpec.fromField(DatabaseModel.ENGINE).filterable().build())
        .column(ColumnSpec.fromField(DatabaseModel.DB_NAME).filterable().build())
        .column(ColumnSpec.fromField(DatabaseModel.SERVER_NAME).filterable().build())
        .column(ColumnSpec.fromField(DatabaseModel.EPHEMERAL).filterable().build())
        .column(ColumnSpec.fromField(DatabaseModel.STATUS).filterable().build())
        .filter(FilterSpec.forField(DatabaseModel.NAME, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(DatabaseModel.NAME)).build())
        .filter(FilterSpec.forField(DatabaseModel.ENGINE, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(DatabaseModel.ENGINE)).build())
        .filter(FilterSpec.forField(DatabaseModel.DB_NAME, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(DatabaseModel.DB_NAME)).build())
        .filter(FilterSpec.forField(DatabaseModel.SERVER_NAME, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(DatabaseModel.SERVER_NAME)).build())
        .filter(FilterSpec.forField(DatabaseModel.EPHEMERAL, FilterSpec.Kind.BOOLEAN)
            .label(FieldLabels.labelFor(DatabaseModel.EPHEMERAL)).build())
        .filter(FilterSpec.forField(DatabaseModel.STATUS, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(DatabaseModel.STATUS)).build())
        .build();

    private @NonNull List<FieldOption<String>> serverOptions() {
        this.serverService.ensureLocal();
        List<FieldOption<String>> options = new ArrayList<>();
        for (String name : this.serverService.names()) {
            options.add(FieldOption.of(name, name));
        }
        return options;
    }

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "database"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "database"); }
    @Override public @NonNull String slug() { return "databases"; }
    @Override public @NonNull Model model() { return Models.get(DatabaseModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.INFRA_GROUP; }
    @Override public int navOrder() { return 10; }
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
        String server = trimmed(coerced.get("server_name"));
        if (server.isEmpty()) {
            server = ServerService.LOCAL;
        }

        ResourceLimits limits = ResourceLimits.of(
            coerced.get("memory_limit_mb") instanceof Integer mb ? mb : null,
            coerced.get("cpu_limit") instanceof Double cpus ? cpus : null);

        // The service persists the record itself (status=provisioning) and
        // provisions the container in the background.
        this.databaseService.createAsync(name, engine, image.isEmpty() ? null : image,
            user, password, database, ephemeral, server, limits);


        Row created = this.model().find().where(DatabaseModel.NAME.eq(name)).first();
        if (created == null) {
            throw new IllegalStateException("Provisioning did not create a record for '" + name + "'");
        }
        return rowKey(created);
    }

    /** Refuses while live sites still depend on the database's injected credentials. */
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
        if (!attachedTo.isEmpty()) {
            throw Violations.ofForm(CmsSupport.violationText("database_in_use")
                .withArg("name", name)
                .withArg("sites", String.join(", ", attachedTo)));
        }
        try {
            this.databaseService.destroy(name, true);
        } catch (IOException e) {
            throw new UncheckedIOException("Destroy of '" + name + "' failed", e);
        }
        // Links to soft-deleted sites are debris once the database is gone.
        links.find().where(SiteDatabaseModel.DATABASE_ID.eq(id)).delete();
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
        return actions;
    }

    @Override
    public @NonNull List<RecordScopedPage<Row>> subpages() {
        return List.of(new DatabaseRestorePage());
    }

    private static @NonNull String trimmed(@Nullable Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }
}
