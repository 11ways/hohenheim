package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.instance.InstanceKindFields;
import be.elevenways.hohenheim.app.PutOnlineGroup;
import be.elevenways.hohenheim.app.PutOnlineReach;
import be.elevenways.hohenheim.ports.PublishedPort;
import be.elevenways.hohenheim.server.runtime.PortPublication;
import be.elevenways.zenit.common.orm.field.StringMapField;
import be.elevenways.hohenheim.HohenheimFormSections;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.instance.WorkloadIsolation;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.runtime.Egress;
import be.elevenways.hohenheim.instance.ImageOrigin;
import be.elevenways.hohenheim.server.runtime.IncusInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.IncusWorkloadType;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.DoubleField;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.TextField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.BadgeColor;
import be.elevenways.zenit.common.ui.ColorHue;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map;

/**
 * The VM kind: a KVM virtual machine on an inventoried Incus host, THROUGH the same
 * driver as the system-container kind ({@code IncusInstanceRuntime} with the
 * VIRTUAL_MACHINE flavour) -- never a parallel VM path. A VM is kernel-boundary
 * isolation: the one workload flavour rated against a hostile root tenant on shared
 * iron.
 *
 * Where a VM genuinely differs from a container, the difference is DECLARED, not
 * inferred: provisioning is cloud-init user-data (the guest's own first-boot lane;
 * there is no environment.* injection into a VM's init), the schema therefore carries
 * {@code cloud_init} instead of environment variables, and there is no privileged
 * flag because the hypervisor boundary is the point. A prepared template (Windows via
 * Microsoft-signed media included) is a DECLARED image origin through this SAME driver,
 * never a parallel VM path: {@code image_origin}, {@code secure_boot} and
 * {@code guest_agent} are the settings that carry what a prepared image needs that a
 * cloud-init Linux guest does not.
 */
public final class VmKind implements InstanceKindHandler {

    public static final Identifier ID = HohenheimIds.id("vm");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    /**
     * The one declared profile: no Docker capability list (not applicable), no
     * privileged variant (a VM's isolation IS the product; there is nothing this kind
     * would deliberately weaken).
     */
    public static final ContainerHardening.Profile VM =
        new ContainerHardening.Profile("incus-vm", List.of());

