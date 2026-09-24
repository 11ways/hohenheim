package be.elevenways.hohenheim.server.runtime;

import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.incus.IncusNetworkPolicy;
import be.elevenways.hohenheim.server.instance.InstanceVolumes;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The instance DEFINITION half of {@link IncusInstanceRuntime}: which config keys and devices
 * the driver writes, and how it reads its own labels and sizes back off a daemon object.
 *
 * AIDEV-NOTE: split out of IncusInstanceRuntime mechanically; every rule here is the one the
 * driver always applied. The read-modify-write of a definition itself is
 * {@code IncusClient.editInstance}, never a second copy here.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
final class IncusDefinitions {

    /** The daemon's name for the one device a workload cannot detach. */
    static final String ROOT_DEVICE = "root";

    /** Config-key prefix Incus reserves for arbitrary user metadata. */
    static final String USER_PREFIX = "user.";

    private IncusDefinitions() {
    }

    /** The config keys this driver OWNS on a converge (everything else is preserved). */
    static void applyManagedConfig(@NonNull InstanceSpec spec, @NonNull IncusWorkloadType type,
                                   @NonNull Map<String, Object> config) {
        if (type == IncusWorkloadType.VIRTUAL_MACHINE) {
            // Managed key: a converge re-asserts it, so an operator edit that drifted
            // from the image's DECLARATION cannot brick the next boot silently. The
            // value is the spec's declaration, not an inference: catalog Linux images are
            // unsigned and need it false, a prepared image (e.g. Microsoft-signed Windows
            // media) can genuinely need it true.
            config.put("security.secureboot", String.valueOf(spec.secureBoot()));
        }
        spec.ownerLabels().forEach((key, value) -> config.put(USER_PREFIX + key, value));
        spec.env().forEach((name, value) -> config.put("environment." + name, value));
        applyLimits(spec.limits(), config);
        // Cloud-init rides the daemon's own config key; the guest's cloud-init reads it
        // from the config drive on first boot (and only first boot -- instance-id bound).
        if (spec.cloudInitUserData() != null && !spec.cloudInitUserData().isBlank()) {
            config.put("cloud-init.user-data", spec.cloudInitUserData());
        }
        // Unprivileged is the DEFAULT and the deliberate posture; only the explicitly
        // declared privileged profile flips it (threat model boundary 1).
        if (IncusInstanceRuntime.PROFILE_PRIVILEGED.equals(spec.hardening().name())) {
            config.put("security.privileged", "true");
        }
        applyRunUser(spec, config);
    }

    /**
     * A spec that declares a run user boots the image's own init, which drops to that uid.
     *
     * AIDEV-NOTE: {@code lxc.init.cmd} rather than arguments, and the command in the
     * ENVIRONMENT rather than in that key: lxc.init.cmd is whitespace-split and does not
     * honour quoting, so a start command with a space would silently become two. The
     * image's {@code hohenheim-init} reads HOHENHEIM_START_COMMAND instead (images/README).
     *
     * AIDEV-NOTE: deliberately NO {@code raw.idmap}. An identity map would give host and
     * namespace uid PARITY, and it was measured working on nightstrom 2026-08-22 -- but
     * only after delegating the workspace uid window in /etc/subuid, and a second range
     * there makes Incus union both into one default map with two entries for namespace id
     * 0, after which EVERY container without its own raw.idmap fails to start. The uid
     * here is therefore a NAMESPACE id, and {@code WorkspaceUids.incusHostUid} is what the
     * controller chowns the volume to.
     */
    static void applyRunUser(@NonNull InstanceSpec spec, @NonNull Map<String, Object> config) {
        if (spec.runUser() == null) {
            return;
        }
        config.put("raw.lxc", "lxc.init.cmd = " + IncusInstanceRuntime.WORKSPACE_INIT);
    }

