package be.elevenways.hohenheim.server.stack;

import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.instance.InstanceKindFields;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.zenit.common.coerce.PrimitiveCoercion;
import be.elevenways.hohenheim.HohenheimFormSections;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.runtime.DockerInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.Egress;
import be.elevenways.hohenheim.server.runtime.HealthCheck;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.runtime.PortPublication;
import be.elevenways.hohenheim.server.util.EnvVars;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.DoubleField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.ListField;
import be.elevenways.zenit.common.orm.field.SchemaField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.StringMapField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.BadgeColor;
import be.elevenways.zenit.common.ui.ColorHue;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static be.elevenways.hohenheim.RawValues.trimmed;

/**
 * The instance kind ONE service of a managed stack lowers onto: an operator-authored
 * container on its own private network, additionally joined to the stack's shared LINK
 * network under its service name (the compose DNS alias). The STACK and STACK SERVICE
 * records keep the product half -- compose-shaped authoring, dependency graph, deployment
 * history and rollback snapshots -- and OWN this instance through the GeneratedRows
 * attribution, so {@link #generatedOnly()} makes a standalone create of this kind a named
 * refusal.
 *
 * Two DECLARED workload-shape differences from a tenant instance, neither settings
 * reachable: {@link #tenantAuthored()} is false (stacks are the operator tier, like sites
 * and databases), and the egress posture is {@link Egress#OPEN} because a stack service is
 * the ordinary published-image mix that legitimately talks outbound at entrypoint. The
 * tenant-range denies still apply -- metadata, host and other tenants stay unreachable.
 *
 * AIDEV-NOTE: the per-service capability declaration ({@code StackServiceModel.CAPABILITIES})
 * rides {@link #specFor} through {@link ContainerHardening#declaring}, which refuses
 * anything outside the closed allow-list at the create funnel. What no declaration can
 * move: drop-ALL as the base, no-new-privileges, the pids cap, the structural refusals and
 * this workload's own network policy.
 */
public final class StackServiceKind implements InstanceKindHandler {

    public static final Identifier ID = HohenheimIds.id("stack_service");

    /**
     * The BASELINE isolation profile every stack service container starts from.
     *
     * AIDEV-NOTE: stacks are OPERATOR-authored (the compose-shaped tier), and their
     * services are the ordinary published-image mix -- nginx, postgres, redis -- all of
     * which chown and drop privileges at entrypoint and all of which refuse to start under
     * STRICT. It is a baseline rather than the whole answer: a service whose image
     * genuinely needs one more capability DECLARES it and {@link #hardeningFor} folds it in.
     */
    public static final ContainerHardening.Profile HARDENING = ContainerHardening.SERVICE;

    /**
     * The DECLARED egress posture of every stack service and of the shared stack network.
     *
     * AIDEV-NOTE: OPEN, decided 2026-08-06 and unchanged by the lowering. A stack is
     * operator-authored compose-shaped content whose services legitimately open outbound
     * connections (package installs at entrypoint, upstream APIs, webhooks); blanket NONE
     * would break those, and the managed-database precedent for NONE (an engine has no
     * legitimate outbound traffic) does not hold here.
     */
    public static final Egress EGRESS = Egress.OPEN;

    /** Size cap for a service's tmpfs mount; compose declares no size and neither did we. */
    public static final long TMPFS_SIZE_BYTES = 256L * 1024 * 1024;

    public static final Schema SETTINGS_SCHEMA = new Schema();

    public static final StringField IMAGE = SETTINGS_SCHEMA.addField(InstanceKindFields.image());

