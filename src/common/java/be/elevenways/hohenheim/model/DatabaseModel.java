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

    public static final EnumField ENGINE = SCHEMA.addField(EnumField.builder("engine")
        .value(ENGINE_POSTGRES, v -> v.displayName("PostgreSQL")
            .label(engineLabel(ENGINE_POSTGRES)).icon("database").color("blue"))
        .value(ENGINE_MYSQL, v -> v.displayName("MySQL")
            .label(engineLabel(ENGINE_MYSQL)).icon("database").color("orange"))
        .value(ENGINE_REDIS, v -> v.displayName("Redis")
            .label(engineLabel(ENGINE_REDIS)).icon("bolt").color("red"))
        .value(ENGINE_MONGO, v -> v.displayName("MongoDB")
            .label(engineLabel(ENGINE_MONGO)).icon("leaf").color("green"))
        .label(HohenheimFormCopy.label("engine"))
        .help(HohenheimFormCopy.help("engine"))
        .build());

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
    public static final EnumField STATUS = SCHEMA.addField(EnumField.builder("status")
        .value(STATUS_PROVISIONING, v -> v.displayName("Provisioning")
            .label(statusLabel(STATUS_PROVISIONING)).icon("rotate").color("warning"))
        .value(STATUS_ACTIVE, v -> v.displayName("Active")
            .label(statusLabel(STATUS_ACTIVE)).icon("circle-check").color("success"))
        .value(STATUS_FAILED, v -> v.displayName("Failed")
            .label(statusLabel(STATUS_FAILED)).icon("circle-xmark").color("destructive"))
        .value(STATUS_DESTROY_FAILED, v -> v.displayName("Destroy failed")
            .label(statusLabel(STATUS_DESTROY_FAILED)).icon("triangle-exclamation").color("destructive"))
        .build());

    /** The translation token for a status; the key IS the stored value. */
    private static Microcopy statusLabel(String status) {
        return Microcopy.of(status).withFilter("scope", "database_status");
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
