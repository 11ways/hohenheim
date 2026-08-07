package be.elevenways.hohenheim.server.stack;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.runtime.DockerInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.Egress;
import be.elevenways.hohenheim.server.runtime.HealthCheck;
import be.elevenways.hohenheim.server.runtime.ImageOrigin;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.runtime.NetworkPosture;
import be.elevenways.hohenheim.server.runtime.PortPublication;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.server.util.EnvVars;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.DoubleField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.ListField;
import be.elevenways.zenit.common.orm.field.SchemaField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.StringMapField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public static final Identifier ID = Identifier.of("hohenheim", "stack_service");

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

    public static final StringField IMAGE = SETTINGS_SCHEMA.addField(
        StringField.builder().name("image").label(HohenheimFormCopy.label("image")).build());

    public static final ListField<String> COMMAND = SETTINGS_SCHEMA.addField(
        ListField.builder(StringField.builder().name("arg").build()).name("command")
            .label(HohenheimFormCopy.label("command")).build());

    // secret(): redacted on derived surfaces (revisions, activity), like every env map.
    public static final StringMapField ENVIRONMENT_VARIABLES = SETTINGS_SCHEMA.addField(
        StringMapField.builder("environment_variables")
            .label(HohenheimFormCopy.label("environment_variables")).secret().build());

    /** Materialized volume name to container path; the names are stack-scoped, not id-keyed. */
    public static final StringMapField VOLUMES = SETTINGS_SCHEMA.addField(
        StringMapField.builder("volumes").label(HohenheimFormCopy.label("volumes")).build());

    public static final ListField<String> TMPFS_PATHS = SETTINGS_SCHEMA.addField(
        ListField.builder(StringField.builder().name("path").build()).name("tmpfs_paths")
            .build());

    public static final Schema PORT_SCHEMA = new Schema();
    public static final IntegerField PORT_CONTAINER = PORT_SCHEMA.addField(
        IntegerField.builder().name("container_port")
            .label(HohenheimFormCopy.label("container_port")).build());
    public static final IntegerField PORT_HOST = PORT_SCHEMA.addField(
        IntegerField.builder().name("host_port")
            .label(HohenheimFormCopy.label("host_port")).build());
    public static final StringField PORT_PROTOCOL = PORT_SCHEMA.addField(
        StringField.builder().name("protocol").build());
    public static final StringField PORT_HOST_IP = PORT_SCHEMA.addField(
        StringField.builder().name("host_ip").label(HohenheimFormCopy.label("host_ip")).build());

    public static final SchemaField PORTS = SETTINGS_SCHEMA.addField(
        SchemaField.builder("ports").subSchema(PORT_SCHEMA).list()
            .label(HohenheimFormCopy.label("ports")).build());

    public static final ListField<String> CAPABILITIES = SETTINGS_SCHEMA.addField(
        ListField.builder(StringField.builder().name("capability").build()).name("capabilities")
            .label(HohenheimFormCopy.label("capabilities")).build());

    public static final StringField HEALTH_CMD = SETTINGS_SCHEMA.addField(
        StringField.builder().name("health_cmd")
            .label(HohenheimFormCopy.label("health_cmd")).build());
    public static final IntegerField HEALTH_INTERVAL_SECONDS = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("health_interval_seconds").defaultValue(10).suffix("s")
            .label(HohenheimFormCopy.label("health_interval")).build());
    public static final IntegerField HEALTH_TIMEOUT_SECONDS = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("health_timeout_seconds").defaultValue(5).suffix("s")
            .label(HohenheimFormCopy.label("health_timeout")).build());
    public static final IntegerField HEALTH_RETRIES = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("health_retries").defaultValue(5)
            .label(HohenheimFormCopy.label("health_retries")).build());
    public static final IntegerField HEALTH_START_PERIOD_SECONDS = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("health_start_period_seconds").defaultValue(0).suffix("s")
            .label(HohenheimFormCopy.label("health_start_period")).build());

    public static final IntegerField MEMORY_LIMIT_MB = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("memory_limit_mb")
            .label(HohenheimFormCopy.label("memory_limit")).build());
    public static final DoubleField CPU_LIMIT = SETTINGS_SCHEMA.addField(
        DoubleField.builder().name("cpu_limit")
            .label(HohenheimFormCopy.label("cpu_limit")).build());

    /** The link handle of the stack's shared network; the join happens between create and start. */
    public static final StringField STACK_NETWORK = SETTINGS_SCHEMA.addField(
        StringField.builder().name("stack_network").filterable(false).build());

    /** The compose service name -- this workload's DNS alias on the shared stack network. */
    public static final StringField SERVICE_NAME = SETTINGS_SCHEMA.addField(
        StringField.builder().name("service_name").filterable(false).build());

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
     * about authoring a declaration -- {@code StackServiceResource} (the form) and
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

    @Override
    public @NonNull Identifier typeId() { return ID; }

    @Override
    public @NonNull String getDisplayName() { return "Stack service"; }

    @Override
    public @NonNull Microcopy getLabel() {
        return Microcopy.of("stack_service").withFilter("scope", "instance_kind");
    }

    @Override
    public String getDescription() { return "One service of a managed stack (managed by its stack)"; }

    @Override
    public Icon getIcon() { return Icon.of("layer-group"); }

    @Override
    public String getColor() { return "purple"; }

    @Override
    public Schema getSchema() { return SETTINGS_SCHEMA; }

    @Override
    public boolean tenantAuthored() { return false; }

    /** Written exclusively by {@link StackInstances} inside the service's system scope. */
    @Override
    public boolean generatedOnly() { return true; }

    @Override
    public @NonNull InstanceRuntime runtimeFor(@NonNull String serverName) {
        return new DockerInstanceRuntime(new ServerService().clientFor(serverName),
            WorkloadNetworkPolicy.forServer(serverName), NetworkPosture.PRIVATE, EGRESS);
    }

    @Override
    public @NonNull InstanceSpec specFor(int instanceId, @NonNull Map<String, Object> settings) {
        String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, instanceId);
        String name = str(settings.get("service_name"));

        List<String> command = stringList(settings.get("command"));
        List<String> capabilities = stringList(settings.get("capabilities"));

        Map<String, String> volumes = new LinkedHashMap<>();
        EnvVars.toMap(settings.get("volumes")).forEach((volume, path) -> {
            if (path != null && !path.isBlank()) {
                volumes.put(volume, path);
            }
        });

        Map<String, Long> tmpfs = new LinkedHashMap<>();
        for (String path : stringList(settings.get("tmpfs_paths"))) {
            if (!path.isBlank()) {
                tmpfs.put(path, TMPFS_SIZE_BYTES);
            }
        }

        String healthCmd = str(settings.get("health_cmd"));
        HealthCheck health = healthCmd.isEmpty() ? null : new HealthCheck(healthCmd,
            intOr(settings.get("health_interval_seconds"), 10),
            intOr(settings.get("health_timeout_seconds"), 5),
            intOr(settings.get("health_retries"), 5),
            intOr(settings.get("health_start_period_seconds"), 0));

        return new InstanceSpec(handle, str(settings.get("image")),
            command.isEmpty() ? null : command,
            EnvVars.toMap(settings.get("environment_variables")), volumes,
            publicationsOf(settings, name),
            ResourceLimits.fromSettings(settings, defaultFootprintMb(settings)),
            resolvedHardening(name, capabilities),
            OwnerLabels.of(InstanceModel.MODEL_ID, instanceId),
            null, null, ImageOrigin.CATALOG, false, true, tmpfs, health, null);
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
        for (Object entry : listOf(settings.get("ports"))) {
            if (!(entry instanceof Map<?, ?> port)) {
                continue;
            }
            int containerPort = intOr(port.get("container_port"), 0);
            int hostPort = intOr(port.get("host_port"), 0);
            if (containerPort <= 0 || hostPort <= 0) {
                continue;
            }
            String hostIp = str(port.get("host_ip"));
            boolean publicExposure;
            if (hostIp.isEmpty() || "0.0.0.0".equals(hostIp) || "::".equals(hostIp)) {
                publicExposure = true;
            } else if ("127.0.0.1".equals(hostIp) || "localhost".equals(hostIp)) {
                publicExposure = false;
            } else {
                throw Violations.ofField("settings.ports", hostIp,
                    Microcopy.of("stack_port_bind_unsupported").withFilter("scope", "violations")
                        .withArg("service", service).withArg("address", hostIp));
            }
            String protocol = str(port.get("protocol"));
            publications.add(new PortPublication(containerPort,
                PortPublication.UDP.equals(protocol) ? PortPublication.UDP : PortPublication.TCP,
                publicExposure, hostPort, null));
        }
        return List.copyOf(publications);
    }

    private static @NonNull List<String> stringList(@Nullable Object value) {
        List<String> values = new ArrayList<>();
        for (Object entry : listOf(value)) {
            if (entry != null) {
                values.add(String.valueOf(entry));
            }
        }
        return values;
    }

    private static @NonNull List<?> listOf(@Nullable Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static int intOr(@Nullable Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
