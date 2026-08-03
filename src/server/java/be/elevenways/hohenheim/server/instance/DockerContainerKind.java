package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.runtime.DockerInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.util.EnvVars;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.DoubleField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.StringMapField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The first instance kind: a Docker container on an inventoried host. The kind key is
 * deliberately {@code docker_container}, not {@code docker} -- kind is the ONE
 * discriminator, so it must leave room for {@code incus_container} and {@code vm}
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
     * {@link ContainerHardening#ESCAPE_KEYS} (Privileged, host namespaces, host bind
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

    // TCP only, published on 127.0.0.1 with an ephemeral host port recorded AFTER start
    // (record-after); a UDP/game port needs the declared pre-allocation mode (not built).
    public static final IntegerField CONTAINER_PORT = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("container_port").label(HohenheimFormCopy.label("container_port"))
            .help(HohenheimFormCopy.help("instance_container_port")).build());

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
    public String getDescription() { return "Run a container image as a managed instance"; }

    @Override
    public Icon getIcon() { return Icon.of("box"); }

    @Override
    public String getColor() { return "blue"; }

    @Override
    public Schema getSchema() { return SETTINGS_SCHEMA; }

    @Override
    public @NonNull InstanceRuntime runtimeFor(@NonNull String serverName) {
        return new DockerInstanceRuntime(new ServerService().clientFor(serverName));
    }

    @Override
    public @NonNull InstanceSpec specFor(int instanceId, @NonNull Map<String, Object> settings) {
        String handle = "hohenheim-instance-" + instanceId;
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

        Object port = settings.get("container_port");
        Integer publishPort = port instanceof Number number && number.intValue() > 0
            ? number.intValue() : null;

        return new InstanceSpec(handle, imageRef, cmd,
            EnvVars.toMap(settings.get("environment_variables")), volumes, publishPort,
            ResourceLimits.fromSettings(settings), HARDENING,
            OwnerLabels.of(InstanceModel.MODEL_ID, instanceId));
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
