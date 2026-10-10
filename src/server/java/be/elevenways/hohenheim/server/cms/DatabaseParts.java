package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.StateBadge;
import be.elevenways.hohenheim.StateLineCell;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.HohenheimCounts;
import be.elevenways.hohenheim.model.GroupedCounts;
import be.elevenways.hohenheim.server.database.DatabaseBackups;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.common.time.RelativeTime;
import be.elevenways.zenit.cms.common.CmsMicrocopy;
import be.elevenways.zenit.cms.common.resource.RecordOverview;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.text.ByteText;
import be.elevenways.zenit.common.ui.BadgeVariant;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.DatabaseWrites;
import be.elevenways.hohenheim.server.database.TenantDatabases;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.key.IdentifierKey;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.typed.CoreTypes;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.Labels;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.render.CmsTemplateIds;
import be.elevenways.zenit.cms.common.render.table.RecordLink;
import be.elevenways.zenit.cms.common.render.table.RecordLinksCell;
import be.elevenways.zenit.cms.common.resource.ListScope;
import be.elevenways.zenit.cms.common.resource.DeleteConfirmation;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceAuthority;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
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
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.widget.common.WidgetInstance;
import be.elevenways.zenit.widget.common.WidgetTree;
import be.elevenways.zenit.widget.common.builtin.CardWidget;
import be.elevenways.zenit.widget.common.builtin.FactListWidget;
import be.elevenways.zenit.widget.common.data.WidgetFact;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static be.elevenways.hohenheim.RawValues.trimmed;

