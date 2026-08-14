package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.runtime.Egress;
import be.elevenways.hohenheim.server.runtime.IncusInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.IncusWorkloadType;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
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
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;
import java.util.Map;

/**
 * The second instance kind and the proof the driver seam is real: a SYSTEM CONTAINER
 * on an inventoried Incus host. The kind key rides the same ONE discriminator the
 * Docker kind established ({@code kind}), and {@link #requiredRuntime()} is what keeps
 * placement honest -- an incus workload only ever lands on a host DECLARING the incus
 * runtime.
 */
public final class IncusContainerKind implements InstanceKindHandler {

    public static final Identifier ID = Identifier.of("hohenheim", "incus_container");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    /**
     * The DECLARED isolation profiles of this kind, expressed in the seam's hardening
     * vocabulary: the NAME is the declaration ({@code IncusInstanceRuntime} maps the
     * privileged one onto {@code security.privileged=true}), the Docker capability list
     * is empty because it does not apply to an Incus container.
     */
    public static final ContainerHardening.Profile UNPRIVILEGED =
        new ContainerHardening.Profile("incus-unprivileged", List.of());
    public static final ContainerHardening.Profile PRIVILEGED =
        new ContainerHardening.Profile(IncusInstanceRuntime.PROFILE_PRIVILEGED, List.of());

    public static final StringField IMAGE = SETTINGS_SCHEMA.addField(
        StringField.builder().name("image").label(HohenheimFormCopy.label("incus_image"))
            .help(HohenheimFormCopy.help("incus_image")).build());

    // secret(): redacted on derived surfaces, masked in forms, kept on blank submit.
    public static final StringMapField ENVIRONMENT_VARIABLES = SETTINGS_SCHEMA.addField(
        StringMapField.builder("environment_variables")
            .label(HohenheimFormCopy.label("environment_variables"))
            .help(HohenheimFormCopy.help("environment_variables")).secret().build());

    public static final IntegerField MEMORY_LIMIT_MB = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("memory_limit_mb").label(HohenheimFormCopy.label("memory_limit"))
            .help(HohenheimFormCopy.help("memory_limit")).build());

    public static final DoubleField CPU_LIMIT = SETTINGS_SCHEMA.addField(
        DoubleField.builder().name("cpu_limit").label(HohenheimFormCopy.label("cpu_limit"))
            .help(HohenheimFormCopy.help("cpu_limit")).build());

    /**
     * The container's own rootfs cap in GB; blank inherits the pool default. On btrfs
     * this is a qgroup limit -- real, but invisible to {@code df} inside the guest (see
     * {@link RootDisk}).
     */
    public static final IntegerField ROOT_DISK_GB = RootDisk.addTo(SETTINGS_SCHEMA);

    /**
     * The container's bandwidth ceiling in Mbit/s; blank leaves the wire unshaped. Only
     * the Incus kinds offer it, because only Incus has a per-NIC rate limiter to hand it
     * to (see {@link NetworkBandwidth}).
     */
    public static final IntegerField NETWORK_LIMIT_MBIT = NetworkBandwidth.addTo(SETTINGS_SCHEMA);

    /**
     * ADMIN-DECLARED escape hatch, unprivileged is the default: a privileged system
     * container's root is meaningfully closer to the HOST's root (threat model
     * boundary 1), and the help text says so in as many words. The instance settings
     * form is an admin surface (tenants see name + crash policy only), and templates
     * carry this flag as operator-approved settings.
     */
    public static final BooleanField PRIVILEGED_FLAG = SETTINGS_SCHEMA.addField(
        BooleanField.builder("privileged").defaultValue(false)
            .label(HohenheimFormCopy.label("incus_privileged"))
            .help(HohenheimFormCopy.help("incus_privileged")).build());

    @Override
    public @NonNull Identifier typeId() { return ID; }

    @Override
    public @NonNull String getDisplayName() { return "Incus container"; }

    @Override
    public @NonNull Microcopy getLabel() {
        return Microcopy.of("incus_container").withFilter("scope", "instance_kind");
    }

    @Override
    public String getDescription() { return "Run a system container on an Incus host"; }

    @Override
    public Icon getIcon() { return Icon.of("cubes"); }

    @Override
    public String getColor() { return "green"; }

    @Override
    public Schema getSchema() { return SETTINGS_SCHEMA; }

    @Override
    public @NonNull String requiredRuntime() { return ServerModel.RUNTIME_INCUS; }

    /** IncusInstanceRuntime implements DeviceAttachSupport; the Docker driver does not. */
    @Override
    public boolean supportsDevices() { return true; }

    @Override
    public @NonNull InstanceRuntime runtimeFor(@NonNull String serverName) {
        // Incus system containers are the tenant tier: OPEN egress (the workload fetches
        // its own updates and dependencies), the tenant-range denies still apply.
        // The server NAME travels so the driver can read the DAEMON HOST's kernel, not
        // the controller's -- kernel truth and daemon config are independent facts here.
        return new IncusInstanceRuntime(new ServerService().incusClientFor(serverName),
            Egress.OPEN, IncusWorkloadType.CONTAINER, serverName);
    }

    @Override
    public @NonNull InstanceSpec specFor(int instanceId, @NonNull Map<String, Object> settings) {
        String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, instanceId);
        String image = settings.get("image") != null
            ? String.valueOf(settings.get("image")).trim() : "";
        boolean privileged = Boolean.TRUE.equals(settings.get("privileged"));
        // No command override (a system container boots its init), no named volumes
        // (the rootfs IS the persistent state) and no port publication yet (proxy
        // devices are a later mechanism) -- each absence is structural, not an omission.
        return InstanceSpec.builder(handle, image,
                ResourceLimits.fromSettings(settings, defaultFootprintMb(settings)),
                privileged ? PRIVILEGED : UNPRIVILEGED,
                OwnerLabels.of(InstanceModel.MODEL_ID, instanceId))
            .env(EnvVars.toMap(settings.get("environment_variables")))
            .rootDiskGb(RootDisk.declaredGb(settings))
            .networkLimitMbit(NetworkBandwidth.declaredMbit(settings))
            .build();
    }

    /** A system container shares the host kernel: its floor is its userland, not a guest OS. */
    @Override
    public int defaultFootprintMb(@NonNull Map<String, Object> settings) {
        return 512;
    }

    // AIDEV-NOTE: NO supportsTemplateCapture here, deliberately. The driver could
    // publish a container (incus publish works for both flavours), but this kind's
    // settings schema declares no image_origin and specFor never sets one, so a
    // captured template's prepared alias would be re-resolved against simplestreams and
    // fail by the wrong name. Capture lands here only together with the origin field
    // and the specFor plumbing -- the "no unwired vocabulary" rule.

    @Override
    public void requirePlaceableOn(@NonNull String serverName,
                                   @NonNull Map<String, Object> settings) {
        // The image-origin setting is a VM-form field today, but the rule is the DRIVER's
        // and it answers for whatever the settings declare -- keeping the two kinds on one
        // call means a prepared container alias needs no second gate later.
        IncusVmKind.requirePreparedImageOn(serverName, settings, IncusWorkloadType.CONTAINER);
    }
}
