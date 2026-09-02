package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.Secrets;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
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
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
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
import be.elevenways.zenit.common.edit.FormSection;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.routing.RouteScope;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Permission;
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
 * Docker-provisioned managed databases. Create provisions the container in the
 * background; every field describing the provisioned engine is frozen afterwards, with
 * backup/restore/destroy as actions -- EXCEPT the resource ceilings, which
 * {@link #updateRow} applies by recreating the container onto the same data volume.
 */
public class DatabaseResource extends RowResource {

    private final DatabaseService databaseService = new DatabaseService();

    private final FormSpec formSpec = FormSpec.builder()
        .add(DatabaseModel.NAME)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(DatabaseModel.ENGINE))
        // Where the record lives, and (for a shared one) which engine. Blank engine means
        // the host's engine of that kind, created on demand.
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(DatabaseModel.PLACEMENT))
        .add(RelationPick.of(DatabaseModel.ENGINE_ID, DatabaseEngineModel.MODEL_ID)
            .creatable(false).build())
        .add(DatabaseModel.DB_NAME)
        .add(DatabaseModel.DB_USER)
        .add(DatabaseModel.DB_PASSWORD)
        .add(DatabaseModel.IMAGE)
        .add(DatabaseModel.EPHEMERAL)
        .add(DatabaseModel.MEMORY_LIMIT_MB)
        .add(DatabaseModel.CPU_LIMIT)
        // See InstanceResource: a host is enrolled deliberately, never inline.
        .add(RelationPick.of(DatabaseModel.SERVER_ID, ServerModel.MODEL_ID)
            .creatable(false).build())
        .add(DatabaseModel.STATUS)
        .add(DatabaseModel.FAILURE_REASON)
        // A managed database is an engine, a database name and its credentials. The image
        // override, the ephemeral flag and the resource ceilings all have defaults.
        .section(FormSection.advanced(
            DatabaseModel.IMAGE.getName(),
            DatabaseModel.EPHEMERAL.getName(),
            DatabaseModel.MEMORY_LIMIT_MB.getName(),
            DatabaseModel.CPU_LIMIT.getName()))
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        // The engine qualifies the managed name; the name INSIDE the engine is what goes
        // into a connection string, so it keeps a column of its own plus the copy chip.
        .column(ColumnSpec.fromField(DatabaseModel.NAME).filterable().subtext("engine").build())
        .column(ColumnSpec.fromField(DatabaseModel.ENGINE).filterable().hidden().build())
        .column(ColumnSpec.fromField(DatabaseModel.DB_NAME).filterable().copyable().build())
        .column(ColumnSpec.fromField(DatabaseModel.SERVER_ID)
            .relation(RelationPick.of(DatabaseModel.SERVER_ID, ServerModel.MODEL_ID).build()).build())
        // Placement and its engine are what an operator scans this list for since the
        // shared tier exists; the tmpfs flag is a rarity and moves behind the picker so
        // the row still fits a laptop screen.
        .column(ColumnSpec.fromField(DatabaseModel.PLACEMENT).filterable().build())
        .column(ColumnSpec.fromField(DatabaseModel.ENGINE_ID)
            .relation(RelationPick.of(DatabaseModel.ENGINE_ID, DatabaseEngineModel.MODEL_ID)
                .build()).build())
        .column(ColumnSpec.fromField(DatabaseModel.EPHEMERAL).filterable().hidden().build())
        .column(ColumnSpec.fromField(DatabaseModel.STATUS).filterable().build())
        .filter(FilterSpec.forField(DatabaseModel.NAME, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(DatabaseModel.NAME)).build())
        .filter(FilterSpec.forField(DatabaseModel.ENGINE, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(DatabaseModel.ENGINE)).build())
        .filter(FilterSpec.forField(DatabaseModel.DB_NAME, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(DatabaseModel.DB_NAME)).build())
        .filter(FilterSpec.forField(DatabaseModel.PLACEMENT, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(DatabaseModel.PLACEMENT)).build())
        .filter(FilterSpec.forField(DatabaseModel.EPHEMERAL, FilterSpec.Kind.BOOLEAN)
            .label(FieldLabels.labelFor(DatabaseModel.EPHEMERAL)).build())
        .filter(FilterSpec.forField(DatabaseModel.STATUS, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(DatabaseModel.STATUS)).build())
        .build();

    /**
     * The server pick defaults to the local daemon (ensuring its row exists for the
     * picker) and the placement to the shared tier.
     *
     * AIDEV-NOTE: shared is a DEFAULT, not a resolution. An engine that cannot host
     * logical databases (Redis) and a tmpfs database are dedicated by definition, and
     * leaving the select on shared for one of those is refused BY NAME
     * ({@code database_placement_unsupported} / {@code database_ephemeral_shared}) rather
     * than silently rewritten -- a form that quietly changes the answer an operator gave
     * is worse than one that says why it cannot take it.
     */
    @Override
    public @NonNull Map<String, Object> createValues(@NonNull Conduit conduit) {
        Map<String, Object> values = CmsSupport.mutable(formSpec().defaultValues());
        values.put("server_id", ServerModel.localServerId());
        values.put(DatabaseModel.PLACEMENT.getName(), DatabaseModel.PLACEMENT_SHARED);
        return Map.copyOf(values);
    }

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "database"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "database"); }
    @Override public @Nullable Microcopy recordLabel() { return Microcopy.of("singular").withFilter("scope", "database"); }
    @Override public @NonNull String slug() { return "databases"; }
    @Override public @NonNull Model model() { return Models.get(DatabaseModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull ListChrome listChrome() { return CmsSupport.WIDE_LIST; }

    /** The managed name and the name inside the engine are different strings; a connection string only ever carries the second. */
    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(DatabaseModel.NAME, DatabaseModel.DB_NAME);
    }

    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.DEPLOY_GROUP; }
    @Override public int navOrder() { return 50; }

    @Override
    public @Nullable Microcopy description() {
        return Microcopy.of("nav_hint").withFilter("scope", "database");
    }
    @Override public @NonNull Icon icon() { return Icon.of("database"); }

    /**
     * Editable, but ONLY for the resource ceilings: {@link #fieldBindings} freezes every
     * other entry once the record exists and {@link #updateRow} applies nothing else.
     *
     * AIDEV-NOTE: this was false until 2026-08-30, and the gap it left was not cosmetic.
     * The caps are booked against the host's memory budget at CREATE and nothing could
     * change them afterwards, so an operator whose database was sized wrong had exactly
     * two options: live with it, or DELETE the record -- which for a database that has
     * data in it is not an option at all. The engine instance is no help either: it is a
     * {@code generatedOnly()} row that {@code InstanceResource.updatableBy} refuses to
     * edit, pointing at "the owning record's own surface", which is this one.
     *
     * The database capability vocabulary deliberately still declares no {@code config}
     * verb (see {@code HohenheimAccess.declareGrantableModels}, which names this
     * resource's immutability as the reason). That stays correct: the tenant funnel's
     * {@code DATABASE_TENANT_WRITABLE} is empty, so no tenant-originated write reaches a
     * stored database row whatever a grant said, and {@link ManageDatabaseResource} keeps
     * this surface closed. Declaring the verb before the funnel admits these two columns
     * would ship a delegation matrix over something nothing enforces -- the very thing
     * that comment forbids. This is an OPERATOR resize.
     */
    @Override public boolean updatable() { return true; }


    /**
     * A record describes a provisioned container, so its every field is frozen once that
     * container exists -- except the resource ceilings, which are the one thing an
     * operator can legitimately get wrong at create and must be able to correct.
     */
    @Override
    public @NonNull List<ResourceFieldBinding> fieldBindings() {
        // STATUS is service-owned even on create.
        return List.of(
            ResourceFieldBinding.of(DatabaseModel.STATUS.getName(), FieldAccess.alwaysReadonly()),
            frozenAfterCreate(DatabaseModel.NAME),
            frozenAfterCreate(DatabaseModel.ENGINE),
            frozenAfterCreate(DatabaseModel.DB_NAME),
            frozenAfterCreate(DatabaseModel.DB_USER),
            frozenAfterCreate(DatabaseModel.DB_PASSWORD),
            frozenAfterCreate(DatabaseModel.IMAGE),
            frozenAfterCreate(DatabaseModel.EPHEMERAL),
            frozenAfterCreate(DatabaseModel.SERVER_ID),
            frozenAfterCreate(DatabaseModel.PLACEMENT),
            frozenAfterCreate(DatabaseModel.ENGINE_ID),
            // A shared record has no ceilings of its own: the container is the ENGINE's
            // and is booked once, at the engine's cap. Hiding them is the honest shape --
            // an editable pair that {@link #updateRow} could only refuse is a control
            // that lies about what it does, and the form notice names where they live.
            hiddenWhenShared(DatabaseModel.MEMORY_LIMIT_MB),
            hiddenWhenShared(DatabaseModel.CPU_LIMIT),
            // The reason is shown ONLY on a record that carries one: the create form
            // (null record) and a healthy record never render an empty failure box.
            ResourceFieldBinding.of(DatabaseModel.FAILURE_REASON.getName(),
                FieldAccess.customRecordAware((ctx, record) ->
                    record instanceof Row row && hasText(row.get(DatabaseModel.FAILURE_REASON))
                        ? FieldAccess.Decision.READONLY : FieldAccess.Decision.HIDDEN)));
    }

    /**
     * Editable on the CREATE form, readonly once the record exists.
     *
     * AIDEV-NOTE: record-AWARE rather than {@code alwaysReadonly()}, and the difference is
     * the whole create form: a readonly binding applies to both views, so freezing these
     * with it would leave an operator unable to type a database name.
     */
    private static @NonNull ResourceFieldBinding frozenAfterCreate(@NonNull Field<?, ?> field) {
        return ResourceFieldBinding.of(field.getName(),
            FieldAccess.customRecordAware((ctx, record) ->
                record == null ? FieldAccess.Decision.EDITABLE : FieldAccess.Decision.READONLY));
    }

    /** Editable on the CREATE form, hidden once the record turns out to be shared. */
    private static @NonNull ResourceFieldBinding hiddenWhenShared(@NonNull Field<?, ?> field) {
        return ResourceFieldBinding.of(field.getName(),
            FieldAccess.customRecordAware((ctx, record) ->
                record instanceof Row row && DatabaseModel.isShared(row)
                    ? FieldAccess.Decision.HIDDEN : FieldAccess.Decision.EDITABLE));
    }

    /**
     * States the consequence the fields cannot: saving a new ceiling RECREATES the engine
     * container, so open connections drop for as long as the engine takes to come back --
     * or, for a shared record, that its ceilings are the engine's and are resized there.
     * Rendered only on a stored record -- on the create form there is nothing to recreate.
     */
    @Override
    public @Nullable Microcopy formNotice(@NonNull Row record,
                                          @NonNull AccessContext accessContext) {
        if (record.get(DatabaseModel.ID) == null) {
            return super.formNotice(record, accessContext);
        }
        return DatabaseModel.isShared(record)
            ? Microcopy.of("shared_notice").withFilter("scope", "database")
            : Microcopy.of("resize_notice").withFilter("scope", "database");
    }

    private static boolean hasText(@Nullable Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        String name = trimmed(coerced.get("name"));
        if (!name.matches("[a-z0-9][a-z0-9-]*")) {
            throw Violations.ofField("name", name, CmsSupport.violationText("name_format"));
        }
        String engineToken = trimmed(coerced.get("engine")).toLowerCase(Locale.ROOT);
        ManagedDatabase.Engine engine = ManagedDatabase.Engine.forToken(engineToken);
        if (engine == null) {
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
        // A blank placement is the service's own default (shared where the engine can host
        // logical databases and the data is persistent), never a third placement here.
        String placement = trimmed(coerced.get(DatabaseModel.PLACEMENT.getName()));
        Integer engineId = coerced.get(DatabaseModel.ENGINE_ID.getName()) instanceof Integer id
            ? id : null;

        return rowKey(this.databaseService.createAsync(name, engine,
            image.isEmpty() ? null : image, user, password, database, ephemeral, server, limits,
            placement.isEmpty() ? null : placement, engineId));
    }

    /**
     * THE resize: the only update this resource performs, and it applies the two resource
     * ceilings and nothing else.
     *
     * The order is the one {@code DatabaseInstances} was split for. The engine row's
     * reservation runs INLINE, so a host without room refuses on the form the operator is
     * looking at ({@code host_capacity_reached}, naming the host, what was asked and what
     * is free) instead of flipping the record to failed on a pool thread minutes later.
     * The container work then rides {@code afterCommit}: a deploy scheduled from inside
     * the CMS mutation transaction would read the row on its own connection before this
     * one commits and apply the OLD ceiling.
     *
     * An unchanged form is a no-op on purpose. A deploy is a RECREATE (there is no Docker
     * update path here), so treating "operator pressed Save" as "recreate the engine"
     * would drop every live connection for nothing.
     */
    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        Integer memoryMb = coerced.get("memory_limit_mb") instanceof Integer mb ? mb : null;
        Double cpus = coerced.get("cpu_limit") instanceof Double c ? c : null;
        if (DatabaseModel.isShared(existing)) {
            // The fields are HIDDEN on a shared record, so a submitted value did not come
            // from the form this resource rendered; refuse it by name instead of booking a
            // ceiling against a container this record does not own.
            if (memoryMb != null || cpus != null) {
                throw Violations.ofForm(CmsSupport.violationText("database_shared_limits"));
            }
            return;
        }
        if (Objects.equals(memoryMb, existing.get(DatabaseModel.MEMORY_LIMIT_MB))
                && Objects.equals(cpus, existing.get(DatabaseModel.CPU_LIMIT))) {
            return;
        }
        Integer recordId = existing.get(DatabaseModel.ID);
        if (recordId == null) {
            throw Violations.ofForm(CmsSupport.violationText("database_resize_failed")
                .withArg("reason", "the record carries no id"));
        }
        ResourceLimits limits = ResourceLimits.of(memoryMb, cpus);
        try {
            // Books the new ceiling against the host budget through the instance write
            // hook, and refuses here when it does not fit.
            DatabaseInstances.reserveEngineRow(existing, limits);
        } catch (Violations refused) {
            throw refused;
        } catch (Exception e) {
            throw Violations.ofForm(CmsSupport.violationText("database_resize_failed")
                .withArg("reason", String.valueOf(e.getMessage())));
        }
        existing.set(DatabaseModel.MEMORY_LIMIT_MB, memoryMb);
        existing.set(DatabaseModel.CPU_LIMIT, cpus);
        // Provisioning again is the honest status: the engine is being recreated, and it
        // is what the list badge, the detail page and AttentionCollector already read.
        existing.set(DatabaseModel.STATUS, DatabaseModel.STATUS_PROVISIONING);
        existing.set(DatabaseModel.FAILURE_REASON, null);
        model().save(existing);
        model().getResolvedDatasource().afterCommit(
            () -> this.databaseService.provisionInBackground(recordId));
    }

    /**
     * A database DELETE demands {@code destroy} on the record (the model's
     * before-remove hook in {@code TenantWrites}), so the synthesized Delete affordance
     * is offered on exactly that answer -- the {@link InstanceDeviceResource} shape:
     * {@link ManageDatabaseResource} reads by the wider {@code view}, and without this
     * a view-only delegate was shown a destroy button the pipeline could only refuse.
     * No updatableBy twin: {@link #updatable()} is true only for the operator resize, which
     * no tenant-originated write can reach ({@code DATABASE_TENANT_WRITABLE} is empty).
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
        refuseWhileAttached(name, id);
        try {
            this.databaseService.destroy(name, true);
        } catch (IOException e) {
            // A NAMED refusal, not a 500: the record is kept (status destroy_failed), the
            // port claim is parked, and the force-destroy action is the recorded way out.
            throw Violations.ofForm(CmsSupport.violationText("database_destroy_failed")
                .withArg("name", name)
                .withArg("reason", e.getMessage()));
        }
        // Links to soft-deleted owners are debris once the database is gone: the row delete
        // inside destroy takes them along through the model funnel (InstanceDatabaseLinks).
    }

    /**
     * A database a live workload still holds is offered DEAD, naming the workloads and
     * the page each is detached on -- the row-action doctrine every other in-use refusal
     * here follows (host, template, runtime image). {@link #deleteRow} refuses with the
     * same facts, so the dead button is never the gate.
     */
    @Override
    public @Nullable Microcopy deleteUnavailableReason(@NonNull Row record,
                                                       @NonNull AccessContext accessContext) {
        String workloads = attachedWorkloads(record.get(DatabaseModel.ID));
        if (!workloads.isEmpty()) {
            return Microcopy.of("delete_in_use").withFilter("scope", "database")
                .withArg("name", String.valueOf((Object) record.get(DatabaseModel.NAME)))
                .withArg("workloads", workloads);
        }
        return super.deleteUnavailableReason(record, accessContext);
    }

    /**
     * What deleting THIS record actually takes with it, which the two placements do not
     * share: a dedicated record's container and data volume go, a shared record's logical
     * database and its user are dropped inside an engine that stays up serving everybody
     * else. One sentence per shape, never one that hedges over both.
     */
    @Override
    public @NonNull ConfirmationSpec deleteConfirmationFor(@NonNull Row record) {
        return deleteConfirmation(Microcopy.of(
                DatabaseModel.isShared(record) ? "delete_confirm_shared" : "delete_confirm")
            .withFilter("scope", "database")
            .withArg("name", String.valueOf((Object) record.get(DatabaseModel.NAME))));
    }

    /** @throws Violations {@code database_in_use} naming the workloads and their detach page */
    private static void refuseWhileAttached(@Nullable String name, @Nullable Integer id) {
        String workloads = attachedWorkloads(id);
        if (!workloads.isEmpty()) {
            throw Violations.ofForm(CmsSupport.violationText("database_in_use")
                .withArg("name", name)
                .withArg("workloads", workloads));
        }
    }

    /**
     * The live workloads attached to a database, each with the URL of the instance's
     * Databases tab (the page a detach happens on), joined for a sentence; empty when
     * nothing holds it.
     */
    private static @NonNull String attachedWorkloads(@Nullable Integer databaseId) {
        if (databaseId == null) {
            return "";
        }
        Conduit conduit = RouteScope.currentConduit();
        String panel = conduit != null ? CmsSupport.panelSlug(conduit) : "admin";
        List<String> workloads = new ArrayList<>();
        for (Row instance : InstanceDatabaseLinks.liveInstances(databaseId)) {
            workloads.add(instance.get(InstanceModel.NAME) + " ("
                + CmsRoutes.subpage(panel, "instances", instance.get(InstanceModel.ID),
                    InstanceDatabasesPage.SLUG).toUrl() + ")");
        }
        return DeleteImpact.join(workloads);
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
        actions.add(this.moveToSharedAction());
        actions.add(this.forceDeleteAction());
        return actions;
    }

    /**
     * Move a dedicated database onto its host's shared engine, in the background, with the
     * record reading Provisioning while it works.
     *
     * Offered only where the move can succeed: a dedicated, active record whose engine has
     * logical databases at all. A tmpfs database is its own container by definition, so it
     * is never offered either.
     */
    private @NonNull RowAction<Row> moveToSharedAction() {
        RowAction.Invoke.Builder<Row> action =
            RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "move_database_shared"))
                .label(Microcopy.of("move_shared").withFilter("scope", "database"))
                .description(Microcopy.of("move_shared_hint").withFilter("scope", "database"))
                .icon(Icon.of("layer-group"))
                // Not DESTRUCTIVE: the move keeps the dump AND the old data volume as two
                // rollbacks, and painting it red beside a real delete devalues the red.
                .style(ActionStyle.PRIMARY)
                .inlineInRow(false)
                .visibleFor((row, ctx) -> movable(row))
                // The record-less fallback the framework requires beside a dynamic one.
                .confirmation(ConfirmationSpec.builder()
                    .title(Microcopy.of("move_shared").withFilter("scope", "database"))
                    .body(Microcopy.of("move_shared_confirm_generic")
                        .withFilter("scope", "database"))
                    .confirmLabel(Microcopy.of("move_shared_ok").withFilter("scope", "database"))
                    .style(ActionStyle.PRIMARY)
                    .build())
                .dynamicConfirmation(row -> ConfirmationSpec.builder()
                    .title(Microcopy.of("move_shared").withFilter("scope", "database"))
                    .body(Microcopy.of("move_shared_confirm").withFilter("scope", "database")
                        .withArg("name", row.get(DatabaseModel.NAME)))
                    .confirmLabel(Microcopy.of("move_shared_ok").withFilter("scope", "database"))
                    .style(ActionStyle.PRIMARY)
                    .build())
                .handler((row, ctx) -> {
                    String name = row.get(DatabaseModel.NAME);
                    this.databaseService.moveToSharedEngineInBackground(name);
                    return CmsActionResult.refreshWithToast(
                        Microcopy.of("move_started").withFilter("scope", "database")
                            .withArg("name", name));
                });
        Permission write = writePermission();
        if (write != null) {
            action = action.requirePermission(write);
        }
        return action.build();
    }

    /** Whether the move lane would accept this record, asked of the lane's own declaration. */
    private static boolean movable(@NonNull Row row) {
        return DatabaseService.moveRefusal(row) == null;
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
                // The same in-use refusal deleteRow makes, asked BEFORE the engine instance
                // is abandoned: the funnel would refuse the row delete anyway, but by then
                // the abandon has already run.
                refuseWhileAttached(name, id);
                ActivityLog.withAction(ActivityLog.ACTION_DELETE, "force-destroy",
                    () -> this.databaseService.forceDestroyRecord(name));
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
