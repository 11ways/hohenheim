package be.elevenways.hohenheim.server.database;

import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.instance.InstanceKindFields;
import be.elevenways.hohenheim.HohenheimFormSections;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.server.docker.ContainerSettings;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.runtime.DockerInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.Egress;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.runtime.PortPublication;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.DoubleField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.StringMapField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.BadgeColor;
import be.elevenways.zenit.common.ui.ColorHue;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static be.elevenways.hohenheim.RawValues.trimmed;

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

    public static final Identifier ID = HohenheimIds.id("database_container");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    /** Lowercase {@link ManagedDatabase.Engine} token; decides port, data path and hardening. */
    public static final StringField ENGINE = SETTINGS_SCHEMA.addField(
        StringField.builder().name("engine").label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("engine")).build());

    public static final StringField IMAGE = SETTINGS_SCHEMA.addField(InstanceKindFields.image());

    /**
     * The named volume the engine's data directory mounts, or blank for an EPHEMERAL
     * database whose data directory is a RAM-backed tmpfs instead (tests, CI, previews).
     */
    public static final StringField DATA_VOLUME = SETTINGS_SCHEMA.addField(
        StringField.builder().name("data_volume").filterable(false).build());

    public static final BooleanField EPHEMERAL = SETTINGS_SCHEMA.addField(
        BooleanField.builder("ephemeral").defaultValue(false)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("ephemeral")).build());

    /**
     * Whether the container is a SHARED engine (a {@code DatabaseEngineModel} row's)
     * hosting many logical databases, which books the engine's shared footprint
     * ({@link ManagedDatabase.Engine#sharedFootprintMb()}) rather than one database's.
     */
    public static final BooleanField SHARED = SETTINGS_SCHEMA.addField(
        BooleanField.builder("shared").defaultValue(false)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("placement")).build());

    public static final StringField COMMAND = SETTINGS_SCHEMA.addField(
        StringField.builder().name("command")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("container_command")).build());

    // secret(): redacted on derived surfaces (revisions, activity), like every env map.
    // The engine's environment never lives here -- passwords, root user and init database
    // all ride the instance-variable secret lane (encrypted column) and merge into the env
    // at deploy; see DatabaseInstances. Rows an older controller wrote are sealed at boot.
    public static final StringMapField ENVIRONMENT_VARIABLES = SETTINGS_SCHEMA.addField(
        InstanceKindFields.environmentVariables());

    public static final IntegerField MEMORY_LIMIT_MB = SETTINGS_SCHEMA.addField(InstanceKindFields.memoryLimit());

    public static final DoubleField CPU_LIMIT = SETTINGS_SCHEMA.addField(InstanceKindFields.cpuLimit());

    /** Size cap for an ephemeral (tmpfs) data mount: 1 GiB -- generous for tests and small
     *  preview databases, while bounding RAM use (tmpfs only consumes RAM for live data). */
    public static final long EPHEMERAL_DATA_SIZE_BYTES = 1024L * 1024 * 1024;

    // Which engine, which image and whether the data survives a restart are the decisions;
    // the rest is what DatabaseService writes for you.
    static {
        SETTINGS_SCHEMA.addSection(HohenheimFormSections.collapsed(HohenheimFormSections.RUNTIME,
            List.of(DATA_VOLUME.getName(), SHARED.getName(), COMMAND.getName(),
                ENVIRONMENT_VARIABLES.getName(), MEMORY_LIMIT_MB.getName(), CPU_LIMIT.getName())));
    }

    @Override
    public @NonNull Identifier typeId() { return ID; }

    @Override
    public @NonNull String getDisplayName() { return "Database container"; }

    @Override
    public Icon getIcon() { return Icon.of("database"); }

    @Override
    public BadgeColor color() { return ColorHue.BLUE; }

    @Override
    public Schema getSchema() { return SETTINGS_SCHEMA; }

    @Override
    public boolean tenantAuthored() { return false; }

    /** Written exclusively by {@link DatabaseInstances} inside the database's system scope. */
    @Override
    public boolean generatedOnly() { return true; }

    @Override
    public @NonNull InstanceRuntime runtimeFor(@NonNull String serverName) {
        return DockerInstanceRuntime.onServer(serverName, Egress.NONE);
    }

    @Override
    public @NonNull InstanceSpec specFor(int instanceId, @NonNull Map<String, Object> settings) {
        ManagedDatabase.Engine engine = engineOf(settings);

        String image = trimmed(settings.get(IMAGE.getName()));
        if (image.isEmpty()) {
            image = engine.defaultImage;
        }

        Map<String, String> volumes = new LinkedHashMap<>();
        Map<String, Long> tmpfs = new LinkedHashMap<>();
        if (isEphemeral(settings)) {
            tmpfs.put(engine.dataPath, EPHEMERAL_DATA_SIZE_BYTES);
        } else {
            volumes.put(trimmed(settings.get("data_volume")), engine.dataPath);
        }

        // Loopback/tcp/no-fixed-port: the record-after shape. Host processes dial the
        // published port; a Docker site joins the link network and dials engine.port.
        PortPublication publication =
            new PortPublication(engine.port, PortPublication.TCP, false, null, null);

        return ContainerSettings.spec(instanceId, image, settings, defaultFootprintMb(settings), engine.hardening())
            .volumes(volumes)
            .publication(publication)
            .tmpfs(tmpfs)
            .build();
    }

    /**
     * A database engine's admitted memory, PER ENGINE
     * ({@link ManagedDatabase.Engine#footprintMb(boolean)}, which carries the measurements and
     * the rule behind them) -- charge == cap, so this is also the cgroup ceiling the
     * engine actually gets when the operator declares no {@code memory_limit_mb}.
     *
     * AIDEV-NOTE: this deliberately does NOT go through {@link #engineOf}, which throws
     * Violations on an unknown token. {@code InstanceCapacity} calls this from a write
     * hook where a throw would refuse the write outright, so an unrecognised engine books
     * the LARGEST declared footprint instead: over-booking costs a little host budget,
     * under-booking hands a workload a cgroup ceiling below its own startup peak.
     *
     * AIDEV-NOTE: the PERSISTENCE SHAPE is read through {@link #isEphemeral}, the same
     * predicate {@link #specFor} mounts the data directory by -- one declaring home, so
     * the number booked can never describe a shape the container does not run in.
     */
    @Override
    public int defaultFootprintMb(@NonNull Map<String, Object> settings) {
        try {
            ManagedDatabase.Engine engine = engineOf(settings);
            if (RawValues.isOn(settings, SHARED) && engine.supportsLogicalDatabases()) {
                return engine.sharedFootprintMb();
            }
            return engine.footprintMb(isEphemeral(settings));
        } catch (Violations unknownEngine) {
            return ManagedDatabase.Engine.maxFootprintMb();
        }
    }

    /**
     * THE persistence decision for a database container: its data directory is a tmpfs
     * (charged to the container's own cgroup) rather than a named volume.
     *
     * A record declaring no {@code data_volume} is ephemeral whatever the flag says --
     * there is nothing to mount -- which is why this is one predicate and not two reads.
     */
    static boolean isEphemeral(@NonNull Map<String, Object> settings) {
        return RawValues.isOn(settings, EPHEMERAL)
            || trimmed(settings.get(DATA_VOLUME.getName())).isEmpty();
    }

    /**
     * @throws Violations naming the engine when the settings carry an unknown token --
     *         never a silent default, which would run the WRONG image on the wrong port
     */
    static ManagedDatabase.@NonNull Engine engineOf(@NonNull Map<String, Object> settings) {
        String token = trimmed(settings.get("engine"));
        ManagedDatabase.Engine engine = ManagedDatabase.Engine.forToken(token);
        if (engine == null) {
            throw Violations.ofField("settings.engine", token,
                HohenheimMicrocopy.VIOLATIONS.of("database_engine_unknown")
                    .withArg("engine", token));
        }
        return engine;
    }
}