    /**
     * AIDEV-NOTE: this is a CONFIG-key predicate and the {@code limits.} clause therefore
     * covers {@code limits.memory} / {@code limits.cpu} / {@code limits.cpu.allowance} --
     * the instance-config namespace. The BANDWIDTH ceiling is not in it: Incus expresses a
     * rate as {@code limits.ingress} / {@code limits.egress} on the NIC DEVICE, which this
     * driver owns through {@code IncusNetworkPolicy.nicDevice} and rewrites wholesale on
     * every converge. Both namespaces are driver-owned on purpose, and both are now
     * DECLARABLE through the product (memory/cpu as ResourceLimits, the rate as
     * NetworkBandwidth) -- a driver-owned key with no product spelling is the shape that
     * makes a converge look like it is eating an operator's configuration.
     */
    static boolean isManagedKey(@NonNull String key) {
        return key.startsWith(USER_PREFIX) || key.startsWith("environment.")
            || key.startsWith("limits.") || key.startsWith("cloud-init.")
            || key.equals("security.privileged") || key.equals("security.secureboot");
    }

    /** Map the operator's cgroup caps onto Incus's limits vocabulary. */
    static void applyLimits(@NonNull ResourceLimits limits, @NonNull Map<String, Object> config) {
        if (limits.memoryMb() != null && limits.memoryMb() > 0) {
            config.put("limits.memory", limits.memoryMb() + "MiB");
        }
        if (limits.cpus() != null && limits.cpus() > 0) {
            double cpus = limits.cpus();
            if (cpus == Math.floor(cpus)) {
                config.put("limits.cpu", String.valueOf((int) cpus));
            } else {
                // Fractional cores have no core-count spelling; allowance is the
                // incus-native equivalent of Docker's NanoCpus.
                config.put("limits.cpu.allowance", Math.round(cpus * 100) + "%");
            }
        }
    }

    /**
     * The Hohenheim-owned host directories of a spec as {@code disk} devices.
     *
     * AIDEV-NOTE: {@code shift=true} is what makes the files usable from inside an
     * unprivileged container -- the daemon idmaps the host directory onto the container's
     * uid range instead of the workload seeing everything as {@code nobody}. Without it a
     * workspace's home directory would mount and then refuse every write, which reads as a
     * broken image rather than as a missing mount option.
     *
     * AIDEV-NOTE: these ARE {@code spec.volumes()}'s counterpart on this driver, and until
     * now a spec carrying volumes deployed on Incus lost them without a word -- the one
     * silent drop in a driver whose every other gap refuses by name. Named volumes still
     * have no Incus meaning; owned host directories do.
     */
    static @NonNull Map<String, Object> bindDevices(@NonNull InstanceSpec spec) {

        Map<String, Object> devices = new LinkedHashMap<>();
        int index = 0;
        // AIDEV-NOTE: the SAME containment rule the Docker funnel applies
        // (InstanceVolumes.requireMountableBy, called from
        // ContainerHardening.requireOwnVolumeSource), and it is called here because this
        // driver never passes through that funnel -- until 2026-08-23 an Incus workload's
        // disk devices were built from spec.binds() with NO check at all, not even the
        // volume-root bound the Docker side has had for weeks. A host path is a mount of
        // real tenant data whichever daemon materializes it.
        Integer instanceId = OwnerLabels.instanceIdOf(OwnerLabels.parse(spec.ownerLabels()));

        for (Map.Entry<String, String> bind : spec.binds().entrySet()) {
            if (bind.getValue() == null || bind.getValue().isBlank()) {
                continue;
            }
            if (instanceId == null) {
                throw new IllegalArgumentException("REFUSED to create container: '"
                    + spec.handle() + "' binds host path '" + bind.getKey() + "' while"
                    + " declaring no instance owner. A bind is permitted per INSTANCE, so a"
                    + " workload with no instance identity may bind nothing.");
            }
            InstanceVolumes.requireMountableBy(bind.getKey(), instanceId);
            Map<String, Object> device = new LinkedHashMap<>();
            device.put("type", "disk");
            device.put("source", bind.getKey());
            device.put("path", bind.getValue());
            // AIDEV-NOTE: no {@code shift} for a workspace. Both live twins report an
            // EMPTY kernel_features set, so the daemon never idmaps the mount and the
            // option is silently inert -- a workspace's home then reads as the raw host
            // uid from inside and refuses every write. The controller chowns the directory
            // to the host uid the namespace id maps to instead
            // (WorkspaceUids.incusHostUid), which needs no kernel feature at all.
            if (spec.runUser() == null) {
                device.put("shift", "true");
            }
            devices.put("hohvol" + (++index), device);
        }

        return devices;
    }

