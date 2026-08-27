package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;

import java.util.List;

/**
 * One service (container) of a managed stack. Mounts, port publications and
 * dependencies are table-stored record lists; the deployer resolves start order
 * from {@code depends_on} topologically. The container's DNS name on the stack
 * network is the service name.
 */
public class StackServiceModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "stack_service");
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
            .label(Microcopy.of(MOUNT_VOLUME).withFilter("scope", "stack_mount_type")).icon("hard-drive"))
        .value(MOUNT_TMPFS, v -> v.displayName("Tmpfs")
            .label(Microcopy.of(MOUNT_TMPFS).withFilter("scope", "stack_mount_type")).icon("memory"))
        .defaultValue(MOUNT_VOLUME)
        .build());
    public static final StringField MOUNT_NAME = MOUNT_SCHEMA.addField(StringField.builder().name("name")
        .label(HohenheimFormCopy.label("name"))
        .build());
    public static final StringField MOUNT_PATH = MOUNT_SCHEMA.addField(StringField.builder().name("container_path")
        .label(HohenheimFormCopy.label("container_path"))
        .build());
    public static final StringField MOUNT_EXTERNAL = MOUNT_SCHEMA.addField(StringField.builder().name("external_name")
        .label(HohenheimFormCopy.label("mount_external_name"))
        .build());

    // -- ports sub-schema ----------------------------------------------------

    public static final Schema PORT_SCHEMA = new Schema();
    public static final IntegerField PORT_CONTAINER = PORT_SCHEMA.addField(IntegerField.builder().name("container_port")
        .label(HohenheimFormCopy.label("container_port"))
        .build());
    public static final IntegerField PORT_HOST = PORT_SCHEMA.addField(IntegerField.builder().name("host_port")
        .label(HohenheimFormCopy.label("host_port"))
        .build());
    public static final EnumField PORT_PROTOCOL = PORT_SCHEMA.addField(EnumField.builder("protocol")
        .value("tcp", v -> v.displayName("TCP")
            .label(Microcopy.of("tcp").withFilter("scope", "port_protocol")))
        .value("udp", v -> v.displayName("UDP")
            .label(Microcopy.of("udp").withFilter("scope", "port_protocol")))
        .defaultValue("tcp")
        .build());
    public static final StringField PORT_HOST_IP = PORT_SCHEMA.addField(StringField.builder().name("host_ip")
        .label(HohenheimFormCopy.label("host_ip"))
        .build());

    // -- depends_on sub-schema -----------------------------------------------

    public static final Schema DEPENDS_SCHEMA = new Schema();
    public static final StringField DEPENDS_SERVICE = DEPENDS_SCHEMA.addField(StringField.builder().name("service")
        .label(HohenheimFormCopy.label("depends_service"))
        .build());
    public static final EnumField DEPENDS_CONDITION = DEPENDS_SCHEMA.addField(EnumField.builder("condition")
        .value(CONDITION_STARTED, v -> v.displayName("Started")
            .label(Microcopy.of(CONDITION_STARTED).withFilter("scope", "depends_condition")))
        .value(CONDITION_HEALTHY, v -> v.displayName("Healthy")
            .label(Microcopy.of(CONDITION_HEALTHY).withFilter("scope", "depends_condition")))
        .defaultValue(CONDITION_STARTED)
        .build());

    // -- fields ---------------------------------------------------------------

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    public static final IntegerField STACK_ID = SCHEMA.addField(IntegerField.builder().name("stack_id").build());

    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name")
        .required()
        .label(HohenheimFormCopy.label("name"))
        .help(HohenheimFormCopy.help("service_name"))
        .build());

    public static final BooleanField ENABLED = SCHEMA.addField(BooleanField.builder("enabled")
        .defaultValue(true)
        .label(HohenheimFormCopy.label("enabled"))
        .build());

    public static final StringField IMAGE = SCHEMA.addField(StringField.builder().name("image")
        .required()
        .label(HohenheimFormCopy.label("image"))
        .help(HohenheimFormCopy.help("service_image"))
        .build());

    public static final ListField<String> COMMAND = SCHEMA.addField(
        ListField.builder(StringField.builder().name("arg").build()).name("command")
            .label(HohenheimFormCopy.label("command"))
            .help(HohenheimFormCopy.help("service_command"))
            .build());

    // Secret AND encrypted: env maps routinely carry credentials, and unlike
    // SiteModel.environment_variables (inside a JSON SchemaField) this is a
    // main-table column, so the encrypted-envelope path is available.
    public static final StringMapField ENVIRONMENT = SCHEMA.addField(StringMapField.builder("environment")
        .secret()
        .encrypted()
        .label(HohenheimFormCopy.label("environment_variables"))
        .help(HohenheimFormCopy.help("service_environment"))
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
            .label(HohenheimFormCopy.label("capabilities"))
            .help(HohenheimFormCopy.help("service_capabilities"))
            .build());

    public static final SchemaField MOUNTS = SCHEMA.addField(
        SchemaField.builder("mounts").subSchema(MOUNT_SCHEMA).list()
            .label(HohenheimFormCopy.label("mounts"))
            .build());

    public static final SchemaField PORTS = SCHEMA.addField(
        SchemaField.builder("ports").subSchema(PORT_SCHEMA).list()
            .label(HohenheimFormCopy.label("ports"))
            .build());

    public static final SchemaField DEPENDS_ON = SCHEMA.addField(
        SchemaField.builder("depends_on").subSchema(DEPENDS_SCHEMA).list()
            .label(HohenheimFormCopy.label("depends_on"))
            .build());

    public static final StringField HEALTH_CMD = SCHEMA.addField(StringField.builder().name("health_cmd")
        .label(HohenheimFormCopy.label("health_cmd"))
        .help(HohenheimFormCopy.help("health_cmd"))
        .build());

    public static final IntegerField HEALTH_INTERVAL_SECONDS = SCHEMA.addField(
        IntegerField.builder().name("health_interval_seconds")
            .defaultValue(10)
            .suffix("s")
            .label(HohenheimFormCopy.label("health_interval"))
            .build());

    public static final IntegerField HEALTH_TIMEOUT_SECONDS = SCHEMA.addField(
        IntegerField.builder().name("health_timeout_seconds")
            .defaultValue(5)
            .suffix("s")
            .label(HohenheimFormCopy.label("health_timeout"))
            .build());

    public static final IntegerField HEALTH_RETRIES = SCHEMA.addField(
        IntegerField.builder().name("health_retries")
            .defaultValue(5)
            .label(HohenheimFormCopy.label("health_retries"))
            .build());

    public static final IntegerField HEALTH_START_PERIOD_SECONDS = SCHEMA.addField(
        IntegerField.builder().name("health_start_period_seconds")
            .defaultValue(0)
            .suffix("s")
            .label(HohenheimFormCopy.label("health_start_period"))
            .build());

    public static final EnumField RESTART_POLICY = SCHEMA.addField(EnumField.builder("restart_policy")
        .value("unless-stopped", v -> v.displayName("Unless stopped")
            .label(restartLabel("unless_stopped")))
        .value("always", v -> v.displayName("Always").label(restartLabel("always")))
        .value("on-failure", v -> v.displayName("On failure").label(restartLabel("on_failure")))
        .value("no", v -> v.displayName("Never").label(restartLabel("never")))
        .defaultValue("unless-stopped")
        .label(HohenheimFormCopy.label("restart_policy"))
        .build());

    /**
     * The translation token for a restart policy; the key is the stored Docker token with
     * its hyphen folded to an underscore, and {@code no} spelled out as {@code never}.
     */
    private static Microcopy restartLabel(String policy) {
        return Microcopy.of(policy).withFilter("scope", "restart_policy");
    }

    public static final IntegerField MEMORY_LIMIT_MB = SCHEMA.addField(IntegerField.builder().name("memory_limit_mb")
        .label(HohenheimFormCopy.label("memory_limit"))
        .help(HohenheimFormCopy.help("memory_limit"))
        .build());

    public static final DoubleField CPU_LIMIT = SCHEMA.addField(DoubleField.builder().name("cpu_limit")
        .label(HohenheimFormCopy.label("cpu_limit"))
        .help(HohenheimFormCopy.help("cpu_limit"))
        .build());

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
        // (getRow() is null) -- for delete(id), criteria deletes and deleteAll alike. The
        // doomed ids are therefore read in the BEFORE hook and released in the AFTER hook;
        // an afterRemove-only hook reading getRow() releases nothing. This stays so a
        // pre-lowering service's leftover claims are released when its row goes.
        SCHEMA.addBeforeRemoveHook(PortLedger::captureDoomedOwners);
        SCHEMA.addAfterRemoveHook(PortLedger::releaseDoomedOwners);
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
