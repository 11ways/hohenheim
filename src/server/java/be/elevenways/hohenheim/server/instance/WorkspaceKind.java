package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.HohenheimFormSections;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.host.HostShell;
import be.elevenways.hohenheim.server.host.VolumeBackends;
import be.elevenways.hohenheim.server.runtime.DockerInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.Egress;
import be.elevenways.hohenheim.server.runtime.ImageOrigin;
import be.elevenways.hohenheim.server.runtime.IncusInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.IncusWorkloadType;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.runtime.PortPublication;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.server.util.EnvVars;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.DoubleField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.StringMapField;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A persistent development box: one container per workspace, on Docker or Incus, started
 * from a RUNTIME IMAGE, with a Hohenheim-owned host directory as its {@code /home/site}
 * and everything outside it disposable.
 *
 * AIDEV-NOTE: this is NOT {@link SystemContainerKind} with a mount. A system container is a
 * rootfs-persistent pet box (Proxmox-style) and stays its own kind; a workspace's rootfs is
 * DISPOSABLE and only its data volume survives, which is a different promise to the person
 * using it and a different reclaim story for the operator.
 *
 * AIDEV-NOTE: the identity a workspace runs as is a NUMBER and nothing else
 * ({@link WorkspaceUids}). Docker carries it in the container's {@code User} field, where it
 * is a HOST uid; Incus reaches it through the image's {@code hohenheim-init}, where it is a
 * NAMESPACE id and the host-side owner of the volume is {@code WorkspaceUids.incusHostUid}
 * of it. That difference is the runtimes', not a choice: the two AIDEV-NOTEs on
 * {@code IncusInstanceRuntime.applyRunUser} record what was measured and why parity was
 * not worth what it costs the rest of the host.
 */
public final class WorkspaceKind implements InstanceKindHandler {

    public static final Identifier ID = Identifier.of("hohenheim", "workspace");
    public static final Schema SETTINGS_SCHEMA = GitSourceSchema.addTo(new Schema());

    /** The one declared volume of every workspace, named in {@code instance_volumes}. */
    public static final String HOME_VOLUME = "home";

    /** Where {@link #HOME_VOLUME} is mounted; everything outside it is disposable. */
    public static final String HOME_PATH = "/home/site";

    /**
     * The DECLARED isolation profile: the same one every ordinary published image runs
     * under ({@link DockerContainerKind#HARDENING} documents why a capability profile is an
     * image-shape declaration, not a trust declaration).
     */
    public static final ContainerHardening.Profile HARDENING = ContainerHardening.SERVICE;

    /** Incus's spelling of the same declaration; the name IS the declaration there. */
    public static final ContainerHardening.Profile INCUS_HARDENING =
        new ContainerHardening.Profile("incus-unprivileged", List.of());

    /** Overrides the runtime image's default command; blank = the image decides. */
    public static final StringField START_COMMAND = SETTINGS_SCHEMA.addField(
        StringField.builder().name("start_command")
            .label(HohenheimFormCopy.label("start_command"))
            .help(HohenheimFormCopy.help("start_command")).build());

