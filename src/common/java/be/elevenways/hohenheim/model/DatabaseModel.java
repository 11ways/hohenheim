package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.model.relation.BelongsTo;

import java.util.Map;

/**
 * A managed database provisioned as a container by ManagedDatabase. Stores the desired
 * configuration (engine, image, credentials, initial database); runtime state (published
 * port, container id) is read from Docker on demand, not persisted.
 *
 * AIDEV-NOTE: the ENGINE tokens declared here are the vocabulary's one home. The server's
 * {@code ManagedDatabase.Engine} (which this common class cannot see) carries each token
 * as its own constant and resolves through {@code Engine.forToken}; a drift test binds the
 * two sets, so adding an engine on one side alone fails the build.
 */
public class DatabaseModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "database");
    public static final Schema SCHEMA = new Schema();

    /** {@link #STATUS} value while the container is being provisioned. */
    public static final String STATUS_PROVISIONING = "provisioning";

    /** {@link #STATUS} value for a provisioned, usable database. */
    public static final String STATUS_ACTIVE = "active";

    /** {@link #STATUS} value when provisioning failed. */
    public static final String STATUS_FAILED = "failed";

    /**
     * {@link #STATUS} value when a destroy could not verify its teardown: the record (and
     * the only copy of {@code db_password}) is deliberately KEPT, the container may still
     * run, and the operator retries or force-destroys explicitly.
     */
    public static final String STATUS_DESTROY_FAILED = "destroy_failed";

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name")
        .label(HohenheimFormCopy.label("name"))
        .help(HohenheimFormCopy.help("database_name"))
        .build());
    /** {@link #ENGINE} token of the PostgreSQL engine. */
    public static final String ENGINE_POSTGRES = "postgres";

    /** {@link #ENGINE} token of the MySQL engine. */
    public static final String ENGINE_MYSQL = "mysql";

    /** {@link #ENGINE} token of the Redis engine. */
    public static final String ENGINE_REDIS = "redis";

    /** {@link #ENGINE} token of the MongoDB engine. */
    public static final String ENGINE_MONGO = "mongo";

    public static final EnumField ENGINE = SCHEMA.addField(engineFieldBuilder("engine")
        .label(HohenheimFormCopy.label("engine"))
        .help(HohenheimFormCopy.help("engine"))
        .build());

    /**
     * The schema-field builder carrying the engine vocabulary, so a second column storing
     * an engine token (a template's declared database) can never drift from this one.
     * Callers finish it with their own label/help, never with more values.
     */
    public static EnumField.Builder engineFieldBuilder(String name) {
        return EnumField.builder(name)
            .value(ENGINE_POSTGRES, v -> v.displayName("PostgreSQL")
                .label(engineLabel(ENGINE_POSTGRES)).icon("database").color("blue"))
            .value(ENGINE_MYSQL, v -> v.displayName("MySQL")
                .label(engineLabel(ENGINE_MYSQL)).icon("database").color("orange"))
            .value(ENGINE_REDIS, v -> v.displayName("Redis")
                .label(engineLabel(ENGINE_REDIS)).icon("bolt").color("red"))
            .value(ENGINE_MONGO, v -> v.displayName("MongoDB")
                .label(engineLabel(ENGINE_MONGO)).icon("leaf").color("green"));
    }

    /** The translation token for a database engine; the key IS the stored value. */
    private static Microcopy engineLabel(String engine) {
        return Microcopy.of(engine).withFilter("scope", "db_engine");
    }

    public static final StringField IMAGE = SCHEMA.addField(StringField.builder().name("image")
        .label(HohenheimFormCopy.label("image"))
        .help(HohenheimFormCopy.help("image"))
        .build());
    public static final StringField DB_USER = SCHEMA.addField(StringField.builder().name("db_user")
        .label(HohenheimFormCopy.label("db_user"))
        .help(HohenheimFormCopy.help("db_user"))
        .build());
    public static final StringField DB_PASSWORD = SCHEMA.addField(StringField.builder().name("db_password")
        .secret()
        .encrypted()
        .label(HohenheimFormCopy.label("db_password"))
        .help(HohenheimFormCopy.help("db_password"))
        .build());
    public static final StringField DB_NAME = SCHEMA.addField(StringField.builder().name("db_name")
        .label(HohenheimFormCopy.label("db_name"))
        .help(HohenheimFormCopy.help("db_name"))
        .build());
    public static final BooleanField EPHEMERAL = SCHEMA.addField(BooleanField.builder("ephemeral")
        .defaultValue(false)
        .label(HohenheimFormCopy.label("ephemeral"))
        .help(HohenheimFormCopy.help("ephemeral"))
        .build());
    // Optional container resource caps (null = unlimited).
    public static final IntegerField MEMORY_LIMIT_MB = SCHEMA.addField(IntegerField.builder().name("memory_limit_mb")
        .label(HohenheimFormCopy.label("memory_limit"))
        .help(HohenheimFormCopy.help("memory_limit"))
        .build());
    public static final DoubleField CPU_LIMIT = SCHEMA.addField(DoubleField.builder().name("cpu_limit")
        .label(HohenheimFormCopy.label("cpu_limit"))
        .help(HohenheimFormCopy.help("cpu_limit"))
        .build());
    /**
     * The provisioning state, with the badge facets every surface renders it through.
     *
     * AIDEV-NOTE: this field is THE home of the status vocabulary. The site-databases tab
     * used to switch on the raw strings and knew only three of the four -- destroy_failed
     * (the one state carrying an operator obligation) rendered as a neutral grey pill with
     * no icon, exactly where an operator looks for it. Every surface now derives an
     * EnumBadgeState from here; nothing re-spells the colours.
     */
    public static final EnumField STATUS = SCHEMA.addField(statusFieldBuilder("status").build());

    /**
     * The schema-field builder carrying the provisioning-status vocabulary, so a second
     * record with the same lifecycle (a shared {@link DatabaseEngineModel}) reads and
     * renders it from this one home.
     */
    public static EnumField.Builder statusFieldBuilder(String name) {
        return EnumField.builder(name)
            .value(STATUS_PROVISIONING, v -> v.displayName("Provisioning")
                .label(statusLabel(STATUS_PROVISIONING)).icon("rotate").color("warning"))
            .value(STATUS_ACTIVE, v -> v.displayName("Active")
                .label(statusLabel(STATUS_ACTIVE)).icon("circle-check").color("success"))
            .value(STATUS_FAILED, v -> v.displayName("Failed")
                .label(statusLabel(STATUS_FAILED)).icon("circle-xmark").color("destructive"))
            .value(STATUS_DESTROY_FAILED, v -> v.displayName("Destroy failed")
                .label(statusLabel(STATUS_DESTROY_FAILED)).icon("triangle-exclamation")
                .color("destructive"));
    }

    /** The translation token for a status; the key IS the stored value. */
    private static Microcopy statusLabel(String status) {
        return Microcopy.of(status).withFilter("scope", "database_status");
    }

    /** {@link #outcomeOf} value: work is still in flight, so a watcher keeps waiting. */
    public static final String OUTCOME_PENDING = "pending";

    /** {@link #outcomeOf} value: the record settled usable. */
    public static final String OUTCOME_OK = "ok";

    /** {@link #outcomeOf} value: the record settled on a failure carrying a reason. */
    public static final String OUTCOME_FAILED = "failed";

    /**
     * THE watcher's fact about a status, so nothing outside this class ever spells a list
     * of "in flight" statuses: every declared {@link #STATUS} value maps here, and
     * {@link #declaresOutcome} is what a drift test asks so a fifth status cannot land
     * without a decision.
     *
     * An UNRECOGNISED status is {@link #OUTCOME_PENDING}, which is the fail-closed answer:
     * a poller keeps waiting and eventually times out naming what it saw, rather than
     * reporting a success or a failure the vocabulary never declared.
     */
    private static final Map<String, String> STATUS_OUTCOMES = Map.of(
        STATUS_PROVISIONING, OUTCOME_PENDING,
        STATUS_ACTIVE, OUTCOME_OK,
        STATUS_FAILED, OUTCOME_FAILED,
        STATUS_DESTROY_FAILED, OUTCOME_FAILED);

    /** How a status lands for something waiting on it: pending, ok or failed. */
    public static String outcomeOf(String status) {
        String outcome = status == null ? null : STATUS_OUTCOMES.get(status);
        return outcome == null ? OUTCOME_PENDING : outcome;
    }

    /** Whether the vocabulary DECLARES an outcome for this status (the drift test's question). */
    public static boolean declaresOutcome(String status) {
        return status != null && STATUS_OUTCOMES.containsKey(status);
    }

    /** {@link #PLACEMENT} value: the record's engine is its own container. */
    public static final String PLACEMENT_DEDICATED = "dedicated";

    /** {@link #PLACEMENT} value: the record is a logical database on a shared engine. */
    public static final String PLACEMENT_SHARED = "shared";

    /**
     * Where the database lives: its own engine container, or a logical database on a
     * host-shared engine ({@link #ENGINE_ID}). THE placement vocabulary's one home; every
     * server-side branch reads {@link #isShared(Row)}.
     *
     * AIDEV-NOTE: nullable, and null reads as DEDICATED on purpose: every record written
     * before 2026-09-02 owns its own container, and the migration that added this column
     * must not have to rewrite them to say so. New records always carry an explicit value
     * (the before-validate hook below defaults it), so null is a fact about age, never a
     * third placement.
     */
    public static final EnumField PLACEMENT = SCHEMA.addField(EnumField.builder("placement")
        .value(PLACEMENT_DEDICATED, v -> v.displayName("Dedicated")
            .label(placementLabel(PLACEMENT_DEDICATED)).icon("box").color("secondary"))
        .value(PLACEMENT_SHARED, v -> v.displayName("Shared")
            .label(placementLabel(PLACEMENT_SHARED)).icon("layer-group").color("blue"))
        .label(HohenheimFormCopy.label("placement"))
        .help(HohenheimFormCopy.help("database_placement"))
        .build());

    /** The translation token for a placement; the key IS the stored value. */
    private static Microcopy placementLabel(String placement) {
        return Microcopy.of(placement).withFilter("scope", "database_placement");
    }

    /**
     * The shared engine ({@code database_engines.id}) a SHARED record is a logical
     * database on; null for a dedicated record. The binding, never the placement fact:
     * a row is shared because {@link #PLACEMENT} says so, and the hook below keeps the
     * two from disagreeing.
     */
    public static final IntegerField ENGINE_ID = SCHEMA.addField(IntegerField.builder()
        .name("engine_id")
        .label(HohenheimFormCopy.label("database_engine"))
        .help(HohenheimFormCopy.help("database_engine"))
        .build());

    /** The shared engine relation, declared so an engine delete can correlate its dependents. */
    public static final BelongsTo<DatabaseEngineModel> DATABASE_ENGINE = SCHEMA.addRelation(
        BelongsTo.to(DatabaseEngineModel.class)
            .name("database_engine")
            .localKey(ENGINE_ID)
            .remoteKey(DatabaseEngineModel.ID)
            .build());

    /** Whether this record is a logical database on a shared engine. */
    public static boolean isShared(Row row) {
        return PLACEMENT_SHARED.equals(row.get(PLACEMENT));
    }

    /**
     * WHY the last provision or destroy failed, verbatim from the daemon; set beside a
     * {@code failed}/{@code destroy_failed} status and cleared by a successful outcome.
     */
    public static final TextField FAILURE_REASON = SCHEMA.addField(TextField.builder()
        .name("failure_reason")
        .label(HohenheimFormCopy.label("failure_reason"))
        .help(HohenheimFormCopy.help("database_failure_reason"))
        .filterable(false)
        .build());
    /** The host this database's container runs on: a {@code servers.id} FK, never a name. */
    public static final IntegerField SERVER_ID = SCHEMA.addField(IntegerField.builder().name("server_id")
        .label(HohenheimFormCopy.label("server"))
        .help(HohenheimFormCopy.help("server"))
        .build());
    /**
     * The owner database-count bucket this record's reservation was charged to, stamped by
     * DatabaseQuota at the write that took it; bookkeeping only, never an ownership
     * authority (the InstanceModel.QUOTA_BUCKET note applies unchanged).
     */
    public static final StringField QUOTA_BUCKET = SCHEMA.addField(
        StringField.builder().name("quota_bucket").filterable(false).build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    /**
     * Whether a name is one Docker will accept as an object name -- which is also, and not
     * by coincidence, exactly one filesystem path SEGMENT.
     *
     * AIDEV-NOTE: this column had no validator at all while BackupDatabases resolves it
     * straight onto the backup root ({@code backupRoot.resolve(db.name())}), and
     * {@code Path.resolve} with a {@code ../} argument walks OUT of the root -- so the dump
     * and the retention prune (which deletes every file past the newest N in that
     * directory) both landed wherever the name pointed. The rule is Docker's own
     * {@code [a-zA-Z0-9][a-zA-Z0-9_.-]*}: no separator can occur, so no traversal can be
     * spelled, and the name stays usable as a container handle.
     *
     * AIDEV-NOTE: SUPERSEDED 2026-08-08, second half. The two earlier notes both rested on
     * "admin-reachable only" -- that premise is GONE: ManageDatabaseResource lets a
     * delegated tenant allocate, so this validator is now a tenant boundary and not merely
     * a containment fix. Nothing about the RULE had to change (it already refuses every
     * separator, so no traversal can be spelled by anyone), but two things around it did.
     *
     * First, the name a tenant submits is a LABEL, not the stored name:
     * {@code TenantDatabases.storedNameFor} prefixes it per OWNER, because the stored name
     * is simultaneously the container handle (ControllerScope.KIND_DB), the data volume
     * ({@code DatabaseInstances.dataVolumeOf}) and the backup directory, and it is unique
     * installation-wide -- so an un-namespaced tenant name would deny that label to every
     * other tenant forever and turn the create form into an oracle over their names. The
     * label passes through this validator BEFORE the prefix is added, and the prefix
     * itself is legal here by construction ({@code u<id>-} or {@code o<8 hex>-}).
     *
     * Second, the INSERT-only create (also 2026-08-08) is what stops a create from
     * SEIZING a taken name: it used to converge onto the existing record and remount that
     * record's data volume under attacker-chosen credentials. The refusal is on the stored
     * name verbatim, so it now fires only within one owner's own namespace.
     */
    public static boolean isValidName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char character = name.charAt(i);
            boolean alphanumeric = (character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z')
                || (character >= '0' && character <= '9');
            if (!alphanumeric && (i == 0 || (character != '_' && character != '.' && character != '-'))) {
                return false;
            }
        }
        return true;
    }

    /** Docker's object-name ceiling, and generous for a directory name. */
    private static final int MAX_NAME_LENGTH = 64;

    static {
        // A row written before M009 carries no placement; it READS as dedicated -- the
        // migration's own rule, applied once at load so no surface (list cell, detail
        // form, filter) ever shows a third, nameless placement.
        SCHEMA.addAfterFindHook(found -> {
            for (Row row : found.getRows()) {
                if (row.get(PLACEMENT) == null) {
                    row.set(PLACEMENT, PLACEMENT_DEDICATED);
                }
            }
        });
        SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row == null || !row.has(NAME.getName())) {
                return;
            }
            Object name = row.get(NAME.getName());
            if (name != null && !isValidName(String.valueOf(name))) {
                throw Violations.ofField(NAME.getName(), name,
                    Microcopy.of("database_name_invalid").withFilter("scope", "violations"));
            }
        });
        // A managed database always has a concrete host; default the FK at create time
        // so no consumer ever re-invents a "blank means local" spelling.
        SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row != null && row.get(ID) == null && row.get(SERVER_ID) == null) {
                row.set(SERVER_ID, ServerModel.localServerId());
            }
        });
        // A new record without a placement is dedicated EXPLICITLY, so only rows older
        // than the column ever carry null. The placement/engine invariant itself lives in
        // the server's DatabaseEngineGuards, installed AFTER TenantWrites: a tenant's
        // frozen-field refusal must win over the invariant's, and hook order is
        // registration order.
        SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row != null && row.get(ID) == null && row.get(PLACEMENT) == null) {
                row.set(PLACEMENT, PLACEMENT_DEDICATED);
            }
        });
        // A database's published host port is recorded in the ledger after the container
        // hands it out (DatabaseService), so the record's death must release it. Via the
        // before/after pairing because a remove context carries CRITERIA, not a row --
        // see PortLedger.captureDoomedOwners. Without this, deleting the record through
        // ANY path other than DatabaseService.destroy leaves the port unclaimable forever.
        SCHEMA.addBeforeRemoveHook(PortLedger::captureDoomedOwners);
        SCHEMA.addAfterRemoveHook(PortLedger::releaseDoomedOwners);
    }

    /** The database with this unique name, or null if none. */
    public Row findByName(String name) {
        return find().where(NAME.eq(name)).first();
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "Database"; }

    @Override
    public String getTableName() { return "managed_databases"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
