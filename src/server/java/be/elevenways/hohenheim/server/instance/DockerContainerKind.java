package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.runtime.DockerInstanceRuntime;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.runtime.PortPublication;
import be.elevenways.hohenheim.server.util.EnvVars;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.DoubleField;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.StringMapField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The first instance kind: a Docker container on an inventoried host. The kind key is
 * deliberately {@code docker_container}, not {@code docker} -- kind is the ONE
 * discriminator, so it must leave room for {@code system_container} and {@code vm}
 * without a second runtime field.
 */
public final class DockerContainerKind implements InstanceKindHandler {

    public static final Identifier ID = Identifier.of("hohenheim", "docker_container");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    /**
     * The DECLARED isolation profile of this kind: drop ALL, add back the five
     * capabilities an ordinary published image needs to chown its data directory and
     * drop to a service user.
     *
     * AIDEV-NOTE: a capability profile is an IMAGE-SHAPE declaration, not a trust
     * declaration -- read it that way or the next reader will conclude we decided
     * tenants are trusted. This kind runs generic tenant-supplied images and it declares
     * the same profile as stacks, managed databases and Docker sites for exactly one
     * reason: ordinary container images are all built the same way. Measured 2026-08-03:
     * postgres:17-alpine as an instance under STRICT dies at entrypoint with
     * "chmod: /var/lib/postgresql/data: Operation not permitted" / "error: failed
     * switching to 'postgres': operation not permitted", which is the archetypal
     * game-server image shape this tier exists to run. The trust-independent floor is
     * unchanged and is where the tenant-vs-operator boundary actually lives: drop-ALL as
     * the base, no-new-privileges, the pids cap, and the structural refusals in
     * {@link ContainerHardening#PERMITTED_KEYS} (Privileged, host namespaces, host bind
     * mounts, devices, sysctls, UsernsMode). The rest of that boundary -- per-tenant
     * network policy -- is NOT BUILT; do not read this constant as a substitute for it.
     * A future kind whose image shape is genuinely narrower declares its own profile
     * here rather than widening or narrowing this one to make one image work.
     */
    public static final ContainerHardening.Profile HARDENING = ContainerHardening.SERVICE;

    public static final StringField IMAGE = SETTINGS_SCHEMA.addField(
        StringField.builder().name("image").label(HohenheimFormCopy.label("image"))
            .help(HohenheimFormCopy.help("image")).build());

    public static final StringField TAG = SETTINGS_SCHEMA.addField(
        StringField.builder().name("tag").label(HohenheimFormCopy.label("image_tag"))
            .help(HohenheimFormCopy.help("image_tag")).build());

    public static final StringField COMMAND = SETTINGS_SCHEMA.addField(
        StringField.builder().name("command").label(HohenheimFormCopy.label("container_command"))
            .help(HohenheimFormCopy.help("container_command")).build());