    /** The port the workspace's process listens on, for readiness and for exposure. */
    public static final IntegerField CONTAINER_PORT = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("container_port")
            .label(HohenheimFormCopy.label("container_port"))
            .help(HohenheimFormCopy.help("instance_container_port")).build());

    // AIDEV-NOTE: there is deliberately no build_command field HERE. GitSourceSchema
    // already declares one and this schema is built from it, so a second declaration is a
    // duplicate key the schema refuses at class load. What the workspace adds is the
    // MEANING: buildCommandOf falls back to the runtime image's default_build_command.

    /** Size cap of the home volume in MB; blank leaves the declaration's own quota. */
    public static final IntegerField HOME_QUOTA_MB = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("home_quota_mb")
            .label(HohenheimFormCopy.label("home_quota"))
            .help(HohenheimFormCopy.help("home_quota")).build());

    // secret(): redacted on derived surfaces, masked in forms, kept on blank submit.
    public static final StringMapField ENVIRONMENT_VARIABLES = SETTINGS_SCHEMA.addField(
        StringMapField.builder("environment_variables")
            .label(HohenheimFormCopy.label("environment_variables"))
            .help(HohenheimFormCopy.help("environment_variables")).secret().build());

    public static final IntegerField MEMORY_LIMIT_MB = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("memory_limit_mb")
            .label(HohenheimFormCopy.label("memory_limit"))
            .help(HohenheimFormCopy.help("memory_limit")).build());

    public static final DoubleField CPU_LIMIT = SETTINGS_SCHEMA.addField(
        DoubleField.builder().name("cpu_limit")
            .label(HohenheimFormCopy.label("cpu_limit"))
            .help(HohenheimFormCopy.help("cpu_limit")).build());

    // What a person DECIDES when creating a workspace is where the code comes from, what
    // builds it, what starts it and which port it serves. Everything else has a working
    // default, so it folds -- in NAMED sections, because a fold whose header says "Build"
    // is browsable and one that says "Advanced" is a drawer. The home cap folds WITH the
    // other ceilings: blank means the declaration's own quota, which makes it a limit and
    // not a decision, and it was the only ceiling in the form rendered outside a fold.
    //
    // AIDEV-NOTE: declared here, after the fields, because Schema.addSection validates
    // membership against the fields declared SO FAR. The git half's members come from
    // GitSourceSchema's own groups, so a field added there lands in the same fold for
    // every kind that hosts it.
    static {
        SETTINGS_SCHEMA.addSection(
            HohenheimFormSections.collapsed(HohenheimFormSections.BUILD, GitSourceSchema.BUILD_DETAIL));
        SETTINGS_SCHEMA.addSection(HohenheimFormSections.collapsed(HohenheimFormSections.DEPLOYMENT,
            HohenheimFormSections.join(GitSourceSchema.DELIVERY, GitSourceSchema.PREVIEWS)));
        SETTINGS_SCHEMA.addSection(
            HohenheimFormSections.collapsed(HohenheimFormSections.RUNTIME, List.of(
                HOME_QUOTA_MB.getName(), ENVIRONMENT_VARIABLES.getName(),
                MEMORY_LIMIT_MB.getName(), CPU_LIMIT.getName())));
    }

    @Override public @NonNull Identifier typeId() { return ID; }

    @Override public @NonNull String getDisplayName() { return "Workspace"; }

    @Override public @NonNull Microcopy getLabel() {
        return Microcopy.of("workspace").withFilter("scope", "instance_kind");
    }

    @Override public @NonNull Microcopy getDescription() {
        return Microcopy.of("workspace").withFilter("scope", "instance_kind_description");
    }

    @Override public Icon getIcon() { return Icon.of("code"); }

    @Override public String getColor() { return "violet"; }

    @Override public Schema getSchema() { return SETTINGS_SCHEMA; }

    /** The operator picks the runtime; the kind runs on both (phase-0 design section 4.3). */
    @Override public @NonNull Set<String> supportedRuntimes() {
        return Set.of(ServerModel.RUNTIME_DOCKER, ServerModel.RUNTIME_INCUS);
    }

    /** A userland plus an editor's language server: measurably more than a bare service. */
    @Override public int defaultFootprintMb(@NonNull Map<String, Object> settings) {
        return 1024;
    }

    /** Mounts its declared volumes ({@code home} first); placement demands quota via the default. */
    @Override public boolean supportsVolumes() { return true; }

    /** Runs inside a runtime image; {@link RuntimeImages#requireFor} refuses without one. */
    @Override public boolean usesRuntimeImage() { return true; }

    /** Its container port is exposable through a site's {@code instance} upstream. */
    @Override public boolean supportsSiteUpstream() { return true; }

    /**
     * Docker or Incus, decided by the HOST's declared runtime -- the one kind that reads it,
     * because it is the one kind that runs on both.
     */
    @Override
    public @NonNull InstanceRuntime runtimeFor(@NonNull String serverName) {

        if (ServerModel.RUNTIME_INCUS.equals(runtimeOf(serverName))) {
            // OPEN egress and the tenant-range denies, the SystemContainerKind stance: a
            // workspace fetches its own dependencies by definition.
            return new IncusInstanceRuntime(new ServerService().incusClientFor(serverName),
                Egress.OPEN, IncusWorkloadType.CONTAINER, serverName);
        }

        return new DockerInstanceRuntime(new ServerService().clientFor(serverName),
            WorkloadNetworkPolicy.forServer(serverName));
    }

    @Override
    public @NonNull InstanceSpec specFor(int instanceId, @NonNull Map<String, Object> settings) {

        Row instance = requireInstance(instanceId);
        Row image = RuntimeImages.requireFor(instance);
        String serverName = ServerModel.nameOf(
            ServerModel.canonicalServerId(instance.get(InstanceModel.SERVER_ID)));
        String runtime = runtimeOf(serverName);
        boolean incus = ServerModel.RUNTIME_INCUS.equals(runtime);

        String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, instanceId);
        int uid = WorkspaceUids.forInstance(instanceId);
        String startCommand = startCommandOf(settings, image);

        Map<String, String> env = new LinkedHashMap<>(
            EnvVars.toMap(settings.get("environment_variables")));

        // AIDEV-NOTE: the host paths are DERIVED here and MATERIALIZED in prepareForDeploy.
        // Two calls, one derivation (InstanceVolumes.hostPathFor), so the directory the
        // daemon binds and the directory the controller creates can never be two paths.
        Map<String, String> binds = Map.of(
            InstanceVolumes.hostPathFor(instanceId, HOME_VOLUME), HOME_PATH);

        InstanceSpec.Builder spec = InstanceSpec.builder(handle,
                RuntimeImages.referenceFor(image, runtime),
                ResourceLimits.fromSettings(settings, defaultFootprintMb(settings)),
                incus ? INCUS_HARDENING : HARDENING,
                OwnerLabels.of(InstanceModel.MODEL_ID, instanceId))
            .binds(binds)
            .publication(publicationOf(settings, image))
            .runUser(uid);

        if (incus) {
            // The image lives in the HOST's own store (RuntimeImages converts it there),
            // so it is never fetched from simplestreams -- an origin of CATALOG would send
            // the daemon looking for `hohenheim/...` on the public image server.
            spec.imageOrigin(ImageOrigin.PREPARED);
            // Incus execs /sbin/init, so the start command and the identity travel in the
            // container environment for the image's hohenheim-init to read. The driver
            // turns runUser() into the raw.idmap and the init.cmd override.
            env.put("HOHENHEIM_START_COMMAND", startCommand);
            env.put("HOHENHEIM_RUN_UID", String.valueOf(uid));
            env.put("HOHENHEIM_WORKDIR", HOME_PATH);
        } else {
            // Docker starts the command itself, under the image's tini entrypoint.
            spec.command(List.of("bash", "-lc", startCommand));
        }

        return spec.env(env).build();
    }

    /**
     * Materialize the home volume with its quota and its ownership, and make the runtime
     * image present on the host.
     */
    @Override
    public void prepareForDeploy(int instanceId, @NonNull String serverName,
                                 @NonNull Map<String, Object> settings) {

        Row instance = requireInstance(instanceId);
        Row image = RuntimeImages.requireFor(instance);

        String runtime = runtimeOf(serverName);
        int uid = WorkspaceUids.forInstance(instanceId);
        int ownerUid = uid;

        if (ServerModel.RUNTIME_INCUS.equals(runtime)) {
            // The uid the WORKLOAD runs as is a namespace id there; the directory on the
            // host has to belong to the host uid that namespace id maps to.
            Row server = Models.get(ServerModel.class).findByName(serverName);
            if (server != null) {
                ownerUid = WorkspaceUids.incusHostUid(uid,
                    WorkspaceUids.incusSubuidBase(serverName, HostShell.forServer(server)));
            }
        }

        ensureHomeDeclared(instanceId, settings);
        InstanceVolumes.mountsFor(instanceId, serverName, ownerUid);
        RuntimeImages.ensurePresent(image, serverName, runtime);
    }

    /**
     * Declare the {@code home} volume if it is not declared yet, and keep its quota in step
     * with the settings.
     *
     * AIDEV-NOTE: the home volume is a REAL {@code instance_volumes} row, not a special
     * case in the mount code. That is what gives a workspace the volumes tab, the usage
     * figure, the snapshot and the quota every other volume has, with nothing written twice.
     */
    public static void ensureHomeDeclared(int instanceId, @NonNull Map<String, Object> settings) {
        Long quota = null;
        if (settings.get("home_quota_mb") instanceof Number number && number.intValue() > 0) {
            quota = number.longValue() * 1024L * 1024L;
        }
        InstanceVolumes.declare(instanceId, HOME_VOLUME, HOME_PATH, quota, true);
    }

    /** The command the workspace supervises: the instance's override, else the image's. */
    public static @NonNull String startCommandOf(@NonNull Map<String, Object> settings,
                                          @NonNull Row image) {
        String declared = str(settings.get("start_command"));
        if (!declared.isEmpty()) {
            return declared;
        }
        String fromImage = str(image.get(RuntimeImageModel.DEFAULT_COMMAND));
        // A runtime image with no default command is a userland to live in, not a service:
        // it idles so the shell and the files tab have something to attach to.
        return fromImage.isEmpty() ? "sleep infinity" : fromImage;
    }

    /** The build command run inside the workspace: the instance's override, else the image's. */
    public static @NonNull String buildCommandOf(@NonNull Map<String, Object> settings,
                                          @NonNull Row image) {
        String declared = str(settings.get("build_command"));
        if (!declared.isEmpty()) {
            return declared;
        }
        return str(image.get(RuntimeImageModel.DEFAULT_BUILD_COMMAND));
    }

    /** Loopback publication of the declared port, else the runtime image's default port. */
    static @Nullable PortPublication publicationOf(@NonNull Map<String, Object> settings,
                                                   @NonNull Row image) {
        Integer port = null;
        if (settings.get("container_port") instanceof Number number && number.intValue() > 0) {
            port = number.intValue();
        } else if (image.get(RuntimeImageModel.DEFAULT_PORT) instanceof Integer fallback
                && fallback > 0) {
            port = fallback;
        }
        return port == null
            ? null : new PortPublication(port, PortPublication.TCP, false, null, null);
    }

    /** The instance record, or the same refusal every other lookup of it gives. */
    private static @NonNull Row requireInstance(int instanceId) {
        Row instance = Models.get(InstanceModel.class).findById(instanceId);
        if (instance == null) {
            throw Violations.ofForm(Microcopy.of("instance_not_found")
                .withFilter("scope", "violations").withArg("id", String.valueOf(instanceId)));
        }
        return instance;
    }

    /** The DECLARED runtime of a host; an unknown host folds to Docker, as resolve does. */
    static @NonNull String runtimeOf(@NonNull String serverName) {
        Row server = Models.get(ServerModel.class).findByName(serverName);
        return server == null ? ServerModel.RUNTIME_DOCKER : ServerModel.runtimeOf(server);
    }

    private static @NonNull String str(@Nullable Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
