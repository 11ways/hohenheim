package be.elevenways.hohenheim.server.runtime;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.incus.ControllerPresence;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.incus.IncusKernelIsolation;
import be.elevenways.hohenheim.server.incus.IncusNetworkPolicy;
import be.elevenways.hohenheim.server.incus.IncusWebSocket;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Driver #2 of the instance tier: SYSTEM CONTAINERS on an Incus daemon, wrapping
 * {@link IncusClient} exactly as the Docker driver wraps DockerClient -- the client is
 * never the seam. Images come from the images: simplestreams server, owner labels ride
 * {@code user.*} config keys (stamped at create, the same attribution doctrine), and
 * the console is Incus's own websocket lane.
 *
 * AIDEV-NOTE: capability honesty. This driver implements {@link ConsoleStreamSupport},
 * {@link NativeSnapshotSupport}, {@link InstallSupport} and {@link AppUpdateSupport}:
 * an Incus system container's persistent state is its ROOTFS, not named volumes, so
 * {@code VolumeSnapshotSupport}'s tar-per-volume contract does not fit -- snapshots
 * are the daemon's own pool-resident snapshots and a backup is its whole-instance
 * export tarball. Install runs INSIDE the instance's own rootfs (created on demand,
 * started for the run, stopped after), NOT in a sibling workload: the app installs
 * into the system it will run on, which is the whole point of a system container.
 * File staging still refuses by name (files_unsupported), never a silent no-op.
 *
 * AIDEV-NOTE: threat model. Per-instance isolation is now ENFORCED through
 * {@link IncusNetworkPolicy}: every tenant instance's NIC carries the shared isolation
 * ACL that rejects egress to the host, the metadata range and every private range
 * (peers included), applied and read-back-VERIFIED before the container runs. A host
 * whose ACL support does not really enforce refuses at deploy. The internet and DNS stay
 * reachable; a closed-egress kind loses even those. What remains a declared limit is
 * boundary 1 of the plan's threat model: a system container is NOT a security boundary
 * against a determined root user, and privileged mode widens that further -- the network
 * ACL isolates the WIRE, not the kernel, and privileged still carries its escape warning.
 */
