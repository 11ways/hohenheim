package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.runtime.Egress;
import be.elevenways.hohenheim.server.runtime.IncusInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.IncusWorkloadType;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.DoubleField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.TextField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

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
 * flag because the hypervisor boundary is the point.
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
     */
    public static final TextField CLOUD_INIT = SETTINGS_SCHEMA.addField(
        TextField.builder().name("cloud_init").label(HohenheimFormCopy.label("cloud_init"))
            .help(HohenheimFormCopy.help("cloud_init")).build());

    public static final IntegerField MEMORY_LIMIT_MB = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("memory_limit_mb").label(HohenheimFormCopy.label("memory_limit"))
            .help(HohenheimFormCopy.help("memory_limit")).build());

    public static final DoubleField CPU_LIMIT = SETTINGS_SCHEMA.addField(
        DoubleField.builder().name("cpu_limit").label(HohenheimFormCopy.label("cpu_limit"))
            .help(HohenheimFormCopy.help("cpu_limit")).build());

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
        String handle = "hohenheim-instance-" + instanceId;
        String image = settings.get("image") != null
            ? String.valueOf(settings.get("image")).trim() : "";
        String cloudInit = settings.get("cloud_init") instanceof String text
            && !text.isBlank() ? text : null;
        // No command override (a VM boots its own kernel), no env (nothing injects
        // into a guest's init -- cloud-init is the provisioning lane), no named
        // volumes (attached disks are instance_devices rows), no port publication
        // (a VM is an addressable system) -- each absence is structural.
        return new InstanceSpec(handle, image, null, Map.of(), Map.of(), null,
            ResourceLimits.fromSettings(settings), VM,
            OwnerLabels.of(InstanceModel.MODEL_ID, instanceId), cloudInit, null);
    }
}
