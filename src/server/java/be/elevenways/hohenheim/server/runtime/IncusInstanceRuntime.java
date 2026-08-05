package be.elevenways.hohenheim.server.runtime;

import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.incus.IncusNetworkPolicy;
import be.elevenways.hohenheim.server.incus.IncusWebSocket;
import be.elevenways.protoblast.common.Blast;

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
        InstallSupport, AppUpdateSupport {

    /** The public image server system-container aliases resolve against. */
    public static final String IMAGE_SERVER = "https://images.linuxcontainers.org";

    /** Hardening profile name that maps onto {@code security.privileged=true}. */
    public static final String PROFILE_PRIVILEGED = "incus-privileged";

    /** Config-key prefix Incus reserves for arbitrary user metadata. */
    private static final String USER_PREFIX = "user.";

    private final @NonNull IncusClient incus;
    private final @NonNull IncusNetworkPolicy policy;
    private final @NonNull Egress egress;

    public IncusInstanceRuntime(@NonNull IncusClient incus) {
        this(incus, Egress.OPEN);
    }

    /** @param egress the KIND-declared egress posture materialized into the NIC's ACL default */
    public IncusInstanceRuntime(@NonNull IncusClient incus, @NonNull Egress egress) {
        this.incus = incus;
        this.policy = new IncusNetworkPolicy(incus);
        this.egress = egress;
    }

    @Override
    public @NonNull String create(@NonNull InstanceSpec spec) throws IOException {
        OwnerLabels.Owner owner = OwnerLabels.parse(spec.ownerLabels());
        if (owner == null) {
            throw new IOException("InstanceSpec '" + spec.handle() + "' carries no valid owner"
                + " labels; an unattributable instance container is forbidden by design");
        }
        // The shared isolation ACL, VERIFIED in the daemon, BEFORE any instance is created
        // on it -- an Incus host whose ACL support does not really enforce refuses here,
        // never at the point where a tenant container is already running unisolated.
        this.policy.ensureIsolationAcl();
        Map<String, Object> nic = this.policy.nicDevice(managedNetworkName(), this.egress);

        // AIDEV-NOTE: converge, never replace. A system container's persistent state
        // IS its rootfs, so the Docker driver's replace-on-create semantic would be
        // silent data loss on every redeploy (and would destroy a freshly restored or
        // imported instance). An existing OWNED instance keeps its rootfs and gets the
        // driver-managed config keys rewritten; a same-named FOREIGN instance stays a
        // loud refusal; a changed settings image only applies at absent-then-create
        // (reinstall is the explicit wipe path, Phase 5's template policy).
        Map<String, Object> existing = ownedExisting(spec.handle(), owner);
        if (existing != null) {
            converge(spec, existing, nic);
            verifyIsolated(spec.handle());
            return spec.handle();
        }

        Map<String, Object> config = new LinkedHashMap<>();
        applyManagedConfig(spec, config);

        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("name", spec.handle());
        definition.put("type", "container");
        definition.put("source", Map.of(
            "type", "image",
            "protocol", "simplestreams",
            "server", IMAGE_SERVER,
            "alias", spec.image()));
        definition.put("config", config);
        // The isolating NIC override is in the CREATE body: there is no instant at which
        // an instance of ours exists on the bridge without it.
        definition.put("devices", Map.of(IncusNetworkPolicy.NIC, nic));
        definition.put("profiles", List.of("default"));
        this.incus.createInstance(definition);
        verifyIsolated(spec.handle());
        return spec.handle();
    }

    /** Read one instance back and require the isolating NIC the deploy just wrote. */
    private void verifyIsolated(@NonNull String handle) throws IOException {
        this.policy.verifyInstance(handle, this.incus.instance(handle), this.egress);
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
    private static void applyManagedConfig(@NonNull InstanceSpec spec,
                                           @NonNull Map<String, Object> config) {
        spec.ownerLabels().forEach((key, value) -> config.put(USER_PREFIX + key, value));
        spec.env().forEach((name, value) -> config.put("environment." + name, value));
        applyLimits(spec.limits(), config);
        // Unprivileged is the DEFAULT and the deliberate posture; only the explicitly
        // declared privileged profile flips it (threat model boundary 1).
        if (PROFILE_PRIVILEGED.equals(spec.hardening().name())) {
            config.put("security.privileged", "true");
        }
    }

    private static boolean isManagedKey(@NonNull String key) {
        return key.startsWith(USER_PREFIX) || key.startsWith("environment.")
            || key.startsWith("limits.") || key.equals("security.privileged");
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
            null);
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

    /** How long the freshly started system needs to accept an exec, worst case. */
    private static final long EXEC_READY_TIMEOUT_MS = 30_000;

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
        IncusClient.ExecResult result = this.incus.exec(spec.handle(),
            List.of("bash", "-ec", script), env, timeoutMs);
        return new InstallOutcome(result.exitCode(), tailOf(result.output()));
    }

    /**
     * Exec with a bring-up retry: the daemon refuses execs for a moment while the
     * container's init is still coming up, and that refusal must not fail the install.
     */
    private IncusClient.@NonNull ExecResult execWhenReady(@NonNull String handle,
                                                          @NonNull List<String> command,
                                                          @NonNull Map<String, String> env,
                                                          long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + EXEC_READY_TIMEOUT_MS;
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
                             long maxBytes) throws IOException {
        // The daemon-side backup object is a TEMPORARY: the export tarball is the
        // artifact, and leaving the object behind would silently fill the pool.
        String backupName = "hib-" + System.currentTimeMillis();
        this.incus.createBackup(spec.handle(), backupName);
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
        replaceDefinition(spec.handle(), existing, config,
            this.policy.nicDevice(managedNetworkName(), this.egress));
        verifyIsolated(spec.handle());
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