public final class IncusInstanceRuntime
        implements InstanceRuntime, ConsoleStreamSupport, NativeSnapshotSupport,
        InstallSupport, AppUpdateSupport, DeviceAttachSupport, RootDiskSizeSupport,
        RootDiskUsageSupport, ExecSupport, ImagePublishSupport {

    @Override
    public ExecSupport.@NonNull ExecOutcome runExec(@NonNull InstanceSpec spec,
                                                    @NonNull List<String> command,
                                                    long timeoutMs) throws IOException {
        if (!status(spec.handle()).running()) {
            throw new IOException("Instance '" + spec.handle() + "' is not running;"
                + " an exec runs inside the live system");
        }
        if (!spec.guestAgent()) {
            throw new IOException("Instance '" + spec.handle() + "' declares no guest agent"
                + " (guest_agent=false); its image cannot run an exec");
        }
        IncusClient.ExecResult result = this.incus.exec(spec.handle(), command,
            java.util.Map.of(), timeoutMs);
        return new ExecSupport.ExecOutcome(result.exitCode(), result.output());
    }

    /** The daemon's name for the one device a workload cannot detach. */
    static final String ROOT_DEVICE = "root";

    /** The public image server system-container aliases resolve against. */
    public static final String IMAGE_SERVER = "https://images.linuxcontainers.org";

    /** Hardening profile name that maps onto {@code security.privileged=true}. */
    public static final String PROFILE_PRIVILEGED = "incus-privileged";

    /** Config-key prefix Incus reserves for arbitrary user metadata. */
    private static final String USER_PREFIX = "user.";

    private final @NonNull IncusClient incus;
    private final @NonNull IncusNetworkPolicy policy;
    private final @NonNull Egress egress;
    private final @NonNull IncusWorkloadType type;
    private final @Nullable String serverName;

    public IncusInstanceRuntime(@NonNull IncusClient incus) {
        this(incus, Egress.OPEN);
    }

    /** @param egress the KIND-declared egress posture materialized into the NIC's ACL default */
    public IncusInstanceRuntime(@NonNull IncusClient incus, @NonNull Egress egress) {
        this(incus, egress, IncusWorkloadType.CONTAINER);
    }

    /** @param type the KIND-declared workload flavour (system container or KVM VM) */
    public IncusInstanceRuntime(@NonNull IncusClient incus, @NonNull Egress egress,
                                @NonNull IncusWorkloadType type) {
        this(incus, egress, type, null);
    }

    /**
     * @param serverName the host record this daemon belongs to, so the driver can reach
     *                   ITS kernel; null leaves the kernel-truth check out entirely (a
     *                   record-less caller has no host to read nftables on)
     */
    public IncusInstanceRuntime(@NonNull IncusClient incus, @NonNull Egress egress,
                                @NonNull IncusWorkloadType type,
                                @Nullable String serverName) {
        this.incus = incus;
        this.policy = new IncusNetworkPolicy(incus);
        this.egress = egress;
        this.type = type;
        this.serverName = serverName;
    }

    /**
     * Best-effort presence refresh beside every shared-object write.
     *
     * AIDEV-NOTE: this is what makes a controller ATTRIBUTABLE the moment it mints its
     * first shared object. The scheduled sweep was the only stamper before, so every
     * short-lived controller (each live test fork mints a fresh identity) left its
     * isolation ACL and hhx bridge UNSTAMPED -- and the reaper never removes unstamped
     * objects, by design. Measured on daystrom 2026-08-10: 78 controller tokens, only 13
     * presence markers, 91 ACLs accumulated. Best-effort on purpose: a failed stamp only
     * degrades to today's unstamped state (never reaped), and a deploy must not die for
     * a liveness marker. {@code ControllerPresence.stamp} no-ops while fresh, so this
     * costs one GET per deploy.
     */
    private void stampPresence() {
        try {
            ControllerPresence.stamp(this.incus);
        } catch (Exception unstamped) {
            Blast.log("INCUS PRESENCE: could not stamp controller presence on",
                this.serverName == null ? "the local daemon" : this.serverName, "-",
                unstamped.getMessage(),
                "- this controller's shared objects here stay UNSTAMPED (never reaped)");
        }
    }

    @Override
    public @NonNull String create(@NonNull InstanceSpec spec) throws IOException {
        OwnerLabels.Owner owner = OwnerLabels.parse(spec.ownerLabels());
        if (owner == null) {
            throw new IOException("InstanceSpec '" + spec.handle() + "' carries no valid owner"
                + " labels; an unattributable instance container is forbidden by design");
        }
        if (!spec.tmpfs().isEmpty()) {
            // The honest refusal, the cloud-init shape in reverse: silently dropping a
            // DECLARED discardable mount would land the workload's "ephemeral" data on
            // the host's storage pool, which is the opposite of what was declared.
            throw new IOException("The incus driver cannot deliver the RAM-backed scratch"
                + " mounts declared for '" + spec.handle() + "' (" + spec.tmpfs().keySet()
                + "); tmpfs mounts are a docker capability");
        }
        if (spec.healthCheck() != null) {
            // The honest refusal, same shape: Incus has no in-workload health probe it
            // runs and reports on, and a declared health gate nobody evaluates always
            // reports healthy -- which is worse than having no gate at all.
            throw new IOException("The incus driver cannot run the health check declared"
                + " for '" + spec.handle() + "'; a runtime-evaluated healthcheck is a"
                + " docker capability");
        }
        // The shared isolation ACL, VERIFIED in the daemon, BEFORE any instance is created
        // on it -- an Incus host whose ACL support does not really enforce refuses here,
        // never at the point where a tenant container is already running unisolated.
        this.policy.ensureIsolationAcl();
        stampPresence();
        Map<String, Object> nic = this.policy.nicDevice(managedNetworkName(), this.egress,
            spec.networkLimitMbit());

        // AIDEV-NOTE: converge, never replace. A system container's persistent state
        // IS its rootfs, so the Docker driver's replace-on-create semantic would be
        // silent data loss on every redeploy (and would destroy a freshly restored or
        // imported instance). An existing OWNED instance keeps its rootfs and gets the
        // driver-managed config keys rewritten; a same-named FOREIGN instance stays a
        // loud refusal; a changed settings image only applies at absent-then-create
        // (reinstall is the explicit wipe path, Phase 5's template policy).
        Map<String, Object> existing = ownedExisting(spec.handle(), owner);
        if (existing != null) {
            // A same-named OWNED workload of the WRONG flavour is never converged over:
            // a container record cannot adopt a VM's definition or vice versa.
            String existingType = String.valueOf(existing.get("type"));
            if (!this.type.apiType().equals(existingType)) {
                throw new IOException("REFUSED to converge '" + spec.handle() + "': the"
                    + " daemon holds a " + existingType + " under this name but this kind"
                    + " declares " + this.type.apiType() + ". Destroy the workload"
                    + " explicitly before changing its flavour.");
            }
            converge(spec, existing, nic);
            verifyIsolated(spec);
            // A root-size change on an EXISTING workload is never folded into the
            // converge PUT: a running grow is accepted and not performed (see
            // RootDiskSizeSupport), so it goes through the stopped-only path, which
            // refuses by name rather than reporting a success it did not deliver.
            reconcileRootDisk(spec);
            return spec.handle();
        }

        Map<String, Object> config = new LinkedHashMap<>();
        // Includes security.secureboot for a VM: the spec's OWN declaration (default
        // false, since catalog VM builds are not Secure Boot signed and the first launch
        // fails naming exactly that, verified live on daystrom); a prepared image may
        // declare true.
        applyManagedConfig(spec, config);

        // Pin honesty: an ABSENT workload with a recorded resolved fingerprint is
        // recreated from THAT image, never by re-resolving the mutable alias.
        Map<String, Object> source = new LinkedHashMap<>();
        boolean pinned = spec.imageFingerprint() != null && !spec.imageFingerprint().isBlank();
        if (spec.imageOrigin() == ImageOrigin.INSTALL_MEDIA) {
            // An EMPTY workload: no image anywhere, the OS arrives interactively from
            // attached install media. VM-only -- a system container shares the host
            // kernel and has no firmware to boot an ISO with.
            if (this.type != IncusWorkloadType.VIRTUAL_MACHINE) {
                throw new IOException("REFUSED to create '" + spec.handle() + "': the"
                    + " install_media origin declares an empty VM installed from an ISO,"
                    + " and a " + this.type.apiType() + " cannot boot install media.");
            }
            source.put("type", "none");
        } else {
            source.put("type", "image");
            if (spec.imageOrigin() == ImageOrigin.PREPARED) {
                // No protocol/server: the daemon resolves this in its OWN image store,
                // never fetched from anywhere.
                requirePreparedImagePresent(spec.image(), spec.imageOrigin(), pinned);
            } else {
                source.put("protocol", "simplestreams");
                source.put("server", IMAGE_SERVER);
            }
            if (pinned) {
                source.put("fingerprint", spec.imageFingerprint());
            } else {
                source.put("alias", spec.image());
            }
        }

        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("name", spec.handle());
        definition.put("type", this.type.apiType());
        definition.put("source", source);
        definition.put("config", config);
        // The isolating NIC override is in the CREATE body: there is no instant at which
        // an instance of ours exists on the bridge without it.
        Map<String, Object> devices = new LinkedHashMap<>();
        devices.put(IncusNetworkPolicy.NIC, nic);
        // The root quota rides the CREATE body too: the daemon sizes the volume while it
        // makes it, so the workload never exists at an unquotaed size. (A VM whose
        // declared size is under the image's own volume is refused by the daemon here,
        // verbatim -- that is a real constraint, not something to paper over.)
        if (spec.rootDiskGb() != null) {
            devices.put(ROOT_DEVICE, rootDevice(spec.rootDiskGb()));
        }
        definition.put("devices", devices);
        definition.put("profiles", List.of("default"));
        this.incus.createInstance(definition);
        verifyIsolated(spec);
        verifyRootDiskDeclared(spec);
        return spec.handle();
    }

    /** The root disk device override: the default profile's pool, our declared size. */
    private @NonNull Map<String, Object> rootDevice(int sizeGb) throws IOException {
        Map<String, Object> device = new LinkedHashMap<>();
        device.put("type", "disk");
        device.put("path", "/");
        device.put("pool", managedPoolName());
        device.put("size", sizeGb + "GiB");
        return device;
    }

    /** Read back what the daemon DECLARES for the root device after a write that set it. */
    private void verifyRootDiskDeclared(@NonNull InstanceSpec spec) throws IOException {
        Integer declared = spec.rootDiskGb();
        if (declared == null) {
            return;
        }
        Integer actual = rootDiskGb(spec);
        if (actual == null || !actual.equals(declared)) {
            throw new IOException("Root disk of '" + spec.handle() + "' was accepted at "
                + declared + "GiB but the daemon reports " + actual + "GiB");
        }
    }

    @Override
    public @Nullable Integer rootDiskGb(@NonNull InstanceSpec spec) throws IOException {
        Map<String, Object> instance = this.incus.instance(spec.handle());
        if (instance.get("devices") instanceof Map<?, ?> devices
                && devices.get(ROOT_DEVICE) instanceof Map<?, ?> root) {
            return parseSizeGb(root.get("size"));
        }
        return null;
    }

    /**
     * The daemon's OBSERVED root-disk figures, straight out of the instance state.
     *
     * AIDEV-NOTE: {@code total} is 0 when the workload declares no root size, and it is
     * passed through as 0 rather than substituted with the pool's capacity. A workload with
     * no enforced ceiling genuinely has no percentage, and inventing one would produce a
     * reassuring "3% used" for storage nothing is rationing.
     */
    @Override
    public RootDiskUsageSupport.@Nullable DiskUsage rootDiskUsage(@NonNull InstanceSpec spec)
            throws IOException {
        Map<String, Object> state;
        try {
            state = this.incus.instanceState(spec.handle());
        } catch (IncusClient.ApiException e) {
            if (e.isNotFound()) {
                return null;   // observed absent: no workload to measure
            }
            throw e;
        }
        if (!"Running".equalsIgnoreCase(String.valueOf(state.get("status")))) {
            return null;
        }
        if (!(state.get("disk") instanceof Map<?, ?> disks
                && disks.get(ROOT_DEVICE) instanceof Map<?, ?> root)) {
            return null;
        }
        Object usage = root.get("usage");
        if (!(usage instanceof Number used)) {
            return null;   // no figure is not a zero figure
        }
        long total = root.get("total") instanceof Number number ? number.longValue() : 0;
        return new RootDiskUsageSupport.DiskUsage(used.longValue(), Math.max(0, total));
    }

    @Override
    public void resizeRootDisk(@NonNull InstanceSpec spec, int sizeGb) throws IOException {
        ContainerState state = status(spec.handle()).state();
        if (state != ContainerState.STOPPED) {
            // The load-bearing guard, not a convenience: a running grow is ACCEPTED and
            // not performed, and the accepted config then blocks the correct retry.
            throw new IOException("REFUSED to resize the root disk of '" + spec.handle()
                + "': the workload is " + state + " and a root disk can only be resized"
                + " while it is STOPPED. Stop it and deploy again.");
        }
        Integer current = rootDiskGb(spec);
        if (current != null && sizeGb < current) {
            throw new IOException("REFUSED to shrink the root disk of '" + spec.handle()
                + "' from " + current + "GiB to " + sizeGb + "GiB: a root disk can only"
                + " grow. Create a smaller workload and migrate the data instead.");
        }
        Map<String, Object> instance = this.incus.instance(spec.handle());
        putDevice(spec.handle(), instance, ROOT_DEVICE, rootDevice(sizeGb));
        Integer actual = rootDiskGb(spec);
        if (actual == null || actual != sizeGb) {
            throw new IOException("Resize of the root disk of '" + spec.handle() + "' to "
                + sizeGb + "GiB did not take: the daemon reports " + actual + "GiB");
        }
    }

    /**
     * Bring an EXISTING workload's root disk to its declared size, or refuse by name.
     *
     * A declaration that already matches costs one read and does nothing; a declaration
     * that is absent leaves the daemon alone entirely (the knob is opt-in, and clearing
     * it must not silently shrink anything).
     *
     * AIDEV-NOTE: known limitation, and it belongs to the daemon rather than to us. If
     * something OUTSIDE this product grows a RUNNING instance's root device, Incus 7.3
     * records the new size and does not apply it, and every API read-back then echoes
     * the recorded value. This reconcile would see "already at the declared size" and
     * skip -- correctly, by every fact it can obtain. Refusing a running grow HERE is
     * what keeps the product from creating that state; it cannot repair one it did not
     * create. The only detection is inside the guest.
     */
    private void reconcileRootDisk(@NonNull InstanceSpec spec) throws IOException {
        Integer declared = spec.rootDiskGb();
        if (declared == null) {
            return;
        }
        Integer current = rootDiskGb(spec);
        if (current != null && current.equals(declared)) {
            return;
        }
        resizeRootDisk(spec, declared);
    }

    /** Read one instance back and require EVERY NIC to carry the isolation just written. */
    private void verifyIsolated(@NonNull String handle) throws IOException {
        this.policy.verifyAllNics(handle, this.incus.instance(handle), this.egress);
    }

    /**
     * Isolation AND the declared bandwidth ceiling, read back from the same instance
     * document -- one GET, so the rate check costs nothing on top of the ACL check.
     *
     * AIDEV-NOTE: a spec that declares NO ceiling behaves exactly as before; the rate half
     * returns immediately. That bound is deliberate (see IncusNetworkPolicy.verifyBandwidth):
     * shipping a read-back that could refuse a deploy is only acceptable while it cannot
     * refuse one that did not ask for the feature.
     */
    private void verifyIsolated(@NonNull InstanceSpec spec) throws IOException {
        Map<String, Object> instance = this.incus.instance(spec.handle());
        this.policy.verifyAllNics(spec.handle(), instance, this.egress);
        this.policy.verifyBandwidth(spec.handle(), instance, spec.networkLimitMbit());
    }

    /**
     * The managed network the default profile's NIC inherits (incusbr0). The device
     * override must name it, or Incus refuses a NIC with an ACL but no network.
     */
    private @NonNull String managedNetworkName() throws IOException {
        Object devices = this.incus.profile("default").get("devices");
        if (devices instanceof Map<?, ?> map && map.get(IncusNetworkPolicy.NIC) instanceof Map<?, ?> nic
                && nic.get("network") instanceof String network && !network.isBlank()) {
            return network;
        }
        throw new IOException("REFUSED to isolate an Incus instance: the default profile has no"
            + " '" + IncusNetworkPolicy.NIC + "' NIC on a managed network to inherit, so there"
            + " is nothing to attach the isolation ACL to. This host is not admissible for"
            + " tenant workloads until its default profile carries a managed bridge NIC.");
    }

    /** The config keys this driver OWNS on a converge (everything else is preserved). */
    private void applyManagedConfig(@NonNull InstanceSpec spec,
                                    @NonNull Map<String, Object> config) {
        if (this.type == IncusWorkloadType.VIRTUAL_MACHINE) {
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
        if (PROFILE_PRIVILEGED.equals(spec.hardening().name())) {
            config.put("security.privileged", "true");
        }
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
    private static boolean isManagedKey(@NonNull String key) {
        return key.startsWith(USER_PREFIX) || key.startsWith("environment.")
            || key.startsWith("limits.") || key.startsWith("cloud-init.")
            || key.equals("security.privileged") || key.equals("security.secureboot");
    }

    /** Rewrite the managed config of an existing OWNED instance; the rootfs is untouched. */
    private void converge(@NonNull InstanceSpec spec, @NonNull Map<String, Object> existing,
                          @NonNull Map<String, Object> nic) throws IOException {
        Map<String, Object> config = new LinkedHashMap<>();
        if (existing.get("config") instanceof Map<?, ?> current) {
            current.forEach((key, value) -> {
                String name = String.valueOf(key);
                // volatile.* and image.* keys ride along unchanged (read-modify-write,
                // the CLI's own shape); only the managed keys are recomputed, so a
                // value REMOVED from the settings really disappears.
                if (!isManagedKey(name)) {
                    config.put(name, value);
                }
            });
        }
        applyManagedConfig(spec, config);
        replaceDefinition(spec.handle(), existing, config, nic);
    }

    /** PUT the instance's mutable definition with a rewritten config map and NIC override. */
    private void replaceDefinition(@NonNull String handle,
                                   @NonNull Map<String, Object> existing,
                                   @NonNull Map<String, Object> config,
                                   @NonNull Map<String, Object> nic) throws IOException {
        putDefinition(handle, existing, config, nic);
    }

    /** @param nic the isolating NIC override, or null to leave the devices untouched */
    private void putDefinition(@NonNull String handle,
                               @NonNull Map<String, Object> existing,
                               @NonNull Map<String, Object> config,
                               @Nullable Map<String, Object> nic) throws IOException {
        Map<String, Object> devices = new LinkedHashMap<>();
        if (existing.get("devices") instanceof Map<?, ?> current) {
            current.forEach((key, value) -> devices.put(String.valueOf(key), value));
        }
        // The isolating NIC override is (re)written every converge: a reboot or an
        // operator edit that dropped it is repaired here, not silently tolerated.
        if (nic != null) {
            devices.put(IncusNetworkPolicy.NIC, nic);
        }

        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("architecture", existing.get("architecture"));
        definition.put("config", config);
        definition.put("devices", devices);
        definition.put("ephemeral", Boolean.TRUE.equals(existing.get("ephemeral")));
        definition.put("profiles", existing.get("profiles") instanceof List<?> profiles
            ? profiles : List.of("default"));
        definition.put("description", existing.get("description") instanceof String text
            ? text : "");
        this.incus.updateInstance(handle, definition);
    }

    /**
     * THE prepared-image constraint, in ONE place: an unpinned prepared alias must already
     * exist in this daemon's own image store.
     *
     * AIDEV-NOTE: extracted from create() so PLACEMENT can consult the same rule before
     * choosing this host (SystemContainerKind/VmKind.requirePlaceableOn). Placement
     * choosing a host whose deploy then refuses by name was a wrong ELIGIBLE SET, and the
     * fix is one authority with two callers -- never a second copy of the rule in the
     * chooser. A CATALOG image returns immediately without touching the daemon, so the
     * common create path pays nothing for this.
     *
     * @throws IOException when the alias is absent, or the daemon cannot be asked
     */
    public void requirePreparedImagePresent(@Nullable String image, @NonNull ImageOrigin origin,
                                            boolean pinned) throws IOException {
        if (origin != ImageOrigin.PREPARED || pinned || image == null || image.isBlank()) {
            // A blank image is the create form's refusal to make, not this one's.
            return;
        }
        if (this.incus.imageFingerprintForAlias(image) == null) {
            // Without this the daemon's own refusal for a missing local alias reads as a
            // generic create failure, and the operator has no idea the alias is absent.
            throw new IOException("Prepared image alias '" + image + "' does not"
                + " exist on server '" + (this.serverName != null ? this.serverName
                : "(unnamed)") + "'; a prepared image is published into the daemon's own"
                + " image store by an operator and is never fetched.");
        }
    }

    /** Map the operator's cgroup caps onto Incus's limits vocabulary. */
    private static void applyLimits(@NonNull ResourceLimits limits,
                                    @NonNull Map<String, Object> config) {
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

    @Override
    public void start(@NonNull String handle) throws IOException {
        try {
            this.incus.changeState(handle, "start", -1, false);
        } catch (IncusClient.ApiException refused) {
            // "already running" is idempotent success (the converge path deploys over
            // a running instance); every other refusal stays a refusal.
            if (status(handle).state() != ContainerState.RUNNING) {
                throw refused;
            }
        }
        requireKernelIsolation(handle);
    }

    /**
     * The KERNEL half of the isolation contract, checked at the one moment the driver
     * makes a workload reachable.
     *
     * AIDEV-NOTE: the daemon's config read-back ({@link IncusNetworkPolicy}) and the
     * daemon host's nftables are independent facts, and they were observed to disagree
     * (see {@link IncusKernelIsolation}). This check is NOT the mechanism that closes
     * that window -- the divergence is created later, by incusd's own restart of a VM a
     * tenant reset -- {@code VerifyIncusIsolation} is. What it does close is the case
     * where the hole is ALREADY open when we start a workload into it. A workload whose
     * isolation cannot be restored is stopped again before this method returns: it does
     * not stay reachable while an operator reads a log line.
     */
    private void requireKernelIsolation(@NonNull String handle) throws IOException {
        String name = this.serverName;
        if (name == null) {
            return;
        }
        Row server = Models.get(ServerModel.class).findByName(name);
        if (server == null) {
            return;
        }
        IncusKernelIsolation kernel = IncusKernelIsolation.forServer(server);
        if (!kernel.available()) {
            // Refusing to answer is not evidence of a leak; the sweep reports the host as
            // unverifiable every run rather than manufacturing a verdict here.
            return;
        }
        try {
            kernel.enforce(handle);
        } catch (IOException unisolated) {
            stop(handle, 10);
            throw unisolated;
        }
    }

    @Override
    public void stop(@NonNull String handle, int graceSeconds) throws IOException {
        try {
            this.incus.changeState(handle, "stop", graceSeconds, false);
        } catch (IncusClient.ApiException refused) {
            // "already stopped" is the idempotent success Docker answers with 304; every
            // OTHER refusal falls through to the forced stop -- Docker's stop kills
            // after the grace window, and this driver keeps that contract.
            if (status(handle).state() == ContainerState.STOPPED) {
                return;
            }
            this.incus.changeState(handle, "stop", graceSeconds, true);
        }
    }

    @Override
    public void destroy(@NonNull String handle) throws IOException {
        try {
            this.incus.changeState(handle, "stop", 10, true);
        } catch (IOException ignored) {
            // stop is a courtesy (already stopped, or already absent); the delete below
            // is the authority
        }
        try {
            this.incus.deleteInstance(handle);
        } catch (IncusClient.ApiException e) {
            if (!e.isNotFound()) {
                throw e;   // refused: NOT gone
            }
            // 404 = observed absent, which is the outcome destroy exists for.
        }
    }

    @Override
    public @NonNull InstanceStatus status(@NonNull String handle) {
        Map<String, Object> state;
        try {
            state = this.incus.instanceState(handle);
        } catch (IncusClient.ApiException e) {
            return new InstanceStatus(e.isNotFound()
                ? ContainerState.ABSENT : ContainerState.UNREACHABLE, null);
        } catch (IOException e) {
            return new InstanceStatus(ContainerState.UNREACHABLE, null);
        }
        boolean running = "Running".equalsIgnoreCase(String.valueOf(state.get("status")));
        // No published port: an Incus container is an addressable system, not a
        // port-mapped process (proxy devices are a later mechanism).
        return new InstanceStatus(running ? ContainerState.RUNNING : ContainerState.STOPPED,
            null, null, running ? liveness(handle) : WorkloadLiveness.UNKNOWN);
    }

    /** The cgroup-v2 file that carries the kill counter, as the instance itself sees it. */
    static final String MEMORY_EVENTS = "/sys/fs/cgroup/memory.events";

    /** Wall-clock cap on the liveness read; a wedged instance must not stall a poll. */
    private static final long LIVENESS_TIMEOUT_MS = 10_000;

    /**
     * THE Incus answer to "is the workload inside this running instance still alive":
     * the {@code oom_kill} counter in the instance's OWN cgroup, read over exec.
     *
     * AIDEV-NOTE: this closes what the driver used to refuse by name. Incus 7.3's
     * instance state carries no OOM counter at all (memory is usage/peak/swap only --
     * verified against a real daemon, the whole {@code /1.0/instances/x/state} body), so
     * the signal has to come out of the kernel. A system container gets its OWN cgroup
     * namespace, so {@code /sys/fs/cgroup/memory.events} inside it IS its payload cgroup
     * -- measured identical to the host's {@code /sys/fs/cgroup/lxc.payload.<name>/}
     * view, byte for byte. The cost is one exec per RUNNING instance per poll (~100 ms
     * measured on daystrom, Incus 7.3); a stopped instance is never exec'd.
     *
     * AIDEV-NOTE: PRESSURE IS NOT A KILL, and the same counterfactual that pinned the
     * Docker side pins this one. Measured on daystrom in a 64 MiB container: 400 MB of
     * page-cache churn gave {@code max 7168, oom_kill 0} while the workload was perfectly
     * healthy, and one OOM-killed CHILD gave {@code oom_kill 1} with the instance still
     * RUNNING. Reading {@code max} would call the first one dead.
     *
     * AIDEV-NOTE: a VIRTUAL MACHINE is refused BY DECLARATION, not by letting the read
     * fail. A VM's guest kernel has no {@code memory.events} at its cgroup root (measured:
     * "can't open ... No such file or directory"), and its memory ceiling is the
     * hypervisor's rather than a cgroup's, so there is nothing to read even in principle
     * -- paying a doomed exec every poll to discover that again would be waste. Every
     * remaining read failure (agent not up yet, exec refused, a garbled body) lands in the
     * same place: UNKNOWN, never SERVING.
     */
    private @NonNull WorkloadLiveness liveness(@NonNull String handle) {
        if (this.type != IncusWorkloadType.CONTAINER) {
            return WorkloadLiveness.UNKNOWN;
        }
        IncusClient.ExecResult result;
        try {
            result = this.incus.exec(handle, List.of("cat", MEMORY_EVENTS), Map.of(),
                LIVENESS_TIMEOUT_MS);
        } catch (IOException | RuntimeException unreadable) {
            return WorkloadLiveness.UNKNOWN;
        }
        if (result.exitCode() != 0) {
            return WorkloadLiveness.UNKNOWN;
        }
        return parseOomKill(result.output());
    }

    /**
     * Read the {@code oom_kill} line out of a cgroup-v2 {@code memory.events} body.
     *
     * @return WORKLOAD_DEAD on a non-zero kill count, SERVING on zero, UNKNOWN when the
     *         body carries no {@code oom_kill} line at all -- an absent counter is not a
     *         zero counter
     */
    static @NonNull WorkloadLiveness parseOomKill(@NonNull String events) {
        for (String line : events.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("oom_kill ")) {
                continue;
            }
            try {
                return Long.parseLong(trimmed.substring("oom_kill ".length()).trim()) > 0
                    ? WorkloadLiveness.WORKLOAD_DEAD : WorkloadLiveness.SERVING;
            } catch (NumberFormatException garbled) {
                return WorkloadLiveness.UNKNOWN;
            }
        }
        return WorkloadLiveness.UNKNOWN;
    }

    // -- ConsoleStreamSupport -------------------------------------------------

    /** Incus refuses a console operation on a stopped instance: attach must follow start. */
    @Override
    public boolean attachRequiresRunning() {
        return true;
    }

    @Override
    public @NonNull Console openConsole(@NonNull String handle) throws IOException {
        Map<String, Object> operation = this.incus.startConsole(handle);
        Object id = operation.get("id");
        Object metadata = operation.get("metadata");
        String secret = metadata instanceof Map<?, ?> meta
            && meta.get("fds") instanceof Map<?, ?> fds
            && fds.get("0") instanceof String value ? value : null;
        if (id == null || secret == null) {
            throw new IOException("Incus console operation of '" + handle
                + "' carried no websocket secret");
        }
        IncusWebSocket socket = this.incus.operationWebSocket(
            "/1.0/operations/" + id, secret);
        // /dev/console is bidirectional by construction: what we write IS delivered to
        // the workload's console, unlike Docker's discarded attach-without-OpenStdin.
        return new Console(new IncusConsoleStream(socket), true);
    }

    @Override
    public @NonNull String consoleTail(@NonNull String handle, int lines) throws IOException {
        String log = this.incus.consoleLog(handle);
        String[] all = log.split("\n", -1);
        if (all.length <= lines) {
            return log;
        }
        return String.join("\n", List.of(all).subList(all.length - lines, all.length));
    }

    /**
     * @throws IOException ALWAYS for a stopped workload: Incus does not report the init
     *         process's exit status, and inventing one (0, -1) would misclassify a
     *         crash as a stop or vice versa -- the console hub's "could not confirm the
     *         exit" lane is the honest landing for this named refusal
     */
    @Override
    public @Nullable Integer exitCode(@NonNull String handle) throws IOException {
        if (status(handle).running()) {
            return null;
        }
        throw new IOException("Incus reports no init exit status for '" + handle
            + "'; the exit outcome cannot be observed on this driver");
    }

    // -- InstallSupport / AppUpdateSupport ------------------------------------

    /** Output tail cap of one install/update run (the durable failure record). */
    private static final int OUTPUT_TAIL_CHARS = 16 * 1024;

    /**
     * Run the install script INSIDE the instance's own system container: create it if
     * absent (the converge path keeps an existing owned rootfs), start it, exec the
     * script with {@code bash -ec}, and stop it again -- the platform's "installed but
     * not running" state stays true at the daemon.
     *
     * @throws IOException for a separate install image: the rootfs IS the install
     *         target, so "run the install elsewhere" cannot be honoured, only refused
     */
    @Override
    public @NonNull InstallOutcome runInstall(@NonNull InstanceSpec spec,
                                              @NonNull String installImage,
                                              @NonNull String script,
                                              @NonNull Map<String, String> env,
                                              long timeoutMs) throws IOException {
        if (!installImage.equals(spec.image())) {
            throw new IOException("The incus driver runs install steps inside the"
                + " instance's own rootfs; a separate install image ('" + installImage
                + "') cannot be honoured. Leave the template's install image empty.");
        }
        // The readiness wait exists to ride out a real guest agent's bring-up; an
        // agent-less image would never answer it, so burning the full
        // execReadyTimeoutMs and reporting a timeout would misreport an absent
        // capability as a broken guest. Refuse by name, and refuse BEFORE create so a
        // workload is never born just to be torn down again.
        if (!spec.guestAgent()) {
            throw new IOException("Instance '" + spec.handle() + "' declares no guest"
                + " agent (guest_agent=false); its image cannot run an exec-driven"
                + " install");
        }
        create(spec);
        boolean started = false;
        try {
            start(spec.handle());
            started = true;
            IncusClient.ExecResult result = execWhenReady(spec.handle(),
                List.of("bash", "-ec", script), env, timeoutMs);
            return new InstallOutcome(result.exitCode(), tailOf(result.output()));
        } finally {
            if (started) {
                try {
                    stop(spec.handle(), 10);
                } catch (IOException stopFailed) {
                    Blast.log("INCUS: could not stop", spec.handle(),
                        "after its install run:", stopFailed.getMessage());
                }
            }
        }
    }

    /** Run the update script inside the RUNNING workload (services restart in place). */
    @Override
    public InstallSupport.@NonNull InstallOutcome runAppUpdate(@NonNull InstanceSpec spec,
                                                               @NonNull String script,
                                                               @NonNull Map<String, String> env,
                                                               long timeoutMs)
            throws IOException {
        if (!status(spec.handle()).running()) {
            throw new IOException("Instance '" + spec.handle() + "' is not running;"
                + " the in-place app update runs inside the live system");
        }
        if (!spec.guestAgent()) {
            throw new IOException("Instance '" + spec.handle() + "' declares no guest agent"
                + " (guest_agent=false); its image cannot run an exec-driven app update");
        }
        IncusClient.ExecResult result = this.incus.exec(spec.handle(),
            List.of("bash", "-ec", script), env, timeoutMs);
        return new InstallOutcome(result.exitCode(), tailOf(result.output()));
    }

    /**
     * Exec with a bring-up retry: the daemon refuses execs while the workload's init
     * (container) or incus agent (VM -- tens of seconds after start) is still coming
     * up, and that refusal must not fail the install. The window is the DECLARED
     * per-flavour one ({@link IncusWorkloadType#execReadyTimeoutMs()}).
     */
    private IncusClient.@NonNull ExecResult execWhenReady(@NonNull String handle,
                                                          @NonNull List<String> command,
                                                          @NonNull Map<String, String> env,
                                                          long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + this.type.execReadyTimeoutMs();
        while (true) {
            try {
                return this.incus.exec(handle, command, env, timeoutMs);
            } catch (IncusClient.ApiException refused) {
                if (System.currentTimeMillis() >= deadline) {
                    throw refused;
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw refused;
                }
            }
        }
    }

    private static @NonNull String tailOf(@NonNull String output) {
        if (output.length() <= OUTPUT_TAIL_CHARS) {
            return output;
        }
        return output.substring(output.length() - OUTPUT_TAIL_CHARS);
    }

    // -- NativeSnapshotSupport ------------------------------------------------

    @Override
    public void createSnapshot(@NonNull InstanceSpec spec, @NonNull String snapshotName)
            throws IOException {
        this.incus.createSnapshot(spec.handle(), snapshotName);
    }

    @Override
    public boolean snapshotExists(@NonNull InstanceSpec spec, @NonNull String snapshotName)
            throws IOException {
        try {
            this.incus.snapshot(spec.handle(), snapshotName);
            return true;
        } catch (IncusClient.ApiException e) {
            if (e.isNotFound()) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public void restoreSnapshot(@NonNull InstanceSpec spec, @NonNull String snapshotName)
            throws IOException {
        this.incus.restoreSnapshot(spec.handle(), snapshotName);
    }

    @Override
    public void deleteSnapshot(@NonNull InstanceSpec spec, @NonNull String snapshotName)
            throws IOException {
        try {
            this.incus.deleteSnapshot(spec.handle(), snapshotName);
        } catch (IncusClient.ApiException e) {
            if (!e.isNotFound()) {
                throw e;   // refused: NOT gone
            }
            // 404 = observed absent, which is what delete exists to establish.
        }
    }

    @Override
    public long exportBackup(@NonNull InstanceSpec spec, @NonNull Path destination,
                             long maxBytes, boolean withSnapshots) throws IOException {
        // The daemon-side backup object is a TEMPORARY: the export tarball is the
        // artifact, and leaving the object behind would silently fill the pool.
        String backupName = "hib-" + System.currentTimeMillis();
        this.incus.createBackup(spec.handle(), backupName, !withSnapshots);
        try {
            return this.incus.exportBackup(spec.handle(), backupName, destination, maxBytes);
        } finally {
            try {
                this.incus.deleteBackup(spec.handle(), backupName);
            } catch (IOException cleanupFailed) {
                Blast.log("INCUS: could not remove temporary backup object", backupName,
                    "of", spec.handle(), ":", cleanupFailed.getMessage());
            }
        }
    }

    @Override
    public void importBackup(@NonNull InstanceSpec spec, @NonNull Path archive)
            throws IOException {
        this.incus.importInstance(archive, spec.handle());
        // Re-identification is part of the import contract: the tarball carries the
        // SOURCE instance's user.* labels (until they are replaced the import is
        // attributed to the wrong record -- a crash inside the window leaves an
        // instance the NEW record's next deploy refuses as foreign, visible operator
        // cleanup, never silent adoption) AND the source's volatile NIC MACs, which
        // the daemon refuses beside the still-running source ("MAC address already
        // defined on another NIC"). Dropping the hwaddr keys makes the daemon mint
        // fresh ones at start.
        Map<String, Object> existing = this.incus.instance(spec.handle());
        Map<String, Object> config = new LinkedHashMap<>();
        boolean carriedMacs = false;
        if (existing.get("config") instanceof Map<?, ?> current) {
            for (Map.Entry<?, ?> entry : current.entrySet()) {
                String name = String.valueOf(entry.getKey());
                if (name.startsWith("volatile.") && name.endsWith(".hwaddr")) {
                    carriedMacs = true;
                    continue;
                }
                config.put(name, entry.getValue());
            }
        }
        spec.ownerLabels().forEach((key, value) -> config.put(USER_PREFIX + key, value));
        // AIDEV-NOTE: the MAC strip is its OWN write, BEFORE ensureIsolationAcl, and the
        // order is load-bearing. Between import and the strip the clone and its source
        // share a MAC at the daemon, and ANY ACL write in that window makes the daemon
        // re-trigger every referencing NIC and fail 409 on the duplicate (observed live
        // on the source instance, not the clone). This write touches only the clone's
        // own definition -- devices unchanged -- so it cannot trip over other instances.
        if (carriedMacs) {
            putDefinition(spec.handle(), existing, config, null);
        }
        // An imported instance re-joins the fleet's isolation exactly like a fresh one:
        // its NIC gets the verified ACL override, so a backup made before isolation
        // existed cannot land an unisolated container.
        this.policy.ensureIsolationAcl();
        stampPresence();
        replaceDefinition(spec.handle(), existing, config,
            this.policy.nicDevice(managedNetworkName(), this.egress, spec.networkLimitMbit()));
        verifyIsolated(spec);
    }

    @Override
    public @NonNull WorkloadClaim claimOf(@NonNull InstanceSpec spec) throws IOException {
        OwnerLabels.Owner owner = OwnerLabels.parse(spec.ownerLabels());
        if (owner == null) {
            throw new IOException("InstanceSpec '" + spec.handle() + "' carries no valid"
                + " owner labels; an attribution question without an owner has no answer");
        }
        Map<String, Object> existing;
        try {
            existing = this.incus.instance(spec.handle());
        } catch (IncusClient.ApiException e) {
            if (e.isNotFound()) {
                return WorkloadClaim.ABSENT;
            }
            throw e;
        }
        OwnerLabels.Owner actual = ownerOf(existing);
        boolean ours = actual != null && actual.model().equals(owner.model())
            && actual.id().equals(owner.id());
        return ours ? WorkloadClaim.OURS : WorkloadClaim.FOREIGN;
    }

    @Override
    public @NonNull ImageIdentity imageIdentity(@NonNull InstanceSpec spec)
            throws IOException {
        Map<String, Object> instance = this.incus.instance(spec.handle());
        String id = instance.get("config") instanceof Map<?, ?> config
            && config.get("volatile.base_image") instanceof String fingerprint
            ? fingerprint : null;
        return new ImageIdentity(spec.image(), id);
    }

    // -- ImagePublishSupport --------------------------------------------------

    /**
     * @throws IOException for a RUNNING or absent workload: publishing a live rootfs
     *         would capture a torn filesystem, and Incus's own refusal for that case is
     *         less specific than the state this driver can already read
     */
    @Override
    public @NonNull String publishImage(@NonNull InstanceSpec spec, @NonNull String alias,
                                        @Nullable String description) throws IOException {
        ContainerState state = status(spec.handle()).state();
        if (state != ContainerState.STOPPED) {
            throw new IOException("REFUSED to publish '" + spec.handle() + "' as an image:"
                + " the workload is " + state + " and only a STOPPED workload captures a"
                + " consistent filesystem. Stop it first.");
        }
        return this.incus.publishImage(spec.handle(), alias, description);
    }

    // -- DeviceAttachSupport --------------------------------------------------

    /** The daemon-side custom-volume name of one device (handle-scoped, collision-free). */
    public static @NonNull String volumeNameOf(@NonNull InstanceSpec spec,
                                               @NonNull String deviceName) {
        return spec.handle() + "-" + deviceName;
    }

    @Override
    public void ensureDisk(@NonNull InstanceSpec spec, @NonNull String deviceName, int sizeGb)
            throws IOException {
        String pool = managedPoolName();
        String volumeName = volumeNameOf(spec, deviceName);
        Map<String, Object> existing = this.incus.customVolume(pool, volumeName);
        if (existing == null) {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("size", sizeGb + "GiB");
            // The attribution doctrine holds for volumes exactly as for workloads:
            // owner labels land at CREATE, so a crash between volume and device leaves
            // the volume attributable and a same-named stranger is refused below.
            spec.ownerLabels().forEach((key, value) -> config.put(USER_PREFIX + key, value));
            Map<String, Object> definition = new LinkedHashMap<>();
            definition.put("name", volumeName);
            definition.put("content_type", "block");
            definition.put("config", config);
            this.incus.createCustomVolume(pool, definition);
        } else {
            requireOwnedVolume(spec, volumeName, existing);
        }

        Map<String, Object> instance = this.incus.instance(spec.handle());
        Map<String, Object> device = new LinkedHashMap<>();
        device.put("type", "disk");
        device.put("pool", pool);
        device.put("source", volumeName);
        putDevice(spec.handle(), instance, deviceName, device);
        requireDevicePresent(spec.handle(), deviceName);
    }

    @Override
    public void resizeDisk(@NonNull InstanceSpec spec, @NonNull String deviceName, int sizeGb)
            throws IOException {
        String pool = managedPoolName();
        String volumeName = volumeNameOf(spec, deviceName);
        Map<String, Object> existing = this.incus.customVolume(pool, volumeName);
        if (existing == null) {
            throw new IOException("Volume '" + volumeName + "' does not exist on pool '"
                + pool + "'; nothing to resize");
        }
        requireOwnedVolume(spec, volumeName, existing);
        Map<String, Object> config = new LinkedHashMap<>();
        if (existing.get("config") instanceof Map<?, ?> current) {
            current.forEach((key, value) -> config.put(String.valueOf(key), value));
        }
        config.put("size", sizeGb + "GiB");
        this.incus.updateCustomVolume(pool, volumeName, Map.of("config", config));
        // Read-back verification: "the API said yes" and "the daemon did it" are
        // independent facts for a resize too.
        Integer actual = diskSizeGb(spec, deviceName);
        if (actual == null || actual != sizeGb) {
            throw new IOException("Resize of volume '" + volumeName + "' to " + sizeGb
                + "GiB did not take: the daemon reports " + actual + "GiB");
        }
    }

    @Override
    public void ensureNic(@NonNull InstanceSpec spec, @NonNull String deviceName)
            throws IOException {
        // The same throwing appliers as the primary NIC: ACL verified in the daemon,
        // the extra bridge verified managed-with-subnet, BEFORE the device lands.
        this.policy.ensureIsolationAcl();
        this.policy.ensureExtraNetwork();
        stampPresence();
        Map<String, Object> instance = this.incus.instance(spec.handle());
        putDevice(spec.handle(), instance, deviceName,
            this.policy.extraNicDevice(this.egress, spec.networkLimitMbit()));
        verifyIsolated(spec);
    }

    /** Boot priority of the workload's own root disk while install media is attached. */
    static final String ROOT_BOOT_PRIORITY = "10";

    /** Boot priority of an attached install-media CD-ROM (below the root disk's). */
    static final String CDROM_BOOT_PRIORITY = "5";

    /**
     * AIDEV-NOTE: the boot-order policy is ENCODED here, not exposed as a knob, and the
     * numbers are the measured ones from docs/prepare-windows-template.md step 5: the
     * firmware only lists a CD that carries a boot.priority, and the DISK must hold the
     * HIGHER one -- while it is blank the firmware falls through to the CD, and the
     * moment the OS makes it bootable the installer stops being re-entered from the
     * media (booting the CD first again after the first-phase reboot strands Windows
     * Setup on its "started an upgrade" question, a full boot cycle to discover).
     */
    @Override
    public void ensureCdrom(@NonNull InstanceSpec spec, @NonNull String deviceName,
                            @NonNull String mediaVolume) throws IOException {
        if (this.type != IncusWorkloadType.VIRTUAL_MACHINE) {
            throw new IOException("Instance '" + spec.handle() + "' is a "
                + this.type.apiType() + "; only a virtual machine can boot install media");
        }
        String pool = managedPoolName();
        Map<String, Object> media = this.incus.customVolume(pool, mediaVolume);
        if (media == null) {
            throw new IOException("Install media volume '" + mediaVolume + "' does not"
                + " exist on pool '" + pool + "' of this host; import the ISO on this"
                + " host first (the media surface on the server record).");
        }
        if (!"iso".equals(media.get("content_type"))) {
            throw new IOException("Volume '" + mediaVolume + "' on pool '" + pool
                + "' is not an ISO volume (content_type "
                + media.get("content_type") + "); refusing to attach it as install media");
        }
        Map<String, Object> instance = this.incus.instance(spec.handle());
        Map<String, Object> cdrom = new LinkedHashMap<>();
        cdrom.put("type", "disk");
        cdrom.put("pool", pool);
        cdrom.put("source", mediaVolume);
        cdrom.put("boot.priority", CDROM_BOOT_PRIORITY);
        putDevice(spec.handle(), instance, deviceName, cdrom);
        ensureRootBootPriority(spec.handle());
        requireDevicePresent(spec.handle(), deviceName);
    }

    /** Stamp the root disk's boot priority ABOVE the media's (see ensureCdrom's note). */
    private void ensureRootBootPriority(@NonNull String handle) throws IOException {
        Map<String, Object> instance = this.incus.instance(handle);
        Map<String, Object> root = new LinkedHashMap<>();
        if (instance.get("devices") instanceof Map<?, ?> devices
                && devices.get(ROOT_DEVICE) instanceof Map<?, ?> existing) {
            existing.forEach((key, value) -> root.put(String.valueOf(key), value));
        } else {
            // No instance-level root override yet (the profile's root applies): mint the
            // minimal one so the priority has a device to ride on.
            root.put("type", "disk");
            root.put("path", "/");
            root.put("pool", managedPoolName());
        }
        if (ROOT_BOOT_PRIORITY.equals(root.get("boot.priority"))) {
            return;
        }
        root.put("boot.priority", ROOT_BOOT_PRIORITY);
        putDevice(handle, instance, ROOT_DEVICE, root);
    }

    @Override
    public void removeDevice(@NonNull InstanceSpec spec, @NonNull String deviceName,
                             boolean hasVolume) throws IOException {
        Map<String, Object> instance;
        try {
            instance = this.incus.instance(spec.handle());
        } catch (IncusClient.ApiException e) {
            if (!e.isNotFound()) {
                throw e;
            }
            instance = null;   // workload gone: only the volume can remain
        }
        if (instance != null && instance.get("devices") instanceof Map<?, ?> devices
                && devices.get(deviceName) != null) {
            Map<String, Object> config = new LinkedHashMap<>();
            if (instance.get("config") instanceof Map<?, ?> current) {
                current.forEach((key, value) -> config.put(String.valueOf(key), value));
            }
            Map<String, Object> remaining = new LinkedHashMap<>();
            devices.forEach((key, value) -> {
                if (!deviceName.equals(String.valueOf(key))) {
                    remaining.put(String.valueOf(key), value);
                }
            });
            Map<String, Object> definition = new LinkedHashMap<>();
            definition.put("architecture", instance.get("architecture"));
            definition.put("config", config);
            definition.put("devices", remaining);
            definition.put("ephemeral", Boolean.TRUE.equals(instance.get("ephemeral")));
            definition.put("profiles", instance.get("profiles") instanceof List<?> profiles
                ? profiles : List.of("default"));
            definition.put("description", instance.get("description") instanceof String text
                ? text : "");
            this.incus.updateInstance(spec.handle(), definition);
        }
        if (hasVolume) {
            deleteVolumes(spec, List.of(deviceName));
        }
    }

    @Override
    public void deleteVolumes(@NonNull InstanceSpec spec, @NonNull List<String> deviceNames)
            throws IOException {
        String pool = managedPoolName();
        for (String deviceName : deviceNames) {
            String volumeName = volumeNameOf(spec, deviceName);
            Map<String, Object> existing = this.incus.customVolume(pool, volumeName);
            if (existing == null) {
                continue;   // observed absent, which is what delete exists to establish
            }
            // Never a stranger's data over a name collision -- the volume-side twin of
            // the workload attribution refusal.
            requireOwnedVolume(spec, volumeName, existing);
            try {
                this.incus.deleteCustomVolume(pool, volumeName);
            } catch (IncusClient.ApiException e) {
                if (!e.isNotFound()) {
                    throw e;
                }
            }
            if (this.incus.customVolume(pool, volumeName) != null) {
                throw new IOException("Volume '" + volumeName + "' still exists on pool '"
                    + pool + "' after its delete was accepted");
            }
        }
    }

    @Override
    public @Nullable Integer diskSizeGb(@NonNull InstanceSpec spec, @NonNull String deviceName)
            throws IOException {
        Map<String, Object> volume = this.incus.customVolume(managedPoolName(),
            volumeNameOf(spec, deviceName));
        if (volume == null || !(volume.get("config") instanceof Map<?, ?> config)) {
            return null;
        }
        return parseSizeGb(config.get("size"));
    }

    /** Parse a daemon size value ("2GiB" or raw bytes) into whole GB, null when unreadable. */
    private static @Nullable Integer parseSizeGb(@Nullable Object size) {
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

    /** Write ONE device onto the instance definition (read-modify-write, NIC untouched). */
    private void putDevice(@NonNull String handle, @NonNull Map<String, Object> instance,
                           @NonNull String deviceName, @NonNull Map<String, Object> device)
            throws IOException {
        Map<String, Object> config = new LinkedHashMap<>();
        if (instance.get("config") instanceof Map<?, ?> current) {
            current.forEach((key, value) -> config.put(String.valueOf(key), value));
        }
        Map<String, Object> devices = new LinkedHashMap<>();
        if (instance.get("devices") instanceof Map<?, ?> current) {
            current.forEach((key, value) -> devices.put(String.valueOf(key), value));
        }
        devices.put(deviceName, device);
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("architecture", instance.get("architecture"));
        definition.put("config", config);
        definition.put("devices", devices);
        definition.put("ephemeral", Boolean.TRUE.equals(instance.get("ephemeral")));
        definition.put("profiles", instance.get("profiles") instanceof List<?> profiles
            ? profiles : List.of("default"));
        definition.put("description", instance.get("description") instanceof String text
            ? text : "");
        this.incus.updateInstance(handle, definition);
    }

    /** Read the instance back and require the device the write just claimed to add. */
    private void requireDevicePresent(@NonNull String handle, @NonNull String deviceName)
            throws IOException {
        Map<String, Object> instance = this.incus.instance(handle);
        boolean present = instance.get("devices") instanceof Map<?, ?> devices
            && devices.get(deviceName) != null;
        if (!present) {
            throw new IOException("Device '" + deviceName + "' of '" + handle
                + "' was accepted but does not read back on the instance");
        }
    }

    /** @throws IOException when the volume's user.* labels do not attribute it to this record */
    private static void requireOwnedVolume(@NonNull InstanceSpec spec,
                                           @NonNull String volumeName,
                                           @NonNull Map<String, Object> volume)
            throws IOException {
        OwnerLabels.Owner want = OwnerLabels.parse(spec.ownerLabels());
        OwnerLabels.Owner actual = ownerOf(volume);
        boolean ours = want != null && actual != null && actual.model().equals(want.model())
            && actual.id().equals(want.id());
        if (!ours) {
            throw new IOException("REFUSED to touch volume '" + volumeName + "': the daemon"
                + " does not attribute it to this record ("
                + (actual != null ? "owned by " + actual.model() + " #" + actual.id()
                    : "no hohenheim owner labels")
                + "). A same-named foreign volume is a name collision, not a leftover.");
        }
    }

    /**
     * The pool the default profile's root disk lives on -- the one pool this driver
     * places custom volumes in, never a guess.
     */
    private @NonNull String managedPoolName() throws IOException {
        return managedPoolNameOf(this.incus);
    }

    /**
     * Static twin of {@link #managedPoolName()} for host-scoped callers with only a
     * client (the install-media surface): ONE authority on which pool is ours, so the
     * media lane and the device lane can never place volumes in different pools.
     */
    public static @NonNull String managedPoolNameOf(@NonNull IncusClient incus)
            throws IOException {
        Object devices = incus.profile("default").get("devices");
        if (devices instanceof Map<?, ?> map && map.get("root") instanceof Map<?, ?> root
                && root.get("pool") instanceof String pool && !pool.isBlank()) {
            return pool;
        }
        throw new IOException("REFUSED to place a volume: the default profile has no root"
            + " disk on a storage pool to inherit. This host is not admissible for disk"
            + " devices until its default profile carries a pooled root disk.");
    }

    /**
     * The existing same-named instance when the daemon attributes it to this record;
     * null when absent, a refusal when foreign.
     */
    private @Nullable Map<String, Object> ownedExisting(@NonNull String handle,
                                                        OwnerLabels.@NonNull Owner owner)
            throws IOException {
        Map<String, Object> existing;
        try {
            existing = this.incus.instance(handle);
        } catch (IncusClient.ApiException e) {
            if (e.isNotFound()) {
                return null;
            }
            throw e;
        }
        OwnerLabels.Owner actual = ownerOf(existing);
        boolean ours = actual != null && actual.model().equals(owner.model())
            && actual.id().equals(owner.id());
        if (!ours) {
            throw new IOException("REFUSED to replace instance '" + handle + "': the daemon"
                + " does not attribute it to this record ("
                + (actual != null ? "owned by " + actual.model() + " #" + actual.id()
                    : "no hohenheim owner labels")
                + "). A same-named foreign instance is a name collision, not a leftover.");
        }
        return existing;
    }

    /** The owner claim of an instance object's {@code user.*} config, or null. */
    private static OwnerLabels.@Nullable Owner ownerOf(@NonNull Map<String, Object> instance) {
        if (!(instance.get("config") instanceof Map<?, ?> config)) {
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
}
