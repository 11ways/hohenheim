package be.elevenways.hohenheim.server.docker;

import be.elevenways.hohenheim.server.util.FileTrees;
import be.elevenways.hohenheim.server.util.Http11;
import be.elevenways.hohenheim.server.util.Json;
import be.elevenways.hohenheim.server.util.Tar;
import be.elevenways.hohenheim.server.util.Watchdog;
import be.elevenways.protoblast.common.dry.Dry;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ScheduledFuture;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

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

    /**
     * AIDEV-NOTE: TEST SEAM and the only one, the {@code WorkloadNetworkPolicy
     * .overrideForTest} precedent exactly. Production code never calls
     * {@link #overrideLocalTransportForTest}; it exists because the local daemon is
     * reached through a CONSTANT path from a dozen call sites ({@code new DockerClient()}
     * inside the release engine, the artifact prune, the volume resolver), so without one
     * seam here every test of those paths must skip on a machine without a Docker socket
     * -- and a skipped test is a green test. One seam, so a hermetic harness stands in for
     * the whole local daemon rather than each caller growing an injection point.
     */
    private static volatile @Nullable Supplier<DockerTransport> localTransportOverride;

    /** The transport addressing the LOCAL daemon; the test override when one is installed. */
    public static DockerTransport localTransport() {
        Supplier<DockerTransport> installed = localTransportOverride;
        return installed != null ? installed.get()
            : new UnixSocketDockerTransport(DEFAULT_SOCKET);
    }

    /** @param supplier the transport {@link #localTransport()} answers with, null to restore */
    public static void overrideLocalTransportForTest(
            @Nullable Supplier<DockerTransport> supplier) {
        localTransportOverride = supplier;
    }

    /** Talk to the local daemon over its default unix socket. */
    public DockerClient() {
        this(localTransport(), DEFAULT_TIMEOUT_MS);
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
        // STREAMED from the file: an image tar is routinely larger than the heap should
        // ever hold (this lane used to Files.readAllBytes it).
        String body = DockerWire.upload(streamTransport(), timeoutMillis, LONG_OP_TIMEOUT_MS,
            "POST", "/images/load?quiet=false", "application/x-tar",
            out -> Files.copy(imageTar, out), MAX_PROGRESS_BYTES);
        throwIfStreamError(body, "Docker image load from " + imageTar.getFileName());
        return body;
    }

    /** Cap on a progress stream answered to an upload (image load); it is text, not payload. */
    private static final long MAX_PROGRESS_BYTES = 64L * 1024 * 1024;

    /** Export an immutable image, with the existing archive lane's wire cap and cleanup. */
    public long saveImage(String imageId, Path outFile, long maxBytes) throws IOException {
        return streamResponseToFile(DockerPaths.image(imageId) + "/get", outFile, maxBytes);
    }

    /**
     * @return the full {@code /images/{name}/json} inspection (Id, RepoTags, Config, ...)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> inspectImage(String name) throws IOException {
        return (Map<String, Object>) parseJson(get(DockerPaths.image(name) + "/json").body());
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
            get(DockerPaths.container(id) + "/json?size=1").body());
        Object size = inspect.get("SizeRw");
        return size instanceof Number number ? number.longValue() : -1;
    }

    /**
     * Remove an image by name/tag or id. {@code name} may contain {@code /} and {@code :}
     * (kept as path separators/literals per the Engine API; every other byte is encoded).
     */
    public void removeImage(String name, boolean force) throws IOException {
        request("DELETE", DockerPaths.image(name) + (force ? "?force=true" : ""), null, null,
            timeoutMillis);
    }

    /** Add {@code repo:tag} as an additional reference to an existing image. */
    public void tagImage(String name, String repo, String tag) throws IOException {
        request("POST", DockerPaths.image(name) + "/tag?repo=" + enc(repo) + "&tag=" + enc(tag),
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
        return (Map<String, Object>) parseJson(get(DockerPaths.volume(name)).body());
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
        request("DELETE", DockerPaths.volume(name) + (force ? "?force=true" : ""), null, null,
            timeoutMillis);
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
    public String createNetwork(String name, Map<String, String> labels,
                                String subnet, String gateway, boolean enableIpv6)
            throws IOException {
        return createNetwork(name, labels, subnet, gateway, enableIpv6, false);
    }

    /**
     * {@link #createNetwork(String, Map, String, String, boolean)} with Docker's
     * {@code Internal} flag: an internal network has no gateway to the outside and no
     * masquerade, and -- the part that matters for a container on several networks --
     * Docker never picks it as the container's DEFAULT ROUTE.
     */
    @SuppressWarnings("unchecked")
    public String createNetwork(String name, Map<String, String> labels,
                                String subnet, String gateway, boolean enableIpv6,
                                boolean internal)
            throws IOException {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("Name", name);
        spec.put("Driver", "bridge");
        spec.put("EnableIPv6", enableIpv6);
        if (internal) {
            spec.put("Internal", true);
        }
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
        return (Map<String, Object>) parseJson(get(DockerPaths.network(idOrName)).body());
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
        request("DELETE", DockerPaths.network(idOrName), null);
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
        request("POST", DockerPaths.network(network) + "/connect", toJson(spec));
    }

    /** Disconnect a container from a network; {@code force} disconnects even a running one. */
    public void disconnectContainerFromNetwork(String network, String containerId,
                                               boolean force) throws IOException {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("Container", containerId);
        if (force) {
            spec.put("Force", true);
        }
        request("POST", DockerPaths.network(network) + "/disconnect", toJson(spec));
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
     *                                  (see {@link ContainerHardening#PERMITTED_KEYS})
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
        request("POST", DockerPaths.container(id) + "/start", null);
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
        request("POST", DockerPaths.container(id) + "/stop?t=" + graceSeconds, null);
    }

    /** Restart a container, giving it {@code graceSeconds} to stop before SIGKILL. */
    public void restartContainer(String id, int graceSeconds) throws IOException {
        request("POST", DockerPaths.container(id) + "/restart?t=" + graceSeconds, null);
    }

    /** Remove a container; {@code force} kills it first if running. */
    public void removeContainer(String id, boolean force) throws IOException {
        request("DELETE", DockerPaths.container(id) + (force ? "?force=true" : ""), null);
    }

    /**
     * @return the container's full {@code /json} inspection (State, NetworkSettings, ...)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> inspectContainer(String id) throws IOException {
        return (Map<String, Object>) parseJson(get(DockerPaths.container(id) + "/json").body());
    }

    /**
     * Fetch a container's logs as a single text snapshot (stdout and stderr interleaved in
     * arrival order, like {@code docker logs}). Demultiplexes Docker's framed stream for
     * non-TTY containers -- all hohenheim-managed containers run without a TTY.
     *
     * @param tail max number of trailing lines, or {@code <= 0} for the full log
     */
    public String containerLogs(String id, boolean stdout, boolean stderr, int tail) throws IOException {
        return containerLogs(id, stdout, stderr, tail, false);
    }

    /**
     * {@link #containerLogs(String, boolean, boolean, int)} with the container's TTY-ness
     * DECLARED: the daemon keeps a TTY container's log raw (no 8-byte frames), so
     * demultiplexing it would eat the first bytes of every chunk as a frame header.
     */
    public String containerLogs(String id, boolean stdout, boolean stderr, int tail,
                                boolean tty) throws IOException {
        String path = DockerPaths.container(id) + "/logs?stdout=" + (stdout ? 1 : 0)
            + "&stderr=" + (stderr ? 1 : 0);
        if (tail > 0) {
            path += "&tail=" + tail;
        }
        byte[] body = exchange("GET", path, null, null, timeoutMillis).body();
        return tty ? new String(body, StandardCharsets.UTF_8) : demuxStream(body);
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
    public ExecResult exec(String containerId, List<String> command, List<String> env) throws IOException {
        return exec(containerId, command, env, null, null);
    }

    /**
     * {@link #exec(String, List, List)} run as an explicit user and/or in a directory.
     *
     * @param user    the numeric uid (or user name) the command runs as, or null for the
     *                container's own configured user
     * @param workdir the working directory, or null for the image's
     */
    @SuppressWarnings("unchecked")
    public ExecResult exec(String containerId, List<String> command, List<String> env,
                           String user, String workdir) throws IOException {
        Map<String, Object> createSpec = new LinkedHashMap<>();
        createSpec.put("AttachStdout", true);
        createSpec.put("AttachStderr", true);
        createSpec.put("Cmd", command);
        if (!env.isEmpty()) {
            createSpec.put("Env", env);
        }
        if (user != null && !user.isBlank()) {
            createSpec.put("User", user);
        }
        if (workdir != null && !workdir.isBlank()) {
            createSpec.put("WorkingDir", workdir);
        }
        Map<String, Object> created = (Map<String, Object>) parseJson(request("POST",
            DockerPaths.container(containerId) + "/exec", toJson(createSpec)).body());
        String execId = (String) created.get("Id");

        // Detach=false streams the (multiplexed, non-TTY) output until the process exits and
        // the daemon closes the connection; LONG_OP_TIMEOUT covers slow ops like a dump.
        RawResponse stream = exchange("POST", DockerPaths.exec(execId) + "/start",
            toJson(Map.of("Detach", false, "Tty", false)).getBytes(StandardCharsets.UTF_8),
            "application/json", LONG_OP_TIMEOUT_MS);

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        walkFrames(stream.body(), (type, buffer, offset, length) ->
            (type == 2 ? stderr : stdout).write(buffer, offset, length));

        Map<String, Object> info = (Map<String, Object>) parseJson(
            get(DockerPaths.exec(execId) + "/json").body());
        int exitCode = info.get("ExitCode") instanceof Number n ? n.intValue() : -1;
        return new ExecResult(exitCode,
            new String(stdout.toByteArray(), StandardCharsets.UTF_8),
            new String(stderr.toByteArray(), StandardCharsets.UTF_8));
    }

    /** Result of a {@link #execStreamed} run: stdout went to the caller's stream, not the heap. */
    public record ExecStreamResult(int exitCode, String stderr, long stdoutBytes) {}

    /**
     * {@link #exec(String, List, List)} with stdout STREAMED to {@code out} instead of held
     * in memory -- the database-dump lane, where stdout is the whole dump. Stderr is kept
     * (bounded) for the error report; the stdout byte cap is enforced on the wire.
     *
     * @param maxStdoutBytes breaching it throws {@link Http11.BodyCapExceededException};
     *                       {@code out} then holds a partial write the CALLER must discard
     */
    @SuppressWarnings("unchecked")
    public ExecStreamResult execStreamed(String containerId, List<String> command,
                                         List<String> env, OutputStream out,
                                         long maxStdoutBytes) throws IOException {
        Map<String, Object> createSpec = new LinkedHashMap<>();
        createSpec.put("AttachStdout", true);
        createSpec.put("AttachStderr", true);
        createSpec.put("Cmd", command);
        if (!env.isEmpty()) {
            createSpec.put("Env", env);
        }
        Map<String, Object> created = (Map<String, Object>) parseJson(request("POST",
            DockerPaths.container(containerId) + "/exec", toJson(createSpec)).body());
        String execId = (String) created.get("Id");

        byte[] startBody = toJson(Map.of("Detach", false, "Tty", false))
            .getBytes(StandardCharsets.UTF_8);
        byte[] request = buildRequest("POST", DockerPaths.exec(execId) + "/start",
            startBody, "application/json", null);
        DockerStreamConnection connection = streamTransport().openStream(request, timeoutMillis);
        ScheduledFuture<?> watchdog = Watchdog.schedule(connection::close, LONG_OP_TIMEOUT_MS);
        DockerWire.FrameDemuxStream demux = new DockerWire.FrameDemuxStream(out, maxStdoutBytes);
        try (InputStream in = new DockerWire.ConnectionInputStream(connection)) {
            Http11.Head head = Http11.readHead(in, DockerWire.PEER);
            if (head.status() < 200 || head.status() >= 300) {
                throw DockerWire.apiError(head.status(), DockerWire.errorBody(in, head));
            }
            Http11.copyBody(in, head, demux, Long.MAX_VALUE, DockerWire.PEER);
            demux.finish();
        } catch (IOException e) {
            if (watchdog.isDone()) {
                throw new IOException("Docker exec stream timed out after "
                    + LONG_OP_TIMEOUT_MS + "ms", e);
            }
            throw e;
        } finally {
            watchdog.cancel(false);
            connection.close();
        }

        Map<String, Object> info = (Map<String, Object>) parseJson(
            get(DockerPaths.exec(execId) + "/json").body());
        int exitCode = info.get("ExitCode") instanceof Number n ? n.intValue() : -1;
        return new ExecStreamResult(exitCode, demux.stderrText(), demux.stdoutBytes());
    }

    /**
     * {@link #exec(String, List, List)} with the process's STDIN fed from {@code in},
     * STREAMED -- the database-restore lane, where stdin is the whole dump and the heap
     * must never hold it (the 5.3 GB mongo archive that OOM-killed a move on 2026-09-02
     * went through {@code Files.readAllBytes} here before).
     *
     * AIDEV-NOTE: the exec pipe has NO half-close, so the command must consume EXACTLY
     * the bytes it is handed and exit on its own ({@code ManagedDatabase.Engine
     * .restoreFromStdinCommand} spells that as {@code head -c <size> | client}); a
     * command that keeps reading after the input ends hangs until the long-op watchdog
     * cuts the connection. Stdin is fed from a helper thread while this thread drains
     * the output frames, because a client that fills its stderr while we are still
     * writing would otherwise deadlock on the daemon's socket buffer.
     *
     * @return the exit code, with stdout and stderr each kept to 64 KiB of text
     */
    @SuppressWarnings("unchecked")
    public ExecResult execWithStdin(String containerId, List<String> command, List<String> env,
                                    InputStream in) throws IOException {
        Map<String, Object> createSpec = new LinkedHashMap<>();
        createSpec.put("AttachStdin", true);
        createSpec.put("AttachStdout", true);
        createSpec.put("AttachStderr", true);
        createSpec.put("Cmd", command);
        if (!env.isEmpty()) {
            createSpec.put("Env", env);
        }
        Map<String, Object> created = (Map<String, Object>) parseJson(request("POST",
            DockerPaths.container(containerId) + "/exec", toJson(createSpec)).body());
        String execId = (String) created.get("Id");

        byte[] startBody = toJson(Map.of("Detach", false, "Tty", false))
            .getBytes(StandardCharsets.UTF_8);
        byte[] request = buildRequest("POST", DockerPaths.exec(execId) + "/start",
            startBody, "application/json", null);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        IOException[] feedFailure = new IOException[1];
        try (ContainerStream stream = ContainerStream.open(streamTransport(), request,
                timeoutMillis, true, false)) {
            ScheduledFuture<?> watchdog = Watchdog.schedule(stream::close, LONG_OP_TIMEOUT_MS);
            Thread feeder = new Thread(() -> {
                byte[] chunk = new byte[64 * 1024];
                try {
                    int read;
                    while ((read = in.read(chunk)) != -1) {
                        if (read == chunk.length) {
                            stream.writeStdin(chunk);
                        } else if (read > 0) {
                            stream.writeStdin(java.util.Arrays.copyOf(chunk, read));
                        }
                    }
                } catch (IOException e) {
                    feedFailure[0] = e;
                }
            }, "docker-exec-stdin");
            feeder.setDaemon(true);
            feeder.start();
            try {
                ContainerStream.Chunk chunk;
                while ((chunk = stream.next()) != null) {
                    appendBounded(chunk.stderr() ? stderr : stdout, chunk.data(), 64 * 1024);
                }
                if (watchdog.isDone()) {
                    throw new IOException("Docker exec stream timed out after "
                        + LONG_OP_TIMEOUT_MS + "ms");
                }
            } finally {
                watchdog.cancel(false);
                stream.close();
                try {
                    feeder.join(10_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        Map<String, Object> info = (Map<String, Object>) parseJson(
            get(DockerPaths.exec(execId) + "/json").body());
        int exitCode = info.get("ExitCode") instanceof Number n ? n.intValue() : -1;
        if (exitCode == 0 && feedFailure[0] != null) {
            // The process claims success while the input never fully arrived: a truncated
            // restore that exited 0 is the one shape this lane must never report as done.
            throw new IOException("Docker exec stdin feed failed before the input ended: "
                + feedFailure[0].getMessage(), feedFailure[0]);
        }
        return new ExecResult(exitCode,
            new String(stdout.toByteArray(), StandardCharsets.UTF_8),
            new String(stderr.toByteArray(), StandardCharsets.UTF_8));
    }

    private static void appendBounded(ByteArrayOutputStream target, byte[] data, int cap) {
        int room = cap - target.size();
        if (room > 0) {
            target.write(data, 0, Math.min(room, data.length));
        }
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
        return attach(id, false);
    }

    /**
     * {@link #attach(String)} with the container's TTY-ness DECLARED by the caller (read
     * off the inspect payload's {@code Config.Tty}): a TTY container's attach stream is
     * raw, never frame-multiplexed, and declaring it removes the first-byte guess that
     * {@link ContainerStream}'s docblock explains for a stream whose bytes the workload
     * chooses.
     */
    public ContainerStream attach(String id, boolean tty) throws IOException {
        String path = DockerPaths.container(id) + "/attach?stream=1&stdout=1&stderr=1&stdin=1";
        byte[] request = buildStreamRequest("POST", path);
        return ContainerStream.open(streamTransport(), request, timeoutMillis, true, tty);
    }

    /**
     * Tell a TTY container's pseudo-terminal its new geometry (the daemon delivers
     * SIGWINCH to the primary process). Refused by the daemon for a container that is
     * not running or was created without {@code Tty}.
     */
    public void resizeTty(String id, int cols, int rows) throws IOException {
        request("POST", DockerPaths.container(id) + "/resize?h=" + rows + "&w=" + cols, null);
    }

    /**
     * Follow a container's logs from {@code tail} lines back: history plus live frames,
     * chunked by the daemon, ending when the container stops (or the consumer closes).
     *
     * @param tail max trailing history lines, or {@code <= 0} for the full log
     */
    public ContainerStream followLogs(String id, int tail) throws IOException {
        String path = DockerPaths.container(id) + "/logs?follow=1&stdout=1&stderr=1"
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
            + " has no streaming lane; console/follow/archive endpoints are unavailable on it");
    }

    /**
     * A streaming request head: no {@code Connection: close} (the stream's lifetime is
     * the consumer's, and attach needs the write half alive), explicit
     * {@code Upgrade: tcp} so the daemon treats attach as the bidirectional stream it is.
     */
    private static byte[] buildStreamRequest(String method, String path) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Connection", "Upgrade");
        headers.put("Upgrade", "tcp");
        return Http11.head(method, path, "docker", headers);
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

    // -----------------------------------------------------------------------
    // Archive lane (tars built and read IN JAVA; see util.Tar for why)
    // -----------------------------------------------------------------------

    /** Writes the entries of one pushed archive; the lane adds the end marker. */
    private interface ArchiveContent {
        void writeTo(Tar.@NonNull Writer writer) throws IOException;
    }

    /**
     * Tar {@code hostDir} and extract it into {@code targetDir} inside the container
     * ({@code PUT /containers/{id}/archive}); {@code targetDir} must already exist. Used to
     * push a file (e.g. a dump to restore) into a running container.
     *
     * AIDEV-NOTE: the archive keeps the {@code ./} root entry {@code tar -C dir -cf - .}
     * always produced, and the daemon applies its mode and owner to {@code targetDir}. That
     * is the shape this lane has always had; a caller that must not re-own an existing
     * directory uses {@link #putArchiveFiles} instead.
     */
    public void putArchiveFromDirectory(String containerId, String targetDir, Path hostDir) throws IOException {
        putArchive(containerId, targetDir, writer -> {
            writer.addDirectoryHeader(hostDir, "./");
            for (Path child : childrenOf(hostDir)) {
                writer.add(child, "./" + child.getFileName());
            }
        });
    }

    /**
     * Like {@link #putArchiveFromDirectory}, but the tar names ONLY the given files: no
     * directory entries, so extraction never re-owns or re-modes an EXISTING container
     * directory (chowning a non-root image's writable volume root to root:root is how a
     * staged config file used to brick the workload). Missing parents are still created
     * by the daemon (root-owned 0755).
     *
     * @param relativeFiles names relative to {@code hostDir}; each is DATA in a tar header,
     *                      never an argument, and one that is absolute or climbs out of
     *                      {@code hostDir} is refused
     */
    public void putArchiveFiles(String containerId, String targetDir, Path hostDir,
                                List<String> relativeFiles) throws IOException {
        List<String> names = new ArrayList<>();
        for (String relative : relativeFiles) {
            names.add(entryNameOf(relative));
        }
        putArchive(containerId, targetDir, writer -> {
            for (String name : names) {
                writer.add(hostDir.resolve(name), name);
            }
        });
    }

    /**
     * Like {@link #putArchiveFromDirectory}/{@link #putArchiveFiles}, but for a
     * {@code targetDir} that need NOT exist in the image: the tar is extracted at
     * {@code /} with every entry prefixed by the target path, so the daemon creates the
     * directory itself. Symlink targets are deliberately NOT rewritten (a relative link
     * inside the pushed tree must keep resolving inside it).
     *
     * @param files the entries to include relative to {@code hostDir}, or null for the
     *              whole directory
     */
    public void putArchiveCreating(String containerId, String targetDir, Path hostDir,
                                   List<String> files) throws IOException {
        String prefix = entryPrefixOf(targetDir);
        List<String> names = new ArrayList<>();
        if (files != null) {
            for (String relative : files) {
                names.add(entryNameOf(relative));
            }
        }
        putArchive(containerId, "/", writer -> {
            if (files == null) {
                writer.addDirectoryHeader(hostDir, prefix.isEmpty() ? "./" : prefix + "/");
                for (Path child : childrenOf(hostDir)) {
                    writer.add(child, (prefix.isEmpty() ? "./" : prefix + "/") + child.getFileName());
                }
                return;
            }
            for (String name : names) {
                writer.add(hostDir.resolve(name), prefix.isEmpty() ? name : prefix + "/" + name);
            }
        });
    }

    /** One archive PUT whose tar is produced straight onto the wire. */
    private void putArchive(String containerId, String targetDir, ArchiveContent content)
            throws IOException {
        DockerWire.upload(streamTransport(), timeoutMillis, LONG_OP_TIMEOUT_MS, "PUT",
            archivePath(containerId, targetDir), "application/x-tar", out -> {
                Tar.Writer writer = new Tar.Writer(out);
                content.writeTo(writer);
                writer.finish();
            }, DockerWire.MAX_ERROR_BODY);
    }

    private static String archivePath(String containerId, String path) {
        return DockerPaths.container(containerId) + "/archive?path=" + enc(path);
    }

    /** A validated relative entry name: no absolute path, no {@code .}/{@code ..}/empty segment. */
    private static String entryNameOf(String relative) throws IOException {
        if (relative.isEmpty() || relative.startsWith("/")) {
            throw new IOException("Refusing archive entry '" + relative + "': not a relative path");
        }
        for (String segment : relative.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IOException("Refusing archive entry '" + relative
                    + "': it is not a plain relative path");
            }
        }
        return relative;
    }

    /** {@code /a/b/} -> {@code a/b}; the root itself is the empty prefix. */
    private static String entryPrefixOf(String targetDir) throws IOException {
        String trimmed = targetDir;
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? "" : entryNameOf(trimmed);
    }

    private static List<Path> childrenOf(Path directory) throws IOException {
        try (Stream<Path> listing = Files.list(directory)) {
            return listing.sorted().toList();
        }
    }

    /**
     * Download a directory from a container ({@code GET /containers/{id}/archive}) as a raw
     * tar, STREAMED to {@code outFile} (never buffered through the heap). Works on stopped
     * containers -- the volume-snapshot mechanism's read primitive. The tar's entries are
     * rooted at the directory's basename (Docker's envelope), which {@link #putArchiveTar}
     * relies on for the restore side.
     *
     * @param maxBytes cap enforced DURING the read; breaching it throws
     *                 {@link Http11.BodyCapExceededException} and deletes the partial file
     * @return the number of bytes written to {@code outFile}
     */
    public long getArchiveTar(String containerId, String path, Path outFile, long maxBytes)
            throws IOException {
        return streamResponseToFile(archivePath(containerId, path), outFile, maxBytes);
    }

    /**
     * Upload a raw tar into a container ({@code PUT /containers/{id}/archive}), extracting
     * it at {@code targetDir} (which must exist). Works on stopped containers -- the
     * volume-snapshot mechanism's write primitive. Extraction MERGES into the target; a
     * faithful restore must therefore write into freshly recreated volumes, never over
     * live contents. The tar STREAMS from disk.
     */
    public void putArchiveTar(String containerId, String targetDir, Path tarFile) throws IOException {
        DockerWire.upload(streamTransport(), timeoutMillis, LONG_OP_TIMEOUT_MS, "PUT",
            archivePath(containerId, targetDir), "application/x-tar",
            out -> Files.copy(tarFile, out), DockerWire.MAX_ERROR_BODY);
    }

    /**
     * Download a single file from a container ({@code GET /containers/{id}/archive}) and return
     * its raw bytes, unwrapping Docker's tar envelope. Binary-safe. {@code path} must point at
     * a single REGULAR file. Small-payload convenience over {@link #getArchiveFileTo};
     * anything dump-sized goes to a file, never through here.
     *
     * @param maxBytes cap on the FILE, enforced during the read (the transfer is allowed the
     *                 tar envelope on top, {@link Tar#transferCapForSingleFile}): over-size
     *                 throws and yields nothing, so a truncated read can never pass for the file. The
     *                 parameter is required deliberately -- an unbounded archive read against
     *                 a data-dependent endpoint is an OOM waiting for the right file.
     * @throws IOException when the archive's one entry is a symlink, hard link, directory
     *         or device: see {@link Tar} for why that is a refusal and never a read
     */
    public byte[] getArchiveFile(String containerId, String path, long maxBytes) throws IOException {
        Path tmpDir = Files.createTempDirectory("hohenheim-getarchive");
        Path tarFile = tmpDir.resolve("archive.tar");
        try {
            streamResponseToFile(archivePath(containerId, path), tarFile,
                Tar.transferCapForSingleFile(maxBytes));
            try (InputStream in = Files.newInputStream(tarFile)) {
                return Tar.readSingleFile(in, maxBytes);
            }
        } finally {
            FileTrees.deleteQuietly(tmpDir);
        }
    }

    /**
     * {@link #getArchiveFile} STREAMED to {@code outFile}: the tar body goes socket-to-disk
     * and the single file is read out of it on disk, so controller heap never holds the
     * payload -- the lane database dumps ride ({@code ManagedDatabase.backupToFile}).
     *
     * AIDEV-NOTE: the tar is PARSED here, never unpacked with a system tool: its one entry
     * must be a REGULAR file. A container that planted a symlink where the dump or build
     * output was expected used to make this lane read whatever host file the link named
     * (the controller's database, its TLS keys) into {@code outFile}.
     *
     * @param maxBytes cap on the FILE, enforced DURING the read (the transfer is allowed the
     *                 tar envelope on top); breaching it throws
     *                 {@link Http11.BodyCapExceededException} and leaves no partial
     *                 {@code outFile}
     * @return the extracted file's size in bytes
     */
    public long getArchiveFileTo(String containerId, String path, Path outFile, long maxBytes)
            throws IOException {
        Path tmpDir = Files.createTempDirectory("hohenheim-getarchive");
        Path tarFile = tmpDir.resolve("archive.tar");
        try {
            streamResponseToFile(archivePath(containerId, path), tarFile,
                Tar.transferCapForSingleFile(maxBytes));
            try (InputStream in = Files.newInputStream(tarFile)) {
                return Tar.readSingleFile(in, outFile, maxBytes);
            }
        } finally {
            FileTrees.deleteQuietly(tmpDir);
        }
    }

    /**
     * Stream one GET response's body straight to {@code outFile} over the transport's
     * streaming lane, with the cap enforced on the wire; see
     * {@link DockerWire#downloadToFile}. The whole exchange rides one
     * {@link #LONG_OP_TIMEOUT_MS} watchdog.
     */
    private long streamResponseToFile(String path, Path outFile, long maxBytes)
            throws IOException {
        return DockerWire.downloadToFile(streamTransport(), timeoutMillis, LONG_OP_TIMEOUT_MS,
            path, outFile, maxBytes);
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
            // A HEAD answer's Content-Length describes the GET it mirrors, never a body.
            response = parseHttpRaw(transport.roundTrip(buildRequest("HEAD",
                archivePath(containerId, path), null, null, null), timeoutMillis), true);
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
        byte[] request = buildStreamRequest("GET", DockerPaths.container(id) + "/stats?stream=1");
        return ContainerStream.open(streamTransport(), request, timeoutMillis, false);
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

    // The wire framing lives in the shared Http11 codec; only the Docker-specific
    // status POLICY (304-is-success, ApiException) stays here.
    private static byte[] buildRequest(String method, String path, byte[] body, String contentType,
                                       Map<String, String> extraHeaders) {
        return Http11.request(method, path, "docker", body, contentType, extraHeaders);
    }

    // Framing is the shared Http11 codec; this applies Docker's status policy: 2xx is
    // success, and 304 ("not modified") is the daemon's idempotent answer for
    // start/stop when the container is already in the requested state.
    private static RawResponse parseHttpRaw(byte[] raw) throws IOException {
        return parseHttpRaw(raw, false);
    }

    /** @param bodilessRequest the request was a HEAD, whose Content-Length describes no body */
    private static RawResponse parseHttpRaw(byte[] raw, boolean bodilessRequest) throws IOException {
        Http11.Raw parsed = Http11.parse(raw, DockerWire.PEER, bodilessRequest);
        int status = parsed.status();
        if ((status < 200 || status >= 300) && status != 304) {
            throw new ApiException(status, "Docker API returned HTTP " + status + ": "
                + new String(parsed.body(), StandardCharsets.UTF_8).trim());
        }
        return new RawResponse(status, parsed.body(), parsed.headers());
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
        return DockerPaths.query(value);
    }

    /** Encode a Map/List/String/Number/Boolean/null tree as plain JSON for request bodies. */
    public static String toJson(Object value) {
        return Json.stringify(value);
    }
}
