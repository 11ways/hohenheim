package be.elevenways.hohenheim.server.database;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.runtime.DockerInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.Egress;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.runtime.NetworkPosture;
import be.elevenways.hohenheim.server.runtime.PortPublication;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.server.util.EnvVars;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.DoubleField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.StringMapField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The instance kind a MANAGED DATABASE's engine container lowers onto: an
 * operator-authored, egress-denied engine reachable on a loopback published port. The
 * DATABASE record keeps the product half (name, credentials, backups, site attachments)
 * and OWNS this instance through the GeneratedRows attribution -- it is written
 * exclusively by {@link DatabaseInstances} inside the database's system scope, and
 * {@link #generatedOnly()} makes a standalone create of this kind a named refusal.
 *
 * Three DECLARED workload-shape differences, none reachable from any settings form:
 * {@link #tenantAuthored()} is false (databases are an operator tier, like site
 * containers), the driver declares {@link Egress#NONE} (an engine has no legitimate
 * outbound-initiated traffic, so an exfiltrating one finds the door closed), and the
 * hardening profile comes from the ENGINE's own measured declaration
 * ({@link ManagedDatabase.Engine#hardening()}) rather than a tier constant.
 *
 * AIDEV-NOTE: the persistent data volume is keyed to the DATABASE RECORD'S NAME
 * ({@code hohenheim-{token}-db-{name}-data}), not to the instance id, and that is
 * deliberate: the instance row is the RUNTIME and the volume is the DATA, which outlives
 * any particular runtime row. It is also what makes the lowering non-destructive -- a
 * pre-lowering database's existing volume is the one this kind mounts, so the engine
 * comes back up on the same bytes and no migration of tenant data exists to get wrong.
 */
public final class DatabaseContainerKind implements InstanceKindHandler {

    public static final Identifier ID = Identifier.of("hohenheim", "database_container");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    /** Lowercase {@link ManagedDatabase.Engine} token; decides port, data path and hardening. */
    public static final StringField ENGINE = SETTINGS_SCHEMA.addField(
        StringField.builder().name("engine").label(HohenheimFormCopy.label("engine")).build());

    public static final StringField IMAGE = SETTINGS_SCHEMA.addField(
        StringField.builder().name("image").label(HohenheimFormCopy.label("image"))
            .help(HohenheimFormCopy.help("image")).build());

    /**
     * The named volume the engine's data directory mounts, or blank for an EPHEMERAL
     * database whose data directory is a RAM-backed tmpfs instead (tests, CI, previews).
     */
    public static final StringField DATA_VOLUME = SETTINGS_SCHEMA.addField(
        StringField.builder().name("data_volume").filterable(false).build());

    public static final BooleanField EPHEMERAL = SETTINGS_SCHEMA.addField(
        BooleanField.builder("ephemeral").defaultValue(false)
            .label(HohenheimFormCopy.label("ephemeral")).build());

    public static final StringField COMMAND = SETTINGS_SCHEMA.addField(
        StringField.builder().name("command")
            .label(HohenheimFormCopy.label("container_command")).build());

    // secret(): redacted on derived surfaces (revisions, activity), like every env map.
    // The engine PASSWORDS never live here -- they ride the instance-variable secret lane
    // (encrypted column) and merge into the env at deploy; see DatabaseInstances.
    public static final StringMapField ENVIRONMENT_VARIABLES = SETTINGS_SCHEMA.addField(
        StringMapField.builder("environment_variables")
            .label(HohenheimFormCopy.label("environment_variables")).secret().build());

    public static final IntegerField MEMORY_LIMIT_MB = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("memory_limit_mb")
            .label(HohenheimFormCopy.label("memory_limit")).build());

    public static final DoubleField CPU_LIMIT = SETTINGS_SCHEMA.addField(
        DoubleField.builder().name("cpu_limit")
            .label(HohenheimFormCopy.label("cpu_limit")).build());

    /** Size cap for an ephemeral (tmpfs) data mount: 1 GiB -- generous for tests and small
     *  preview databases, while bounding RAM use (tmpfs only consumes RAM for live data). */
    public static final long EPHEMERAL_DATA_SIZE_BYTES = 1024L * 1024 * 1024;

    @Override
    public @NonNull Identifier typeId() { return ID; }

    @Override
    public @NonNull String getDisplayName() { return "Database container"; }

    @Override
    public @NonNull Microcopy getLabel() {
        return Microcopy.of("database_container").withFilter("scope", "instance_kind");
    }

    @Override
    public String getDescription() { return "A managed database's engine (managed by its database)"; }

    @Override
    public Icon getIcon() { return Icon.of("database"); }

    @Override
    public String getColor() { return "blue"; }

    @Override
    public Schema getSchema() { return SETTINGS_SCHEMA; }

    @Override
    public boolean tenantAuthored() { return false; }

    /** Written exclusively by {@link DatabaseInstances} inside the database's system scope. */
    @Override
    public boolean generatedOnly() { return true; }

    @Override
    public @NonNull InstanceRuntime runtimeFor(@NonNull String serverName) {
        return new DockerInstanceRuntime(new ServerService().clientFor(serverName),
            WorkloadNetworkPolicy.forServer(serverName), NetworkPosture.PRIVATE, Egress.NONE);
    }

    @Override
    public @NonNull InstanceSpec specFor(int instanceId, @NonNull Map<String, Object> settings) {
        ManagedDatabase.Engine engine = engineOf(settings);
        String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, instanceId);

        String image = str(settings.get("image"));
        if (image.isEmpty()) {
            image = engine.defaultImage;
        }

        String command = str(settings.get("command"));
        List<String> cmd = command.isEmpty() ? null : List.of(command.split("\\s+"));

        boolean ephemeral = Boolean.TRUE.equals(settings.get("ephemeral"));
        String dataVolume = str(settings.get("data_volume"));
        Map<String, String> volumes = new LinkedHashMap<>();
        Map<String, Long> tmpfs = new LinkedHashMap<>();
        if (ephemeral || dataVolume.isEmpty()) {
            tmpfs.put(engine.dataPath, EPHEMERAL_DATA_SIZE_BYTES);
        } else {
            volumes.put(dataVolume, engine.dataPath);
        }

        // Loopback/tcp/no-fixed-port: the record-after shape. Host processes dial the
        // published port; a Docker site joins the link network and dials engine.port.
        PortPublication publication =
            new PortPublication(engine.port, PortPublication.TCP, false, null, null);

        return InstanceSpec.builder(handle, image,
                ResourceLimits.fromSettings(settings, defaultFootprintMb(settings)),
                engine.hardening(), OwnerLabels.of(InstanceModel.MODEL_ID, instanceId))
            .command(cmd)
            .env(EnvVars.toMap(settings.get("environment_variables")))
            .volumes(volumes)
            .publication(publication)
            .tmpfs(tmpfs)
            .build();
    }

    /**
     * A database engine's admitted memory, PER ENGINE
     * ({@link ManagedDatabase.Engine#footprintMb()}, which carries the measurements and
     * the rule behind them) -- charge == cap, so this is also the cgroup ceiling the
     * engine actually gets when the operator declares no {@code memory_limit_mb}.
     *
     * AIDEV-NOTE: this deliberately does NOT go through {@link #engineOf}, which throws
     * Violations on an unknown token. {@code InstanceCapacity} calls this from a write
     * hook where a throw would refuse the write outright, so an unrecognised engine books
     * the LARGEST declared footprint instead: over-booking costs a little host budget,
     * under-booking hands a workload a cgroup ceiling below its own startup peak.
     */
    @Override
    public int defaultFootprintMb(@NonNull Map<String, Object> settings) {
        try {
            return engineOf(settings).footprintMb();
        } catch (Violations unknownEngine) {
            return ManagedDatabase.Engine.maxFootprintMb();
        }
    }

    /**
     * @throws Violations naming the engine when the settings carry an unknown token --
     *         never a silent default, which would run the WRONG image on the wrong port
     */
    static ManagedDatabase.@NonNull Engine engineOf(@NonNull Map<String, Object> settings) {
        String token = str(settings.get("engine"));
        ManagedDatabase.Engine engine = ManagedDatabase.Engine.forToken(token);
        if (engine == null) {
            throw Violations.ofField("settings.engine", token,
                Microcopy.of("database_engine_unknown").withFilter("scope", "violations")
                    .withArg("engine", token));
        }
        return engine;
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