    // The port requirement lives HERE, in the kind settings (and therefore in every
    // template's settings baseline): container_port names the port, the two enums below
    // declare its protocol and exposure, host_port optionally fixes the host-side number.
    public static final IntegerField CONTAINER_PORT = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("container_port").label(HohenheimFormCopy.label("container_port"))
            .help(HohenheimFormCopy.help("instance_container_port")).build());

    /** {@link #PORT_EXPOSURE}: published on 127.0.0.1 only (the default, the safe posture). */
    public static final String EXPOSURE_LOOPBACK = "loopback";

    /** {@link #PORT_EXPOSURE}: published on 0.0.0.0 -- a DELIBERATE, declared decision. */
    public static final String EXPOSURE_PUBLIC = "public";

    // Loopback/tcp/no-fixed-port = record-after ephemeral (unchanged behaviour); any
    // other combination derives the PRE-ALLOCATION strategy (claim before create).
    public static final EnumField PORT_PROTOCOL = SETTINGS_SCHEMA.addField(
        EnumField.builder("port_protocol")
            .value("tcp", v -> v.displayName("TCP").icon("right-left")
                .label(Microcopy.of("tcp").withFilter("scope", "port_protocol")))
            .value("udp", v -> v.displayName("UDP").icon("paper-plane")
                .label(Microcopy.of("udp").withFilter("scope", "port_protocol")))
            .defaultValue("tcp")
            .label(HohenheimFormCopy.label("port_protocol"))
            .help(HohenheimFormCopy.help("port_protocol"))
            .build());

    public static final EnumField PORT_EXPOSURE = SETTINGS_SCHEMA.addField(
        EnumField.builder("port_exposure")
            .value(EXPOSURE_LOOPBACK, v -> v.displayName("Loopback only").icon("house-lock")
                .label(Microcopy.of("loopback").withFilter("scope", "port_exposure"))
                .color("teal"))
            .value(EXPOSURE_PUBLIC, v -> v.displayName("Public").icon("globe")
                .label(Microcopy.of("public").withFilter("scope", "port_exposure"))
                .color("orange"))
            .defaultValue(EXPOSURE_LOOPBACK)
            .label(HohenheimFormCopy.label("port_exposure"))
            .help(HohenheimFormCopy.help("port_exposure"))
            .build());

    // Optional fixed host port; declaring one always pre-allocates it in the ledger.
    public static final IntegerField HOST_PORT = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("host_port").label(HohenheimFormCopy.label("host_port"))
            .help(HohenheimFormCopy.help("host_port")).build());

    // secret(): redacted on derived surfaces, masked in forms, kept on blank submit.
    public static final StringMapField ENVIRONMENT_VARIABLES = SETTINGS_SCHEMA.addField(
        StringMapField.builder("environment_variables")
            .label(HohenheimFormCopy.label("environment_variables"))
            .help(HohenheimFormCopy.help("environment_variables")).secret().build());

    // Persistent named volumes: logical name -> container path, materialized as
    // hohenheim-instance-{id}-vol-{name}, owner-labelled at birth.
    public static final StringMapField VOLUMES = SETTINGS_SCHEMA.addField(
        StringMapField.builder("volumes").label(HohenheimFormCopy.label("volumes"))
            .help(HohenheimFormCopy.help("instance_volumes")).build());

    public static final IntegerField MEMORY_LIMIT_MB = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("memory_limit_mb").label(HohenheimFormCopy.label("memory_limit"))
            .help(HohenheimFormCopy.help("memory_limit")).build());

    public static final DoubleField CPU_LIMIT = SETTINGS_SCHEMA.addField(
        DoubleField.builder().name("cpu_limit").label(HohenheimFormCopy.label("cpu_limit"))
            .help(HohenheimFormCopy.help("cpu_limit")).build());

    @Override
    public @NonNull Identifier typeId() { return ID; }

    @Override
    public @NonNull String getDisplayName() { return "Docker container"; }

    // Declared explicitly: the TypeDefinition default silently renders the raw English
    // display name when the microcopy key is missing, which is how localization rots.
    @Override
    public @NonNull Microcopy getLabel() {
        return Microcopy.of("docker_container").withFilter("scope", "instance_kind");
    }

    @Override
    public @NonNull Microcopy getDescription() {
        return Microcopy.of("docker_container").withFilter("scope", "instance_kind_description");
    }

    @Override
    public Icon getIcon() { return Icon.of("box"); }

    @Override
    public String getColor() { return "blue"; }

    @Override
    public Schema getSchema() { return SETTINGS_SCHEMA; }

    /** A published container port is exposable through a site's {@code instance} upstream. */
    @Override public boolean supportsSiteUpstream() { return true; }

    @Override
    public @NonNull InstanceRuntime runtimeFor(@NonNull String serverName) {
        return new DockerInstanceRuntime(new ServerService().clientFor(serverName),
            WorkloadNetworkPolicy.forServer(serverName));
    }

    @Override
    public @NonNull InstanceSpec specFor(int instanceId, @NonNull Map<String, Object> settings) {
        String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, instanceId);
        String image = str(settings.get("image"));
        String tag = str(settings.get("tag"));
        String imageRef = tag.isEmpty() || image.contains(":") ? image : image + ":" + tag;

        String command = str(settings.get("command"));
        List<String> cmd = command.isEmpty() ? null : List.of(command.split("\\s+"));

        Map<String, String> volumes = new LinkedHashMap<>();
        EnvVars.toMap(settings.get("volumes")).forEach((name, path) -> {
            if (path != null && !path.isBlank()) {
                volumes.put(handle + "-vol-" + name, path);
            }
        });

        return InstanceSpec.builder(handle, imageRef,
                ResourceLimits.fromSettings(settings, defaultFootprintMb(settings)), HARDENING,
                OwnerLabels.of(InstanceModel.MODEL_ID, instanceId))
            .command(cmd)
            .env(EnvVars.toMap(settings.get("environment_variables")))
            .volumes(volumes)
            .publication(publicationOf(settings))
            .build();
    }

    /** An application container: its own userland and heap, nothing underneath it. */
    @Override
    public int defaultFootprintMb(@NonNull Map<String, Object> settings) {
        return 512;
    }

    /**
     * Derive the declared publication from the kind settings.
     *
     * @throws Violations when protocol/exposure/host_port are declared without a
     *         container port -- half a declaration silently publishing nothing is the
     *         reports-success shape this codebase hunts by name
     */
    static @Nullable PortPublication publicationOf(@NonNull Map<String, Object> settings) {
        Object port = settings.get("container_port");
        Integer containerPort = port instanceof Number number && number.intValue() > 0
            ? number.intValue() : null;
        String protocol = str(settings.get("port_protocol"));
        String exposure = str(settings.get("port_exposure"));
        Object host = settings.get("host_port");
        Integer hostPort = host instanceof Number number && number.intValue() > 0
            ? number.intValue() : null;
        if (containerPort == null) {
            boolean declaresShape = "udp".equals(protocol)
                || EXPOSURE_PUBLIC.equals(exposure) || hostPort != null;
            if (declaresShape) {
                throw Violations.ofField("settings.container_port", null,
                    Microcopy.of("port_shape_without_port").withFilter("scope", "violations"));
            }
            return null;
        }
        return new PortPublication(containerPort,
            "udp".equals(protocol) ? PortPublication.UDP : PortPublication.TCP,
            EXPOSURE_PUBLIC.equals(exposure), hostPort, null);
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
