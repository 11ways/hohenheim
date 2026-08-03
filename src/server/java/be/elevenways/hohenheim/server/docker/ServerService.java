package be.elevenways.hohenheim.server.docker;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.HostProbe;
import be.elevenways.hohenheim.server.util.DatasourceScoped;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The multi-server inventory: persists Docker hosts ({@link ServerModel}) and builds a
 * {@link DockerClient} for each. The implicit {@code local} host is ensured to exist; remote hosts
 * are reached over SSH.
 *
 * AIDEV-NOTE: the admin LIST reads STORED host state ({@link #storedStates}) -- it
 * never probes a daemon, because the old per-render serial probe (8s ceiling PER
 * host) hung the page on one down remote and its exception-to-null swallowing made
 * host-down, bad-key, host-key-changed, docker-absent and DNS failure one
 * indistinguishable state with ZEROED capacity -- worse than unknown, because an
 * allocator reading cpus=0 treats an unreachable host as an empty one. Live probes
 * are explicit ({@link #probeAndStore}) and always PERSIST their typed outcome.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public class ServerService extends DatasourceScoped {

    // The mode vocabulary is owned by the model's EnumField declaration.
    public static final String LOCAL = ServerModel.MODE_LOCAL;
    public static final String MODE_LOCAL = ServerModel.MODE_LOCAL;
    public static final String MODE_SSH = ServerModel.MODE_SSH;

    // Short deadline for an explicit reachability probe so a down remote can't hang long.
    private static final long PING_TIMEOUT_MS = 8000;

    public ServerService() {
        super(null);
    }

    public ServerService(Datasource datasource) {
        super(datasource);
    }

    private static ServerModel model() {
        return Models.get(ServerModel.class);
    }

    /** Ensure the implicit local host has a record (idempotent; delegates to THE derivation). */
    public void ensureLocal() {
        exec(ServerModel::localServerId);
    }

    /** A server with its LIVE reachability and host resource snapshot (explicit probes only). */
    public record Summary(String name, String mode, String sshTarget, boolean reachable,
                          int cpus, long memoryBytes, int containersRunning, int containersTotal,
                          int images, String dockerVersion, String operatingSystem,
                          String osType, String architecture,
                          @Nullable String errorKind, @Nullable String errorDetail) {}

    /** One host's STORED state: what the list and any allocator read, no daemon involved. */
    public record HostState(String name, String mode, String sshTarget,
                            String admission, String posture, boolean preflightOk,
                            @Nullable Instant probedAt, @Nullable Instant lastSeenAt,
                            @Nullable String lastErrorKind, @Nullable String lastError,
                            @Nullable Map<String, Object> capabilities) {}

    /** Every host's stored state, in one query, without touching any daemon. */
    public List<HostState> storedStates() {
        List<HostState> result = new ArrayList<>();
        for (Row row : query(() -> model().find().all())) {
            result.add(stateOf(row));
        }
        return result;
    }

    private static HostState stateOf(Row row) {
        String target = row.get(ServerModel.SSH_TARGET);
        Object capabilities = row.get(ServerModel.CAPABILITIES);
        @SuppressWarnings("unchecked")
        Map<String, Object> capabilityMap =
            capabilities instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
        return new HostState(
            row.get(ServerModel.NAME),
            row.get(ServerModel.MODE),
            target != null ? target : "",
            String.valueOf((Object) row.get(ServerModel.ADMISSION)),
            String.valueOf((Object) row.get(ServerModel.POSTURE)),
            Boolean.TRUE.equals(row.get(ServerModel.PREFLIGHT_OK)),
            row.get(ServerModel.PROBED_AT),
            row.get(ServerModel.LAST_SEEN_AT),
            row.get(ServerModel.LAST_ERROR_KIND),
            row.get(ServerModel.LAST_ERROR),
            capabilityMap);
    }

    /**
     * LIVE-probe one named server and PERSIST the typed outcome on its record
     * (last_seen_at on success, last_error_kind/last_error on failure), so even an
     * on-demand probe leaves durable evidence. Null when the server is unknown.
     */
    public @Nullable Summary probeAndStore(String name) {
        if (LOCAL.equals(name)) {
            ensureLocal();
        }
        Row row = query(() -> model().findByName(name));
        if (row == null) {
            return null;
        }
        HostProbe.Outcome outcome = probe(row);
        if (outcome.reachable()) {
            exec(() -> HostProbe.recordSuccess(name));
        } else {
            exec(() -> HostProbe.recordFailure(name, outcome));
        }
        return summaryOf(row, outcome);
    }

    /** Just the server names (no reachability probe), for form dropdowns. */
    public List<String> names() {
        List<String> names = new ArrayList<>();
        for (Row row : query(() -> model().find().all())) {
            names.add(row.get(ServerModel.NAME));
        }
        return names;
    }

    /** A DockerClient for the named server (local socket or remote over SSH). */
    public DockerClient clientFor(String name) {
        Row row = query(() -> model().findByName(name));
        if (row == null && LOCAL.equals(name)) {
            // The local daemon always exists; its row is bookkeeping that used
            // to appear only once an admin FORM rendered (ensureLocal), so a
            // restore/backup on a fresh install 500ed with "No server named
            // 'local'" before anyone opened the databases form.
            ensureLocal();
            row = query(() -> model().findByName(name));
        }
        if (row == null) {
            throw new IllegalArgumentException("No server named '" + name + "'");
        }
        return new DockerClient(transportFor(row));
    }

    /** @return the stored SSH target for a named remote server, or null for unknown/local */
    public String sshTarget(String name) {
        Row row = query(() -> model().findByName(name));
        return row != null ? row.get(ServerModel.SSH_TARGET) : null;
    }

    /** Register (or update) a remote SSH server. */
    public void add(String name, String sshTarget) {
        exec(() -> {
            ServerModel model = model();
            Row row = model.findByName(name);
            if (row == null) {
                row = model.createEmptyRow();
                row.set(ServerModel.NAME, name);
            }
            row.set(ServerModel.MODE, MODE_SSH);
            row.set(ServerModel.SSH_TARGET, sshTarget);
            model.save(row);
        });
    }

    /**
     * Remove a server; the implicit local host (the machine itself) cannot be removed.
     * ServerModel's beforeRemove hook REFUSES the delete while live stacks, databases
     * or instances still reference the host -- on every delete path, not just this one.
     */
    public void remove(String name) {
        if (LOCAL.equals(name)) {
            return;
        }
        exec(() -> model().find().where(ServerModel.NAME.eq(name)).delete());
    }

    private static DockerTransport transportFor(Row row) {
        if (MODE_SSH.equals(row.get(ServerModel.MODE))) {
            return ProcessDockerTransport.overSsh(row.get(ServerModel.SSH_TARGET));
        }
        return new UnixSocketDockerTransport(DockerClient.DEFAULT_SOCKET);
    }

    /** One live daemon probe with a TYPED outcome; never a silent null. */
    private static HostProbe.Outcome probe(Row row) {
        try {
            return HostProbe.Outcome.success(
                new DockerClient(transportFor(row), PING_TIMEOUT_MS).info());
        } catch (Exception e) {
            return HostProbe.classify(e);
        }
    }

    private static Summary summaryOf(Row row, HostProbe.Outcome outcome) {
        String target = row.get(ServerModel.SSH_TARGET);
        Map<String, Object> info = outcome.info();
        return new Summary(
            row.get(ServerModel.NAME),
            row.get(ServerModel.MODE),
            target != null ? target : "",
            outcome.reachable(),
            asInt(info, "NCPU"),
            asLong(info, "MemTotal"),
            asInt(info, "ContainersRunning"),
            asInt(info, "Containers"),
            asInt(info, "Images"),
            asString(info, "ServerVersion"),
            asString(info, "OperatingSystem"),
            asString(info, "OSType"),
            asString(info, "Architecture"),
            outcome.kind() != null ? outcome.kind().token : null,
            outcome.detail());
    }

    private static int asInt(Map<String, Object> info, String key) {
        return info != null && info.get(key) instanceof Number n ? n.intValue() : 0;
    }

    private static long asLong(Map<String, Object> info, String key) {
        return info != null && info.get(key) instanceof Number n ? n.longValue() : 0L;
    }

    private static String asString(Map<String, Object> info, String key) {
        return info != null && info.get(key) != null ? String.valueOf(info.get(key)) : "";
    }
}
