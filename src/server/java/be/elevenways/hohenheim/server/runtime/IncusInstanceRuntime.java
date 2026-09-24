package be.elevenways.hohenheim.server.runtime;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.incus.ControllerPresence;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.incus.IncusKernelIsolation;
import be.elevenways.hohenheim.server.incus.IncusNetworkPolicy;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import static be.elevenways.hohenheim.server.runtime.IncusDefinitions.ROOT_DEVICE;

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
 *
 * AIDEV-NOTE: this class keeps the LIFECYCLE (create/converge, start, stop, destroy, status)
 * and is the one public face of the driver; the definition rules live in
 * {@link IncusDefinitions}, and the device, guest (exec/console/install) and archive
 * (snapshot/backup/publish) lanes in {@link IncusDeviceLane}, {@link IncusGuestLane} and
 * {@link IncusArchiveLane}, which every capability method here delegates to.
 */
public final class IncusInstanceRuntime
        implements InstanceRuntime, ConsoleStreamSupport, NativeSnapshotSupport,
        InstallSupport, AppUpdateSupport, DeviceAttachSupport, RootDiskSizeSupport,
        RootDiskUsageSupport, ExecSupport, ImagePublishSupport {

    /** The public image server system-container aliases resolve against. */
    public static final String IMAGE_SERVER = "https://images.linuxcontainers.org";

    /** Hardening profile name that maps onto {@code security.privileged=true}. */
    public static final String PROFILE_PRIVILEGED = "incus-privileged";

    private final @NonNull IncusClient incus;
    private final @NonNull IncusNetworkPolicy policy;
    private final @NonNull Egress egress;
    private final @NonNull IncusWorkloadType type;
    private final @Nullable String serverName;
    private final @NonNull IncusDeviceLane devices;
    private final @NonNull IncusGuestLane guest;
    private final @NonNull IncusArchiveLane archive;

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
        this.devices = new IncusDeviceLane(this, incus, this.policy, egress, type);
        this.guest = new IncusGuestLane(this, incus, type);
        this.archive = new IncusArchiveLane(this, incus, this.policy, egress);
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
    void stampPresence() {
        try {
            ControllerPresence.stamp(this.incus);
        } catch (Exception unstamped) {
            Blast.log("INCUS PRESENCE: could not stamp controller presence on",
                this.serverName == null ? "the local daemon" : this.serverName, "-",
                unstamped.getMessage(),
                "- this controller's shared objects here stay UNSTAMPED (never reaped)");
        }
    }

    /**
     * The optional spec capabilities this driver DELIVERS; every other requested one is
     * refused by name before anything is created (see {@link SpecFeature}).
     */
    public static final Set<SpecFeature> FEATURES = Collections.unmodifiableSet(EnumSet.of(
        SpecFeature.CLOUD_INIT, SpecFeature.ROOT_DISK_SIZE, SpecFeature.BANDWIDTH_LIMIT,
        SpecFeature.PREPARED_IMAGE, SpecFeature.INSTALL_MEDIA, SpecFeature.RUN_USER));

    @Override
    public @NonNull String create(@NonNull InstanceSpec spec) throws IOException {
        OwnerLabels.Owner owner = OwnerLabels.parse(spec.ownerLabels());
        if (owner == null) {
            throw new IOException("InstanceSpec '" + spec.handle() + "' carries no valid owner"
                + " labels; an unattributable instance container is forbidden by design");
        }
        // The honest refusal for every capability this driver cannot deliver (a pipe
        // offered as a terminal, "ephemeral" tmpfs data landing on the pool, a health gate
        // nobody evaluates, a working-directory override): see SpecFeature.
        SpecFeature.requireSupported(spec, FEATURES, "incus");
        // The shared isolation ACL, VERIFIED in the daemon, BEFORE any instance is created
        // on it -- an Incus host whose ACL support does not really enforce refuses here,
        // never at the point where a tenant container is already running unisolated.
        this.policy.ensureIsolationAcl();
        stampPresence();
        Map<String, Object> nic = this.policy.nicDevice(
            IncusDefinitions.managedNetworkName(this.incus), this.egress, spec.networkLimitMbit());

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
            converge(spec, nic);
            verifyIsolated(spec);
            // A root-size change on an EXISTING workload is never folded into the
            // converge PUT: a running grow is accepted and not performed (see
            // RootDiskSizeSupport), so it goes through the stopped-only path, which
            // refuses by name rather than reporting a success it did not deliver.
            this.devices.reconcileRootDisk(spec);
            return spec.handle();
        }

        Map<String, Object> config = new LinkedHashMap<>();
        // Includes security.secureboot for a VM: the spec's OWN declaration (default
        // false, since catalog VM builds are not Secure Boot signed and the first launch
        // fails naming exactly that, verified live on daystrom); a prepared image may
        // declare true.
        IncusDefinitions.applyManagedConfig(spec, this.type, config);

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
            devices.put(ROOT_DEVICE, this.devices.rootDevice(spec.rootDiskGb()));
        }
        devices.putAll(IncusDefinitions.bindDevices(spec));
        definition.put("devices", devices);
        definition.put("profiles", List.of("default"));
        this.incus.createInstance(definition);
        verifyIsolated(spec);
        this.devices.verifyRootDiskDeclared(spec);
        return spec.handle();
    }

    // -- RootDiskSizeSupport / RootDiskUsageSupport -----------------------------

    @Override
    public @Nullable Integer rootDiskGb(@NonNull InstanceSpec spec) throws IOException {
        return this.devices.rootDiskGb(spec);
    }

    @Override
    public RootDiskUsageSupport.@Nullable DiskUsage rootDiskUsage(@NonNull InstanceSpec spec)
            throws IOException {
        return this.devices.rootDiskUsage(spec);
    }

    @Override
    public void resizeRootDisk(@NonNull InstanceSpec spec, int sizeGb) throws IOException {
        this.devices.resizeRootDisk(spec, sizeGb);
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
    void verifyIsolated(@NonNull InstanceSpec spec) throws IOException {
        Map<String, Object> instance = this.incus.instance(spec.handle());
        this.policy.verifyAllNics(spec.handle(), instance, this.egress);
        this.policy.verifyBandwidth(spec.handle(), instance, spec.networkLimitMbit());
    }

    /** PID 1 of a workspace container; shipped by every runtime image (images/README.md). */
    public static final String WORKSPACE_INIT = "/sbin/hohenheim-init";

    /** Rewrite the managed config of an existing OWNED instance; the rootfs is untouched. */
    private void converge(@NonNull InstanceSpec spec, @NonNull Map<String, Object> nic)
            throws IOException {
        this.incus.editInstance(spec.handle(), (config, devices) -> {
            // volatile.* and image.* keys ride along unchanged (read-modify-write, the
            // CLI's own shape); only the managed keys are recomputed, so a value REMOVED
            // from the settings really disappears.
            config.keySet().removeIf(IncusDefinitions::isManagedKey);
            IncusDefinitions.applyManagedConfig(spec, this.type, config);
            // The isolating NIC override is (re)written every converge: a reboot or an
            // operator edit that dropped it is repaired here, not silently tolerated.
            devices.put(IncusNetworkPolicy.NIC, nic);
        });
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
        return this.guest.openConsole(handle);
    }

    @Override
    public @NonNull String consoleTail(@NonNull String handle, int lines) throws IOException {
        return this.guest.consoleTail(handle, lines);
    }

    /** @throws IOException ALWAYS for a stopped workload; see {@link IncusGuestLane#exitCode} */
    @Override
    public @Nullable Integer exitCode(@NonNull String handle) throws IOException {
        return this.guest.exitCode(handle);
    }

    // -- ExecSupport / InstallSupport / AppUpdateSupport ----------------------

    @Override
    public ExecSupport.@NonNull ExecOutcome runExec(@NonNull InstanceSpec spec,
                                                    @NonNull List<String> command,
                                                    ExecSupport.@NonNull ExecOptions options,
                                                    long timeoutMs) throws IOException {
        return this.guest.runExec(spec, command, options, timeoutMs);
    }

    /** Runs INSIDE the instance's own rootfs; see {@link IncusGuestLane#runInstall}. */
    @Override
    public @NonNull InstallOutcome runInstall(@NonNull InstanceSpec spec,
                                              @NonNull String installImage,
                                              @NonNull String script,
                                              @NonNull Map<String, String> env,
                                              long timeoutMs) throws IOException {
        return this.guest.runInstall(spec, installImage, script, env, timeoutMs);
    }

    @Override
    public InstallSupport.@NonNull InstallOutcome runAppUpdate(@NonNull InstanceSpec spec,
                                                               @NonNull String script,
                                                               @NonNull Map<String, String> env,
                                                               long timeoutMs)
            throws IOException {
        return this.guest.runAppUpdate(spec, script, env, timeoutMs);
    }

    // -- NativeSnapshotSupport ------------------------------------------------

    @Override
    public void createSnapshot(@NonNull InstanceSpec spec, @NonNull String snapshotName)
            throws IOException {
        this.archive.createSnapshot(spec, snapshotName);
    }

    @Override
    public boolean snapshotExists(@NonNull InstanceSpec spec, @NonNull String snapshotName)
            throws IOException {
        return this.archive.snapshotExists(spec, snapshotName);
    }

    @Override
    public void restoreSnapshot(@NonNull InstanceSpec spec, @NonNull String snapshotName)
            throws IOException {
        this.archive.restoreSnapshot(spec, snapshotName);
    }

    @Override
    public void deleteSnapshot(@NonNull InstanceSpec spec, @NonNull String snapshotName)
            throws IOException {
        this.archive.deleteSnapshot(spec, snapshotName);
    }

    @Override
    public long exportBackup(@NonNull InstanceSpec spec, @NonNull Path destination,
                             long maxBytes, boolean withSnapshots) throws IOException {
        return this.archive.exportBackup(spec, destination, maxBytes, withSnapshots);
    }

    @Override
    public void importBackup(@NonNull InstanceSpec spec, @NonNull Path archive)
            throws IOException {
        this.archive.importBackup(spec, archive);
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
        return OwnerLabels.matches(IncusDefinitions.ownerOf(existing), owner)
            ? WorkloadClaim.OURS : WorkloadClaim.FOREIGN;
    }

    @Override
    public @NonNull ImageIdentity imageIdentity(@NonNull InstanceSpec spec)
            throws IOException {
        return new ImageIdentity(spec.image(), baseImageOf(this.incus.instance(spec.handle())));
    }

    /**
     * The image fingerprint a daemon instance object says it was created from.
     *
     * @return {@code volatile.base_image}, or null when the daemon names none
     */
    public static @Nullable String baseImageOf(@NonNull Map<String, Object> instance) {
        return instance.get("config") instanceof Map<?, ?> config
            && config.get("volatile.base_image") instanceof String fingerprint
            ? fingerprint : null;
    }

    // -- ImagePublishSupport --------------------------------------------------

    /** @throws IOException for a RUNNING or absent workload; see {@link IncusArchiveLane#publishImage} */
    @Override
    public @NonNull String publishImage(@NonNull InstanceSpec spec, @NonNull String alias,
                                        @Nullable String description) throws IOException {
        return this.archive.publishImage(spec, alias, description);
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
        this.devices.ensureDisk(spec, deviceName, sizeGb);
    }

    @Override
    public void resizeDisk(@NonNull InstanceSpec spec, @NonNull String deviceName, int sizeGb)
            throws IOException {
        this.devices.resizeDisk(spec, deviceName, sizeGb);
    }

    @Override
    public void ensureNic(@NonNull InstanceSpec spec, @NonNull String deviceName)
            throws IOException {
        this.devices.ensureNic(spec, deviceName);
    }

    /** The boot-order policy is encoded in {@link IncusDeviceLane#ensureCdrom}. */
    @Override
    public void ensureCdrom(@NonNull InstanceSpec spec, @NonNull String deviceName,
                            @NonNull String mediaVolume) throws IOException {
        this.devices.ensureCdrom(spec, deviceName, mediaVolume);
    }

    @Override
    public void removeDevice(@NonNull InstanceSpec spec, @NonNull String deviceName,
                             boolean hasVolume) throws IOException {
        this.devices.removeDevice(spec, deviceName, hasVolume);
    }

    @Override
    public void deleteVolumes(@NonNull InstanceSpec spec, @NonNull List<String> deviceNames)
            throws IOException {
        this.devices.deleteVolumes(spec, deviceNames);
    }

    @Override
    public @Nullable Integer diskSizeGb(@NonNull InstanceSpec spec, @NonNull String deviceName)
            throws IOException {
        return this.devices.diskSizeGb(spec, deviceName);
    }

    /**
     * The pool the default profile's root disk lives on, for host-scoped callers with only a
     * client (the install-media surface): ONE authority on which pool is ours, so the media
     * lane and the device lane can never place volumes in different pools.
     */
    public static @NonNull String managedPoolNameOf(@NonNull IncusClient incus)
            throws IOException {
        return IncusDefinitions.managedPoolName(incus);
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
        OwnerLabels.Owner actual = IncusDefinitions.ownerOf(existing);
        if (!OwnerLabels.matches(actual, owner)) {
            throw new IOException("REFUSED to replace instance '" + handle + "': the daemon"
                + " does not attribute it to this record ("
                + IncusDefinitions.foreignOwner(actual)
                + "). A same-named foreign instance is a name collision, not a leftover.");
        }
        return existing;
    }
}