    public static final StringField IMAGE = SETTINGS_SCHEMA.addField(
        StringField.builder().name(InstanceKindFields.IMAGE).label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("vm_image"))
            .help(HohenheimMicrocopy.HELP.of("vm_image")).build());

    /**
     * Cloud-init user-data, {@code {{KEY}}} placeholders resolved against the
     * instance's variables at deploy (the config-file substitution shape) -- the
     * template mechanism IS the provisioning vocabulary, secret lane included.
     *
     * AIDEV-NOTE: secret() because nothing ENFORCES the placeholder convention this
     * docblock describes -- the field accepts a literal, and cloud-init user-data is
     * the canonical carrier of ssh_authorized_keys, chpasswd and runcmd bootstrap
     * tokens. It is a JSON sub-field of schemaFrom("kind"), so encrypted() is refused
     * (Schema.refuseEncryptedJsonSubFields) and secret() is the ONLY lever there is:
     * it redacts the value on every derived surface and rides the FormSecrets
     * mask/keep-on-blank/__clear lane, exactly like every sibling env-var map.
     */
    public static final TextField CLOUD_INIT = SETTINGS_SCHEMA.addField(
        TextField.builder().name("cloud_init").label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("cloud_init"))
            .help(HohenheimMicrocopy.HELP.of("cloud_init")).secret().build());

    public static final IntegerField MEMORY_LIMIT_MB = SETTINGS_SCHEMA.addField(InstanceKindFields.memoryLimit());

    public static final DoubleField CPU_LIMIT = SETTINGS_SCHEMA.addField(InstanceKindFields.cpuLimit());

    /**
     * The guest's own root disk in GB; blank inherits the image's size. A VM root is a
     * real block volume, so this is enforced by the hypervisor, not advisory -- and it
     * can only ever GROW (see {@link RootDisk} for the measurements).
     */
    public static final IntegerField ROOT_DISK_GB = RootDisk.addTo(SETTINGS_SCHEMA);

    /**
     * The guest's bandwidth ceiling in Mbit/s; blank leaves the wire unshaped. Enforced
     * by the daemon on every NIC the workload carries (see {@link NetworkBandwidth}).
     */
    public static final IntegerField NETWORK_LIMIT_MBIT = NetworkBandwidth.addTo(SETTINGS_SCHEMA);

    /**
     * The closed set of image origins. The keys come from {@link ImageOrigin} itself --
     * a second spelling of "catalog"/"prepared" here would be a parallel vocabulary the
     * driver and the form could drift apart on.
     */
    public static final EnumField IMAGE_ORIGIN = SETTINGS_SCHEMA.addField(InstanceKindFields.imageOrigin(true));

    /**
     * DECLARED, not hardcoded: this driver always forced {@code security.secureboot=false}
     * because catalog Linux images are unsigned, but a prepared image can genuinely
     * REQUIRE Secure Boot (Microsoft-signed Windows media boots fine with it on). The
     * setting is the image's own declaration, never an inference from the origin.
     */
    public static final BooleanField SECURE_BOOT = SETTINGS_SCHEMA.addField(
        BooleanField.builder("secure_boot").defaultValue(false)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("secure_boot"))
            .help(HohenheimMicrocopy.HELP.of("secure_boot")).build());

    /**
     * A prepared image may carry no incus guest agent at all; when declared false, an
     * exec-driven operation (install/app-update) REFUSES BY NAME instead of waiting out
     * the ready timeout and reporting a false timeout.
     */
    public static final BooleanField GUEST_AGENT = SETTINGS_SCHEMA.addField(
        BooleanField.builder("guest_agent").defaultValue(true)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("guest_agent"))
            .help(HohenheimMicrocopy.HELP.of("guest_agent")).build());

    /** What contains a guest: its own kernel behind the hypervisor, the fact {@link #isolation()} answers. */
    private static final WorkloadIsolation ISOLATION = WorkloadIsolation.VIRTUAL_MACHINE;

    /**
     * The ports the guest publishes on every address of its host, by name: each is a NAT proxy of the host forwarding
     * to the guest's eth0, pinned to a static address of the bridge for it (IncusPublishedPorts). A VM hidden behind
     * the isolating bridge is reached from outside through exactly these.
     */
    public static final StringMapField PUBLISHED_PORTS = SETTINGS_SCHEMA.addField(
        InstanceKindFields.publishedPorts(ISOLATION));

    public static final StringField SRV_SERVICE = SETTINGS_SCHEMA.addField(InstanceKindFields.srvService());

    // A VM is SIZED when it is created, so memory, cores and disk stay visible -- this is
    // the one kind where the ceilings ARE the decision. What folds is the guest half:
    // a first-boot cloud-config nobody pastes while creating, and two image declarations
    // whose defaults suit every catalog image; and the ports, which "Put something online" asks.
    static {
        SETTINGS_SCHEMA.addSection(HohenheimFormSections.collapsed(HohenheimFormSections.GUEST,
            List.of(CLOUD_INIT.getName(), SECURE_BOOT.getName(), GUEST_AGENT.getName())));
        SETTINGS_SCHEMA.addSection(HohenheimFormSections.collapsed(HohenheimFormSections.PORTS,
            List.of(PUBLISHED_PORTS.getName(), SRV_SERVICE.getName())));
    }

    @Override
    public @NonNull Identifier typeId() { return ID; }

    @Override
    public @NonNull String getDisplayName() { return "Virtual machine"; }

    @Override
    public Icon getIcon() { return Icon.of("server"); }

    @Override public PutOnlineGroup putOnlineGroup() { return PutOnlineGroup.MACHINE; }

    @Override
    public BadgeColor color() { return ColorHue.PURPLE; }

    @Override
    public Schema getSchema() { return SETTINGS_SCHEMA; }

    @Override
    public @NonNull Set<String> supportedRuntimes() { return Set.of(ServerModel.RUNTIME_INCUS); }

    /** IncusInstanceRuntime implements DeviceAttachSupport; the Docker driver does not. */
    @Override
    public boolean supportsDevices() { return true; }

    /** The one kind with firmware to boot an ISO: the driver's ensureCdrom is VM-only. */
    @Override
    public boolean supportsInstallMedia() { return true; }

    /** A VM that publishes ports is a service reached on them; any other is a machine reached on its console. */
    @Override
    public @NonNull PutOnlineReach reachOf(@NonNull Map<String, Object> settings) {
        return PublishedPort.declaredIn(settings) ? PutOnlineReach.PORTS : PutOnlineReach.NONE;
    }

    /** Put online as a machine reached on its console, or as a service reached on the ports it publishes. */
    @Override
    public @NonNull List<PutOnlineReach> putOnlineReaches() {
        return List.of(PutOnlineReach.NONE, PutOnlineReach.PORTS);
    }

    @Override
    public @NonNull List<String> portSettings() {
        return List.of(PUBLISHED_PORTS.getName());
    }

    /** IncusInstanceRuntime implements ImagePublishSupport (incus publish). */
    @Override
    public boolean supportsTemplateCapture() { return true; }

    /**
     * The one kind that answers anything but {@code SHARED_KERNEL}: a hypervisor boundary
     * is what this kind IS, per the class docblock's "the one workload flavour rated
     * against a hostile root tenant on shared iron".
     */
    @Override
    public @NonNull WorkloadIsolation isolation() { return ISOLATION; }

    @Override
    public @NonNull InstanceRuntime runtimeFor(@NonNull String serverName) {
        // Tenant tier, OPEN egress like the container kind: the workload fetches its
        // own updates; the tenant-range denies still apply on every NIC.
        // The server NAME travels so the driver can read the DAEMON HOST's kernel, not
        // the controller's -- kernel truth and daemon config are independent facts here.
        return new IncusInstanceRuntime(new ServerService().incusClientFor(serverName),
            Egress.OPEN, IncusWorkloadType.VIRTUAL_MACHINE, serverName);
    }

    @Override
    public @NonNull InstanceSpec specFor(int instanceId, @NonNull Map<String, Object> settings) {
        String cloudInit = settings.get("cloud_init") instanceof String text
            && !text.isBlank() ? text : null;
        boolean secureBoot = RawValues.isOn(settings, SECURE_BOOT);
        boolean guestAgent = RawValues.isOn(settings, GUEST_AGENT);
        // No command override (a VM boots its own kernel), no env (nothing injects
        // into a guest's init -- cloud-init is the provisioning lane), no named
        // volumes (attached disks are instance_devices rows) -- each absence is structural.
        return IncusSpecs.spec(instanceId, settings, defaultFootprintMb(settings), VM)
            .cloudInitUserData(cloudInit)
            .secureBoot(secureBoot)
            .guestAgent(guestAgent)
            .publications(publicationsOf(settings))
            .build();
    }

    /**
     * @return every published port as a public publication
     * @throws Violations naming a port declared on loopback: a VM's ports are NAT proxies on every host address
     */
    static @NonNull List<PortPublication> publicationsOf(@NonNull Map<String, Object> settings) {
        List<PortPublication> publications = new ArrayList<>();
        for (PublishedPort port : PublishedPort.of(settings)) {
            if (port.loopback()) {
                throw Violations.ofField("settings." + PublishedPort.FIELD, port.spec(),
                    HohenheimMicrocopy.VIOLATIONS.of("vm_port_loopback").withArg("name", port.name()));
            }
            publications.add(PortPublication.of(port));
        }
        return publications;
    }

    /**
     * A VM boots its own kernel, firmware and page cache before the guest's workload gets
     * anything, so its floor is a whole multiple of a container's. This is also the number
     * Incus itself defaults an unconfigured VM to, which keeps the booking and what the
     * hypervisor actually hands out the same figure.
     */
    @Override
    public int defaultFootprintMb(@NonNull Map<String, Object> settings) {
        return 1024;
    }

    @Override
    public void requireImageOn(@NonNull String serverName, @NonNull Map<String, Object> settings) {
        requirePreparedImageOn(serverName, settings, IncusWorkloadType.VIRTUAL_MACHINE);
    }

    /**
     * An {@code install_media} VM is created EMPTY and installed interactively from an
     * attached ISO -- the one origin whose settings legitimately carry no image at all.
     */
    @Override
    public boolean allowsBlankImage(@NonNull Map<String, Object> settings) {
        return !ImageOrigin.of(settings).carriesImage();
    }

    /**
     * Ask the DRIVER's own prepared-image rule whether this host could run these settings.
     *
     * AIDEV-NOTE: shared by both Incus kinds and it calls
     * IncusInstanceRuntime.requirePreparedImagePresent -- the same method create() calls.
     * A second implementation of "is the alias there" living in the chooser is exactly the
     * drift that let placement pick a host whose deploy then refused by name.
     */
    static void requirePreparedImageOn(@NonNull String serverName,
                                       @NonNull Map<String, Object> settings,
                                       @NonNull IncusWorkloadType type) {
        ImageOrigin origin = ImageOrigin.of(settings);
        if (origin != ImageOrigin.PREPARED) {
            return;
        }
        String image = settings.get(IMAGE.getName()) != null
            ? String.valueOf(settings.get(IMAGE.getName())).trim() : "";
        try {
            new IncusInstanceRuntime(new ServerService().incusClientFor(serverName),
                Egress.OPEN, type, serverName)
                .requirePreparedImagePresent(image, origin, false);
        } catch (IOException absent) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("host_prepared_image_missing")
                .withArg("name", serverName)
                .withArg("image", image));
        }
    }
}
