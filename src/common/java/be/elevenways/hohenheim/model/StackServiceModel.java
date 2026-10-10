package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.instance.InstanceKindFields;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.model.relation.BelongsTo;

import java.util.List;

/**
 * One service (container) of a managed stack. Mounts, port publications and
 * dependencies are table-stored record lists; the deployer resolves start order
 * from {@code depends_on} topologically. The container's DNS name on the stack
 * network is the service name.
 */
public class StackServiceModel extends Model {

    public static final Identifier MODEL_ID = HohenheimIds.id("stack_service");
    public static final Schema SCHEMA = new Schema();

    /** {@code depends_on} condition: the dependency's container is running. */
    public static final String CONDITION_STARTED = "started";

    /** {@code depends_on} condition: the dependency reports a healthy healthcheck. */
    public static final String CONDITION_HEALTHY = "healthy";

    /** Mount kinds: a named (stack-scoped) volume or an in-memory tmpfs. */
    public static final String MOUNT_VOLUME = "volume";
    public static final String MOUNT_TMPFS = "tmpfs";

    // -- mounts sub-schema ---------------------------------------------------

    public static final Schema MOUNT_SCHEMA = new Schema();
    public static final EnumField MOUNT_TYPE = MOUNT_SCHEMA.addField(EnumField.builder("type")
        .value(MOUNT_VOLUME, v -> v.displayName("Volume")
            .label(HohenheimMicrocopy.STACK_MOUNT_TYPE.of(MOUNT_VOLUME)).icon("hard-drive"))
        .value(MOUNT_TMPFS, v -> v.displayName("Tmpfs")
            .label(HohenheimMicrocopy.STACK_MOUNT_TYPE.of(MOUNT_TMPFS)).icon("memory"))
        .defaultValue(MOUNT_VOLUME)
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("mount_type"))
        .build());
    public static final StringField MOUNT_NAME = MOUNT_SCHEMA.addField(StringField.builder().name("name")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("name"))
        .build());
    public static final StringField MOUNT_PATH = MOUNT_SCHEMA.addField(StringField.builder().name("container_path")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("container_path"))
        .build());
    public static final StringField MOUNT_EXTERNAL = MOUNT_SCHEMA.addField(StringField.builder().name("external_name")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("mount_external_name"))
        .build());

    // -- ports sub-schema ----------------------------------------------------

    public static final Schema PORT_SCHEMA = new Schema();
    public static final IntegerField PORT_CONTAINER = PORT_SCHEMA.addField(IntegerField.builder().name("container_port")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("container_port"))
        .build());
    public static final IntegerField PORT_HOST = PORT_SCHEMA.addField(IntegerField.builder().name("host_port")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("host_port"))
        .build());
    public static final EnumField PORT_PROTOCOL = PORT_SCHEMA.addField(EnumField.builder("protocol")
        .value("tcp", v -> v.displayName("TCP")
            .label(HohenheimMicrocopy.PORT_PROTOCOL.of("tcp")))
        .value("udp", v -> v.displayName("UDP")
            .label(HohenheimMicrocopy.PORT_PROTOCOL.of("udp")))
        .defaultValue("tcp")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("protocol"))
        .build());
    public static final StringField PORT_HOST_IP = PORT_SCHEMA.addField(StringField.builder().name("host_ip")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("host_ip"))
        .build());

    // -- depends_on sub-schema -----------------------------------------------

    public static final Schema DEPENDS_SCHEMA = new Schema();
    public static final StringField DEPENDS_SERVICE = DEPENDS_SCHEMA.addField(StringField.builder().name("service")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("depends_service"))
        .build());
    public static final EnumField DEPENDS_CONDITION = DEPENDS_SCHEMA.addField(EnumField.builder("condition")
        .value(CONDITION_STARTED, v -> v.displayName("Started")
            .label(HohenheimMicrocopy.DEPENDS_CONDITION.of(CONDITION_STARTED)))
        .value(CONDITION_HEALTHY, v -> v.displayName("Healthy")
            .label(HohenheimMicrocopy.DEPENDS_CONDITION.of(CONDITION_HEALTHY)))
        .defaultValue(CONDITION_STARTED)
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("depends_condition"))
        .build());

    // -- fields ---------------------------------------------------------------

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    public static final IntegerField STACK_ID = SCHEMA.addField(IntegerField.builder().name("stack_id")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("stack")).build());

    /** The owning stack, declared so a stack delete takes its services in one statement (StackCascades). */
    public static final BelongsTo<StackModel> STACK = SCHEMA.addRelation(
        BelongsTo.to(StackModel.class)
            .name("stack")
            .localKey(STACK_ID)
            .remoteKey(StackModel.ID)
            .build());

    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name")
        .required()
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("name"))
        .help(HohenheimMicrocopy.HELP.of("service_name"))
        .build());

    public static final BooleanField ENABLED = SCHEMA.addField(BooleanField.builder("enabled")
        .defaultValue(true)
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("enabled"))
        .build());

    public static final StringField IMAGE = SCHEMA.addField(StringField.builder().name("image")
        .required()
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("image"))
        .help(HohenheimMicrocopy.HELP.of("service_image"))
        .build());

    public static final ListField<String> COMMAND = SCHEMA.addField(
        ListField.builder(StringField.builder().name("arg").build()).name("command")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("command"))
            .help(HohenheimMicrocopy.HELP.of("service_command"))
            .build());

    // Secret AND encrypted: env maps routinely carry credentials, and unlike
    // SiteModel.environment_variables (inside a JSON SchemaField) this is a
    // main-table column, so the encrypted-envelope path is available.
    public static final StringMapField ENVIRONMENT = SCHEMA.addField(StringMapField.builder("environment")
        .secret()
        .encrypted()
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("environment_variables"))
        .help(HohenheimMicrocopy.HELP.of("service_environment"))
        .build());

    /**
     * Linux capabilities this service's IMAGE needs on top of the tier's baseline.
     *
     * AIDEV-NOTE: an IMAGE-SHAPE declaration, not a trust grant, and the stored value is
     * not the authority on what is legal -- {@code ContainerHardening.declaring} validates
     * every name against the closed allow-list at the create funnel, so a row written
     * before a name left the list (or by anything that bypasses this resource) refuses at
     * DEPLOY rather than running with a capability nobody would grant today. The resource
     * validates the same way so the operator sees it in the form instead of in a
     * deployment log.
     */
    public static final ListField<String> CAPABILITIES = SCHEMA.addField(
        ListField.builder(StringField.builder().name("capability").build()).name("capabilities")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("capabilities"))
            .help(HohenheimMicrocopy.HELP.of("service_capabilities"))
            .build());

    public static final SchemaField MOUNTS = SCHEMA.addField(
        SchemaField.builder("mounts").subSchema(MOUNT_SCHEMA).list()
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("mounts"))
            .build());

    public static final SchemaField PORTS = SCHEMA.addField(
        SchemaField.builder("ports").subSchema(PORT_SCHEMA).list()
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("ports"))
            .build());

    public static final SchemaField DEPENDS_ON = SCHEMA.addField(
        SchemaField.builder("depends_on").subSchema(DEPENDS_SCHEMA).list()
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("depends_on"))
            .build());

    public static final StringField HEALTH_CMD = SCHEMA.addField(StringField.builder().name("health_cmd")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("health_cmd"))
        .help(HohenheimMicrocopy.HELP.of("health_cmd"))
        .build());

    public static final IntegerField HEALTH_INTERVAL_SECONDS = SCHEMA.addField(
        IntegerField.builder().name("health_interval_seconds")
            .defaultValue(10)
            .suffix("s")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("health_interval"))
            .build());

    public static final IntegerField HEALTH_TIMEOUT_SECONDS = SCHEMA.addField(
        IntegerField.builder().name("health_timeout_seconds")
            .defaultValue(5)
            .suffix("s")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("health_timeout"))
            .build());

    public static final IntegerField HEALTH_RETRIES = SCHEMA.addField(
        IntegerField.builder().name("health_retries")
            .defaultValue(5)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("health_retries"))
            .build());

    public static final IntegerField HEALTH_START_PERIOD_SECONDS = SCHEMA.addField(
        IntegerField.builder().name("health_start_period_seconds")
            .defaultValue(0)
            .suffix("s")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("health_start_period"))
            .build());

    public static final EnumField RESTART_POLICY = SCHEMA.addField(EnumField.builder("restart_policy")
        .value("unless-stopped", v -> v.displayName("Unless stopped")
            .label(HohenheimMicrocopy.RESTART_POLICY.of("unless_stopped")))
        .value("always", v -> v.displayName("Always").label(HohenheimMicrocopy.RESTART_POLICY.of("always")))
        .value("on-failure", v -> v.displayName("On failure").label(HohenheimMicrocopy.RESTART_POLICY.of("on_failure")))
        .value("no", v -> v.displayName("Never").label(HohenheimMicrocopy.RESTART_POLICY.of("never")))
        .defaultValue("unless-stopped")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("restart_policy"))
        .build());

    public static final IntegerField MEMORY_LIMIT_MB = SCHEMA.addField(InstanceKindFields.memoryLimit());

    public static final DoubleField CPU_LIMIT = SCHEMA.addField(InstanceKindFields.cpuLimit());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    static {
        // AIDEV-NOTE: a service's declared host ports are claimed by the DEPLOY, not by
        // the save -- since the stack tier lowered onto the instance runtime contract the
        // claim belongs to the service's owned INSTANCE (claim-before-create, verified
        // against the daemon's own binding afterwards, parked on a `releasing` transition).
        // The save-time diff-sync that used to live here was a SECOND owner for the same
        // physical port, written with no daemon evidence at all. The resource keeps its
        // friendly pre-write ledger READ; the ledger's unique index is still the arbiter,
        // one layer down at deploy.
        //
        // Remove hooks fire ONCE for the whole delete with a CRITERIA-only context
        // (getRow() is null) -- for delete(id), criteria deletes and deleteAll alike, which
        // PortLedger.parkClaimsOnRemove handles. This stays so a pre-lowering service's
        // leftover claims are released when its row goes.
        PortLedger.parkClaimsOnRemove(SCHEMA);
    }

    /** All services of a stack, declaration order by id. */
    public List<Row> findByStackId(int stackId) {
        return find().where(STACK_ID.eq(stackId)).orderBy(ID, be.elevenways.zenit.common.orm.query.SortOrder.ASC).all();
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "StackService"; }

    @Override
    public String getTableName() { return "stack_services"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
