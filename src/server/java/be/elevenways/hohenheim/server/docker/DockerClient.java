package be.elevenways.hohenheim.server.docker;

import be.elevenways.hohenheim.server.util.Json;
import be.elevenways.protoblast.common.dry.Dry;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.concurrent.TimeUnit;

/**
 * Minimal Docker Engine API client speaking HTTP/1.1 over a pluggable {@link DockerTransport}
 * -- a local unix socket by default, or a remote daemon over SSH, whose argv comes from
 * {@code HostKeys.sshArgv} so the pin is always enforced. The
 * foundation for Hohenext's container/app/database layer.
 *
 * Each call opens a fresh connection with {@code Connection: close} (no keep-alive) and reads to
 * EOF, then decodes a chunked body if present. A per-request watchdog (in the transport) aborts
 * the connection if the daemon stalls, so an unresponsive daemon can't pin the calling thread
 * forever. Unversioned API paths are used, so the daemon serves them at its current API version.
 *
 * Request bodies are encoded as plain JSON ({@link #toJson}) rather than via DRY,
 * whose {@code stringify} emits DRY's extended (non-JSON) syntax the daemon rejects.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public class DockerClient {

    /** Default Docker daemon socket on Linux. */
    public static final String DEFAULT_SOCKET = "/var/run/docker.sock";

    /**
     * A non-2xx answer from a REACHED daemon, carrying the HTTP status so callers can
     * tell "the resource is absent" (404, an OBSERVED fact) from "the daemon could not
     * be asked" (a plain IOException) -- the distinction every verified-teardown path
     * in C6 hinges on.
     */
    public static class ApiException extends IOException {

        private final int status;

        ApiException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return this.status;
        }

        /** The daemon answered and the resource does not exist. */
        public boolean isNotFound() {
            return this.status == 404;
        }
    }

    /** Default per-request deadline (connect + full read); generous enough for a
     *  container stop grace period, short enough to catch a truly-hung daemon. */
    public static final long DEFAULT_TIMEOUT_MS = 60_000;

    /** Deadline for long streaming operations (image pull / build) that legitimately
     *  run for minutes; still bounded so a wedged stream eventually fails. */
    public static final long LONG_OP_TIMEOUT_MS = 600_000;

    private final DockerTransport transport;
    private final long timeoutMillis;

    /** Talk to the local daemon over its default unix socket. */
    public DockerClient() {
        this(new UnixSocketDockerTransport(DEFAULT_SOCKET), DEFAULT_TIMEOUT_MS);
    }

    public DockerClient(DockerTransport transport) {
        this(transport, DEFAULT_TIMEOUT_MS);
    }

    // AIDEV-NOTE: honest instrumentation for the roles contract, not bookkeeping.
    // "roles.stacks/databases off" promises NO DockerClient is ever constructed,
    // and a promise nothing can observe is security theater; the role tests
    // assert this counter stays at zero on a docker-less boot.
    private static final java.util.concurrent.atomic.AtomicLong CONSTRUCTED =
        new java.util.concurrent.atomic.AtomicLong();

    public DockerClient(DockerTransport transport, long timeoutMillis) {
        this.transport = transport;
        this.timeoutMillis = timeoutMillis;
        CONSTRUCTED.incrementAndGet();
    }

    /** @return how many DockerClient instances this JVM has ever constructed */
    public static long constructionCount() {
        return CONSTRUCTED.get();
    }

    // -----------------------------------------------------------------------
    // Daemon
    // -----------------------------------------------------------------------

    /**
     * @return true if the daemon answers {@code /_ping} with "OK"; false on any error
     */
    public boolean ping() {
        try {
            return "OK".equals(get("/_ping").body().trim());
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * @return the daemon's {@code /version} payload (Version, ApiVersion, Os, Arch, ...)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> version() throws IOException {
        return (Map<String, Object>) parseJson(get("/version").body());
    }

    /**
     * @return the daemon's {@code /info} payload (NCPU, MemTotal, Containers, ContainersRunning,
     *         Images, ...) -- a cheap host-level resource snapshot
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> info() throws IOException {
        return (Map<String, Object>) parseJson(get("/info").body());
    }

    // -----------------------------------------------------------------------
    // Images
    // -----------------------------------------------------------------------

    /**
     * @return one map per local image as returned by {@code /images/json}
     */
    @SuppressWarnings("unchecked")
    public List<Object> listImages() throws IOException {
        return (List<Object>) parseJson(get("/images/json").body());
    }

    /**
     * Pull an image, blocking until the pull completes. The daemon streams progress
     * (and returns HTTP 200 even on failure), so this scans the stream for an error
     * object and throws if present.
     *
     * @param image repository, e.g. {@code "library/alpine"} or {@code "alpine"}
     * @param tag   tag, e.g. {@code "latest"} (defaults to {@code latest} when null/blank)
     */
    public void pullImage(String image, String tag) throws IOException {
        pullImage(image, tag, null);
    }

    /**
     * Pull an image, optionally authenticating against a private registry via the
     * {@code X-Registry-Auth} header.
     */
    public void pullImage(String image, String tag, RegistryAuth auth) throws IOException {
        String resolvedTag = (tag == null || tag.isBlank()) ? "latest" : tag;
        String path = "/images/create?fromImage=" + enc(image) + "&tag=" + enc(resolvedTag);
        Map<String, String> headers = auth == null ? null : Map.of("X-Registry-Auth", auth.encode());
        String body = new String(
            exchange("POST", path, null, null, headers, LONG_OP_TIMEOUT_MS).body(),
            StandardCharsets.UTF_8);
        throwIfStreamError(body, "Docker image pull for " + image + ":" + resolvedTag);
    }

    /**
     * Registry credentials for {@code X-Registry-Auth}: sent base64-encoded per the Engine
     * API. {@code serverAddress} is the registry host (e.g. {@code "ghcr.io"}); null/blank
     * targets Docker Hub.
     */
    public record RegistryAuth(String username, String password, String serverAddress) {

        /** @return the base64-encoded JSON auth config the Engine API expects */
        public String encode() {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("username", username);
            config.put("password", password);
            if (serverAddress != null && !serverAddress.isBlank()) {
                config.put("serveraddress", serverAddress);
            }
            return Base64.getUrlEncoder().encodeToString(
                toJson(config).getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Load a docker-format image tar into the daemon ({@code POST /images/load}) -- the
     * ONE way a built artifact enters this daemon.
     *
     * AIDEV-NOTE: there is deliberately no {@code buildImage} here any more. The daemon's
     * own {@code /build} endpoint executes the tenant's Dockerfile INSIDE the daemon, as
     * root on the host, with the daemon's network and no quota of any kind -- it is the
     * control-plane trust domain by definition, which is precisely what the sandboxed
     * builders wave exists to leave. Builds run in a hardened, quota-bound, daemonless
     * container ({@code server.build.BuildSandbox}) and their artifact arrives here as a
     * tar. Do not reintroduce /build: it has no sandbox to add.
     *
     * @return the daemon's NDJSON progress stream (the loaded reference is in it)
     * @throws IOException when the stream carries an error object
     */
    public String loadImage(Path imageTar) throws IOException {
        String body = request("POST", "/images/load?quiet=false",
            Files.readAllBytes(imageTar), "application/x-tar", LONG_OP_TIMEOUT_MS).body();
        throwIfStreamError(body, "Docker image load from " + imageTar.getFileName());
        return body;
    }

    /**
     * @return the full {@code /images/{name}/json} inspection (Id, RepoTags, Config, ...)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> inspectImage(String name) throws IOException {
        return (Map<String, Object>) parseJson(get("/images/" + name + "/json").body());
    }

    /**
     * The size of a container's WRITABLE LAYER, as the daemon accounts it.
     *
     * AIDEV-NOTE: {@code ?size=1} is not free -- the daemon walks the layer -- so this is
     * a watchdog poll, never a status-path call. It is the only disk accounting available
     * on an ordinary overlay2 host: the {@code --storage-opt size=} cgroup equivalent
     * needs xfs prjquota, which hohenheim does not own.
     *
     * @return SizeRw in bytes, or -1 when the daemon did not report it
     */
    @SuppressWarnings("unchecked")
    public long containerWritableBytes(String id) throws IOException {
        Map<String, Object> inspect = (Map<String, Object>) parseJson(
            get("/containers/" + id + "/json?size=1").body());
        Object size = inspect.get("SizeRw");
        return size instanceof Number number ? number.longValue() : -1;
    }

    /**
     * Remove an image by name/tag or id. {@code name} may contain {@code /} and {@code :}
     * (kept as path, not URL-encoded, per the Engine API).
     */
    public void removeImage(String name, boolean force) throws IOException {
        request("DELETE", "/images/" + name + (force ? "?force=true" : ""), null, null, timeoutMillis);
    }

    /** Add {@code repo:tag} as an additional reference to an existing image. */
    public void tagImage(String name, String repo, String tag) throws IOException {
        request("POST", "/images/" + name + "/tag?repo=" + enc(repo) + "&tag=" + enc(tag),
            null, null, timeoutMillis);
    }

    /**
     * Pull {@code image:tag} only if it is not already present locally. {@code image} may
     * embed the tag (e.g. {@code "postgres:17-alpine"}); a colon that is part of a
     * registry host:port (followed by {@code /}) is not treated as a tag separator.
     */
    public void ensureImage(String image, String tag) throws IOException {
        ensureImage(image, tag, null);
    }

    /** Conditional pull with optional private-registry credentials. */
    public void ensureImage(String image, String tag, RegistryAuth auth) throws IOException {
        // A bare content-addressed id ("sha256:...") is a LOCAL artifact -- the shape a
        // sandboxed build's output is pinned by. It is verified present and NEVER pulled:
        // treating it as repo "sha256" with a tag would send the digest to a registry as
        // a tag name and fail with an unreadable 404 on every deploy of a built site.
        if (image.startsWith("sha256:")) {
            for (Object entry : listImages()) {
                if (entry instanceof Map<?, ?> local && image.equals(local.get("Id"))) {
                    return;
                }
            }
            throw new IOException("Image '" + image + "' is a content-addressed reference that"
                + " this daemon does not have. A digest-pinned release cannot be satisfied by"
                + " pulling (nothing publishes it); rebuild it.");
        }
        // Digest-pinned reference (repo@sha256:...): present iff the digest is in
        // RepoDigests; pulls pass the digest via the tag parameter per the Engine API.
        int at = image.indexOf('@');
        if (at > 0) {
            String repo = image.substring(0, at);
            String digest = image.substring(at + 1);
            if (digest.isBlank()) {
                throw new IOException("Malformed digest-pinned image reference: " + image);
            }
            // Compose-style pins carry a tag before the digest (repo:tag@sha256:...);
            // the engine pulls by digest, so the tag must be stripped from fromImage.
            int colon = repo.lastIndexOf(':');
            if (colon > repo.lastIndexOf('/')) {
                repo = repo.substring(0, colon);
            }
            // Presence needs digest AND repository: createContainer uses the user's
            // spelling, so a same-digest image known only under ANOTHER repo (hub vs a
            // private mirror) would pass this check and then fail create with "No such
            // image" -- identically on every retry. Repos compare hub-normalized
            // ("nginx" == "docker.io/library/nginx"), never by raw string, because
            // RepoDigests entries are registry-normalized while the spec is not.
            for (Object entry : listImages()) {
                Object repoDigests = ((Map<?, ?>) entry).get("RepoDigests");
                if (!(repoDigests instanceof List<?> digests)) {
                    continue;
                }
                for (Object stored : digests) {
                    if (digestRefMatches(String.valueOf(stored), repo, digest)) {
                        return;
                    }
                }
            }
            String path = "/images/create?fromImage=" + enc(repo) + "&tag=" + enc(digest);
            Map<String, String> headers = auth == null ? null : Map.of("X-Registry-Auth", auth.encode());
            String body = new String(
                exchange("POST", path, null, null, headers, LONG_OP_TIMEOUT_MS).body(),
                StandardCharsets.UTF_8);
            throwIfStreamError(body, "Docker image pull for " + repo + "@" + digest);
            return;
        }

        String repo = image;
        String resolvedTag = tag;
        int colon = image.lastIndexOf(':');
        if (colon > 0 && !image.substring(colon + 1).contains("/")) {
            repo = image.substring(0, colon);
            resolvedTag = image.substring(colon + 1);
        }
        if (resolvedTag == null || resolvedTag.isBlank()) {
            resolvedTag = "latest";
        }
        String ref = repo + ":" + resolvedTag;
        for (Object entry : listImages()) {
            Object repoTags = ((Map<?, ?>) entry).get("RepoTags");
            if (repoTags instanceof List<?> tags && tags.contains(ref)) {
                return;
            }
        }
        pullImage(repo, resolvedTag, auth);
    }

    /** Whether a RepoDigests entry names the same (hub-normalized) repo and digest. */
    public static boolean digestRefMatches(String stored, String wantedRepo, String digest) {
        int storedAt = stored.indexOf('@');
        if (storedAt <= 0 || !stored.substring(storedAt + 1).equals(digest)) {
            return false;
        }
        return normalizeRepo(stored.substring(0, storedAt)).equals(normalizeRepo(wantedRepo));
    }

    /** Canonical hub form: "nginx" == "docker.io/library/nginx" == "library/nginx". */
    public static String normalizeRepo(String repo) {
        String normalized = repo;
        if (normalized.startsWith("docker.io/")) {
            normalized = normalized.substring("docker.io/".length());
        }
        if (!normalized.contains("/")) {
            normalized = "library/" + normalized;
        }
        return normalized;
    }

    // -----------------------------------------------------------------------
    // Volumes
    // -----------------------------------------------------------------------

    /**
     * Create (or return, when it already exists with the same driver) a named volume.
     * Docker's {@code /volumes/create} is idempotent per name.
     *
     * @param labels optional volume labels (null for none); hohenheim ownership labels
     *               are how the stack tier marks volumes it may touch
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> createVolume(String name, Map<String, String> labels) throws IOException {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("Name", name);
        if (labels != null && !labels.isEmpty()) {
            spec.put("Labels", labels);
        }
        return (Map<String, Object>) parseJson(request("POST", "/volumes/create", toJson(spec)).body());
    }

    /**
     * @return the volume's inspection payload (Name, Driver, Mountpoint, Labels, ...)
     * @throws IOException when the volume does not exist (404)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> inspectVolume(String name) throws IOException {
        return (Map<String, Object>) parseJson(get("/volumes/" + enc(name)).body());
    }

    /**
     * @return one map per volume ({@code /volumes} returns {Volumes: [...], Warnings: [...]})
     */
    public List<Object> listVolumes() throws IOException {
        Object parsed = parseJson(get("/volumes").body());
        Object volumes = parsed instanceof Map<?, ?> map ? map.get("Volumes") : null;
        return volumes instanceof List<?> list ? new ArrayList<>(list) : List.of();
    }

    /** Remove a named volume; {@code force} removes it even if in use by stopped containers. */
    public void removeVolume(String name, boolean force) throws IOException {
        request("DELETE", "/volumes/" + enc(name) + (force ? "?force=true" : ""), null, null, timeoutMillis);
    }

    // -----------------------------------------------------------------------
    // Networks
    // -----------------------------------------------------------------------

    /**
     * Create a bridge network. Unlike volumes, network creation is NOT idempotent per
     * name (Docker allows duplicate names); callers wanting ensure-semantics use
     * {@link #findNetworkByName} first.
     *
     * AIDEV-NOTE: {@code EnableIPv6} is sent EXPLICITLY rather than omitted. A daemon can
     * default it on (daemon-level default network options), and a v6-enabled network under
     * a v4-only host policy is a bypass -- see WorkloadNetworkPolicy. Saying false out loud
     * is what makes the intent survive a daemon-side default change; the applier still
     * reads the answer back rather than trusting this.
     *
     * @param labels optional network labels (null for none)
     * @param subnet optional IPAM subnet in CIDR form (null lets Docker pick)
     * @param gateway optional IPAM gateway (null lets Docker pick; requires subnet)
     * @param enableIpv6 whether the network gets IPv6 addressing
     * @return the new network's id
     */
    @SuppressWarnings("unchecked")
    public String createNetwork(String name, Map<String, String> labels,
                                String subnet, String gateway, boolean enableIpv6)
            throws IOException {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("Name", name);
        spec.put("Driver", "bridge");
        spec.put("EnableIPv6", enableIpv6);
        if (labels != null && !labels.isEmpty()) {
            spec.put("Labels", labels);
        }
        if (subnet != null && !subnet.isBlank()) {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("Subnet", subnet);
            if (gateway != null && !gateway.isBlank()) {
                config.put("Gateway", gateway);
            }
            spec.put("IPAM", Map.of("Driver", "default", "Config", List.of(config)));
        }
        Map<String, Object> result = (Map<String, Object>) parseJson(
            request("POST", "/networks/create", toJson(spec)).body());
        return (String) result.get("Id");
    }

    /**
     * @return the network's inspection payload (Id, Name, Driver, IPAM, Containers, Labels, ...)
     * @throws IOException when the network does not exist (404)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> inspectNetwork(String idOrName) throws IOException {
        return (Map<String, Object>) parseJson(get("/networks/" + enc(idOrName)).body());
    }

    /**
     * @return one map per network as returned by {@code /networks}
     */
    @SuppressWarnings("unchecked")
    public List<Object> listNetworks() throws IOException {
        return (List<Object>) parseJson(get("/networks").body());
    }

    /**
     * @return the network with that exact name, or null when none exists (name lookup by
     *         list-and-filter because {@code /networks/{name}} also matches id prefixes)
     */
    public Map<String, Object> findNetworkByName(String name) throws IOException {
        for (Object entry : listNetworks()) {
            if (entry instanceof Map<?, ?> network && name.equals(network.get("Name"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) network;
                return typed;
            }
        }
        return null;
    }

    /** Remove a network by id or name; fails while containers are still connected. */
    public void removeNetwork(String idOrName) throws IOException {
        request("DELETE", "/networks/" + enc(idOrName), null);
    }

    /**
     * Connect a container to a network, optionally with DNS aliases other containers on
     * that network can resolve (the compose service-name convention).
     */
    public void connectContainerToNetwork(String network, String containerId,
                                          List<String> aliases) throws IOException {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("Container", containerId);
        if (aliases != null && !aliases.isEmpty()) {
            spec.put("EndpointConfig", Map.of("Aliases", aliases));
        }
        request("POST", "/networks/" + enc(network) + "/connect", toJson(spec));
    }

    /** Disconnect a container from a network; {@code force} disconnects even a running one. */
    public void disconnectContainerFromNetwork(String network, String containerId,
                                               boolean force) throws IOException {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("Container", containerId);
        if (force) {
            spec.put("Force", true);
        }
        request("POST", "/networks/" + enc(network) + "/disconnect", toJson(spec));
    }

    // -----------------------------------------------------------------------
    // Containers
    // -----------------------------------------------------------------------

    /**
     * @param includeStopped also list non-running containers (Docker's {@code all=true})
     * @return one map per container as returned by {@code /containers/json}
     */
    @SuppressWarnings("unchecked")
    public List<Object> listContainers(boolean includeStopped) throws IOException {
        String path = "/containers/json" + (includeStopped ? "?all=true" : "");
        return (List<Object>) parseJson(get(path).body());
    }

    /**
     * Create a HARDENED container from a spec (e.g. {@code Image}, {@code Cmd},
     * {@code Env}, {@code ExposedPorts}, {@code HostConfig}). The image must already be
     * present locally (call {@link #pullImage} first if unsure).
     *
     * AIDEV-NOTE: THE single /containers/create call in the codebase, and the profile is
     * a required parameter on purpose -- this is the one funnel that makes container
     * hardening unskippable. There is deliberately no overload without it: a caller that
     * would rather not think about isolation still has to name a profile, and STRICT is
     * the answer when it does not know. Do not add a two-argument convenience.
     *
     * @param name    optional container name (null/blank for a daemon-assigned name)
     * @param profile the workload kind's declared capability needs
     * @return the new container's id
     * @throws IllegalArgumentException when the spec carries a privilege escape
     *                                  (see {@link ContainerHardening#ESCAPE_KEYS})
     */
    public String createContainer(String name, Map<String, Object> spec,
                                  ContainerHardening.Profile profile) throws IOException {
        return createContainer(name, spec, profile, null);
    }

    /**
     * Create a hardened container with a TIGHTER process cap than the host default.
     *
     * @param tighterPidsLimit only ever lowers the cap (see
     *                         {@link ContainerHardening#applyTo(Map, ContainerHardening.Profile, Integer)})
     */
    @SuppressWarnings("unchecked")
    public String createContainer(String name, Map<String, Object> spec,
                                  ContainerHardening.Profile profile,
                                  Integer tighterPidsLimit) throws IOException {
        Map<String, Object> hardened = new LinkedHashMap<>(spec);
        ContainerHardening.applyTo(hardened, profile, tighterPidsLimit);
        String path = "/containers/create" + (name != null && !name.isBlank() ? "?name=" + enc(name) : "");
        Map<String, Object> result = (Map<String, Object>) parseJson(
            request("POST", path, toJson(hardened)).body());
        return (String) result.get("Id");
    }

    /** Start a created container (idempotent: an already-running container is a no-op). */
    public void startContainer(String id) throws IOException {
        request("POST", "/containers/" + id + "/start", null);
    }

    /** Stop a running container with Docker's default grace period. */
    public void stopContainer(String id) throws IOException {
        stopContainer(id, 10);
    }

    /**
     * Stop a running container, giving it {@code graceSeconds} to exit before SIGKILL.
     * Idempotent (the daemon returns 304 if already stopped). Keep {@code graceSeconds}
     * below the client timeout so the request itself doesn't time out waiting on the grace.
     */
    public void stopContainer(String id, int graceSeconds) throws IOException {
        request("POST", "/containers/" + id + "/stop?t=" + graceSeconds, null);
    }

    /** Restart a container, giving it {@code graceSeconds} to stop before SIGKILL. */
    public void restartContainer(String id, int graceSeconds) throws IOException {
        request("POST", "/containers/" + id + "/restart?t=" + graceSeconds, null);
    }

    /** Remove a container; {@code force} kills it first if running. */
    public void removeContainer(String id, boolean force) throws IOException {
        request("DELETE", "/containers/" + id + (force ? "?force=true" : ""), null);
    }

    /**
     * @return the container's full {@code /json} inspection (State, NetworkSettings, ...)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> inspectContainer(String id) throws IOException {
        return (Map<String, Object>) parseJson(get("/containers/" + id + "/json").body());
    }

    /**
     * Fetch a container's logs as a single text snapshot (stdout and stderr interleaved in
     * arrival order, like {@code docker logs}). Demultiplexes Docker's framed stream for
     * non-TTY containers -- all hohenheim-managed containers run without a TTY.
     *
     * @param tail max number of trailing lines, or {@code <= 0} for the full log
     */
    public String containerLogs(String id, boolean stdout, boolean stderr, int tail) throws IOException {
        String path = "/containers/" + id + "/logs?stdout=" + (stdout ? 1 : 0) + "&stderr=" + (stderr ? 1 : 0);
        if (tail > 0) {
            path += "&tail=" + tail;
        }
        return demuxStream(exchange("GET", path, null, null, timeoutMillis).body());
    }

    /** Result of an in-container {@link #exec}: exit code plus stdout and stderr, kept separate
     *  so callers (e.g. backups) get clean stdout without diagnostic noise from stderr. */
    public record ExecResult(int exitCode, String stdout, String stderr) {
        /** stdout with stderr appended -- for simple logging when separation doesn't matter. */
        public String output() {
            return stderr.isEmpty() ? stdout : stdout + stderr;
        }
    }

    /** Run a command inside a running container; see {@link #exec(String, List, List)}. */
    public ExecResult exec(String containerId, List<String> command) throws IOException {
        return exec(containerId, command, List.of());
    }

    /**
     * Run a command inside a running container and block until it exits, capturing stdout and
     * stderr separately plus the exit code. The basis of database backup/restore, which run
     * {@code pg_dump}/{@code psql} (etc.) inside the engine's own container.
     *
     * @param env {@code "KEY=value"} entries set for the command (e.g. a dump password, which
     *            stays out of the captured stdout)
     */
    @SuppressWarnings("unchecked")
    public ExecResult exec(String containerId, List<String> command, List<String> env) throws IOException {
        Map<String, Object> createSpec = new LinkedHashMap<>();
        createSpec.put("AttachStdout", true);
        createSpec.put("AttachStderr", true);
        createSpec.put("Cmd", command);
        if (!env.isEmpty()) {
            createSpec.put("Env", env);
        }
        Map<String, Object> created = (Map<String, Object>) parseJson(request("POST",
            "/containers/" + containerId + "/exec", toJson(createSpec)).body());
        String execId = (String) created.get("Id");

        // Detach=false streams the (multiplexed, non-TTY) output until the process exits and
        // the daemon closes the connection; LONG_OP_TIMEOUT covers slow ops like a dump.
        RawResponse stream = exchange("POST", "/exec/" + execId + "/start",
            toJson(Map.of("Detach", false, "Tty", false)).getBytes(StandardCharsets.UTF_8),
            "application/json", LONG_OP_TIMEOUT_MS);

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        walkFrames(stream.body(), (type, buffer, offset, length) ->
            (type == 2 ? stderr : stdout).write(buffer, offset, length));

        Map<String, Object> info = (Map<String, Object>) parseJson(get("/exec/" + execId + "/json").body());
        int exitCode = info.get("ExitCode") instanceof Number n ? n.intValue() : -1;
        return new ExecResult(exitCode,
            new String(stdout.toByteArray(), StandardCharsets.UTF_8),
            new String(stderr.toByteArray(), StandardCharsets.UTF_8));
    }

    // -----------------------------------------------------------------------
    // Streaming (the SECOND transport contract; see DockerStreamTransport)
    // -----------------------------------------------------------------------

    /**
     * Attach to a container's console: live stdout/stderr frames plus stdin writes.
     * The daemon HIJACKS this connection (raw stream after the header block), and it
     * ends when the container exits -- which is exactly the observation the console
     * hub's crash detection rides. Works on a CREATED container too (`docker run`'s own
     * create-attach-start order), which is how output between create and attach is
     * never missed.
     *
     * Stdin only reaches the workload when the container was created with
     * {@code OpenStdin: true}; the daemon silently discards writes otherwise, so
     * callers that promise delivery must check the inspect payload first.
     *
     * @throws IOException also when this client's transport has no streaming lane
     */
    public ContainerStream attach(String id) throws IOException {
        String path = "/containers/" + id + "/attach?stream=1&stdout=1&stderr=1&stdin=1";
        byte[] request = buildStreamRequest("POST", path);
        return ContainerStream.open(streamTransport(), request, timeoutMillis, true);
    }

    /**
     * Follow a container's logs from {@code tail} lines back: history plus live frames,
     * chunked by the daemon, ending when the container stops (or the consumer closes).
     *
     * @param tail max trailing history lines, or {@code <= 0} for the full log
     */
    public ContainerStream followLogs(String id, int tail) throws IOException {
        String path = "/containers/" + id + "/logs?follow=1&stdout=1&stderr=1"
            + (tail > 0 ? "&tail=" + tail : "");
        byte[] request = buildStreamRequest("GET", path);
        return ContainerStream.open(streamTransport(), request, timeoutMillis, false);
    }

    /**
     * The streaming lane of this client's transport -- a NAMED refusal when the
     * transport cannot stream, never a silent no-op (the capability-interface doctrine).
     */
    private DockerStreamTransport streamTransport() throws IOException {
        if (this.transport instanceof DockerStreamTransport streaming) {
            return streaming;
        }
        throw new IOException("Docker transport " + this.transport.getClass().getSimpleName()
            + " has no streaming lane; console/follow endpoints are unavailable on it");
    }

    /**
     * A streaming request head: no {@code Connection: close} (the stream's lifetime is
     * the consumer's, and attach needs the write half alive), explicit
     * {@code Upgrade: tcp} so the daemon treats attach as the bidirectional stream it is.
     */
    private static byte[] buildStreamRequest(String method, String path) {
        String head = method + ' ' + path + " HTTP/1.1\r\n"
            + "Host: docker\r\n"
            + "Connection: Upgrade\r\n"
            + "Upgrade: tcp\r\n\r\n";
        return head.getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * @return the host port Docker published for {@code containerPort}/tcp on the container
     * @throws IOException if the container did not publish that port
     */
    public int publishedPort(String containerId, int containerPort) throws IOException {
        Object networkSettings = inspectContainer(containerId).get("NetworkSettings");
        Object ports = networkSettings instanceof Map<?, ?> ns ? ns.get("Ports") : null;
        Object bindings = ports instanceof Map<?, ?> p ? p.get(containerPort + "/tcp") : null;
        if (bindings instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> binding) {
            Object hostPort = binding.get("HostPort");
            if (hostPort != null && !hostPort.toString().isBlank()) {
                try {
                    return Integer.parseInt(hostPort.toString());
                } catch (NumberFormatException e) {
                    throw new IOException("Bad published port '" + hostPort + "'");
                }
            }
        }
        throw new IOException("Container did not publish port " + containerPort);
    }

    /**
     * Tar {@code hostDir} and extract it into {@code targetDir} inside the container
     * ({@code PUT /containers/{id}/archive}); {@code targetDir} must already exist. Used to
     * push a file (e.g. a dump to restore) into a running container.
     */
    public void putArchiveFromDirectory(String containerId, String targetDir, Path hostDir) throws IOException {
        String path = "/containers/" + containerId + "/archive?path=" + enc(targetDir);
        request("PUT", path, tarDirectory(hostDir), "application/x-tar", LONG_OP_TIMEOUT_MS);
    }

    /**
     * Like {@link #putArchiveFromDirectory}, but the tar names ONLY the given files: no
     * directory entries, so extraction never re-owns or re-modes an EXISTING container
     * directory (chowning a non-root image's writable volume root to root:root is how a
     * staged config file used to brick the workload). Missing parents are still created
     * by the daemon (root-owned 0755).
     */
    public void putArchiveFiles(String containerId, String targetDir, Path hostDir,
                                List<String> relativeFiles) throws IOException {
        String path = "/containers/" + containerId + "/archive?path=" + enc(targetDir);
        List<String> command = new ArrayList<>(List.of("tar", "-C", hostDir.toString(), "-cf", "-"));
        command.addAll(relativeFiles);
        request("PUT", path, tarWith(command, hostDir), "application/x-tar", LONG_OP_TIMEOUT_MS);
    }

    /**
     * Download a directory from a container ({@code GET /containers/{id}/archive}) as a raw
     * tar, written to {@code outFile}. Works on stopped containers -- the volume-snapshot
     * mechanism's read primitive. The tar's entries are rooted at the directory's basename
     * (Docker's envelope), which {@link #putArchiveTar} relies on for the restore side.
     *
     * @param maxBytes response cap enforced DURING the read (see DockerTransport); the
     *                 whole tar is buffered through memory, so the cap is the heap guard
     * @return the number of bytes written to {@code outFile}
     */
    public long getArchiveTar(String containerId, String path, Path outFile, long maxBytes)
            throws IOException {
        RawResponse response = exchangeBounded("GET",
            "/containers/" + containerId + "/archive?path=" + enc(path), maxBytes);
        Files.write(outFile, response.body());
        return response.body().length;
    }

    /**
     * Upload a raw tar into a container ({@code PUT /containers/{id}/archive}), extracting
     * it at {@code targetDir} (which must exist). Works on stopped containers -- the
     * volume-snapshot mechanism's write primitive. Extraction MERGES into the target; a
     * faithful restore must therefore write into freshly recreated volumes, never over
     * live contents.
     */
    public void putArchiveTar(String containerId, String targetDir, Path tarFile) throws IOException {
        String path = "/containers/" + containerId + "/archive?path=" + enc(targetDir);
        request("PUT", path, Files.readAllBytes(tarFile), "application/x-tar", LONG_OP_TIMEOUT_MS);
    }

    // A bounded exchange for endpoints whose response size is data-dependent (archives).
    private RawResponse exchangeBounded(String method, String path, long maxBytes) throws IOException {
        return parseHttpRaw(transport.roundTrip(
            buildRequest(method, path, null, null, null), LONG_OP_TIMEOUT_MS, maxBytes));
    }

    /**
     * Download a single file from a container ({@code GET /containers/{id}/archive}) and return
     * its raw bytes, unwrapping Docker's tar envelope. Binary-safe (used for RDB / mongodump
     * archives). {@code path} must point at a single file, not a directory.
     *
     * @param maxBytes cap on the TRANSFER, enforced during the read: over-size throws and
     *                 yields nothing, so a truncated read can never pass for the file. The
     *                 parameter is required deliberately -- an unbounded archive read against
     *                 a data-dependent endpoint is an OOM waiting for the right file.
     */
    public byte[] getArchiveFile(String containerId, String path, long maxBytes) throws IOException {
        byte[] tar = exchangeBounded("GET",
            "/containers/" + containerId + "/archive?path=" + enc(path), maxBytes).body();
        return extractSingleFile(tar);
    }

    /**
     * lstat one container path ({@code HEAD /containers/{id}/archive}), decoded from the
     * daemon's {@code X-Docker-Container-Path-Stat} header. LSTAT, not stat: a symlink is
     * reported AS a symlink with its target, which is what the file manager's containment
     * walk needs to see BEFORE the daemon resolves through it.
     *
     * AIDEV-NOTE: the daemon resolves the path inside the CONTAINER's rootfs scope, so an
     * absolute link target names a container path, never a host one -- but it does NOT
     * confine to any volume, and it reports the RESULT of resolving an intermediate
     * symlink as an ordinary file. Containment above this call is not optional.
     *
     * @throws FileNotFoundException when the path does not exist
     */
    public PathStat statArchivePath(String containerId, String path) throws IOException {
        RawResponse response;
        try {
            response = parseHttpRaw(transport.roundTrip(buildRequest("HEAD",
                "/containers/" + containerId + "/archive?path=" + enc(path), null, null, null),
                timeoutMillis));
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw new FileNotFoundException(
                    "No such path in container " + containerId + ": " + path);
            }
            throw e;
        }
        String encoded = response.header("X-Docker-Container-Path-Stat");
        if (encoded == null || encoded.isEmpty()) {
            throw new IOException("Docker returned no path stat for " + path);
        }
        Object parsed = parseJson(new String(
            Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> stat)) {
            throw new IOException("Docker returned an unreadable path stat for " + path);
        }
        long mode = stat.get("mode") instanceof Number number ? number.longValue() : 0;
        long size = stat.get("size") instanceof Number number ? number.longValue() : 0;
        String linkTarget = stat.get("linkTarget") instanceof String target ? target : "";
        return new PathStat(String.valueOf(stat.get("name")), mode, size, linkTarget,
            String.valueOf(stat.get("mtime")));
    }

    /**
     * One lstat as Docker reports it. {@code mode} carries GO's {@code os.FileMode} bits,
     * not POSIX ones: the type lives in the HIGH bits (bit 31 directory, bit 27 symlink)
     * and only the low 9 bits are permissions.
     */
    public record PathStat(String name, long mode, long size, String linkTarget, String mtime) {

        private static final long GO_MODE_DIR = 1L << 31;
        private static final long GO_MODE_SYMLINK = 1L << 27;
        /** Every non-permission bit Go sets; anything left is "some other kind of node". */
        private static final long GO_MODE_TYPE_MASK = 0xFFFFFE00L;

        public boolean isDirectory() {
            return (this.mode & GO_MODE_DIR) != 0;
        }

        public boolean isSymlink() {
            return (this.mode & GO_MODE_SYMLINK) != 0;
        }

        /** A plain file: no type bit at all is set. */
        public boolean isRegularFile() {
            return (this.mode & GO_MODE_TYPE_MASK) == 0;
        }

        /** The permission bits as a 4-digit octal string, e.g. {@code 0644}. */
        public String permissions() {
            StringBuilder octal = new StringBuilder(Long.toOctalString(this.mode & 0777));
            while (octal.length() < 4) {
                octal.insert(0, '0');
            }
            return octal.toString();
        }
    }

    /**
     * Follow a container's resource stats: one JSON sample per interval, chunked by the
     * daemon, ending when the container stops (or the consumer closes). The
     * {@link #followLogs} sibling on the streaming transport.
     */
    public ContainerStream followStats(String id) throws IOException {
        byte[] request = buildStreamRequest("GET", "/containers/" + id + "/stats?stream=1");
        return ContainerStream.open(streamTransport(), request, timeoutMillis, false);
    }

    // Extract the one file from a tar via the system `tar` (symmetry with tarDirectory).
    private static byte[] extractSingleFile(byte[] tar) throws IOException {
        Path tmpDir = Files.createTempDirectory("hohenheim-getarchive");
        Path tarFile = tmpDir.resolve("archive.tar");
        try {
            Files.write(tarFile, tar);
            Process process = new ProcessBuilder("tar", "-xf", tarFile.toString(), "-C", tmpDir.toString()).start();
            try {
                if (!process.waitFor(60, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("tar extract of archive timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("tar extract of archive interrupted");
            }
            if (process.exitValue() != 0) {
                throw new IOException("tar extract of archive failed (exit " + process.exitValue() + ")");
            }
            try (Stream<Path> files = Files.list(tmpDir)) {
                Path extracted = files.filter(file -> !file.equals(tarFile)).findFirst()
                    .orElseThrow(() -> new IOException("Docker archive contained no file"));
                return Files.readAllBytes(extracted);
            }
        } finally {
            deleteRecursively(tmpDir);
        }
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    // -----------------------------------------------------------------------
    // HTTP/1.1 over the transport (unix socket or process stdio)
    // -----------------------------------------------------------------------

    private record Response(int status, String body) {}

    private record RawResponse(int status, byte[] body, Map<String, String> headers) {
        RawResponse(int status, byte[] body) {
            this(status, body, Map.of());
        }

        /** Header lookup is case-insensitive on the wire; keys are stored lower-cased. */
        String header(String name) {
            return this.headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private Response get(String path) throws IOException {
        return request("GET", path, null, null, timeoutMillis);
    }

    private Response request(String method, String path, String jsonBody) throws IOException {
        byte[] body = jsonBody == null ? null : jsonBody.getBytes(StandardCharsets.UTF_8);
        return request(method, path, body, jsonBody == null ? null : "application/json", timeoutMillis);
    }

    private Response request(String method, String path, byte[] body, String contentType, long timeoutMs)
            throws IOException {
        RawResponse response = exchange(method, path, body, contentType, timeoutMs);
        return new Response(response.status(), new String(response.body(), StandardCharsets.UTF_8));
    }

    // Performs the HTTP exchange via the transport and returns the byte-accurate body, so binary
    // endpoints (multiplexed log/exec streams, archive downloads) aren't corrupted by a UTF-8 decode.
    private RawResponse exchange(String method, String path, byte[] body, String contentType, long timeoutMs)
            throws IOException {
        return exchange(method, path, body, contentType, null, timeoutMs);
    }

    private RawResponse exchange(String method, String path, byte[] body, String contentType,
                                 Map<String, String> extraHeaders, long timeoutMs) throws IOException {
        return parseHttpRaw(transport.roundTrip(
            buildRequest(method, path, body, contentType, extraHeaders), timeoutMs));
    }

    private static byte[] buildRequest(String method, String path, byte[] body, String contentType,
                                       Map<String, String> extraHeaders) {
        StringBuilder head = new StringBuilder();
        head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        head.append("Host: docker\r\n");
        head.append("Accept: application/json\r\n");
        if (extraHeaders != null) {
            for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
                head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
            }
        }
        if (body != null) {
            head.append("Content-Type: ").append(contentType).append("\r\n");
            head.append("Content-Length: ").append(body.length).append("\r\n");
        }
        head.append("Connection: close\r\n\r\n");

        byte[] headBytes = head.toString().getBytes(StandardCharsets.ISO_8859_1);
        if (body == null || body.length == 0) {
            return headBytes;
        }
        byte[] request = new byte[headBytes.length + body.length];
        System.arraycopy(headBytes, 0, request, 0, headBytes.length);
        System.arraycopy(body, 0, request, headBytes.length, body.length);
        return request;
    }

    // Tar the build context via the system `tar` (handles file modes, symlinks, and
    // nesting robustly); the daemon's /build endpoint wants the context as a tar body.
    private static byte[] tarDirectory(Path dir) throws IOException {
        return tarWith(List.of("tar", "-C", dir.toString(), "-cf", "-", "."), dir);
    }

    private static byte[] tarWith(List<String> command, Path dir) throws IOException {
        Process process = new ProcessBuilder(command).start();
        byte[] tar;
        try (var stdout = process.getInputStream()) {
            tar = stdout.readAllBytes();
        }
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("tar of build context timed out: " + dir);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("tar of build context interrupted: " + dir);
        }
        if (process.exitValue() != 0) {
            throw new IOException("tar of build context failed (exit " + process.exitValue() + "): " + dir);
        }
        return tar;
    }

    // AIDEV-NOTE: We decode raw bytes as ISO-8859-1 (1 char == 1 byte) so header
    // splitting and chunk-size offsets stay byte-accurate, then re-decode the
    // assembled body as UTF-8. Don't "simplify" this to a single UTF-8 decode.
    private static RawResponse parseHttpRaw(byte[] raw) throws IOException {
        String text = new String(raw, StandardCharsets.ISO_8859_1);
        int sep = text.indexOf("\r\n\r\n");
        if (sep < 0) {
            throw new IOException("Malformed HTTP response from Docker daemon");
        }

        String[] headLines = text.substring(0, sep).split("\r\n");
        int status = parseStatus(headLines[0]);

        boolean chunked = false;
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < headLines.length; i++) {
            String line = headLines[i].toLowerCase(Locale.ROOT);
            if (line.startsWith("transfer-encoding:") && line.contains("chunked")) {
                chunked = true;
            }
            int colon = headLines[i].indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon), headLines[i].substring(colon + 1).trim());
            }
        }

        String body = text.substring(sep + 4);
        if (chunked) {
            body = dechunk(body);
        }
        // ISO-8859-1 round-trips each char back to its exact source byte, preserving binary
        // bodies (log/exec frames); JSON callers re-decode these bytes as UTF-8 in request().
        byte[] bodyBytes = body.getBytes(StandardCharsets.ISO_8859_1);

        // 2xx is success; 304 ("not modified") is the daemon's idempotent answer for
        // start/stop when the container is already in the requested state.
        if ((status < 200 || status >= 300) && status != 304) {
            throw new ApiException(status, "Docker API returned HTTP " + status + ": "
                + new String(bodyBytes, StandardCharsets.UTF_8).trim());
        }
        return new RawResponse(status, bodyBytes, headers);
    }

    private static int parseStatus(String statusLine) throws IOException {
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2) {
            throw new IOException("Bad status line from Docker daemon: " + statusLine);
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("Bad status code from Docker daemon: " + statusLine);
        }
    }

    // Decodes HTTP/1.1 chunked transfer encoding. Throws on malformed framing
    // rather than returning a silently-truncated body (matches parseHttp's strictness).
    private static String dechunk(String body) throws IOException {
        StringBuilder out = new StringBuilder();
        int pos = 0;
        while (true) {
            int crlf = body.indexOf("\r\n", pos);
            if (crlf < 0) {
                throw new IOException("Malformed chunked body from Docker daemon: missing chunk header");
            }
            String sizeToken = body.substring(pos, crlf).trim();
            int semicolon = sizeToken.indexOf(';');           // strip any chunk extension
            if (semicolon >= 0) {
                sizeToken = sizeToken.substring(0, semicolon);
            }
            int size;
            try {
                size = Integer.parseInt(sizeToken, 16);
            } catch (NumberFormatException e) {
                throw new IOException("Malformed chunked body from Docker daemon: bad chunk size '" + sizeToken + "'");
            }
            if (size == 0) {
                break;                                          // terminating chunk
            }
            int start = crlf + 2;
            int end = start + size;
            if (end > body.length()) {
                throw new IOException("Malformed chunked body from Docker daemon: chunk exceeds available data");
            }
            out.append(body, start, end);
            pos = end + 2;                                       // skip the chunk's trailing CRLF
        }
        return out.toString();
    }

    private interface FrameConsumer {
        void accept(int streamType, byte[] buffer, int offset, int length);
    }

    // AIDEV-NOTE: Docker multiplexes stdout/stderr over one stream for non-TTY containers:
    // each frame is an 8-byte header [streamType, 0,0,0, size(4, big-endian)] then `size`
    // payload bytes (streamType 1=stdout, 2=stderr). TTY containers send a raw unframed
    // stream; unframed or truncated data is delivered as a single stdout slice.
    private static void walkFrames(byte[] data, FrameConsumer consumer) {
        int pos = 0;
        while (pos + 8 <= data.length) {
            int streamType = data[pos] & 0xFF;
            if (streamType > 2 || data[pos + 1] != 0 || data[pos + 2] != 0 || data[pos + 3] != 0) {
                consumer.accept(1, data, pos, data.length - pos);   // unframed (TTY) -> stdout
                return;
            }
            int size = ((data[pos + 4] & 0xFF) << 24) | ((data[pos + 5] & 0xFF) << 16)
                     | ((data[pos + 6] & 0xFF) << 8) | (data[pos + 7] & 0xFF);
            pos += 8;
            if (size < 0 || pos + size > data.length) {
                consumer.accept(1, data, pos, data.length - pos);   // truncated -> stdout
                return;
            }
            consumer.accept(streamType, data, pos, size);
            pos += size;
        }
    }

    // stdout and stderr concatenated in arrival order, like `docker logs`.
    private static String demuxStream(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        walkFrames(data, (type, buffer, offset, length) -> out.write(buffer, offset, length));
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Object parseJson(String json) {
        return new Dry().parse(json);
    }

    /**
     * The pull/build endpoints stream NDJSON progress and return HTTP 200 even on failure. Parse the
     * stream line-by-line and throw if any status object carries an {@code error} field. A substring
     * scan for {@code "error":} is fragile -- a benign progress line can contain that text.
     */
    private static void throwIfStreamError(String body, String what) throws IOException {
        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.charAt(0) != '{') {
                continue;
            }
            Object parsed;
            try {
                parsed = parseJson(trimmed);
            } catch (RuntimeException notJson) {
                // A line we can't parse as a single JSON object (e.g. concatenated objects). Don't
                // silently skip it -- if it structurally carries an error field, surface it rather
                // than risk a silent partial pull/build.
                if (trimmed.matches(".*\"error\"\\s*:.*")) {
                    throw new IOException(what + " failed -- " + trimmed);
                }
                continue;
            }
            if (parsed instanceof Map<?, ?> obj && obj.get("error") != null) {
                throw new IOException(what + " failed -- " + obj.get("error"));
            }
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Encode a Map/List/String/Number/Boolean/null tree as plain JSON for request bodies. */
    public static String toJson(Object value) {
        return Json.stringify(value);
    }
}
