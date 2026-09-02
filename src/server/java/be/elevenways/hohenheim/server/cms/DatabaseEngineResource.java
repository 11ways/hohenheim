package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.Secrets;
import be.elevenways.hohenheim.server.database.DatabaseEngines;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.FieldAccess;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FormSection;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
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
import java.util.Objects;

/**
 * The SHARED database engines: one engine process per (host, kind) serving many managed
 * databases as logical databases. Create provisions the container in the background, the
 * two resource ceilings are the only editable columns afterwards (a recreate, exactly
 * like a dedicated database's resize), and delete is refused while any record still lives
 * on the engine.
 *
 * AIDEV-NOTE: an engine deliberately survives its last database. Recreating one costs a
 * minute and a port, and a host booking that flaps with the last delete is worse than an
 * idle engine process -- see docs/shared-database-engines.md.
 */
public class DatabaseEngineResource extends RowResource {

    /** The panel slug, referenced by the placement column's relation pick. */
    public static final String SLUG = "database-engines";

    private final FormSpec formSpec = FormSpec.builder()
        .add(DatabaseEngineModel.NAME)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(DatabaseEngineModel.ENGINE))
        .add(DatabaseEngineModel.ROOT_USER)
        .add(DatabaseEngineModel.ROOT_PASSWORD)
        .add(DatabaseEngineModel.IMAGE)
        .add(DatabaseEngineModel.MEMORY_LIMIT_MB)
        .add(DatabaseEngineModel.CPU_LIMIT)
        // See InstanceResource: a host is enrolled deliberately, never inline.
        .add(RelationPick.of(DatabaseEngineModel.SERVER_ID, ServerModel.MODEL_ID)
            .creatable(false).build())
        .add(DatabaseEngineModel.STATUS)
        .add(DatabaseEngineModel.FAILURE_REASON)
        // An engine is a kind, a host and its superuser. The image override and the
        // ceilings all have defaults.
        .section(FormSection.advanced(
            DatabaseEngineModel.IMAGE.getName(),
            DatabaseEngineModel.MEMORY_LIMIT_MB.getName(),
            DatabaseEngineModel.CPU_LIMIT.getName()))
        .build();

    /** The virtual column counting the managed databases living on an engine. */
    private static final String DATABASES_COLUMN = "databases";

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(DatabaseEngineModel.NAME).filterable().subtext("engine").build())
        .column(ColumnSpec.fromField(DatabaseEngineModel.ENGINE).filterable().hidden().build())
        .column(ColumnSpec.fromField(DatabaseEngineModel.SERVER_ID)
            .relation(RelationPick.of(DatabaseEngineModel.SERVER_ID, ServerModel.MODEL_ID).build())
            .build())
        .column(ColumnSpec.fromField(DatabaseEngineModel.STATUS).filterable().build())
        .column(ColumnSpec.virtual(DATABASES_COLUMN,
            Microcopy.of("databases").withFilter("scope", "database_engine")).build())
        .column(ColumnSpec.fromField(DatabaseEngineModel.MEMORY_LIMIT_MB).build())
        .filter(FilterSpec.forField(DatabaseEngineModel.NAME, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(DatabaseEngineModel.NAME)).build())
        .filter(FilterSpec.forField(DatabaseEngineModel.ENGINE, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(DatabaseEngineModel.ENGINE)).build())
        .filter(FilterSpec.forField(DatabaseEngineModel.STATUS, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(DatabaseEngineModel.STATUS)).build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "database_engine"); }
    @Override public @NonNull Microcopy label() {
        return Microcopy.of("plural").withFilter("scope", "database_engine");
    }
    @Override public @Nullable Microcopy recordLabel() {
        return Microcopy.of("singular").withFilter("scope", "database_engine");
    }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Model model() { return Models.get(DatabaseEngineModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull ListChrome listChrome() { return CmsSupport.WIDE_LIST; }

    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(DatabaseEngineModel.NAME);
    }

    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.DEPLOY_GROUP; }

    /** Right after Databases: the tier the list above it places records on. */
    @Override public int navOrder() { return 51; }

    @Override
    public @Nullable Microcopy description() {
        return CmsSupport.navHint("database_engine");
    }

    @Override public @NonNull Icon icon() { return Icon.of("server"); }

    /** Editable, but ONLY for the resource ceilings; see {@link #updateRow}. */
    @Override public boolean updatable() { return true; }

    /** The host pick defaults to the local daemon, and the superuser to the shipped word. */
    @Override
    public @NonNull Map<String, Object> createValues(@NonNull Conduit conduit) {
        Map<String, Object> values = CmsSupport.mutable(formSpec().defaultValues());
        values.put(DatabaseEngineModel.SERVER_ID.getName(), ServerModel.localServerId());
        values.put(DatabaseEngineModel.ROOT_USER.getName(), "root");
        return Map.copyOf(values);
    }

    /**
     * Every field describing the provisioned container is frozen once it exists, exactly
     * like {@link DatabaseResource}: the ceilings are the one thing an operator can
     * legitimately get wrong at create and must be able to correct.
     */
    @Override
    public @NonNull List<ResourceFieldBinding> fieldBindings() {
        return List.of(
            ResourceFieldBinding.of(DatabaseEngineModel.STATUS.getName(),
                FieldAccess.alwaysReadonly()),
            frozenAfterCreate(DatabaseEngineModel.NAME),
            frozenAfterCreate(DatabaseEngineModel.ENGINE),
            frozenAfterCreate(DatabaseEngineModel.IMAGE),
            frozenAfterCreate(DatabaseEngineModel.SERVER_ID),
            frozenAfterCreate(DatabaseEngineModel.ROOT_USER),
            frozenAfterCreate(DatabaseEngineModel.ROOT_PASSWORD),
            // Shown ONLY on a record that carries one: the create form and a healthy
            // engine never render an empty failure box.
            ResourceFieldBinding.of(DatabaseEngineModel.FAILURE_REASON.getName(),
                FieldAccess.customRecordAware((ctx, record) ->
                    record instanceof Row row && hasText(row.get(DatabaseEngineModel.FAILURE_REASON))
                        ? FieldAccess.Decision.READONLY : FieldAccess.Decision.HIDDEN)));
    }

    /** Editable on the CREATE form, readonly once the record exists. */
    private static @NonNull ResourceFieldBinding frozenAfterCreate(@NonNull Field<?, ?> field) {
        return ResourceFieldBinding.of(field.getName(),
            FieldAccess.customRecordAware((ctx, record) ->
                record == null ? FieldAccess.Decision.EDITABLE : FieldAccess.Decision.READONLY));
    }

    /**
     * States what the fields cannot: a new ceiling recreates the engine container, and
     * every database on it loses its connections until it is back.
     */
    @Override
    public @Nullable Microcopy formNotice(@NonNull Row record,
                                          @NonNull AccessContext accessContext) {
        return record.get(DatabaseEngineModel.ID) == null
            ? super.formNotice(record, accessContext)
            : Microcopy.of("resize_notice").withFilter("scope", "database_engine");
    }

    /**
     * Persist the engine as {@code provisioning}, reserve its instance row INLINE (so a
     * host without room refuses on this form) and bring the container up after commit.
     *
     * There is deliberately no refusal of a second engine of the same kind on one host:
     * an operator running two major versions side by side is the reason an engine can be
     * created by hand at all. The allocation funnel resolves the FIRST one, and a shared
     * database picks the other explicitly.
     */
    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        String name = trimmed(coerced.get(DatabaseEngineModel.NAME.getName()));
        if (!name.matches("[a-z0-9][a-z0-9-]*")) {
            throw Violations.ofField(DatabaseEngineModel.NAME.getName(), name,
                CmsSupport.violationText("name_format"));
        }
        String engineToken = trimmed(coerced.get(DatabaseEngineModel.ENGINE.getName()))
            .toLowerCase(Locale.ROOT);
        ManagedDatabase.Engine engine = ManagedDatabase.Engine.forToken(engineToken);
        if (engine == null) {
            throw Violations.ofField(DatabaseEngineModel.ENGINE.getName(), engineToken,
                CmsSupport.violationText("unknown_engine").withArg("engine", engineToken));
        }
        if (!engine.supportsLogicalDatabases()) {
            // The same refusal the placement makes, on the same key: an engine that has no
            // per-database namespace can only ever serve one database, which is a
            // DEDICATED record and not this tier.
            throw Violations.ofField(DatabaseEngineModel.ENGINE.getName(), engineToken,
                CmsSupport.violationText("database_placement_unsupported")
                    .withArg("engine", engineToken));
        }
        String rootUser = trimmed(coerced.get(DatabaseEngineModel.ROOT_USER.getName()));
        if (rootUser.isEmpty()) {
            rootUser = "root";
        }
        String rootPassword = trimmed(coerced.get(DatabaseEngineModel.ROOT_PASSWORD.getName()));
        if (rootPassword.isEmpty()) {
            rootPassword = Secrets.generatePassword();
        }
        String image = trimmed(coerced.get(DatabaseEngineModel.IMAGE.getName()));
        ResourceLimits limits = ResourceLimits.of(
            coerced.get(DatabaseEngineModel.MEMORY_LIMIT_MB.getName()) instanceof Integer mb
                ? mb : null,
            coerced.get(DatabaseEngineModel.CPU_LIMIT.getName()) instanceof Double cpus
                ? cpus : null);

        Model model = model();
        Row row = model.createEmptyRow();
        row.set(DatabaseEngineModel.NAME, name);
        row.set(DatabaseEngineModel.ENGINE, engine.token());
        row.set(DatabaseEngineModel.IMAGE, image.isEmpty() ? null : image);
        row.set(DatabaseEngineModel.SERVER_ID,
            coerced.get(DatabaseEngineModel.SERVER_ID.getName()) instanceof Integer serverId
                ? serverId : ServerModel.localServerId());
        row.set(DatabaseEngineModel.ROOT_USER, rootUser);
        row.set(DatabaseEngineModel.ROOT_PASSWORD, rootPassword);
        row.set(DatabaseEngineModel.MEMORY_LIMIT_MB, limits.memoryMb());
        row.set(DatabaseEngineModel.CPU_LIMIT, limits.cpus());
        row.set(DatabaseEngineModel.STATUS, DatabaseModel.STATUS_PROVISIONING);
        model.save(row);

        // Books the engine against the host budget through the instance write hook, and
        // refuses here (never on a pool thread minutes later) when it does not fit.
        DatabaseEngines.reserveRow(row, limits);

        Integer engineId = row.get(DatabaseEngineModel.ID);
        if (engineId != null) {
            model.getResolvedDatasource().afterCommit(
                () -> DatabaseEngines.provisionInBackground(engineId));
        }
        return rowKey(row);
    }

    /**
     * THE resize, and the only update this resource performs: the two ceilings, applied by
     * recreating the container after the reservation was re-booked inline. Same order and
     * same reasons as {@link DatabaseResource#updateRow}.
     */
    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        Integer memoryMb = coerced.get(DatabaseEngineModel.MEMORY_LIMIT_MB.getName())
            instanceof Integer mb ? mb : null;
        Double cpus = coerced.get(DatabaseEngineModel.CPU_LIMIT.getName())
            instanceof Double c ? c : null;
        if (Objects.equals(memoryMb, existing.get(DatabaseEngineModel.MEMORY_LIMIT_MB))
                && Objects.equals(cpus, existing.get(DatabaseEngineModel.CPU_LIMIT))) {
            return;
        }
        Integer engineId = existing.get(DatabaseEngineModel.ID);
        if (engineId == null) {
            throw Violations.ofForm(CmsSupport.violationText("database_resize_failed")
                .withArg("reason", "the record carries no id"));
        }
        ResourceLimits limits = ResourceLimits.of(memoryMb, cpus);
        try {
            DatabaseEngines.reserveRow(existing, limits);
        } catch (Violations refused) {
            throw refused;
        } catch (Exception e) {
            throw Violations.ofForm(CmsSupport.violationText("database_resize_failed")
                .withArg("reason", String.valueOf(e.getMessage())));
        }
        existing.set(DatabaseEngineModel.MEMORY_LIMIT_MB, memoryMb);
        existing.set(DatabaseEngineModel.CPU_LIMIT, cpus);
        existing.set(DatabaseEngineModel.STATUS, DatabaseModel.STATUS_PROVISIONING);
        existing.set(DatabaseEngineModel.FAILURE_REASON, null);
        model().save(existing);
        model().getResolvedDatasource().afterCommit(
            () -> DatabaseEngines.redeployInBackground(engineId));
    }

    /**
     * Verified teardown: the container and its data volume go, and the row with them.
     *
     * @throws Violations {@code database_engine_in_use} while a record still lives on it,
     *         {@code database_engine_destroy_failed} when the teardown is unconfirmed
     */
    @Override
    public void deleteRow(@NonNull Row existing, @NonNull AccessContext accessContext) {
        Integer engineId = existing.get(DatabaseEngineModel.ID);
        if (engineId == null) {
            return;
        }
        try {
            DatabaseEngines.destroy(engineId, true);
        } catch (IOException e) {
            // A NAMED refusal, not a 500: the record is kept (status destroy_failed) and
            // the force-destroy action is the recorded way out.
            throw Violations.ofForm(CmsSupport.violationText("database_engine_destroy_failed")
                .withArg("name", String.valueOf((Object) existing.get(DatabaseEngineModel.NAME)))
                .withArg("reason", String.valueOf(e.getMessage())));
        }
    }

    /**
     * An engine still hosting databases is offered DEAD, naming them -- the row-action
     * doctrine every other in-use refusal here follows. {@link #deleteRow} refuses with
     * the same facts, so the dead button is never the gate.
     */
    @Override
    public @Nullable Microcopy deleteUnavailableReason(@NonNull Row record,
                                                       @NonNull AccessContext accessContext) {
        Integer engineId = record.get(DatabaseEngineModel.ID);
        List<Row> hosted = engineId == null ? List.of() : DatabaseEngines.databasesOn(engineId);
        if (!hosted.isEmpty()) {
            return Microcopy.of("delete_in_use").withFilter("scope", "database_engine")
                .withArg("name", String.valueOf((Object) record.get(DatabaseEngineModel.NAME)))
                .withArg("databases", DatabaseEngines.names(hosted));
        }
        return super.deleteUnavailableReason(record, accessContext);
    }

    /** Deleting an engine destroys its container AND the volume every database sat on. */
    @Override
    public @NonNull ConfirmationSpec deleteConfirmationFor(@NonNull Row record) {
        return deleteConfirmation(Microcopy.of("delete_confirm")
            .withFilter("scope", "database_engine")
            .withArg("name", String.valueOf((Object) record.get(DatabaseEngineModel.NAME))));
    }

    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        List<RowAction<Row>> actions = new ArrayList<>(super.rowActions());
        actions.add(this.forceDeleteAction());
        return actions;
    }

    /**
     * The recorded escape hatch for a genuinely unreachable host: visible ONLY once a
     * normal destroy already failed, typed-confirmed with the engine's own name, and
     * ActivityLog-recorded. The container and volume may survive on the host; the
     * reconciler reports them as orphans.
     */
    private @NonNull RowAction<Row> forceDeleteAction() {
        return RowAction.Invoke.<Row>builder(
                Identifier.of("hohenheim", "force_delete_database_engine"))
            .label(Microcopy.of("force_delete").withFilter("scope", "database_engine"))
            .description(Microcopy.of("force_delete_hint").withFilter("scope", "database_engine"))
            .icon(Icon.of("triangle-exclamation"))
            .style(ActionStyle.DESTRUCTIVE)
            .inlineInRow(false)
            .visibleFor((row, ctx) ->
                DatabaseModel.STATUS_DESTROY_FAILED.equals(row.get(DatabaseEngineModel.STATUS)))
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("force_delete").withFilter("scope", "database_engine"))
                .body(Microcopy.of("force_delete_confirm_generic")
                    .withFilter("scope", "database_engine"))
                .confirmLabel(Microcopy.of("force_delete_ok").withFilter("scope", "database_engine"))
                .style(ActionStyle.DESTRUCTIVE)
                .build())
            .dynamicConfirmation(row -> ConfirmationSpec.builder()
                .title(Microcopy.of("force_delete").withFilter("scope", "database_engine"))
                .body(Microcopy.of("force_delete_confirm").withFilter("scope", "database_engine")
                    .withArg("name", row.get(DatabaseEngineModel.NAME)))
                .confirmLabel(Microcopy.of("force_delete_ok").withFilter("scope", "database_engine"))
                .style(ActionStyle.DESTRUCTIVE)
                .requireTypedConfirmation(row.get(DatabaseEngineModel.NAME))
                .build())
            .handler((row, ctx) -> {
                Integer engineId = row.get(DatabaseEngineModel.ID);
                String name = row.get(DatabaseEngineModel.NAME);
                ActivityLog.withAction(ActivityLog.ACTION_DELETE, "force-destroy", () -> {
                    try {
                        if (engineId != null) {
                            DatabaseEngines.forceDestroy(engineId);
                        }
                    } catch (IOException e) {
                        throw Violations.ofForm(
                            CmsSupport.violationText("database_engine_destroy_failed")
                                .withArg("name", name)
                                .withArg("reason", String.valueOf(e.getMessage())));
                    }
                });
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("force_delete_done").withFilter("scope", "database_engine")
                        .withArg("name", name));
            })
            .build();
    }

    /** The count of managed databases living on an engine, for the virtual column. */
    @Override
    public @Nullable Object cellValue(@NonNull Row row, @NonNull ColumnSpec column) {
        if (DATABASES_COLUMN.equals(column.name())) {
            Integer engineId = row.get(DatabaseEngineModel.ID);
            return engineId == null ? 0 : DatabaseEngines.databasesOn(engineId).size();
        }
        return super.cellValue(row, column);
    }

    private static boolean hasText(@Nullable Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    private static @NonNull String trimmed(@Nullable Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }
}