    public static final ListField<String> COMMAND = SETTINGS_SCHEMA.addField(
        ListField.builder(StringField.builder().name("arg").build()).name("command")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("command")).build());

    // secret(): redacted on derived surfaces (revisions, activity), like every env map.
    public static final StringMapField ENVIRONMENT_VARIABLES = SETTINGS_SCHEMA.addField(
        InstanceKindFields.environmentVariables());

    /** Materialized volume name to container path; the names are stack-scoped, not id-keyed. */
    public static final StringMapField VOLUMES = SETTINGS_SCHEMA.addField(
        StringMapField.builder("volumes").label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("volumes")).build());

    public static final ListField<String> TMPFS_PATHS = SETTINGS_SCHEMA.addField(
        ListField.builder(StringField.builder().name("path").build()).name("tmpfs_paths")
            .build());

    /**
     * One {@code ports} entry. AIDEV-NOTE: the names are the SERVICE record's port columns,
     * because the entries are written by {@code StackSpec.PortSpec.toMap} -- one shape for
     * the snapshot and this setting, read back here by these same fields.
     */
    public static final Schema PORT_SCHEMA = new Schema();
    public static final IntegerField PORT_CONTAINER = PORT_SCHEMA.addField(
        IntegerField.builder().name(StackServiceModel.PORT_CONTAINER.getName())
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("container_port")).build());
    public static final IntegerField PORT_HOST = PORT_SCHEMA.addField(
        IntegerField.builder().name(StackServiceModel.PORT_HOST.getName())
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("host_port")).build());
    public static final StringField PORT_PROTOCOL = PORT_SCHEMA.addField(
        StringField.builder().name(StackServiceModel.PORT_PROTOCOL.getName()).build());
    public static final StringField PORT_HOST_IP = PORT_SCHEMA.addField(
        StringField.builder().name(StackServiceModel.PORT_HOST_IP.getName())
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("host_ip")).build());

    public static final SchemaField PORTS = SETTINGS_SCHEMA.addField(
        SchemaField.builder("ports").subSchema(PORT_SCHEMA).list()
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("ports")).build());

    public static final ListField<String> CAPABILITIES = SETTINGS_SCHEMA.addField(
        ListField.builder(StringField.builder().name("capability").build()).name("capabilities")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("capabilities")).build());

    public static final StringField HEALTH_CMD = SETTINGS_SCHEMA.addField(
        StringField.builder().name("health_cmd")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("health_cmd")).build());
    public static final IntegerField HEALTH_INTERVAL_SECONDS = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("health_interval_seconds").defaultValue(10).suffix("s")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("health_interval")).build());
    public static final IntegerField HEALTH_TIMEOUT_SECONDS = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("health_timeout_seconds").defaultValue(5).suffix("s")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("health_timeout")).build());
    public static final IntegerField HEALTH_RETRIES = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("health_retries").defaultValue(5)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("health_retries")).build());
    public static final IntegerField HEALTH_START_PERIOD_SECONDS = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("health_start_period_seconds").defaultValue(0).suffix("s")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("health_start_period")).build());

    public static final IntegerField MEMORY_LIMIT_MB = SETTINGS_SCHEMA.addField(InstanceKindFields.memoryLimit());
    public static final DoubleField CPU_LIMIT = SETTINGS_SCHEMA.addField(InstanceKindFields.cpuLimit());

    /** The link handle of the stack's shared network; the join happens between create and start. */
    public static final StringField STACK_NETWORK = SETTINGS_SCHEMA.addField(
        StringField.builder().name("stack_network").filterable(false).build());

    /** The compose service name -- this workload's DNS alias on the shared stack network. */
    public static final StringField SERVICE_NAME = SETTINGS_SCHEMA.addField(
        StringField.builder().name("service_name").filterable(false).build());

    /** The owning stack record's id; the shared network's owner labels name it. */
    public static final IntegerField STACK_ID = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("stack_id").filterable(false).build());

    /**
     * A service's admitted memory when it declares no {@code memory_limit_mb}. Charge ==
     * cap, so this is also the cgroup ceiling. 512 MB is the site-container number: a
     * stack service is the same shape of workload (an ordinary published image), and the
     * pre-lowering tier booked NOTHING at all, so any honest number is an improvement.
     */
    @Override
    public int defaultFootprintMb(@NonNull Map<String, Object> settings) {
        return 512;
    }

    /**
     * The profile ONE service runs with: the tier baseline plus whatever its author
     * declared, REFUSED by name if that is not declarable.
     *
     * @throws IllegalArgumentException naming the capability and why it is not declarable
     */
    public static ContainerHardening.@NonNull Profile hardeningFor(@NonNull String name,
                                                                   @NonNull List<String> capabilities) {
        return ContainerHardening.declaring(HARDENING, "service " + name, capabilities);
    }

    /**
     * The profile a RESOLVED spec carries: {@link #hardeningFor}, degrading to the bare
     * tier baseline when the stored declaration is not satisfiable.
     *
     * AIDEV-NOTE: found 2026-08-07 by ContainerHardeningTest's refusal journey. specFor
     * runs on EVERY resolve -- stop, status and DESTROY included -- so a throw here made a
     * service carrying an illegal capability row UNDELETABLE, the same trap the stack
     * teardown path already refuses to fall into for nftables. Degrading is safe because
     * it can only ever NARROW: the container is created from this profile, so the worst
     * case is the baseline. The REFUSAL is not lost, it moved to the two places that are
     * about authoring a declaration -- {@code StackParts.services()} (the form) and
     * {@link StackInstances#deploy} (the runtime funnel) -- both calling
     * {@link #hardeningFor}, so there is one definition and no second copy of the rule.
     */
    private static ContainerHardening.@NonNull Profile resolvedHardening(@NonNull String name,
                                                                         @NonNull List<String> capabilities) {
        try {
            return hardeningFor(name, capabilities);
        } catch (IllegalArgumentException notDeclarable) {
            Blast.log("STACK: service", name,
                "declares a capability that is not declarable; resolving it with the bare"
                    + " tier baseline so it stays stoppable and deletable -", 
                notDeclarable.getMessage());
            return HARDENING;
        }
    }

    // Every field here is written from the stack's compose file, so this form is a
    // READING surface far more often than an editing one: image, command, environment,
    // volumes and ports answer "what is this service", and the probe, the ceilings and
    // the two handles the stack owns fold away behind headers that say so.
    static {
        SETTINGS_SCHEMA.addSection(HohenheimFormSections.collapsed(HohenheimFormSections.HEALTH,
            List.of(HEALTH_CMD.getName(), HEALTH_INTERVAL_SECONDS.getName(),
                HEALTH_TIMEOUT_SECONDS.getName(), HEALTH_RETRIES.getName(),
                HEALTH_START_PERIOD_SECONDS.getName())));
        SETTINGS_SCHEMA.addSection(HohenheimFormSections.collapsed(HohenheimFormSections.RUNTIME,
            List.of(TMPFS_PATHS.getName(), CAPABILITIES.getName(),
                MEMORY_LIMIT_MB.getName(), CPU_LIMIT.getName())));
        SETTINGS_SCHEMA.addSection(HohenheimFormSections.collapsed(HohenheimFormSections.MANAGED,
            List.of(STACK_NETWORK.getName(), SERVICE_NAME.getName(), STACK_ID.getName())));
    }

    @Override
    public @NonNull Identifier typeId() { return ID; }

    @Override
    public @NonNull String getDisplayName() { return "Stack service"; }

    @Override
    public Icon getIcon() { return Icon.of("layer-group"); }

    @Override
    public BadgeColor color() { return ColorHue.PURPLE; }

    @Override
    public Schema getSchema() { return SETTINGS_SCHEMA; }

    @Override
    public boolean tenantAuthored() { return false; }

    /** Written exclusively by {@link StackInstances} inside the service's system scope. */
    @Override
    public boolean generatedOnly() { return true; }

    @Override
    public @NonNull InstanceRuntime runtimeFor(@NonNull String serverName) {
        return DockerInstanceRuntime.onServer(serverName, EGRESS);
    }

    @Override
    public @NonNull InstanceSpec specFor(int instanceId, @NonNull Map<String, Object> settings) {
        String name = trimmed(settings.get(SERVICE_NAME.getName()));

        List<String> command = PrimitiveCoercion.toTextList(settings.get(COMMAND.getName()));
        List<String> capabilities = PrimitiveCoercion.toTextList(settings.get(CAPABILITIES.getName()));

        Map<String, String> volumes = new LinkedHashMap<>();
        EnvVars.toMap(settings.get(VOLUMES.getName())).forEach((volume, path) -> {
            if (path != null && !path.isBlank()) {
                volumes.put(volume, path);
            }
        });

        Map<String, Long> tmpfs = new LinkedHashMap<>();
        for (String path : PrimitiveCoercion.toTextList(settings.get(TMPFS_PATHS.getName()))) {
            if (!path.isBlank()) {
                tmpfs.put(path, TMPFS_SIZE_BYTES);
            }
        }

        String healthCmd = trimmed(settings.get(HEALTH_CMD.getName()));
        HealthCheck health = healthCmd.isEmpty() ? null : new HealthCheck(healthCmd,
            RawValues.intOr(settings.get(HEALTH_INTERVAL_SECONDS.getName()), 10),
            RawValues.intOr(settings.get(HEALTH_TIMEOUT_SECONDS.getName()), 5),
            RawValues.intOr(settings.get(HEALTH_RETRIES.getName()), 5),
            RawValues.intOr(settings.get(HEALTH_START_PERIOD_SECONDS.getName()), 0));

        // The environment arrives through the RESOLVED settings: InstanceService.resolve
        // folds the instance's secret variable rows (where the stack's env now lives) into
        // this key, on top of whatever a pre-upgrade row still carries in plaintext.
        return InstanceSpec.forInstance(instanceId, trimmed(settings.get(IMAGE.getName())), settings,
                defaultFootprintMb(settings), resolvedHardening(name, capabilities))
            .command(command.isEmpty() ? null : command)
            .env(EnvVars.toMap(settings.get(ENVIRONMENT_VARIABLES.getName())))
            .volumes(volumes)
            .publications(publicationsOf(settings, name))
            .tmpfs(tmpfs)
            .healthCheck(health)
            .build();
    }

    /**
     * The service's declared host-port publications. Every stack port is an
     * operator-FIXED host port, so every one of them rides the pre-allocation strategy
     * and holds a real ledger claim before its container exists -- which the pre-lowering
     * tier's own {@code syncStackService} claim could not guarantee, because it was
     * written when the row was SAVED and never checked against the daemon.
     *
     * @throws Violations naming the bind address when it is neither the whole host nor
     *         loopback: {@link PortPublication} expresses exactly those two exposures, and
     *         accepting a third spelling would publish somewhere nothing declared
     */
    private static @NonNull List<PortPublication> publicationsOf(@NonNull Map<String, Object> settings,
                                                                 @NonNull String service) {
        List<PortPublication> publications = new ArrayList<>();
        for (Object entry : RawValues.list(settings.get(PORTS.getName()))) {
            if (!(entry instanceof Map<?, ?> port)) {
                continue;
            }
            int containerPort = RawValues.intOr(port.get(PORT_CONTAINER.getName()), 0);
            int hostPort = RawValues.intOr(port.get(PORT_HOST.getName()), 0);
            if (containerPort <= 0 || hostPort <= 0) {
                continue;
            }
            String hostIp = trimmed(port.get(PORT_HOST_IP.getName()));
            boolean publicExposure;
            if (hostIp.isEmpty() || "0.0.0.0".equals(hostIp) || "::".equals(hostIp)) {
                publicExposure = true;
            } else if ("127.0.0.1".equals(hostIp) || "localhost".equals(hostIp)) {
                publicExposure = false;
            } else {
                throw Violations.ofField("settings.ports", hostIp,
                    HohenheimMicrocopy.VIOLATIONS.of("stack_port_bind_unsupported")
                        .withArg("service", service).withArg("address", hostIp));
            }
            String protocol = trimmed(port.get(PORT_PROTOCOL.getName()));
            publications.add(new PortPublication(containerPort,
                PortPublication.UDP.equals(protocol) ? PortPublication.UDP : PortPublication.TCP,
                publicExposure, hostPort, null));
        }
        return List.copyOf(publications);
    }
}
