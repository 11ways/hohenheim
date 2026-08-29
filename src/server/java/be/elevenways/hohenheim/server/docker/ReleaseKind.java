package be.elevenways.hohenheim.server.docker;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.HohenheimFormSections;
import be.elevenways.hohenheim.instance.ConsoleKind;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
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
import be.elevenways.zenit.common.orm.field.EnumField;
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
 * the canonical runtime-resource contract -- the APPLICATION record keeps the source,
 * the build, the variables and the volumes; the running container is an owned instance of
 * this kind, written exclusively by {@code ApplicationReleases} inside the GeneratedRows system scope
 * (a standalone create of this kind is refused, see ApplicationReleases.install).
 *
 * One DECLARED difference from {@code DockerContainerKind}, a workload-shape
 * declaration not reachable from any settings form: {@link #tenantAuthored()} is false
 * (operator tier, predates host admission). The network posture is
 * {@link NetworkPosture#PRIVATE} like every other tier since the isolation wave: a
 * site release container gets its own policied network, which is also what makes
 * database env injection possible for applications (the container joins each attached
 * database's network as a second network, see {@code ApplicationReleases}). A site container
 * still on the old shared bridge migrates the moment its next release deploys -- the
 * release path replaces the container. Port shape is fixed: loopback/tcp/ephemeral
 * record-after -- the reverse proxy reaches the workload over 127.0.0.1, the world
 * cannot.
 */
public final class ReleaseKind implements InstanceKindHandler {

    public static final Identifier ID = Identifier.of("hohenheim", "release");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    /** Same measured profile as every other container authority (see DockerContainerKind). */
    public static final ContainerHardening.Profile HARDENING = ContainerHardening.SERVICE;

    public static final StringField IMAGE = SETTINGS_SCHEMA.addField(
        StringField.builder().name("image").label(HohenheimFormCopy.label("image"))
            .help(HohenheimFormCopy.help("image")).build());

    /**
     * The content-addressed ID of the build ApplicationReleases last ran (git-sourced
     * applications only). It exists so a NEW build is a visible settings change the convergence check
     * releases on, while an unchanged rebuild converges to the cached ID and rolls
     * nothing; since the digest-pinning wave {@code image} carries the same digest.
     */
    public static final StringField BUILT_IMAGE_ID = SETTINGS_SCHEMA.addField(
        StringField.builder().name("built_image_id").filterable(false).build());

    /**
     * The operator-spelled image reference {@code image} was resolved FROM
     * (image-sourced applications only): {@code image} pins the content-addressed digest the
     * release actually runs, this keeps the human-findable name. Never an identity.
     */
    public static final StringField IMAGE_REF = SETTINGS_SCHEMA.addField(
        StringField.builder().name("image_ref").filterable(false).build());

    /**
     * Identity of the SOURCE inputs this spec was resolved from (application settings with
     * build_context replaced by commit_sha) -- the release wave's convergence
     * discriminator. Matching it is what lets a converge of an unchanged
     * git-sourced application skip the sandbox build entirely.
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

    /** The application's declared console, copied per release by ApplicationReleases. */
    public static final EnumField CONSOLE_KIND = SETTINGS_SCHEMA.addField(
        ConsoleKind.fieldBuilder(ConsoleKind.SETTING)
            .label(HohenheimFormCopy.label("console_kind"))
            .help(HohenheimFormCopy.help("console_kind")).build());

    public static final IntegerField CONTAINER_PORT = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("container_port").label(HohenheimFormCopy.label("container_port"))
            .help(HohenheimFormCopy.help("container_port")).build());

    // secret(): redacted on derived surfaces (revisions, activity), like every env map.
    public static final StringMapField ENVIRONMENT_VARIABLES = SETTINGS_SCHEMA.addField(
        StringMapField.builder("environment_variables")
            .label(HohenheimFormCopy.label("environment_variables"))
            .help(HohenheimFormCopy.help("environment_variables")).secret().build());

    // The APPLICATION's volume directories: host path -> container path, resolved by
    // ApplicationReleases.desiredSettings off the APPLICATION id and written onto every
    // release it generates. A rollback re-deploys whatever it stored, which is why specFor
    // mounts these paths verbatim instead of deriving them from this release's own id --
    // deriving is exactly what made a gated swap mount an empty volume.
    public static final StringMapField VOLUME_MOUNTS = SETTINGS_SCHEMA.addField(
        StringMapField.builder(ApplicationReleases.VOLUME_MOUNTS)
            .label(HohenheimFormCopy.label("volumes"))
            .help(HohenheimFormCopy.help("volumes")).build());

    public static final IntegerField MEMORY_LIMIT_MB = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("memory_limit_mb").label(HohenheimFormCopy.label("memory_limit"))
            .help(HohenheimFormCopy.help("memory_limit")).build());

    public static final DoubleField CPU_LIMIT = SETTINGS_SCHEMA.addField(
        DoubleField.builder().name("cpu_limit").label(HohenheimFormCopy.label("cpu_limit"))
            .help(HohenheimFormCopy.help("cpu_limit")).build());

    // A release is GENERATED per deploy: what a reader wants is what it runs, and the four
    // provenance handles the release engine stamped are evidence, not input. They stay in
    // the form (folding is a display state, never a payload filter) under a header that
    // says who owns them.
    static {
        SETTINGS_SCHEMA.addSection(HohenheimFormSections.collapsed(HohenheimFormSections.LIMITS,
            List.of(MEMORY_LIMIT_MB.getName(), CPU_LIMIT.getName())));
        SETTINGS_SCHEMA.addSection(HohenheimFormSections.collapsed(HohenheimFormSections.MANAGED,
            List.of(BUILT_IMAGE_ID.getName(), IMAGE_REF.getName(), SOURCE_FINGERPRINT.getName())));
    }

    @Override
    public @NonNull Identifier typeId() { return ID; }

    @Override
    public @NonNull String getDisplayName() { return "Release"; }

    @Override
    public @NonNull Microcopy getLabel() {
        return Microcopy.of("release").withFilter("scope", "instance_kind");
    }

    @Override
    public @NonNull Microcopy getDescription() {
        return Microcopy.of("release").withFilter("scope", "instance_kind_description");
    }

    @Override
    public Icon getIcon() { return Icon.of("globe"); }

    @Override
    public String getColor() { return "cyan"; }

    @Override
    public Schema getSchema() { return SETTINGS_SCHEMA; }

    @Override
    public boolean tenantAuthored() { return false; }

    /** Written exclusively by {@code ApplicationReleases} inside the application scope. */
    @Override
    public boolean generatedOnly() { return true; }

    @Override
    public @NonNull InstanceRuntime runtimeFor(@NonNull String serverName) {
        return new DockerInstanceRuntime(new ServerService().clientFor(serverName),
            WorkloadNetworkPolicy.forServer(serverName), NetworkPosture.PRIVATE, Egress.OPEN);
    }

    @Override
    public @NonNull InstanceSpec specFor(int instanceId, @NonNull Map<String, Object> settings) {
        String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, instanceId);
        String image = str(settings.get("image"));
        String tag = str(settings.get("tag"));
        String imageRef = tag.isEmpty() || image.contains(":") ? image : image + ":" + tag;

        String command = str(settings.get("command"));
        List<String> cmd = command.isEmpty() ? null : List.of(command.split("\\s+"));

        // AIDEV-NOTE: the keys are HOST PATHS under the volume root, minted from the
        // APPLICATION's id, and this method may never re-derive them from the release's own
        // handle -- that is the defect the site-keyed volumes were introduced to fix and
        // the re-key had to carry forward. A release mints a new instance row, so a mount
        // derived from it is empty on every gated swap.
        Map<String, String> binds = new LinkedHashMap<>();
        EnvVars.toMap(settings.get(ApplicationReleases.VOLUME_MOUNTS))
            .forEach((hostPath, path) -> {
                if (path != null && !path.isBlank()) {
                    binds.put(hostPath, path);
                }
            });

        Object port = settings.get("container_port");
        PortPublication publication = port instanceof Number number && number.intValue() > 0
            ? new PortPublication(number.intValue(), PortPublication.TCP, false, null, null)
            : null;

        return InstanceSpec.builder(handle, imageRef,
                ResourceLimits.fromSettings(settings, defaultFootprintMb(settings)),
                HARDENING, OwnerLabels.of(InstanceModel.MODEL_ID, instanceId))
            .command(cmd)
            .env(EnvVars.toMap(settings.get("environment_variables")))
            .binds(binds)
            .publication(publication)
            .tty(ConsoleKind.requireDeclared(settings).interactive())
            .build();
    }

    /**
     * An application's lowered release container. It books like any other workload even though it
     * is operator-authored and skips the admission gate -- capacity is physics, not
     * authorization, and a host full of releases is full for tenants too.
     */
    @Override
    public int defaultFootprintMb(@NonNull Map<String, Object> settings) {
        return 512;
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
