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
import be.elevenways.hohenheim.server.runtime.ImageOrigin;
import be.elevenways.hohenheim.server.runtime.IncusInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.IncusWorkloadType;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.DoubleField;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.TextField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * The VM kind: a KVM virtual machine on an inventoried Incus host, THROUGH the same
 * driver as the system-container kind ({@code IncusInstanceRuntime} with the
 * VIRTUAL_MACHINE flavour) -- never a parallel VM path. A VM is the plan's boundary-1
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
public final class IncusVmKind implements InstanceKindHandler {

    public static final Identifier ID = Identifier.of("hohenheim", "incus_vm");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    /**
     * The one declared profile: no Docker capability list (not applicable), no
     * privileged variant (a VM's isolation IS the product; there is nothing this kind
     * would deliberately weaken).
     */
    public static final ContainerHardening.Profile VM =
        new ContainerHardening.Profile("incus-vm", List.of());

    public static final StringField IMAGE = SETTINGS_SCHEMA.addField(
        StringField.builder().name("image").label(HohenheimFormCopy.label("vm_image"))
            .help(HohenheimFormCopy.help("vm_image")).build());

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
        TextField.builder().name("cloud_init").label(HohenheimFormCopy.label("cloud_init"))
            .help(HohenheimFormCopy.help("cloud_init")).secret().build());

    public static final IntegerField MEMORY_LIMIT_MB = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("memory_limit_mb").label(HohenheimFormCopy.label("memory_limit"))
            .help(HohenheimFormCopy.help("memory_limit")).build());

    public static final DoubleField CPU_LIMIT = SETTINGS_SCHEMA.addField(
        DoubleField.builder().name("cpu_limit").label(HohenheimFormCopy.label("cpu_limit"))
            .help(HohenheimFormCopy.help("cpu_limit")).build());

    /**
     * The closed set of image origins. The keys come from {@link ImageOrigin} itself --
     * a second spelling of "catalog"/"prepared" here would be a parallel vocabulary the
     * driver and the form could drift apart on.
     */
    public static final EnumField IMAGE_ORIGIN = SETTINGS_SCHEMA.addField(
        EnumField.builder("image_origin")
            .value(ImageOrigin.CATALOG.key(), v -> v.displayName("Catalog")
                .icon("cloud-arrow-down")
                .label(Microcopy.of("catalog").withFilter("scope", "image_origin")))
            .value(ImageOrigin.PREPARED.key(), v -> v.displayName("Prepared template")
                .icon("hard-drive")
                .label(Microcopy.of("prepared").withFilter("scope", "image_origin")))
            .defaultValue(ImageOrigin.CATALOG.key())
            .label(HohenheimFormCopy.label("image_origin"))
            .help(HohenheimFormCopy.help("image_origin")).build());

    /**
     * DECLARED, not hardcoded: this driver always forced {@code security.secureboot=false}
     * because catalog Linux images are unsigned, but a prepared image can genuinely
     * REQUIRE Secure Boot (Microsoft-signed Windows media boots fine with it on). The
     * setting is the image's own declaration, never an inference from the origin.
     */
    public static final BooleanField SECURE_BOOT = SETTINGS_SCHEMA.addField(
        BooleanField.builder("secure_boot").defaultValue(false)
            .label(HohenheimFormCopy.label("secure_boot"))
            .help(HohenheimFormCopy.help("secure_boot")).build());

    /**
     * A prepared image may carry no incus guest agent at all; when declared false, an
     * exec-driven operation (install/app-update) REFUSES BY NAME instead of waiting out
     * the ready timeout and reporting a false timeout.
     */
    public static final BooleanField GUEST_AGENT = SETTINGS_SCHEMA.addField(
        BooleanField.builder("guest_agent").defaultValue(true)
            .label(HohenheimFormCopy.label("guest_agent"))
            .help(HohenheimFormCopy.help("guest_agent")).build());

    @Override
    public @NonNull Identifier typeId() { return ID; }

    @Override
    public @NonNull String getDisplayName() { return "Virtual machine"; }

    @Override
    public @NonNull Microcopy getLabel() {
        return Microcopy.of("incus_vm").withFilter("scope", "instance_kind");
    }

    @Override
    public String getDescription() { return "Run a KVM virtual machine on an Incus host"; }

    @Override
    public Icon getIcon() { return Icon.of("server"); }

    @Override
    public String getColor() { return "purple"; }

    @Override
    public Schema getSchema() { return SETTINGS_SCHEMA; }

    @Override
    public @NonNull String requiredRuntime() { return ServerModel.RUNTIME_INCUS; }

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
        String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, instanceId);
        String image = settings.get("image") != null
            ? String.valueOf(settings.get("image")).trim() : "";
        String cloudInit = settings.get("cloud_init") instanceof String text
            && !text.isBlank() ? text : null;
        ImageOrigin imageOrigin = ImageOrigin.fromKey(
            settings.get("image_origin") instanceof String origin ? origin : null);
        boolean secureBoot = Boolean.TRUE.equals(settings.get("secure_boot"));
        boolean guestAgent = !Boolean.FALSE.equals(settings.get("guest_agent"));
        // No command override (a VM boots its own kernel), no env (nothing injects
        // into a guest's init -- cloud-init is the provisioning lane), no named
        // volumes (attached disks are instance_devices rows), no port publication
        // (a VM is an addressable system) -- each absence is structural.
        return new InstanceSpec(handle, image, null, Map.of(), Map.of(), null,
            ResourceLimits.fromSettings(settings, defaultFootprintMb()), VM,
            OwnerLabels.of(InstanceModel.MODEL_ID, instanceId), cloudInit, null,
            imageOrigin, secureBoot, guestAgent);
    }

    /**
     * A VM boots its own kernel, firmware and page cache before the guest's workload gets
     * anything, so its floor is a whole multiple of a container's. This is also the number
     * Incus itself defaults an unconfigured VM to, which keeps the booking and what the
     * hypervisor actually hands out the same figure.
     */
    @Override
    public int defaultFootprintMb() {
        return 1024;
    }

    @Override
    public void requirePlaceableOn(@NonNull String serverName,
                                   @NonNull Map<String, Object> settings) {
        requirePreparedImageOn(serverName, settings, IncusWorkloadType.VIRTUAL_MACHINE);
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
        ImageOrigin origin = ImageOrigin.fromKey(
            settings.get("image_origin") instanceof String key ? key : null);
        if (origin != ImageOrigin.PREPARED) {
            return;
        }
        String image = settings.get("image") != null
            ? String.valueOf(settings.get("image")).trim() : "";
        try {
            new IncusInstanceRuntime(new ServerService().incusClientFor(serverName),
                Egress.OPEN, type, serverName)
                .requirePreparedImagePresent(image, origin, false);
        } catch (IOException absent) {
            throw Violations.ofForm(Microcopy.of("host_prepared_image_missing")
                .withFilter("scope", "violations")
                .withArg("name", serverName)
                .withArg("image", image));
        }
    }
}