    /**
     * The managed network the default profile's NIC inherits (incusbr0). The device
     * override must name it, or Incus refuses a NIC with an ACL but no network.
     */
    static @NonNull String managedNetworkName(@NonNull IncusClient incus) throws IOException {
        Object devices = incus.profile("default").get("devices");
        if (devices instanceof Map<?, ?> map && map.get(IncusNetworkPolicy.NIC) instanceof Map<?, ?> nic
                && nic.get("network") instanceof String network && !network.isBlank()) {
            return network;
        }
        throw new IOException("REFUSED to isolate an Incus instance: the default profile has no"
            + " '" + IncusNetworkPolicy.NIC + "' NIC on a managed network to inherit, so there"
            + " is nothing to attach the isolation ACL to. This host is not admissible for"
            + " tenant workloads until its default profile carries a managed bridge NIC.");
    }

    /**
     * The pool the default profile's root disk lives on -- the one pool this driver places
     * custom volumes in, never a guess.
     */
    static @NonNull String managedPoolName(@NonNull IncusClient incus) throws IOException {
        Object devices = incus.profile("default").get("devices");
        if (devices instanceof Map<?, ?> map && map.get("root") instanceof Map<?, ?> root
                && root.get("pool") instanceof String pool && !pool.isBlank()) {
            return pool;
        }
        throw new IOException("REFUSED to place a volume: the default profile has no root"
            + " disk on a storage pool to inherit. This host is not admissible for disk"
            + " devices until its default profile carries a pooled root disk.");
    }

    /** Parse a daemon size value ("2GiB" or raw bytes) into whole GB, null when unreadable. */
    static @Nullable Integer parseSizeGb(@Nullable Object size) {
        if (size == null) {
            return null;
        }
        String text = String.valueOf(size).trim();
        if (text.endsWith("GiB")) {
            try {
                return Integer.parseInt(text.substring(0, text.length() - 3).trim());
            } catch (NumberFormatException unreadable) {
                return null;
            }
        }
        try {
            long bytes = Long.parseLong(text);
            return (int) (bytes / (1024L * 1024L * 1024L));
        } catch (NumberFormatException unreadable) {
            return null;
        }
    }

    /** A per-NIC MAC the daemon minted ({@code volatile.<nic>.hwaddr}). */
    static boolean isVolatileMac(@NonNull String key) {
        return key.startsWith("volatile.") && key.endsWith(".hwaddr");
    }

    /** The owner claim of an instance or volume object's {@code user.*} config, or null. */
    static OwnerLabels.@Nullable Owner ownerOf(@NonNull Map<String, Object> object) {
        if (!(object.get("config") instanceof Map<?, ?> config)) {
            return null;
        }
        Map<String, Object> labels = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : config.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (key.startsWith(USER_PREFIX)) {
                labels.put(key.substring(USER_PREFIX.length()), entry.getValue());
            }
        }
        return OwnerLabels.parse(labels);
    }

    /** The refusal text for a same-named object the daemon attributes to someone else. */
    static @NonNull String foreignOwner(OwnerLabels.@Nullable Owner actual) {
        return actual != null ? "owned by " + actual.model() + " #" + actual.id()
            + " of controller " + actual.controller()
            : "no hohenheim owner labels";
    }
}
