package be.elevenways.hohenheim.server.runtime;

import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.incus.IncusWebSocket;

import java.io.IOException;
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
 * ONLY: an Incus system container's persistent state is its ROOTFS, not named volumes,
 * so {@code VolumeSnapshotSupport}'s tar-per-volume contract does not fit -- native
 * Incus snapshots/backups are the coming mechanism, and until they land, snapshot and
 * backup requests refuse by name through the existing missing-capability funnels
 * (snapshots_unsupported, files_unsupported, ...), never a silent no-op.
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
public final class IncusInstanceRuntime implements InstanceRuntime, ConsoleStreamSupport {

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
        // Replace only a leftover instance the daemon attributes to THIS record; a
        // same-named foreign instance is a loud refusal, never a force-remove.
        removeIfOwnedBy(spec.handle(), owner);

        Map<String, Object> config = new LinkedHashMap<>();
        spec.ownerLabels().forEach((key, value) -> config.put(USER_PREFIX + key, value));
        spec.env().forEach((name, value) -> config.put("environment." + name, value));
        applyLimits(spec.limits(), config);
        // Unprivileged is the DEFAULT and the deliberate posture; only the explicitly
        // declared privileged profile flips it (threat model boundary 1).
        if (PROFILE_PRIVILEGED.equals(spec.hardening().name())) {
            config.put("security.privileged", "true");
        }

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
        this.incus.changeState(handle, "start", -1, false);
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

    /**
     * Remove a same-named instance ONLY when its {@code user.*} config attributes it to
     * this record; absent is a verified no-op, foreign is a refusal.
     */
    private void removeIfOwnedBy(@NonNull String handle, OwnerLabels.@NonNull Owner owner)
            throws IOException {
        Map<String, Object> existing;
        try {
            existing = this.incus.instance(handle);
        } catch (IncusClient.ApiException e) {
            if (e.isNotFound()) {
                return;
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
        destroy(handle);
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
