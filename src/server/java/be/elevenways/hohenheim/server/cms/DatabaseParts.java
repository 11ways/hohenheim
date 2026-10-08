package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.StateLineCell;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.HohenheimCounts;
import be.elevenways.hohenheim.server.database.DatabaseBackups;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.common.time.RelativeTime;
import be.elevenways.protoblast.common.time.RelativeTimeWording;
import be.elevenways.zenit.cms.common.resource.RecordOverview;
import be.elevenways.zenit.common.text.ByteText;
import be.elevenways.zenit.common.ui.BadgeVariant;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.Secrets;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.database.DatabaseEngines;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.InstanceDatabaseLinks;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.database.TenantDatabases;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
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
import be.elevenways.zenit.cms.common.resource.RowWriteCall;
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
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.activity.ZenitActivityAction;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.orm.query.aggregate.Aggregate;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.routing.RouteScope;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.widget.common.WidgetInstance;
import be.elevenways.zenit.widget.common.WidgetTree;
import be.elevenways.zenit.widget.common.builtin.CardWidget;
import be.elevenways.zenit.widget.common.builtin.FactListWidget;
import be.elevenways.zenit.widget.common.data.WidgetFact;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The managed-database tier's parts: the operator's databases, their /manage twin and the shared engines (stage 4
 * contract 10). Create provisions in the background, the resource ceilings are the only columns an update writes,
 * and every delete is a domain operation whose "still in use" refusal is its availability.
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

    /** The databases entry slug, on both panels. */
    public static final String SLUG = "databases";

    /** The engines entry slug, which the placement column's relation pick and the attention items name. */
    public static final String ENGINES_SLUG = "database-engines";

    private static final SubjectType<Row> DATABASE = SubjectType.record(DatabaseModel.MODEL_ID);
    private static final SubjectType<Row> ENGINE = SubjectType.record(DatabaseEngineModel.MODEL_ID);

    /** Where a dedicated record keeps the ceilings the shared resize lane reads and writes. */
    private static final ProvisionedRecords.Columns DATABASE_CEILINGS = new ProvisionedRecords.Columns(
        DatabaseModel.ID, DatabaseModel.MEMORY_LIMIT_MB, DatabaseModel.CPU_LIMIT,
        DatabaseModel.STATUS, DatabaseModel.FAILURE_REASON);

    /** Where an engine keeps the ceilings the shared resize lane reads and writes. */
    private static final ProvisionedRecords.Columns ENGINE_CEILINGS = new ProvisionedRecords.Columns(
        DatabaseEngineModel.ID, DatabaseEngineModel.MEMORY_LIMIT_MB, DatabaseEngineModel.CPU_LIMIT,
        DatabaseEngineModel.STATUS, DatabaseEngineModel.FAILURE_REASON);

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

    /** Request memo of every database's live workloads: two queries per rendered list. */
    private static final IdentifierKey<Map<Integer, List<Row>>> USED_BY =
        IdentifierKey.of("hohenheim", "database_used_by");

    /** The virtual column counting the managed databases living on an engine. */
    private static final String DATABASES_COLUMN = "databases";

    /** Request memo of every engine's database count: one grouped aggregate per rendered list. */
    private static final IdentifierKey<Map<Integer, Long>> DATABASE_COUNTS =
        IdentifierKey.of("hohenheim", "database_engine_database_counts");

    /** The create verb in the Databases board's words: the list's button and the form's heading. */
    private static final Microcopy CREATE_TITLE = Microcopy.of("create_title").withFilter("scope", "database");

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
        .label(Microcopy.of("delete").withFilter("scope", "cms"))
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
            .label(Microcopy.of("move_shared").withFilter("scope", "database"))
            .description(Microcopy.of("move_shared_hint").withFilter("scope", "database"))
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
            .label(Microcopy.of("back_up_now").withFilter("scope", "database"))
            .description(Microcopy.of("back_up_now_hint").withFilter("scope", "database"))
            .icon(Icon.of("box-archive"))
            .one(DATABASE)
            .gate(OperationGate.permission(HohenheimSources.ADMIN_ACCESS))
            .command(CmsCommands.EXTERNAL)
            .register();

    /** The recorded escape hatch once a normal destroy failed: the record goes, the host may keep orphans. */
    public static final Operation<Row, Void, Void> FORCE_DELETE =
        Operation.declare(HohenheimIds.id("force_delete_database"))
            .happened(OperationSentences.of("force_delete_database"))
            .label(Microcopy.of("force_delete").withFilter("scope", "database"))
            .description(Microcopy.of("force_delete_hint").withFilter("scope", "database"))
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
            .label(Microcopy.of("delete").withFilter("scope", "cms"))
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
            .label(Microcopy.of("force_delete").withFilter("scope", "database_engine"))
            .description(Microcopy.of("force_delete_hint").withFilter("scope", "database_engine"))
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
            .availability((database, access) -> inUseReason(database))
            .handle(call -> {
                destroy(call.subject());
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
            .handle(call -> {
                new DatabaseService().moveToSharedEngineInBackground(call.subject().get(DatabaseModel.NAME));
                return null;
            });
        OperationHandlers.attach(FORCE_DELETE)
            .applies(database -> DatabaseModel.STATUS_DESTROY_FAILED.equals(database.get(DatabaseModel.STATUS)))
            .handle(call -> {
                Row database = call.subject();
                String name = database.get(DatabaseModel.NAME);
                // The same in-use refusal the delete makes, asked BEFORE the engine instance is abandoned: the
                // funnel would refuse the row delete anyway, but by then the abandon has already run.
                refuseWhileAttached(name, database.get(DatabaseModel.ID));
                ActivityLog.withAction(ZenitActivityAction.DELETE, "force-destroy",
                    () -> new DatabaseService().forceDestroyRecord(name));
                return null;
            });
        OperationHandlers.attach(DELETE_ENGINE)
            .availability((engine, access) -> engineInUseReason(engine))
            .handle(call -> {
                destroyEngine(call.subject());
                return 1;
            });
        OperationHandlers.attach(FORCE_DELETE_ENGINE)
            .applies(engine -> DatabaseModel.STATUS_DESTROY_FAILED.equals(engine.get(DatabaseEngineModel.STATUS)))
            .handle(call -> {
                Row engine = call.subject();
                Integer engineId = engine.get(DatabaseEngineModel.ID);
                String name = engine.get(DatabaseEngineModel.NAME);
                ActivityLog.withAction(ZenitActivityAction.DELETE, "force-destroy", () -> {
                    try {
                        if (engineId != null) {
                            DatabaseEngines.forceDestroy(engineId);
                        }
                    } catch (IOException e) {
                        throw Violations.ofForm(CmsSupport.violationText("database_engine_destroy_failed")
                            .withArg("name", name)
                            .withArg("reason", String.valueOf(e.getMessage())));
                    }
                });
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
            .column(ColumnSpec.virtual(RUNS_ON_COLUMN, listCopy("runs_on_column")).hidden().build())
            .column(ColumnSpec.fromField(DatabaseModel.ENGINE).filterable().build())
            .column(ColumnSpec.virtual(USED_BY_COLUMN, listCopy("used_by_column"))
                .renderer(CmsTemplateIds.CELL_RECORD_LINKS).build())
            .column(ColumnSpec.virtual(LAST_BACKUP_COLUMN, listCopy("last_backup_column"))
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
        return entry("database")
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
                .tabLabel(AppOverview.copy("configuration"))
                .bindings(adminBindings())
                .createDefaults(DatabaseParts::createDefaults)
                .notice((database, access) -> resizeNotice(database))
                .build())
            .writes(ResourceMutations.rows()
                .create(DatabaseParts::provision)
                .update(DatabaseParts::resize)
                .delete(DELETE)
                .build())
            .deleteConfirmation(deleteConfirmation())
            .actions(List.of(backUpNow(), backupLink(HohenheimIds.id("backup_database"), false), moveToShared(),
                forceDelete()))
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
        return entry("manage_database")
            // A row of the tenant's sidebar, in the board's place (ManagePanel's sidebar note).
            .navGroup(NavGroup.DEFAULT)
            .navOrder(30)
            .scope(TenantScopes.DATABASES)
            // The host and the engine on it are operator inventory: never a rule, sort, search or value of this list.
            .withholds(HostFields.of(DatabaseModel.MODEL_ID))
            // NAV-ONLY (zero granted databases hide the empty list); the route stays scoped. reachesAny, because an
            // id set cannot express every-record authority.
            .hasInScopeRecords(access -> HohenheimAccess.reachesAny(access, DatabaseModel.MODEL_ID,
                HohenheimAccess.VIEW))
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
            .actions(List.of(backupLink(HohenheimIds.id("manage_backup_database"), true)))
            .tabs(ResourceTabs.<Row>of(List.of(new ManageDatabaseCredentialsPage())))
            .build();
    }

    /** The identity, nav placement and plain row reads both database twins share. */
    private static PanelResource.@NonNull Builder<Row> entry(@NonNull String id) {
        return PanelResource.builder(HohenheimIds.id(id), SLUG, DATABASE)
            .label(Microcopy.of("plural").withFilter("scope", "database"))
            .recordLabel(Microcopy.of("singular").withFilter("scope", "database"))
            .description(Microcopy.of("nav_hint").withFilter("scope", "database"))
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
            ? Microcopy.of("shared_notice").withFilter("scope", "database")
            : Microcopy.of("resize_notice").withFilter("scope", "database");
    }

    /**
     * The service persists the record itself (status provisioning) and provisions the container in the background;
     * it REFUSES a name that is already taken ({@code database_name_taken}) rather than converging onto it.
     *
     * AIDEV-NOTE: the returned id is the row the service inserted, never the answer to a re-query by name: that is
     * how a colliding create once reported success while it had overwritten someone else's database.
     */
    private static @NonNull Object provision(@NonNull RowWriteCall call) {
        Map<String, Object> values = call.values();
        String name = trimmed(values.get(DatabaseModel.NAME.getName()));
        if (!name.matches("[a-z0-9][a-z0-9-]*")) {
            throw Violations.ofField("name", name, CmsSupport.violationText("name_format"));
        }
        String engineToken = trimmed(values.get(DatabaseModel.ENGINE.getName())).toLowerCase(Locale.ROOT);
        ManagedDatabase.Engine engine = ManagedDatabase.Engine.forToken(engineToken);
        if (engine == null) {
            throw Violations.ofField("engine", engineToken,
                CmsSupport.violationText("unknown_engine").withArg("engine", engineToken));
        }
        String database = trimmed(values.get(DatabaseModel.DB_NAME.getName()));
        if (database.isEmpty()) {
            throw Violations.ofField("db_name", database, CmsSupport.violationText("database_name_required"));
        }
        String user = trimmed(values.get(DatabaseModel.DB_USER.getName()));
        if (user.isEmpty()) {
            user = "appuser";
        }
        String password = trimmed(values.get(DatabaseModel.DB_PASSWORD.getName()));
        if (password.isEmpty()) {
            password = Secrets.generatePassword();
        }
        String image = trimmed(values.get(DatabaseModel.IMAGE.getName()));
        boolean ephemeral = Boolean.TRUE.equals(values.get(DatabaseModel.EPHEMERAL.getName()));
        // The FK is canonical; the service API still speaks the (unique) server name.
        String server = ServerModel.nameOf(
            values.get(DatabaseModel.SERVER_ID.getName()) instanceof Integer serverId ? serverId : null);
        ResourceLimits limits = ResourceLimits.of(
            values.get(DatabaseModel.MEMORY_LIMIT_MB.getName()) instanceof Integer mb ? mb : null,
            values.get(DatabaseModel.CPU_LIMIT.getName()) instanceof Double cpus ? cpus : null);
        // A blank placement is the service's own default (shared where the engine can host logical databases and the
        // data is persistent), never a third placement here.
        String placement = trimmed(values.get(DatabaseModel.PLACEMENT.getName()));
        Integer engineId = values.get(DatabaseModel.ENGINE_ID.getName()) instanceof Integer id ? id : null;
        Row created = new DatabaseService().createAsync(name, engine, image.isEmpty() ? null : image, user, password,
            database, ephemeral, server, limits, placement.isEmpty() ? null : placement, engineId);
        return created.get(DatabaseModel.ID);
    }

    /**
     * THE resize: the two resource ceilings and nothing else, through the lane shared with the engines
     * ({@link ProvisionedRecords#resize}). The engine row's reservation runs INLINE, so a host without room refuses on
     * this form ({@code host_capacity_reached}). A ceiling the write does not carry keeps its stored value.
     */
    private static @Nullable Object resize(@NonNull RowWriteCall call) {
        Row existing = Objects.requireNonNull(call.record());
        if (DatabaseModel.isShared(existing)) {
            // The fields are HIDDEN on a shared record, so a submitted value did not come from the rendered form;
            // refuse it by name instead of booking a ceiling against a container this record does not own.
            if (ProvisionedRecords.carriesCeiling(call.values(), DATABASE_CEILINGS)) {
                throw Violations.ofForm(CmsSupport.violationText("database_shared_limits"));
            }
            return null;
        }
        DatabaseService service = new DatabaseService();
        ProvisionedRecords.resize(Models.get(DatabaseModel.class), existing, call.values(), DATABASE_CEILINGS,
            DatabaseInstances::reserveEngineRow, service::provisionInBackground);
        return null;
    }

    /** An operator holds {@code destroy} on every record; a delegate on the records it was granted it on. */
    static boolean mayDestroy(@NonNull Row database, @NonNull AccessContext access) {
        Integer id = database.get(DatabaseModel.ID);
        return id != null && HohenheimAccess.hasDatabaseCapability(access, id, HohenheimAccess.DESTROY);
    }

    /**
     * A database a live workload still holds is offered DEAD, naming the workloads and the page each is detached on;
     * the handler refuses with the same facts.
     *
     * AIDEV-NOTE: both tiers count. Since 2026-08-08 a database can be attached to an instance, and a refusal that only
     * counted SITES would have let a tenant destroy the engine out from under their own running game server.
     *
     * @return null when nothing holds it
     */
    static @Nullable Microcopy inUseReason(@NonNull Row database) {
        String workloads = attachedWorkloads(database.get(DatabaseModel.ID));
        if (workloads.isEmpty()) {
            return null;
        }
        return Microcopy.of("delete_in_use").withFilter("scope", "database")
            .withArg("name", String.valueOf((Object) database.get(DatabaseModel.NAME)))
            .withArg("workloads", workloads);
    }

    /**
     * The verified teardown: a NAMED refusal, never a 500, when it is unconfirmed; the record is then kept (status
     * {@code destroy_failed}), the port claim parked, and the force delete is the recorded way out.
     */
    private static void destroy(@NonNull Row database) {
        String name = database.get(DatabaseModel.NAME);
        refuseWhileAttached(name, database.get(DatabaseModel.ID));
        try {
            new DatabaseService().destroy(name, true);
        } catch (IOException e) {
            String detail = WithheldFailure.operatorDetail(e);
            throw Violations.ofForm(detail == null
                ? CmsSupport.violationText("database_destroy_failed_tenant").withArg("name", name)
                : CmsSupport.violationText("database_destroy_failed")
                    .withArg("name", name)
                    .withArg("reason", detail));
        }
        // Links to soft-deleted owners are debris once the database is gone: the row delete inside destroy takes them
        // along through the model funnel (InstanceDatabaseLinks).
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
     * The live workloads attached to a database, each with the URL of the instance's Databases tab (the page a detach
     * happens on), joined for a sentence; empty when nothing holds it.
     */
    private static @NonNull String attachedWorkloads(@Nullable Integer databaseId) {
        if (databaseId == null) {
            return "";
        }
        Conduit conduit = RouteScope.currentConduit();
        String panel = conduit != null ? CmsSupport.panelSlug(conduit) : HohenheimSlugs.ADMIN;
        List<String> workloads = new ArrayList<>();
        for (Row instance : InstanceDatabaseLinks.liveInstances(databaseId)) {
            workloads.add(instance.get(InstanceModel.NAME) + " ("
                + CmsRoutes.subpage(panel, HohenheimSlugs.INSTANCES, instance.get(InstanceModel.ID),
                    InstanceDatabasesPage.SLUG).toUrl() + ")");
        }
        return DeleteImpact.join(workloads);
    }

    /**
     * What deleting THIS record actually takes with it, which the two placements do not share: a dedicated record's
     * container and data volume go, a shared record's logical database and user are dropped inside an engine that
     * stays up serving everybody else.
     */
    private static @NonNull DeleteConfirmation<Row> deleteConfirmation() {
        return DeleteConfirmation.<Row>of(DeleteConfirmation.body(
                Microcopy.of("delete_confirm").withFilter("scope", "database")))
            .forRow((database, request) -> DeleteConfirmation.body(Microcopy.of(
                    DatabaseModel.isShared(database) ? "delete_confirm_shared" : "delete_confirm")
                .withFilter("scope", "database")
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
            .label(Microcopy.of("backup").withFilter("scope", "database"))
            .icon(Icon.of("download"))
            .route((database, request) -> HohenheimEndpoints.DATABASES_BACKUP
                .with(HohenheimEndpoints.DATABASE_NAME, database.get(DatabaseModel.NAME)));
        if (delegated) {
            link.shownWhen((database, access) -> HohenheimAccess.reachesRecord(access, DatabaseModel.MODEL_ID,
                database.get(DatabaseModel.ID), HohenheimAccess.BACKUPS));
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
                (request, result) -> CmsActionResult.refreshWithToast(Microcopy.of("move_started")
                    .withFilter("scope", "database")
                    .withArg("name", request.subject().get(DatabaseModel.NAME))))
            .inlineInRow(false)
            .inlineOnRecord(false)
            // The record-less fallback the framework requires beside a dynamic one.
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("move_shared").withFilter("scope", "database"))
                .body(Microcopy.of("move_shared_confirm_generic").withFilter("scope", "database"))
                .confirmLabel(Microcopy.of("move_shared_ok").withFilter("scope", "database"))
                .style(ActionStyle.PRIMARY)
                .build())
            .dynamicConfirmation(database -> ConfirmationSpec.builder()
                .title(Microcopy.of("move_shared").withFilter("scope", "database"))
                .body(Microcopy.of("move_shared_confirm").withFilter("scope", "database")
                    .withArg("name", database.get(DatabaseModel.NAME)))
                .confirmLabel(Microcopy.of("move_shared_ok").withFilter("scope", "database"))
                .style(ActionStyle.PRIMARY)
                .build())
            .build();
    }

    /**
     * Visible ONLY once a normal destroy already failed, typed-confirmed with the database's own name and recorded;
     * the container and volume may survive on the host, where the reconciler reports them as orphans.
     */
    private static @NonNull PanelAction<Row> forceDelete() {
        return PanelAction.<Row, Void>places(FORCE_DELETE, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(Microcopy.of("force_delete_done")
                    .withFilter("scope", "database")
                    .withArg("name", request.subject().get(DatabaseModel.NAME))))
            .style(ActionStyle.DESTRUCTIVE)
            .inlineInRow(false)
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("force_delete").withFilter("scope", "database"))
                .body(Microcopy.of("force_delete_confirm_generic").withFilter("scope", "database"))
                .confirmLabel(Microcopy.of("force_delete_ok").withFilter("scope", "database"))
                .style(ActionStyle.DESTRUCTIVE)
                .build())
            .dynamicConfirmation(database -> ConfirmationSpec.builder()
                .title(Microcopy.of("force_delete").withFilter("scope", "database"))
                .body(Microcopy.of("force_delete_confirm").withFilter("scope", "database")
                    .withArg("name", database.get(DatabaseModel.NAME)))
                .confirmLabel(Microcopy.of("force_delete_ok").withFilter("scope", "database"))
                .style(ActionStyle.DESTRUCTIVE)
                .requireTypedConfirmation(database.get(DatabaseModel.NAME))
                .build())
            .build();
    }

    /**
     * "Back up now" in the row and the record heading; the dump runs in the background and lands in the Backups card.
     */
    private static @NonNull PanelAction<Row> backUpNow() {
        return PanelAction.<Row, Void>places(BACK_UP_NOW, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(Microcopy.of("back_up_started")
                    .withFilter("scope", "database")
                    .withArg("name", request.subject().get(DatabaseModel.NAME))))
            .build();
    }

    /** @return why this database cannot be backed up now, or null when it can */
    static @Nullable Microcopy backUpUnavailable(@NonNull Row database) {
        if (Boolean.TRUE.equals(database.get(DatabaseModel.EPHEMERAL))) {
            return Microcopy.of("back_up_temporary").withFilter("scope", "database");
        }
        // A dump runs inside the engine serving it: the verdict every surface reads, never the stored "active".
        if (!DatabaseVerdict.ofDatabase(database).serves()) {
            return Microcopy.of("back_up_not_active").withFilter("scope", "database");
        }
        return null;
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
        List<Row> instances = CmsSupport.memo(request.conduit(), USED_BY,
                InstanceDatabaseLinks::liveInstancesByDatabase)
            .getOrDefault(database.get(DatabaseModel.ID), List.of());
        if (instances.isEmpty()) {
            return RecordLinksCell.none(DatabaseOverview.copy("used_by_none"));
        }
        boolean listed = AppDirectory.offers(request.panel(), InstanceParts.SLUG, request.access());
        List<RecordLink> links = new ArrayList<>();
        for (Row instance : instances) {
            boolean opens = listed && HohenheimAccess.reachesRecord(request.access(), InstanceModel.MODEL_ID,
                instance.get(InstanceModel.ID), HohenheimAccess.VIEW);
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
            String host = ServerModel.nameOf(ServerModel.canonicalServerId(engine.get(DatabaseEngineModel.SERVER_ID)));
            Microcopy holds = listCopy("engine_holds")
                .withArg("databases", HohenheimCounts.of("databases", counts.getOrDefault(id, 0L)))
                .withArg("state", Labels.inSentence(DatabaseVerdict.ofEngine(engine).state().label()));
            facts.add(WidgetFact.link(
                listCopy("engine_on_host").withArg("engine", CmsSupport.enumValueLabel(DatabaseEngineModel.ENGINE,
                        String.valueOf((Object) engine.get(DatabaseEngineModel.ENGINE)))).withArg("host", host)
                    .resolve(conduit.getLocales(), conduit.getMessageResolver()),
                holds.resolve(conduit.getLocales(), conduit.getMessageResolver()),
                CmsRoutes.open(panelSlug, ENGINES_SLUG, id).toUrl()));
        }
        WidgetInstance card = new WidgetInstance(CardWidget.ID,
            Map.of("title", listCopy("engines_title"), "lead", listCopy("engines_lead")),
            new WidgetTree(List.of(new WidgetInstance(FactListWidget.ID, Map.of()).withData(facts))));
        return new WidgetTree(List.of(AdminDashboard.section(card)));
    }

    /**
     * The newest stored dump as a word and a line: how long ago and how big, a warning when it is older than the
     * nightly backup allows, "Never" as a warning while a persistent database has none, and "Not backed up" for a
     * temporary one.
     */
    private static @NonNull StateLineCell lastBackupCell(@NonNull Row database, @NonNull PanelRequest request) {
        if (Boolean.TRUE.equals(database.get(DatabaseModel.EPHEMERAL))) {
            return new StateLineCell("temporary", BadgeVariant.OUTLINE,
                Microcopy.of("backup_temporary").withFilter("scope", "database_overview"),
                Microcopy.of("backup_temporary_detail").withFilter("scope", "database_overview"), null);
        }
        String name = database.get(DatabaseModel.NAME);
        DatabaseBackups.Stored newest = name == null ? null : DatabaseBackups.newest(name);
        if (newest == null) {
            return new StateLineCell("never", BadgeVariant.WARNING,
                Microcopy.of("backup_never").withFilter("scope", "database_overview"),
                Microcopy.of("backup_never_detail").withFilter("scope", "database_overview"), null);
        }
        Conduit conduit = request.conduit();
        String ago = RelativeTime.ago(newest.at(),
            RelativeTimeWording.resolve(conduit.getLocales(), conduit.getMessageResolver()));
        String size = ByteText.human(newest.bytes());
        if (newest.at().isBefore(Now.instant().minus(BACKUP_OVERDUE_AFTER))) {
            return new StateLineCell("overdue", BadgeVariant.WARNING, Microcopy.literal(ago),
                Microcopy.of("backup_overdue_detail").withFilter("scope", "database_overview").withArg("size", size),
                null);
        }
        return new StateLineCell("done", BadgeVariant.SUCCESS, Microcopy.literal(ago), Microcopy.literal(size), null);
    }

    /** The state column every database and engine list draws in place of the stored status. */
    private static @NonNull ColumnSpec stateColumn() {
        return ColumnSpec.virtual(STATE_COLUMN, listCopy("state_column"))
            .renderer(HohenheimTemplateIds.CELL_STATE_LINE).build();
    }

    private static @NonNull Microcopy listCopy(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "database_list");
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
                Microcopy.of("databases").withFilter("scope", "database_engine")).build())
            .column(ColumnSpec.fromField(DatabaseEngineModel.MEMORY_LIMIT_MB).build())
            .column(ColumnSpec.fromField(DatabaseEngineModel.STATUS).filterable().hidden().build())
            .filter(FilterSpec.leaf(DatabaseEngineModel.NAME, CoreTypes.CONTAINS)
                .label(FieldLabels.labelFor(DatabaseEngineModel.NAME)).build())
            .filter(FilterSpec.leaf(DatabaseEngineModel.ENGINE, CoreTypes.EQUALS)
                .label(FieldLabels.labelFor(DatabaseEngineModel.ENGINE)).build())
            .filter(FilterSpec.leaf(DatabaseEngineModel.STATUS, CoreTypes.EQUALS)
                .label(FieldLabels.labelFor(DatabaseEngineModel.STATUS)).build())
            .build();
        return PanelResource.builder(HohenheimIds.id("database_engine"), ENGINES_SLUG, ENGINE)
            .label(Microcopy.of("plural").withFilter("scope", "database_engine"))
            .recordLabel(Microcopy.of("singular").withFilter("scope", "database_engine"))
            .description(CmsSupport.navHint("database_engine"))
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
                    : Microcopy.of("resize_notice").withFilter("scope", "database_engine"))
                .build())
            .writes(ResourceMutations.rows()
                .create(DatabaseParts::provisionEngine)
                .update(call -> {
                    ProvisionedRecords.resize(Models.get(DatabaseEngineModel.class),
                        Objects.requireNonNull(call.record()), call.values(), ENGINE_CEILINGS,
                        DatabaseEngines::reserveRow, DatabaseEngines::redeployInBackground);
                    return null;
                })
                .delete(DELETE_ENGINE)
                .build())
            // Deleting an engine destroys its container AND the volume every database sat on.
            .deleteConfirmation(DeleteConfirmation.<Row>of(DeleteConfirmation.body(
                    Microcopy.of("delete_confirm").withFilter("scope", "database_engine")))
                .forRow((engine, request) -> DeleteConfirmation.body(Microcopy.of("delete_confirm")
                    .withFilter("scope", "database_engine")
                    .withArg("name", String.valueOf((Object) engine.get(DatabaseEngineModel.NAME))))))
            .actions(List.of(forceDeleteEngine()))
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
     * Persist the engine as {@code provisioning}, reserve its instance row INLINE (so a host without room refuses on
     * this form) and bring the container up after commit.
     *
     * AIDEV-NOTE: there is deliberately no refusal of a second engine of the same kind on one host: running two major
     * versions side by side is why an engine can be created by hand at all. The allocation funnel resolves the FIRST.
     */
    private static @NonNull Object provisionEngine(@NonNull RowWriteCall call) {
        Map<String, Object> values = call.values();
        String name = trimmed(values.get(DatabaseEngineModel.NAME.getName()));
        if (!name.matches("[a-z0-9][a-z0-9-]*")) {
            throw Violations.ofField(DatabaseEngineModel.NAME.getName(), name,
                CmsSupport.violationText("name_format"));
        }
        String engineToken = trimmed(values.get(DatabaseEngineModel.ENGINE.getName())).toLowerCase(Locale.ROOT);
        ManagedDatabase.Engine engine = ManagedDatabase.Engine.forToken(engineToken);
        if (engine == null) {
            throw Violations.ofField(DatabaseEngineModel.ENGINE.getName(), engineToken,
                CmsSupport.violationText("unknown_engine").withArg("engine", engineToken));
        }
        if (!engine.supportsLogicalDatabases()) {
            // The placement's refusal, on its key: an engine with no per-database namespace can only ever serve one
            // database, which is a DEDICATED record and not this tier.
            throw Violations.ofField(DatabaseEngineModel.ENGINE.getName(), engineToken,
                CmsSupport.violationText("database_placement_unsupported").withArg("engine", engineToken));
        }
        String rootUser = trimmed(values.get(DatabaseEngineModel.ROOT_USER.getName()));
        if (rootUser.isEmpty()) {
            rootUser = "root";
        }
        String rootPassword = trimmed(values.get(DatabaseEngineModel.ROOT_PASSWORD.getName()));
        if (rootPassword.isEmpty()) {
            rootPassword = Secrets.generatePassword();
        }
        String image = trimmed(values.get(DatabaseEngineModel.IMAGE.getName()));
        ResourceLimits limits = ResourceLimits.of(
            values.get(DatabaseEngineModel.MEMORY_LIMIT_MB.getName()) instanceof Integer mb ? mb : null,
            values.get(DatabaseEngineModel.CPU_LIMIT.getName()) instanceof Double cpus ? cpus : null);

        Model model = Models.get(DatabaseEngineModel.class);
        Row row = model.createEmptyRow();
        row.set(DatabaseEngineModel.NAME, name);
        row.set(DatabaseEngineModel.ENGINE, engine.token());
        row.set(DatabaseEngineModel.IMAGE, image.isEmpty() ? null : image);
        row.set(DatabaseEngineModel.SERVER_ID,
            values.get(DatabaseEngineModel.SERVER_ID.getName()) instanceof Integer serverId
                ? serverId : ServerModel.localServerId());
        row.set(DatabaseEngineModel.ROOT_USER, rootUser);
        row.set(DatabaseEngineModel.ROOT_PASSWORD, rootPassword);
        row.set(DatabaseEngineModel.MEMORY_LIMIT_MB, limits.memoryMb());
        row.set(DatabaseEngineModel.CPU_LIMIT, limits.cpus());
        row.set(DatabaseEngineModel.STATUS, DatabaseModel.STATUS_PROVISIONING);
        model.save(row);

        // Books the engine against the host budget through the instance write hook, and refuses here (never on a pool
        // thread minutes later) when it does not fit.
        DatabaseEngines.reserveRow(row, limits);

        Integer engineId = row.get(DatabaseEngineModel.ID);
        if (engineId != null) {
            model.getResolvedDatasource().afterCommit(() -> DatabaseEngines.provisionInBackground(engineId));
        }
        return engineId;
    }

    /**
     * An engine still hosting databases is offered DEAD, naming them; the handler refuses with the same facts.
     *
     * @return null when no record lives on it
     */
    static @Nullable Microcopy engineInUseReason(@NonNull Row engine) {
        Integer engineId = engine.get(DatabaseEngineModel.ID);
        List<Row> hosted = engineId == null ? List.of() : DatabaseEngines.databasesOn(engineId);
        if (hosted.isEmpty()) {
            return null;
        }
        return Microcopy.of("delete_in_use").withFilter("scope", "database_engine")
            .withArg("name", String.valueOf((Object) engine.get(DatabaseEngineModel.NAME)))
            .withArg("databases", DatabaseEngines.names(hosted));
    }

    /**
     * Verified teardown: the container and its data volume go, and the row with them.
     *
     * @throws Violations {@code database_engine_destroy_failed} when the teardown is unconfirmed; the record is kept
     *                    (status destroy_failed) and the force delete is the recorded way out
     */
    private static void destroyEngine(@NonNull Row engine) {
        Integer engineId = engine.get(DatabaseEngineModel.ID);
        if (engineId == null) {
            return;
        }
        try {
            DatabaseEngines.destroy(engineId, true);
        } catch (IOException e) {
            throw Violations.ofForm(CmsSupport.violationText("database_engine_destroy_failed")
                .withArg("name", String.valueOf((Object) engine.get(DatabaseEngineModel.NAME)))
                .withArg("reason", String.valueOf(e.getMessage())));
        }
    }

    /** The recorded escape hatch, visible ONLY once a normal destroy already failed, typed-confirmed and recorded. */
    private static @NonNull PanelAction<Row> forceDeleteEngine() {
        return PanelAction.<Row, Void>places(FORCE_DELETE_ENGINE, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(Microcopy.of("force_delete_done")
                    .withFilter("scope", "database_engine")
                    .withArg("name", request.subject().get(DatabaseEngineModel.NAME))))
            .style(ActionStyle.DESTRUCTIVE)
            .inlineInRow(false)
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("force_delete").withFilter("scope", "database_engine"))
                .body(Microcopy.of("force_delete_confirm_generic").withFilter("scope", "database_engine"))
                .confirmLabel(Microcopy.of("force_delete_ok").withFilter("scope", "database_engine"))
                .style(ActionStyle.DESTRUCTIVE)
                .build())
            .dynamicConfirmation(engine -> ConfirmationSpec.builder()
                .title(Microcopy.of("force_delete").withFilter("scope", "database_engine"))
                .body(Microcopy.of("force_delete_confirm").withFilter("scope", "database_engine")
                    .withArg("name", engine.get(DatabaseEngineModel.NAME)))
                .confirmLabel(Microcopy.of("force_delete_ok").withFilter("scope", "database_engine"))
                .style(ActionStyle.DESTRUCTIVE)
                .requireTypedConfirmation(engine.get(DatabaseEngineModel.NAME))
                .build())
            .build();
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
        Map<Integer, Long> counts = new HashMap<>();
        for (Row group : Models.get(DatabaseModel.class).find()
                .where(DatabaseModel.ENGINE_ID.isNotNull())
                .groupBy(DatabaseModel.ENGINE_ID)
                .aggregateAll(Aggregate.count().as("database_count"))) {
            Object engineId = group.get(DatabaseModel.ENGINE_ID.getName());
            Object counted = group.get("database_count");
            if (engineId instanceof Number id && counted instanceof Number number) {
                counts.put(id.intValue(), number.longValue());
            }
        }
        return counts;
    }

    private static @NonNull String trimmed(@Nullable Object value) {
        return ProvisionedRecords.trimmed(value);
    }
}