/**
 * The managed-database tier's parts: the operator's databases, their /manage twin and the shared engines (stage 4
 * contract 10). Create provisions in the background, the resource ceilings are the only columns an update writes,
 * and every delete is a domain operation whose "still in use" refusal is its availability. The writes themselves, and
 * the name rule they check, are {@link DatabaseWrites}'; this class only declares the panel.
 *
 * AIDEV-NOTE: the /manage twin is a NARROWING, never a gate of its own: its rows are the databases the caller may view
 * ({@link TenantScopes#DATABASES}), it allocates through the tenant funnel ({@link TenantDatabases#allocate}) and
 * edits nothing; {@code TenantWrites.DATABASE_TENANT_WRITABLE} stays empty, so no tenant-originated write reaches a
 * stored database row whatever a grant says. The delete is the same operation on both twins: it demands
 * {@code destroy} on the record, which an operator holds on every one.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class DatabaseParts {

    private static final SubjectType<Row> DATABASE = SubjectType.record(DatabaseModel.MODEL_ID);
    private static final SubjectType<Row> ENGINE = SubjectType.record(DatabaseEngineModel.MODEL_ID);

    /** The list's virtual columns: where a database runs, what uses it, and its newest stored dump. */
    private static final String RUNS_ON_COLUMN = "runs_on";
    private static final String USED_BY_COLUMN = "used_by";
    private static final String LAST_BACKUP_COLUMN = "last_backup";
    /**
     * What a database or engine is doing ({@link DatabaseVerdict}), where the stored lifecycle status used to read:
     * "Active" only says provisioning finished. The stored status stays a hidden, filterable column.
     */
    private static final String STATE_COLUMN = "state";
    /** A newest dump older than the nightly 03:00 run plus half a day of slack means the backups stopped. */
    static final Duration BACKUP_OVERDUE_AFTER = Duration.ofHours(36);

    /** The virtual column counting the managed databases living on an engine. */
    private static final String DATABASES_COLUMN = "databases";

    /** Request memo of every engine's database count: one grouped aggregate per rendered list. */
    private static final IdentifierKey<Map<Integer, Long>> DATABASE_COUNTS =
        IdentifierKey.of("hohenheim", "database_engine_database_counts");

    /** The create verb in the Databases board's words: the list's button and the form's heading. */
    private static final Microcopy CREATE_TITLE = HohenheimMicrocopy.DATABASE.of("create_title");

    /** The operator's create and resize form. */
    private static final FormSpec ADMIN_FORM = FormSpec.builder()
        .createTitle(CREATE_TITLE)
        .add(DatabaseModel.NAME)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(DatabaseModel.ENGINE))
        // Where the record lives, and (for a shared one) which engine. Blank engine means the host's engine of
        // that kind, created on demand.
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(DatabaseModel.PLACEMENT))
        .add(RelationPick.of(DatabaseModel.ENGINE_ID, DatabaseEngineModel.MODEL_ID).creatable(false).build())
        .add(DatabaseModel.DB_NAME)
        .add(DatabaseModel.DB_USER)
        .add(DatabaseModel.DB_PASSWORD)
        .add(DatabaseModel.IMAGE)
        .add(DatabaseModel.EPHEMERAL)
        .add(DatabaseModel.MEMORY_LIMIT_MB)
        .add(DatabaseModel.CPU_LIMIT)
        // See InstanceParts: a host is enrolled deliberately, never inline.
        .add(RelationPick.of(DatabaseModel.SERVER_ID, ServerModel.MODEL_ID).creatable(false).build())
        .add(DatabaseModel.STATUS)
        .add(DatabaseModel.FAILURE_REASON)
        // A managed database is an engine, a database name and its credentials. The image override, the
        // ephemeral flag and the resource ceilings all have defaults.
        .section(FormSection.advanced(
            DatabaseModel.IMAGE.getName(),
            DatabaseModel.EPHEMERAL.getName(),
            DatabaseModel.MEMORY_LIMIT_MB.getName(),
            DatabaseModel.CPU_LIMIT.getName()))
        .build();

    /** The engine create and resize form. */
    private static final FormSpec ENGINE_FORM = FormSpec.builder()
        .add(DatabaseEngineModel.NAME)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(DatabaseEngineModel.ENGINE))
        .add(DatabaseEngineModel.ROOT_USER)
        .add(DatabaseEngineModel.ROOT_PASSWORD)
        .add(DatabaseEngineModel.IMAGE)
        .add(DatabaseEngineModel.MEMORY_LIMIT_MB)
        .add(DatabaseEngineModel.CPU_LIMIT)
        // See InstanceParts: a host is enrolled deliberately, never inline.
        .add(RelationPick.of(DatabaseEngineModel.SERVER_ID, ServerModel.MODEL_ID).creatable(false).build())
        .add(DatabaseEngineModel.STATUS)
        .add(DatabaseEngineModel.FAILURE_REASON)
        // An engine is a kind, a host and its superuser. The image override and the ceilings have defaults.
        .section(FormSection.advanced(
            DatabaseEngineModel.IMAGE.getName(),
            DatabaseEngineModel.MEMORY_LIMIT_MB.getName(),
            DatabaseEngineModel.CPU_LIMIT.getName()))
        .build();

    /**
     * Destroys a managed database: its container and volume (dedicated) or its logical database and user (shared).
     * Demands {@code destroy} on the record; offered dead while a live workload still holds its credentials.
     */
    public static final Operation<Row, Void, Integer> DELETE = Operation.declare(HohenheimIds.id("delete_database"))
        .happened(OperationSentences.of("delete_database"))
        .label(CmsMicrocopy.of("delete"))
        .icon(Icon.TRASH)
        .one(DATABASE)
        .gate(OperationGate.open())
        .facts(OperationFact.DESTRUCTIVE)
        .result(Integer.class)
        .command(CmsCommands.EXTERNAL)
        .register();

    /** Moves a dedicated database onto its host's shared engine, in the background. */
    public static final Operation<Row, Void, Void> MOVE_TO_SHARED =
        Operation.declare(HohenheimIds.id("move_database_shared"))
            .happened(OperationSentences.of("move_database_shared"))
            .label(HohenheimMicrocopy.DATABASE.of("move_shared"))
            .description(HohenheimMicrocopy.DATABASE.of("move_shared_hint"))
            .icon(Icon.of("layer-group"))
            .one(DATABASE)
            .gate(OperationGate.permission(HohenheimSources.ADMIN_ACCESS))
            .command(CmsCommands.EXTERNAL)
            .register();

    /**
     * Dumps the database next to its nightly dumps, now, in the background; offered dead on a temporary database and
     * on one that is not running.
     */
    public static final Operation<Row, Void, Void> BACK_UP_NOW =
        Operation.declare(HohenheimIds.id("back_up_database"))
            .happened(OperationSentences.of("back_up_database"))
            .label(HohenheimMicrocopy.DATABASE.of("back_up_now"))
            .description(HohenheimMicrocopy.DATABASE.of("back_up_now_hint"))
            .icon(Icon.of("box-archive"))
            .one(DATABASE)
            .gate(OperationGate.permission(HohenheimSources.ADMIN_ACCESS))
            .command(CmsCommands.EXTERNAL)
            .register();

    /** The recorded escape hatch once a normal destroy failed: the record goes, the host may keep orphans. */
    public static final Operation<Row, Void, Void> FORCE_DELETE =
        Operation.declare(HohenheimIds.id("force_delete_database"))
            .happened(OperationSentences.of("force_delete_database"))
            .label(HohenheimMicrocopy.DATABASE.of("force_delete"))
            .description(HohenheimMicrocopy.DATABASE.of("force_delete_hint"))
            .icon(Icon.of("triangle-exclamation"))
            .one(DATABASE)
            .gate(OperationGate.permission(HohenheimSources.ADMIN_ACCESS))
            .facts(OperationFact.DESTRUCTIVE)
            .command(CmsCommands.EXTERNAL)
            .register();

    /** Destroys an engine: its container and the volume every database sat on; offered dead while one still does. */
    public static final Operation<Row, Void, Integer> DELETE_ENGINE =
        Operation.declare(HohenheimIds.id("delete_database_engine"))
            .happened(OperationSentences.of("delete_database_engine"))
            .label(CmsMicrocopy.of("delete"))
            .icon(Icon.TRASH)
            .one(ENGINE)
            .gate(OperationGate.permission(HohenheimSources.ADMIN_ACCESS))
            .facts(OperationFact.DESTRUCTIVE)
            .result(Integer.class)
            .command(CmsCommands.EXTERNAL)
            .register();

    /** The engine's recorded escape hatch once a normal destroy failed. */
    public static final Operation<Row, Void, Void> FORCE_DELETE_ENGINE =
        Operation.declare(HohenheimIds.id("force_delete_database_engine"))
            .happened(OperationSentences.of("force_delete_database_engine"))
            .label(HohenheimMicrocopy.DATABASE_ENGINE.of("force_delete"))
            .description(HohenheimMicrocopy.DATABASE_ENGINE.of("force_delete_hint"))
            .icon(Icon.of("triangle-exclamation"))
            .one(ENGINE)
            .gate(OperationGate.permission(HohenheimSources.ADMIN_ACCESS))
            .facts(OperationFact.DESTRUCTIVE)
            .command(CmsCommands.EXTERNAL)
            .register();

    static {
        OperationHandlers.attach(DELETE)
            .authorize((database, input, access) -> mayDestroy(database, access) ? null
                : new DomainRefusal(ZenitRefusalReason.FORBIDDEN, "destroying a database demands destroy on it"))
            .availability((database, access) -> DeleteImpact.databaseInUse(database))
            .handle(call -> {
                DatabaseWrites.destroy(call.subject());
                return 1;
            });
        OperationHandlers.attach(BACK_UP_NOW)
            .availability((database, access) -> backUpUnavailable(database))
            .handle(call -> {
                DatabaseBackups.backUpInBackground(call.subject().get(DatabaseModel.NAME));
                return null;
            });
        OperationHandlers.attach(MOVE_TO_SHARED)
            .applies(database -> DatabaseService.moveRefusal(database) == null)
            .availability((database, access) -> moveUnavailable(database))
            .handle(call -> {
                new DatabaseService().moveToSharedEngineInBackground(call.subject().get(DatabaseModel.NAME));
                return null;
            });
        OperationHandlers.attach(FORCE_DELETE)
            .applies(database -> DatabaseModel.STATUS_DESTROY_FAILED.equals(database.get(DatabaseModel.STATUS)))
            .handle(call -> {
                DatabaseWrites.forceDestroy(call.subject());
                return null;
            });
        OperationHandlers.attach(DELETE_ENGINE)
            .availability((engine, access) -> DeleteImpact.engineInUse(engine))
            .handle(call -> {
                DatabaseWrites.destroyEngine(call.subject());
                return 1;
            });
        OperationHandlers.attach(FORCE_DELETE_ENGINE)
            .applies(engine -> DatabaseModel.STATUS_DESTROY_FAILED.equals(engine.get(DatabaseEngineModel.STATUS)))
            .handle(call -> {
                DatabaseWrites.forceDestroyEngine(call.subject());
                return null;
            });
    }

    private DatabaseParts() {
    }

    // -- the operator's databases ---------------------------------------------------------------------------------

    /**
     * Docker-provisioned managed databases. Every field describing the provisioned engine is frozen once it exists,
     * except the resource ceilings, which the update applies by recreating the container onto the same data volume.
     *
     * AIDEV-NOTE: the update was absent until 2026-08-30, and the gap was not cosmetic: the caps are booked against
     * the host's memory budget at CREATE, so a database sized wrong could only be lived with or DELETED. The database
     * capability vocabulary still declares no {@code config} verb (see
     * {@code HohenheimAccess.declareGrantableModels}): the tenant funnel admits no write to a stored database row,
     * so this is an OPERATOR resize, which the /manage twin does not offer.
     */
    public static @NonNull PanelResource<Row> admin() {
        TableSpec<Row> table = TableSpec.<Row>builder()
            // Board Databases: the name with where it runs under it, the engine, what uses it and its last backup.
            // The name inside the engine, the host, the placement and its engine move behind the picker and the
            // filters; the overview and the Restore tab carry them.
            .column(ColumnSpec.fromField(DatabaseModel.NAME).filterable().subtext(RUNS_ON_COLUMN).build())
            .column(ColumnSpec.virtual(RUNS_ON_COLUMN, HohenheimMicrocopy.DATABASE_LIST.of("runs_on_column")).hidden()
                .build())
            .column(ColumnSpec.fromField(DatabaseModel.ENGINE).filterable().build())
            .column(ColumnSpec.virtual(USED_BY_COLUMN, HohenheimMicrocopy.DATABASE_LIST.of("used_by_column"))
                .renderer(CmsTemplateIds.CELL_RECORD_LINKS).build())
            .column(ColumnSpec.virtual(LAST_BACKUP_COLUMN, HohenheimMicrocopy.DATABASE_LIST.of("last_backup_column"))
                .renderer(HohenheimTemplateIds.CELL_STATE_LINE).build())
            .column(stateColumn())
            .column(ColumnSpec.fromField(DatabaseModel.DB_NAME).filterable().copyable().hidden().build())
            .column(ColumnSpec.fromField(DatabaseModel.SERVER_ID)
                .relation(RelationPick.of(DatabaseModel.SERVER_ID, ServerModel.MODEL_ID).build()).hidden().build())
            .column(ColumnSpec.fromField(DatabaseModel.PLACEMENT).filterable().hidden().build())
            .column(ColumnSpec.fromField(DatabaseModel.ENGINE_ID)
                .relation(RelationPick.of(DatabaseModel.ENGINE_ID, DatabaseEngineModel.MODEL_ID).build())
                .hidden().build())
            .column(ColumnSpec.fromField(DatabaseModel.EPHEMERAL).filterable().hidden().build())
            .column(ColumnSpec.fromField(DatabaseModel.STATUS).filterable().hidden().build())
            .filter(FilterSpec.leaf(DatabaseModel.NAME, CoreTypes.CONTAINS)
                .label(FieldLabels.labelFor(DatabaseModel.NAME)).build())
            .filter(FilterSpec.leaf(DatabaseModel.ENGINE, CoreTypes.EQUALS)
                .label(FieldLabels.labelFor(DatabaseModel.ENGINE)).build())
            .filter(FilterSpec.leaf(DatabaseModel.DB_NAME, CoreTypes.CONTAINS)
                .label(FieldLabels.labelFor(DatabaseModel.DB_NAME)).build())
            .filter(FilterSpec.leaf(DatabaseModel.PLACEMENT, CoreTypes.EQUALS)
                .label(FieldLabels.labelFor(DatabaseModel.PLACEMENT)).build())
            .filter(FilterSpec.leaf(DatabaseModel.EPHEMERAL, CoreTypes.IS_TRUE, CoreTypes.IS_FALSE)
                .label(FieldLabels.labelFor(DatabaseModel.EPHEMERAL)).build())
            .filter(FilterSpec.leaf(DatabaseModel.STATUS, CoreTypes.EQUALS)
                .label(FieldLabels.labelFor(DatabaseModel.STATUS)).build())
            .build();
        return entry(HohenheimIds.id("database"))
            // One of the admin sidebar's eight rows; the /manage twin keeps its group.
            .navGroup(NavGroup.DEFAULT)
            .navOrder(30)
            .list(ResourceList.rows(table).chrome(CmsSupport.WIDE_LIST).facets().ruleFilters()
                // The managed name and the name inside the engine are different strings; a connection string only
                // ever carries the second.
                .search(DatabaseModel.NAME, DatabaseModel.DB_NAME)
                .widgets(scope -> AttentionCollector.band(DashboardAttention.band(AttentionCollector.databases())))
                .computed(Objects.requireNonNull(table.column(RUNS_ON_COLUMN)),
                    (database, request) -> runsOnCell(database, request))
                .computed(Objects.requireNonNull(table.column(USED_BY_COLUMN)),
                    (database, request) -> usedByCell(database, request))
                .computed(Objects.requireNonNull(table.column(LAST_BACKUP_COLUMN)),
                    (database, request) -> lastBackupCell(database, request))
                .computed(Objects.requireNonNull(table.column(STATE_COLUMN)),
                    (database, request) -> DatabaseVerdict.ofDatabase(database).cell())
                .rowLinkToTab(RecordOverview.SLUG)
                // Board Databases: the shared engines the listed records live on, under the list.
                .widgetsBelow(DatabaseParts::enginesCard)
                .build())
            .form(ResourceForm.<Row>of(ADMIN_FORM)
                .landingTab(RecordOverview.SLUG)
                .tabLabel(HohenheimMicrocopy.APP_OVERVIEW.of("configuration"))
                .bindings(adminBindings())
                .createDefaults(DatabaseParts::createDefaults)
                .notice((database, access) -> resizeNotice(database))
                .build())
            .writes(ResourceMutations.rows()
                .create(call -> DatabaseWrites.create(call.values()))
                .update(call -> {
                    DatabaseWrites.resize(Objects.requireNonNull(call.record()), call.values());
                    return null;
                })
                .delete(DELETE)
                .build())
            .deleteConfirmation(deleteConfirmation())
            .actions(List.of(backUpNow(), backupLink(HohenheimIds.id("backup_database"), false), moveToShared(),
                forceDelete(FORCE_DELETE, HohenheimMicrocopy.DATABASE, DatabaseModel.NAME)))
            // The overview first (the record's front door), then the restore tab; no history, as the legacy subpages()
            // override had it; contributed tabs (zenit-auth's Access tab) join like on every converted entry.
            .tabs(ResourceTabs.<Row>of(List.of(DatabaseOverview.tab(), new DatabaseRestorePage())).withContributions())
            .build();
    }

    /**
     * The /manage twin: the databases the caller may view, allocated through the tenant funnel, backed up by a holder
     * of {@code backups}, its credentials on their own capability's tab; no edit, move or force delete.
     */
    public static @NonNull PanelResource<Row> manage() {
        FormSpec form = FormSpec.builder()
            .createTitle(CREATE_TITLE)
            .add(DatabaseModel.NAME)
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(DatabaseModel.ENGINE))
            .build();
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(DatabaseModel.NAME).build())
            .column(ColumnSpec.fromField(DatabaseModel.ENGINE).build())
            .column(ColumnSpec.fromField(DatabaseModel.DB_NAME).copyable().build())
            // The placement TOKEN and nothing else: a tenant may see that their database shares an engine (it
            // explains why no ceilings are theirs to set), never which engine it is -- an engine name is another
            // tenant's neighbour list.
            .column(ColumnSpec.fromField(DatabaseModel.PLACEMENT).build())
            .column(stateColumn())
            .column(ColumnSpec.fromField(DatabaseModel.STATUS).filterable().hidden().build())
            .build();
        // A row of the tenant's sidebar, in the board's place (ManagePanel's sidebar note), shown while the tenant
        // holds a database or may create one (board Manage-Home, the doors drawn only where they open). reachesAny,
        // because an id set cannot express every-record authority.
        return ManageTwin.listed(entry(ManageTwin.id("database")), TenantScopes.DATABASES,
                ResourceTabs.<Row>of(List.of(new ManageDatabaseCredentialsPage())),
                access -> HohenheimAccess.reachesAny(access, DatabaseModel.MODEL_ID, HohenheimCapabilities.VIEW)
                    || TenantDatabases.canAllocate(access))
            .navGroup(NavGroup.DEFAULT)
            .navOrder(30)
            // The host and the engine on it are operator inventory: never a rule, sort, search or value of this list.
            .withholds(HostFields.of(DatabaseModel.MODEL_ID))
            .list(ResourceList.rows(table).chrome(CmsSupport.WIDE_LIST).facets().ruleFilters()
                .search(DatabaseModel.NAME, DatabaseModel.DB_NAME)
                .computed(Objects.requireNonNull(table.column(STATE_COLUMN)),
                    (database, request) -> DatabaseVerdict.ofDatabase(database).cell())
                .build())
            .form(ResourceForm.<Row>of(form).build())
            // THE tenant allocation funnel, in one call: the namespaced name, the credentials, the placement, the
            // creator's manage grant and the quota charge. Hidden AND enforced without the type-level allocation
            // permission; the funnel asks it again, because the API and any later caller reach it, not this.
            .authority(ResourceAuthority.<Row>builder().create(TenantDatabases.DATABASES_CREATE, null).build())
            .writes(ResourceMutations.rows()
                .create(call -> TenantDatabases.allocate(call.access(),
                    call.values().get(DatabaseModel.NAME.getName()),
                    call.values().get(DatabaseModel.ENGINE.getName())).get(DatabaseModel.ID))
                .delete(DELETE)
                .build())
            .deleteConfirmation(deleteConfirmation())
            .actions(List.of(backupLink(ManageTwin.id("backup_database"), true)))
            .build();
    }

    /** The identity, nav placement and plain row reads both database twins share. */
    private static PanelResource.@NonNull Builder<Row> entry(@NonNull Identifier id) {
        return PanelResource.builder(id, HohenheimSlugs.DATABASES, DATABASE)
            .label(HohenheimMicrocopy.DATABASE.of("plural"))
            .recordLabel(HohenheimMicrocopy.DATABASE.of("singular"))
            .description(HohenheimMicrocopy.DATABASE.of("nav_hint"))
            .icon(Icon.of("database"))
            .navGroup(HohenheimPanel.DEPLOY_GROUP)
            .navOrder(50)
            .reads(ResourceReads.rows());
    }

    /**
     * A record describes a provisioned container, so its every field is frozen once that container exists -- except
     * the resource ceilings, the one thing an operator can legitimately get wrong at create.
     */
    private static @NonNull List<ResourceFieldBinding> adminBindings() {
        // STATUS is service-owned even on create.
        return List.of(
            ResourceFieldBinding.of(DatabaseModel.STATUS.getName(), FieldAccess.alwaysReadonly()),
            ProvisionedRecords.frozenAfterCreate(DatabaseModel.NAME),
            ProvisionedRecords.frozenAfterCreate(DatabaseModel.ENGINE),
            ProvisionedRecords.frozenAfterCreate(DatabaseModel.DB_NAME),
            ProvisionedRecords.frozenAfterCreate(DatabaseModel.DB_USER),
            ProvisionedRecords.frozenAfterCreate(DatabaseModel.DB_PASSWORD),
            ProvisionedRecords.frozenAfterCreate(DatabaseModel.IMAGE),
            ProvisionedRecords.frozenAfterCreate(DatabaseModel.EPHEMERAL),
            ProvisionedRecords.frozenAfterCreate(DatabaseModel.SERVER_ID),
            ProvisionedRecords.frozenAfterCreate(DatabaseModel.PLACEMENT),
            ProvisionedRecords.frozenAfterCreate(DatabaseModel.ENGINE_ID),
            // A shared record has no ceilings of its own: the container is the ENGINE's and is booked once, at the
            // engine's cap. Hiding them is the honest shape; the form notice names where they live.
            hiddenWhenShared(DatabaseModel.MEMORY_LIMIT_MB),
            hiddenWhenShared(DatabaseModel.CPU_LIMIT),
            // The reason is shown ONLY on a record that carries one.
            ProvisionedRecords.failureReasonWhenSet(DatabaseModel.FAILURE_REASON));
    }

    /** Editable on the CREATE form, hidden once the record turns out to be shared. */
    private static @NonNull ResourceFieldBinding hiddenWhenShared(@NonNull Field<?, ?> field) {
        return ResourceFieldBinding.of(field.getName(),
            FieldAccess.customRecordAware((ctx, record) ->
                record instanceof Row row && DatabaseModel.isShared(row)
                    ? FieldAccess.Decision.HIDDEN : FieldAccess.Decision.EDITABLE));
    }

    /**
     * The server pick defaults to the local daemon (ensuring its row exists for the picker) and the placement to the
     * shared tier.
     *
     * AIDEV-NOTE: shared is a DEFAULT, not a resolution. An engine that cannot host logical databases (Redis) and a
     * tmpfs database are dedicated by definition, and leaving the select on shared for one of those is refused BY NAME
     * ({@code database_placement_unsupported} / {@code database_ephemeral_shared}) rather than silently rewritten.
     */
    private static @NonNull Map<String, Object> createDefaults(@NonNull PanelRequest request) {
        Map<String, Object> values = new LinkedHashMap<>(ADMIN_FORM.defaultValues());
        values.put(DatabaseModel.SERVER_ID.getName(), ServerModel.localServerId());
        values.put(DatabaseModel.PLACEMENT.getName(), DatabaseModel.PLACEMENT_SHARED);
        return values;
    }

    /**
     * What the fields cannot say: a new ceiling RECREATES the engine container, so open connections drop while it
     * comes back -- or, for a shared record, that its ceilings are the engine's. Only on a stored record.
     */
    private static @Nullable Microcopy resizeNotice(@NonNull Row database) {
        if (database.get(DatabaseModel.ID) == null) {
            return null;
        }
        return DatabaseModel.isShared(database)
            ? HohenheimMicrocopy.DATABASE.of("shared_notice")
            : HohenheimMicrocopy.DATABASE.of("resize_notice");
    }

    /** An operator holds {@code destroy} on every record; a delegate on the records it was granted it on. */
    static boolean mayDestroy(@NonNull Row database, @NonNull AccessContext access) {
        return HohenheimAccess.hasDatabaseCapability(access, database.get(DatabaseModel.ID), HohenheimCapabilities.DESTROY);
    }

    /**
     * What deleting THIS record actually takes with it, which the two placements do not share: a dedicated record's
     * container and data volume go, a shared record's logical database and user are dropped inside an engine that
     * stays up serving everybody else.
     */
    private static @NonNull DeleteConfirmation<Row> deleteConfirmation() {
        return DeleteConfirmation.<Row>of(DeleteConfirmation.body(
                HohenheimMicrocopy.DATABASE.of("delete_confirm")))
            .forRow((database, request) -> DeleteConfirmation.body(HohenheimMicrocopy.DATABASE.of(
                    DatabaseModel.isShared(database) ? "delete_confirm_shared" : "delete_confirm")
                .withArg("name", String.valueOf((Object) database.get(DatabaseModel.NAME)))));
    }

    /**
     * The backup download.
     *
     * @param delegated whether it is the /manage twin's, shown only to a holder of {@code backups} (reachesRecord,
     *                  never the fresh walk: this runs once per rendered row, and the download keeps the walk)
     */
    private static @NonNull PanelAction<Row> backupLink(@NonNull Identifier id, boolean delegated) {
        PanelAction.LinkBuilder<Row> link = PanelAction.<Row>link(id, ActionPlacement.ROW)
            .label(HohenheimMicrocopy.DATABASE.of("backup"))
            .icon(Icon.of("download"))
            .route((database, request) -> HohenheimEndpoints.DATABASES_BACKUP
                .with(HohenheimEndpoints.DATABASE_NAME, database.get(DatabaseModel.NAME)));
        if (delegated) {
            link.shownWhen((database, access) -> HohenheimAccess.reachesRecord(access, DatabaseModel.MODEL_ID,
                database.get(DatabaseModel.ID), HohenheimCapabilities.BACKUPS));
        }
        return link.build();
    }

    /**
     * Offered only where the move can succeed: a dedicated, active record whose engine has logical databases at all.
     *
     * AIDEV-NOTE: not DESTRUCTIVE: the move keeps the dump AND the old data volume as two rollbacks, and painting it
     * red beside a real delete devalues the red. Not PRIMARY either, and in the heading's More menu: a placement change
     * done once in a database's life never leads its page (board Databases); Back up now leads by position.
     */
    private static @NonNull PanelAction<Row> moveToShared() {
        return PanelAction.<Row, Void>places(MOVE_TO_SHARED, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(HohenheimMicrocopy.DATABASE.of("move_started")
                    .withArg("name", request.subject().get(DatabaseModel.NAME))))
            .inlineInRow(false)
            .inlineOnRecord(false)
            // The record-less fallback the framework requires beside a dynamic one.
            .confirmation(Confirmations.of(HohenheimMicrocopy.DATABASE.of("move_shared"),
                HohenheimMicrocopy.DATABASE.of("move_shared_ok"),
                HohenheimMicrocopy.DATABASE.of("move_shared_confirm_generic"), ActionStyle.PRIMARY))
            .dynamicConfirmation(database -> Confirmations.of(HohenheimMicrocopy.DATABASE.of("move_shared"),
                HohenheimMicrocopy.DATABASE.of("move_shared_ok"),
                HohenheimMicrocopy.DATABASE.of("move_shared_confirm").withArg("name", database.get(DatabaseModel.NAME)),
                ActionStyle.PRIMARY))
            .build();
    }

    /**
     * The recorded escape hatch of a database or an engine, visible ONLY once a normal destroy already failed and
     * typed-confirmed with the record's own name; the container and volume may survive on the host, where the
     * reconciler reports them as orphans.
     *
     * @param scope the record kind's copy: {@code force_delete}, {@code force_delete_confirm_generic},
     *              {@code force_delete_confirm} and {@code force_delete_done}
     */
    private static @NonNull PanelAction<Row> forceDelete(@NonNull Operation<Row, Void, Void> operation,
                                                         @NonNull HohenheimMicrocopy scope, @NonNull StringField name) {
        ConfirmationSpec force = Confirmations.of(scope.of("force_delete"), scope.of("force_delete_confirm_generic"),
            ActionStyle.DESTRUCTIVE);
        return PanelAction.<Row, Void>places(operation, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(scope.of("force_delete_done")
                    .withArg("name", request.subject().get(name))))
            .style(ActionStyle.DESTRUCTIVE)
            .inlineInRow(false)
            .confirmation(force)
            .dynamicConfirmation(row -> Confirmations.typed(force.withBody(scope.of("force_delete_confirm")
                .withArg("name", row.get(name))), row.get(name)))
            .build();
    }

    /**
     * "Back up now" in the row and the record heading; the dump runs in the background and lands in the Backups card.
     */
    private static @NonNull PanelAction<Row> backUpNow() {
        return PanelAction.<Row, Void>places(BACK_UP_NOW, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(HohenheimMicrocopy.DATABASE.of("back_up_started")
                    .withArg("name", request.subject().get(DatabaseModel.NAME))))
            .build();
    }

    /** @return why this database cannot be backed up now, or null when it can */
    static @Nullable Microcopy backUpUnavailable(@NonNull Row database) {
        if (Boolean.TRUE.equals(database.get(DatabaseModel.EPHEMERAL))) {
            return HohenheimMicrocopy.DATABASE.of("back_up_temporary");
        }
        // A dump runs inside the engine serving it.
        return whileNotServing(database, HohenheimMicrocopy.DATABASE.of("back_up_not_active"),
            HohenheimMicrocopy.DATABASE.of("back_up_not_serving"));
    }

    /** @return why this database cannot move onto a shared engine now (the move dumps it from the engine it leaves) */
    public static @Nullable Microcopy moveUnavailable(@NonNull Row database) {
        return whileNotServing(database, HohenheimMicrocopy.DATABASE.of("move_not_active"),
            HohenheimMicrocopy.DATABASE.of("move_not_serving"));
    }

    /**
     * Why a verb that runs inside this database's engine (a dump, a move) cannot run now: the verdict every surface
     * reads, never the stored "active".
     *
     * @param plain      the verb's words for a verdict that gives no reason
     * @param withReason the verb's words around the verdict's reason
     * @return those words, null while the database serves
     */
    private static @Nullable Microcopy whileNotServing(@NonNull Row database, @NonNull Microcopy plain,
                                                       @NonNull Microcopy withReason) {
        DatabaseVerdict verdict = DatabaseVerdict.ofDatabase(database);
        if (verdict.serves()) {
            return null;
        }
        return verdict.reason() == null ? plain : withReason.withArg("reason", verdict.reason());
    }

    /** @return what a database with no dump yet is told: why it cannot be backed up now, or how it will be */
    static @NonNull Microcopy neverBackedUpDetail(@NonNull Row database) {
        Microcopy unavailable = backUpUnavailable(database);
        return unavailable != null ? unavailable : HohenheimMicrocopy.DATABASE_OVERVIEW.of("backup_never_detail");
    }

    /** The muted line under a database's name: its shared engine and host, or its own container's host. */
    private static @NonNull String runsOnCell(@NonNull Row database, @NonNull PanelRequest request) {
        Conduit conduit = request.conduit();
        return DatabaseOverview.runsOn(database).resolve(conduit.getLocales(), conduit.getMessageResolver());
    }

    /**
     * The apps using a database, by name, each linked to its overview (the overview's Used by card's link) for a
     * reader who may open it; "No app" when none does.
     */
    private static @NonNull RecordLinksCell usedByCell(@NonNull Row database, @NonNull PanelRequest request) {
        List<Row> instances = DeleteImpact.liveInstancesOf(database.get(DatabaseModel.ID));
        if (instances.isEmpty()) {
            return RecordLinksCell.none(HohenheimMicrocopy.DATABASE_OVERVIEW.of("used_by_none"));
        }
        boolean listed = AppDirectory.offers(request.panel(), HohenheimSlugs.INSTANCES, request.access());
        List<RecordLink> links = new ArrayList<>();
        for (Row instance : instances) {
            boolean opens = listed && HohenheimAccess.reachesRecord(request.access(), InstanceModel.MODEL_ID,
                instance.get(InstanceModel.ID), HohenheimCapabilities.VIEW);
            links.add(new RecordLink(String.valueOf((Object) instance.get(InstanceModel.NAME)),
                opens ? InstanceParts.recordRoute(request.panelSlug(), instance, null).toUrl() : null));
        }
        return RecordLinksCell.of(links);
    }

    /**
     * The Engines card under the list (board Databases): every shared engine as "MySQL on daystrom", how many databases
     * it holds and its state, each opening its engine. Nothing while no engine exists.
     */
    private static @NonNull WidgetTree enginesCard(@NonNull ListScope scope) {
        List<Row> engines = Models.get(DatabaseEngineModel.class).find()
            .orderBy(DatabaseEngineModel.ID, SortOrder.ASC).all();
        if (engines.isEmpty()) {
            return WidgetTree.empty();
        }
        Conduit conduit = scope.accessContext().conduit();
        String panelSlug = CmsSupport.panelSlug(conduit);
        Map<Integer, Long> counts = countDatabasesPerEngine();
        List<WidgetFact> facts = new ArrayList<>();
        for (Row engine : engines) {
            Integer id = engine.get(DatabaseEngineModel.ID);
            String host = ServerModel.canonicalNameOf(engine.get(DatabaseEngineModel.SERVER_ID));
            Microcopy holds = HohenheimMicrocopy.DATABASE_LIST.of("engine_holds")
                .withArg("databases", HohenheimCounts.of("databases", counts.getOrDefault(id, 0L)))
                .withArg("state", Labels.inSentence(DatabaseVerdict.ofEngine(engine).state().label()));
            facts.add(WidgetFact.link(
                HohenheimMicrocopy.DATABASE_LIST.of("engine_on_host")
                    .withArg("engine", CmsSupport.enumValueLabel(DatabaseEngineModel.ENGINE,
                        String.valueOf((Object) engine.get(DatabaseEngineModel.ENGINE)))).withArg("host", host)
                    .resolve(conduit.getLocales(), conduit.getMessageResolver()),
                holds.resolve(conduit.getLocales(), conduit.getMessageResolver()),
                CmsRoutes.open(panelSlug, HohenheimSlugs.DATABASE_ENGINES, id).toUrl()));
        }
        WidgetInstance card = new WidgetInstance(CardWidget.ID,
            Map.of("title", HohenheimMicrocopy.DATABASE_LIST.of("engines_title"), "lead",
                HohenheimMicrocopy.DATABASE_LIST.of("engines_lead")),
            new WidgetTree(List.of(new WidgetInstance(FactListWidget.ID, Map.of()).withData(facts))));
        return new WidgetTree(List.of(AdminDashboard.section(card)));
    }

    /**
     * Where a database's backups stand: the Last backup cell and the dashboard's Backups tile read one verdict.
     *
     * AIDEV-NOTE: a {@link StateBadge}, not a worded state: an overdue or recent dump's badge reads the dump's age.
     */
    enum BackupState implements StateBadge {

        /** A temporary database is never backed up, by design. */
        TEMPORARY("temporary", BadgeVariant.OUTLINE),

        /** A persistent database with no stored dump. */
        NEVER("never", BadgeVariant.WARNING),

        /** The newest dump is older than the nightly backup allows ({@link #BACKUP_OVERDUE_AFTER}). */
        OVERDUE("overdue", BadgeVariant.WARNING),

        /** The newest dump is recent. */
        DONE("done", BadgeVariant.SUCCESS);

        private final String token;
        private final BadgeVariant variant;

        BackupState(@NonNull String token, @NonNull BadgeVariant variant) {
            this.token = token;
            this.variant = variant;
        }

        @Override
        public @NonNull String token() {
            return this.token;
        }

        @Override
        public @NonNull BadgeVariant variant() {
            return this.variant;
        }

        /** @return whether the database is meant to have backups at all, so a count of backed-up things holds it */
        boolean expectsBackups() {
            return switch (this) {
                case TEMPORARY -> false;
                case NEVER, OVERDUE, DONE -> true;
            };
        }
    }

    /**
     * @param state  where its backups stand
     * @param newest its newest stored dump, null when it has none or is temporary
     */
    record BackupReading(@NonNull BackupState state, DatabaseBackups.@Nullable Stored newest) {}

    /** @return where this database's backups stand, read from the dumps on disk ({@link DatabaseBackups}) */
    static @NonNull BackupReading backupOf(@NonNull Row database) {
        if (Boolean.TRUE.equals(database.get(DatabaseModel.EPHEMERAL))) {
            return new BackupReading(BackupState.TEMPORARY, null);
        }
        String name = database.get(DatabaseModel.NAME);
        DatabaseBackups.Stored newest = name == null ? null : DatabaseBackups.newest(name);
        if (newest == null) {
            return new BackupReading(BackupState.NEVER, null);
        }
        return new BackupReading(newest.at().isBefore(Now.instant().minus(BACKUP_OVERDUE_AFTER))
            ? BackupState.OVERDUE : BackupState.DONE, newest);
    }

    /**
     * The newest stored dump as a word and a line: how long ago and how big, a warning when it is older than the
     * nightly backup allows, "Never" as a warning while a persistent database has none, and "Not backed up" for a
     * temporary one.
     */
    private static @NonNull StateLineCell lastBackupCell(@NonNull Row database, @NonNull PanelRequest request) {
        BackupReading backup = backupOf(database);
        DatabaseBackups.Stored newest = backup.newest();
        BackupState state = backup.state();
        return switch (state) {
            case TEMPORARY -> StateLineCell.of(state, HohenheimMicrocopy.DATABASE_OVERVIEW.of("backup_temporary"),
                HohenheimMicrocopy.DATABASE_OVERVIEW.of("backup_temporary_detail"));
            case NEVER -> StateLineCell.of(state, HohenheimMicrocopy.DATABASE_OVERVIEW.of("backup_never"),
                neverBackedUpDetail(database));
            case OVERDUE -> StateLineCell.of(state, Microcopy.literal(ago(newest, request)),
                HohenheimMicrocopy.DATABASE_OVERVIEW.of("backup_overdue_detail")
                    .withArg("size", ByteText.human(newest.bytes())));
            case DONE -> StateLineCell.of(state, Microcopy.literal(ago(newest, request)),
                Microcopy.literal(ByteText.human(newest.bytes())));
        };
    }

    private static @NonNull String ago(DatabaseBackups.@NonNull Stored dump, @NonNull PanelRequest request) {
        return RelativeTime.ago(dump.at(), CmsSupport.timeWording(request.conduit()));
    }

    /** The state column every database and engine list draws in place of the stored status. */
    private static @NonNull ColumnSpec stateColumn() {
        return ColumnSpec.virtual(STATE_COLUMN, HohenheimMicrocopy.DATABASE_LIST.of("state_column"))
            .renderer(HohenheimTemplateIds.CELL_STATE_LINE).build();
    }

    // -- the shared engines ---------------------------------------------------------------------------------------

    /**
     * The SHARED database engines: one engine process per (host, kind) serving many managed databases as logical
     * databases. Create provisions in the background, the two ceilings are the only editable columns afterwards (a
     * recreate, exactly like a dedicated database's resize), and the delete is offered dead while a record lives on it.
     *
     * AIDEV-NOTE: an engine deliberately survives its last database. Recreating one costs a minute and a port, and a
     * host booking that flaps with the last delete is worse than an idle engine process (docs/shared-database-engines).
     */
    public static @NonNull PanelResource<Row> engines() {
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(DatabaseEngineModel.NAME).filterable().subtext("engine").build())
            .column(ColumnSpec.fromField(DatabaseEngineModel.ENGINE).filterable().hidden().build())
            .column(ColumnSpec.fromField(DatabaseEngineModel.SERVER_ID)
                .relation(RelationPick.of(DatabaseEngineModel.SERVER_ID, ServerModel.MODEL_ID).build())
                .build())
            .column(stateColumn())
            .column(ColumnSpec.virtual(DATABASES_COLUMN,
                HohenheimMicrocopy.DATABASE_ENGINE.of("databases")).build())
            .column(ColumnSpec.fromField(DatabaseEngineModel.MEMORY_LIMIT_MB).build())
            .column(ColumnSpec.fromField(DatabaseEngineModel.STATUS).filterable().hidden().build())
            .filter(FilterSpec.leaf(DatabaseEngineModel.NAME, CoreTypes.CONTAINS)
                .label(FieldLabels.labelFor(DatabaseEngineModel.NAME)).build())
            .filter(FilterSpec.leaf(DatabaseEngineModel.ENGINE, CoreTypes.EQUALS)
                .label(FieldLabels.labelFor(DatabaseEngineModel.ENGINE)).build())
            .filter(FilterSpec.leaf(DatabaseEngineModel.STATUS, CoreTypes.EQUALS)
                .label(FieldLabels.labelFor(DatabaseEngineModel.STATUS)).build())
            .build();
        return PanelResource.builder(HohenheimIds.id("database_engine"), HohenheimSlugs.DATABASE_ENGINES, ENGINE)
            .label(HohenheimMicrocopy.DATABASE_ENGINE.of("plural"))
            .recordLabel(HohenheimMicrocopy.DATABASE_ENGINE.of("singular"))
            .description(CmsSupport.navHint(HohenheimMicrocopy.DATABASE_ENGINE))
            .icon(Icon.of("server"))
            .navGroup(HohenheimPanel.DEPLOY_GROUP)
            // Right after Databases: the tier the list above it places records on.
            .navOrder(51)
            .reads(ResourceReads.rows())
            .list(ResourceList.rows(table).chrome(CmsSupport.WIDE_LIST).facets().ruleFilters()
                .search(DatabaseEngineModel.NAME)
                .computed(Objects.requireNonNull(table.column(DATABASES_COLUMN)),
                    (engine, request) -> databaseCount(engine, request))
                .computed(Objects.requireNonNull(table.column(STATE_COLUMN)),
                    (engine, request) -> DatabaseVerdict.ofEngine(engine).cell())
                .build())
            .form(ResourceForm.<Row>of(ENGINE_FORM)
                .bindings(engineBindings())
                .createDefaults(DatabaseParts::engineDefaults)
                // A new ceiling recreates the engine container, and every database on it loses its connections until
                // it is back.
                .notice((engine, access) -> engine.get(DatabaseEngineModel.ID) == null ? null
                    : HohenheimMicrocopy.DATABASE_ENGINE.of("resize_notice"))
                .build())
            .writes(ResourceMutations.rows()
                .create(call -> DatabaseWrites.createEngine(call.values()))
                .update(call -> {
                    DatabaseWrites.resizeEngine(Objects.requireNonNull(call.record()), call.values());
                    return null;
                })
                .delete(DELETE_ENGINE)
                .build())
            // Deleting an engine destroys its container AND the volume every database sat on.
            .deleteConfirmation(DeleteConfirmation.<Row>of(DeleteConfirmation.body(
                    HohenheimMicrocopy.DATABASE_ENGINE.of("delete_confirm")))
                .forRow((engine,
                    request) -> DeleteConfirmation.body(HohenheimMicrocopy.DATABASE_ENGINE.of("delete_confirm")
                    .withArg("name", String.valueOf((Object) engine.get(DatabaseEngineModel.NAME))))))
            .actions(List.of(forceDelete(FORCE_DELETE_ENGINE, HohenheimMicrocopy.DATABASE_ENGINE,
                DatabaseEngineModel.NAME)))
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions())
            .build();
    }

    /** The host pick defaults to the local daemon, and the superuser to the shipped word. */
    private static @NonNull Map<String, Object> engineDefaults(@NonNull PanelRequest request) {
        Map<String, Object> values = new LinkedHashMap<>(ENGINE_FORM.defaultValues());
        values.put(DatabaseEngineModel.SERVER_ID.getName(), ServerModel.localServerId());
        values.put(DatabaseEngineModel.ROOT_USER.getName(), "root");
        return values;
    }

    /** Every field describing the provisioned container is frozen once it exists, except the ceilings. */
    private static @NonNull List<ResourceFieldBinding> engineBindings() {
        return List.of(
            ResourceFieldBinding.of(DatabaseEngineModel.STATUS.getName(), FieldAccess.alwaysReadonly()),
            ProvisionedRecords.frozenAfterCreate(DatabaseEngineModel.NAME),
            ProvisionedRecords.frozenAfterCreate(DatabaseEngineModel.ENGINE),
            ProvisionedRecords.frozenAfterCreate(DatabaseEngineModel.IMAGE),
            ProvisionedRecords.frozenAfterCreate(DatabaseEngineModel.SERVER_ID),
            ProvisionedRecords.frozenAfterCreate(DatabaseEngineModel.ROOT_USER),
            ProvisionedRecords.frozenAfterCreate(DatabaseEngineModel.ROOT_PASSWORD),
            // Shown ONLY on a record that carries one.
            ProvisionedRecords.failureReasonWhenSet(DatabaseEngineModel.FAILURE_REASON));
    }

    /**
     * The engine's database count, off the request's memo: ONE grouped aggregate over every engine per rendered
     * list instead of one query per row.
     */
    private static long databaseCount(@NonNull Row engine, @NonNull PanelRequest request) {
        Integer engineId = engine.get(DatabaseEngineModel.ID);
        if (engineId == null) {
            return 0;
        }
        return CmsSupport.memo(request.conduit(), DATABASE_COUNTS, DatabaseParts::countDatabasesPerEngine)
            .getOrDefault(engineId, 0L);
    }

    /** @return engine id -> managed database count, for every engine holding one */
    private static @NonNull Map<Integer, Long> countDatabasesPerEngine() {
        return GroupedCounts.of(Models.get(DatabaseModel.class).find(), DatabaseModel.ENGINE_ID);
    }
}
