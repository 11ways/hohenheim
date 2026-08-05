package be.elevenways.hohenheim.server.runtime;

import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.incus.IncusClient;
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
 * AIDEV-NOTE: capability honesty. This driver implements {@link ConsoleStreamSupport}
 * and {@link NativeSnapshotSupport}: an Incus system container's persistent state is
 * its ROOTFS, not named volumes, so {@code VolumeSnapshotSupport}'s tar-per-volume
 * contract does not fit -- snapshots are the daemon's own pool-resident snapshots and
 * a backup is its whole-instance export tarball. File staging and install still
 * refuse by name through the existing missing-capability funnels
 * (files_unsupported, install_unsupported), never a silent no-op.
 *
 * AIDEV-NOTE: threat model. Containers land on the daemon's managed bridge -- there is
 * NO per-instance network with a verified kernel policy here yet (the Docker tier's
 * WorkloadNetworkPolicy has no Incus counterpart in this wave), so tenant containers on
 * one Incus host can reach each other and the host address. That is the declared
 * shared_container posture the placement gate requires the OPERATOR to accept, plus
 * boundary 1 of the plan's threat model: a system container is NOT a security boundary
 * against a determined root user; privileged mode widens that further and is why it
 * carries a stated escape warning on the kind settings.
 */
public final class IncusInstanceRuntime
        implements InstanceRuntime, ConsoleStreamSupport, NativeSnapshotSupport {

    /** The public image server system-container aliases resolve against. */
    public static final String IMAGE_SERVER = "https://images.linuxcontainers.org";

    /** Hardening profile name that maps onto {@code security.privileged=true}. */
    public static final String PROFILE_PRIVILEGED = "incus-privileged";

    /** Config-key prefix Incus reserves for arbitrary user metadata. */
    private static final String USER_PREFIX = "user.";

    private final @NonNull IncusClient incus;

    public IncusInstanceRuntime(@NonNull IncusClient incus) {
        this.incus = incus;
    }

    @Override
    public @NonNull String create(@NonNull InstanceSpec spec) throws IOException {
        OwnerLabels.Owner owner = OwnerLabels.parse(spec.ownerLabels());
        if (owner == null) {
            throw new IOException("InstanceSpec '" + spec.handle() + "' carries no valid owner"
                + " labels; an unattributable instance container is forbidden by design");
        }
        // AIDEV-NOTE: converge, never replace. A system container's persistent state
        // IS its rootfs, so the Docker driver's replace-on-create semantic would be
        // silent data loss on every redeploy (and would destroy a freshly restored or
        // imported instance). An existing OWNED instance keeps its rootfs and gets the
        // driver-managed config keys rewritten; a same-named FOREIGN instance stays a
        // loud refusal; a changed settings image only applies at absent-then-create
        // (reinstall is the explicit wipe path, Phase 5's template policy).
        Map<String, Object> existing = ownedExisting(spec.handle(), owner);
        if (existing != null) {
            converge(spec, existing);
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
        definition.put("profiles", List.of("default"));
        this.incus.createInstance(definition);
        return spec.handle();
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
    private void converge(@NonNull InstanceSpec spec, @NonNull Map<String, Object> existing)
            throws IOException {
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
        replaceDefinition(spec.handle(), existing, config);
    }

    /** PUT the instance's mutable definition with a rewritten config map. */
    private void replaceDefinition(@NonNull String handle,
                                   @NonNull Map<String, Object> existing,
                                   @NonNull Map<String, Object> config) throws IOException {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("architecture", existing.get("architecture"));
        definition.put("config", config);
        definition.put("devices", existing.get("devices") instanceof Map<?, ?> devices
            ? devices : Map.of());
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
        // Re-identification is part of the import contract, in ONE definition write:
        // the tarball carries the SOURCE instance's user.* labels (until they are
        // replaced the import is attributed to the wrong record -- a crash inside the
        // window leaves an instance the NEW record's next deploy refuses as foreign,
        // visible operator cleanup, never silent adoption) AND the source's volatile
        // NIC MACs, which the daemon refuses to start beside the still-running source
        // ("MAC address already defined on another NIC"). Dropping the hwaddr keys
        // makes the daemon mint fresh ones at start.
        Map<String, Object> existing = this.incus.instance(spec.handle());
        Map<String, Object> config = new LinkedHashMap<>();
        if (existing.get("config") instanceof Map<?, ?> current) {
            current.forEach((key, value) -> {
                String name = String.valueOf(key);
                if (name.startsWith("volatile.") && name.endsWith(".hwaddr")) {
                    return;
                }
                config.put(name, value);
            });
        }
        spec.ownerLabels().forEach((key, value) -> config.put(USER_PREFIX + key, value));
        replaceDefinition(spec.handle(), existing, config);
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
