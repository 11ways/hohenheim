package be.elevenways.hohenheim.server.docker;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.model.InstanceModel;
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
 * The instance kind a Docker SITE's running release lowers onto: an operator-authored,
 * reverse-proxied web workload behind the host proxy. The kind is the wiring half of
 * the canonical runtime-resource contract -- the SITE record keeps domains, TLS,
 * routing and revisions; the running container is an owned instance of this kind,
 * written exclusively by {@link SiteInstances} inside the GeneratedRows system scope
 * (a standalone create of this kind is refused, see SiteInstances.install).
 *
 * One DECLARED difference from {@code DockerContainerKind}, a workload-shape
 * declaration not reachable from any settings form: {@link #tenantAuthored()} is false
 * (operator tier, predates host admission). The network posture is
 * {@link NetworkPosture#PRIVATE} like every other tier since the isolation wave: a
 * site release container gets its own policied network, which is also what makes
 * database env injection possible for Docker sites (the container joins each attached
 * database's network as a second network, see {@code SiteInstances}). A site container
 * still on the old shared bridge migrates the moment its next release deploys -- the
 * release path replaces the container. Port shape is fixed: loopback/tcp/ephemeral
 * record-after -- the reverse proxy reaches the workload over 127.0.0.1, the world
 * cannot.
 */
public final class SiteContainerKind implements InstanceKindHandler {

    public static final Identifier ID = Identifier.of("hohenheim", "site_container");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    /** Same measured profile as every other container authority (see DockerContainerKind). */
    public static final ContainerHardening.Profile HARDENING = ContainerHardening.SERVICE;

    public static final StringField IMAGE = SETTINGS_SCHEMA.addField(
        StringField.builder().name("image").label(HohenheimFormCopy.label("image"))
            .help(HohenheimFormCopy.help("image")).build());

    /**
     * The content-addressed ID of the build SiteInstances last ran (git-sourced sites
     * only). It exists so a NEW build is a visible settings change the convergence check
     * releases on, while an unchanged rebuild converges to the cached ID and rolls
     * nothing; since the digest-pinning wave {@code image} carries the same digest.
     */
    public static final StringField BUILT_IMAGE_ID = SETTINGS_SCHEMA.addField(
        StringField.builder().name("built_image_id").filterable(false).build());

    /**
     * The operator-spelled image reference {@code image} was resolved FROM
     * (image-sourced sites only): {@code image} pins the content-addressed digest the
     * release actually runs, this keeps the human-findable name. Never an identity.
     */
    public static final StringField IMAGE_REF = SETTINGS_SCHEMA.addField(
        StringField.builder().name("image_ref").filterable(false).build());

    /**
     * Identity of the SOURCE inputs this spec was resolved from (site settings with
     * build_context replaced by commit_sha) -- the release wave's convergence
     * discriminator. Matching it is what lets a routing reload of an unchanged
     * git-sourced site skip the sandbox build entirely.
     */
    public static final StringField SOURCE_FINGERPRINT = SETTINGS_SCHEMA.addField(
        StringField.builder().name("source_fingerprint").filterable(false).build());

    /** Path the release health gate probes on the published port (default "/"). */
    public static final StringField HEALTH_PATH = SETTINGS_SCHEMA.addField(
        StringField.builder().name("health_path").filterable(false).build());

    public static final StringField TAG = SETTINGS_SCHEMA.addField(
        StringField.builder().name("tag").label(HohenheimFormCopy.label("image_tag"))
            .help(HohenheimFormCopy.help("image_tag")).build());

    public static final StringField COMMAND = SETTINGS_SCHEMA.addField(
        StringField.builder().name("command").label(HohenheimFormCopy.label("container_command"))
            .help(HohenheimFormCopy.help("container_command")).build());

    public static final IntegerField CONTAINER_PORT = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("container_port").label(HohenheimFormCopy.label("container_port"))
            .help(HohenheimFormCopy.help("container_port")).build());

    // secret(): redacted on derived surfaces (revisions, activity), like every env map.
    public static final StringMapField ENVIRONMENT_VARIABLES = SETTINGS_SCHEMA.addField(
        StringMapField.builder("environment_variables")
            .label(HohenheimFormCopy.label("environment_variables"))
            .help(HohenheimFormCopy.help("environment_variables")).secret().build());

    // Persistent named volumes: logical name -> container path, materialized as
    // hohenheim-instance-{id}-vol-{name}, owner-labelled at birth.
    public static final StringMapField VOLUMES = SETTINGS_SCHEMA.addField(
        StringMapField.builder("volumes").label(HohenheimFormCopy.label("volumes"))
            .help(HohenheimFormCopy.help("volumes")).build());

    public static final IntegerField MEMORY_LIMIT_MB = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("memory_limit_mb").label(HohenheimFormCopy.label("memory_limit"))
            .help(HohenheimFormCopy.help("memory_limit")).build());

    public static final DoubleField CPU_LIMIT = SETTINGS_SCHEMA.addField(
        DoubleField.builder().name("cpu_limit").label(HohenheimFormCopy.label("cpu_limit"))
            .help(HohenheimFormCopy.help("cpu_limit")).build());

    @Override
    public @NonNull Identifier typeId() { return ID; }

    @Override
    public @NonNull String getDisplayName() { return "Site container"; }

    @Override
    public @NonNull Microcopy getLabel() {
        return Microcopy.of("site_container").withFilter("scope", "instance_kind");
    }

    @Override
    public String getDescription() { return "A Docker site's running release (managed by its site)"; }

    @Override
    public Icon getIcon() { return Icon.of("globe"); }

    @Override
    public String getColor() { return "cyan"; }

    @Override
    public Schema getSchema() { return SETTINGS_SCHEMA; }

    @Override
    public boolean tenantAuthored() { return false; }

    @Override
    public @NonNull InstanceRuntime runtimeFor(@NonNull String serverName) {
        return new DockerInstanceRuntime(new ServerService().clientFor(serverName),
            WorkloadNetworkPolicy.forServer(serverName), NetworkPosture.PRIVATE, Egress.OPEN);
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
        PortPublication publication = port instanceof Number number && number.intValue() > 0
            ? new PortPublication(number.intValue(), PortPublication.TCP, false, null, null)
            : null;

        return new InstanceSpec(handle, imageRef, cmd,
            EnvVars.toMap(settings.get("environment_variables")), volumes,
            publication, ResourceLimits.fromSettings(settings), HARDENING,
            OwnerLabels.of(InstanceModel.MODEL_ID, instanceId));
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
