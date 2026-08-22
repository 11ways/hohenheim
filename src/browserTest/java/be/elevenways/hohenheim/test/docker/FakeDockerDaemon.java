package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.instance.WorkloadIsolation;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.DockerTransport;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ReleaseKind;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.server.runtime.PortPublication;
import be.elevenways.hohenheim.server.runtime.WorkloadLiveness;
import be.elevenways.hohenheim.server.util.Json;
import be.elevenways.protoblast.common.dry.Dry;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.Icon;
import com.sun.net.httpserver.HttpServer;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The hermetic Docker daemon of the site tier: ONE in-memory container/volume/image store
 * with TWO faces onto the same state -- an {@link InstanceRuntime} the site kind deploys
 * through, and a {@link DockerTransport} every {@code new DockerClient()} in the release
 * engine speaks raw HTTP/1.1 to. The two faces must agree, because the product itself
 * reads BOTH: a release deploys through the runtime and the fast-reuse lane
 * ({@code SiteInstances.reusableStatus}) inspects the daemon directly.
 *
 * AIDEV-NOTE: this is the FakeIncusTransport doctrine applied to the docker lane, and it
 * generalizes FakeNativeDaemons rather than duplicating it: FakeNativeDaemons fakes a
 * whole KIND of its own (nothing production-shaped points at it), while a site release is
 * always a {@code hohenheim:release}, so the fake has to stand in for that kind's
 * runtime instead -- {@link #install()} re-registers the real kind's identifier with a
 * handler that differs in {@code runtimeFor} alone. An unhandled HTTP path throws loudly
 * rather than answering success: a gap here must fail the test, never hide in a default.
 *
 * EVERY FAKE MUST BE ABLE TO SAY NO. The failures this can produce, each one a real
 * product path: an unhealthy candidate ({@link #answerWithForNextWorkload}), a container
 * the daemon reports RUNNING while its workload is dead ({@link #reportWorkloadDead},
 * which is the daemon's {@code OOMKilled} the reuse lane refuses to reuse), a create
 * that refuses a foreign workload under our handle, and a stop the daemon will not
 * settle ({@link #refuseStopOf}, the drain's stop-failure branch).
 *
 * AIDEV-NOTE: the stop-failure knob was DECLINED when this fake was written -- "a knob no
 * test uses is scaffolding" -- and it arrives now WITH the journey that consumes it
 * ({@code aSupersededReleaseWhoseStopFailsNeverTakesTheNewReleaseDownWithIt}). The rule
 * did not change: a fake capability and its first consumer land together or not at all.
 *
 * What it deliberately does NOT model, so nothing reads its green as total coverage: a
 * running container's real port binding is a real {@link HttpServer} on loopback (so the
 * engine's health probe is a genuine HTTP round trip), but there is no image, no cgroup,
 * no network namespace and no nftables. {@code DockerInstanceRuntime}, WorkloadNetworks
 * and the kernel policy are NOT exercised here -- SiteReleaseLiveTest remains their only
 * proof, along with "zero dropped requests through a real proxy".
 */
final class FakeDockerDaemon implements DockerTransport {

    /** One fake container; its published port is a REAL loopback listener. */
    static final class Workload {
        final @NonNull String name;
        final @NonNull Map<String, String> labels;
        final @NonNull String image;
        final int containerPort;
        boolean running;
        int hostPort;
        @Nullable HttpServer responder;

        Workload(@NonNull String name, @NonNull Map<String, String> labels,
                 @NonNull String image, int containerPort) {
            this.name = name;
            this.labels = labels;
            this.image = image;
            this.containerPort = containerPort;
        }
    }

    private final Map<String, Workload> workloads = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> volumes = new LinkedHashMap<>();
    private final Set<String> images = new LinkedHashSet<>();

    /** Every daemon-facing act in order; the assertable half of "what actually happened". */
    private final List<String> calls = new ArrayList<>();

    /** handle -> the HTTP status its loopback responder answers (200 unless scripted). */
    private final Map<String, Integer> health = new LinkedHashMap<>();

    /** handles whose container reports RUNNING while the workload inside it is dead. */
    private final Set<String> workloadDead = new LinkedHashSet<>();

    /** Handles whose stop the daemon refuses; see {@link #refuseStopOf}. */
    private final Set<String> stopFails = new LinkedHashSet<>();

    /** Consumed by the next create: the status that workload's responder will answer. */
    private @Nullable Integer nextHealth;

    /** Consumed by the next start: a writer that appears while the daemon is working. */
    private @Nullable Runnable duringNextStart;

    // -- scripting ------------------------------------------------------------

    /**
     * The status the NEXT workload created answers with. A release engine mints its own
     * candidate row, so the handle to script does not exist yet when the test decides the
     * candidate must be unhealthy -- this is the only honest way to say it in advance.
     */
    void answerWithForNextWorkload(int httpStatus) {
        this.nextHealth = httpStatus;
    }

    /**
     * Run {@code work} INSIDE the next start, on the caller's thread and inside its
     * datasource scope: the only honest way to place a rival writer in the middle of an
     * operation, where a real one lands while the daemon spends its minutes.
     */
    void duringNextStart(@NonNull Runnable work) {
        this.duringNextStart = work;
    }

    /** Report this workload as RUNNING with a dead process inside (the OOM-kill shape). */
    void reportWorkloadDead(@NonNull String handle) {
        this.workloadDead.add(handle);
    }

    /**
     * Refuse every stop of this handle, on BOTH faces: the workload keeps running and the
     * caller gets the daemon's refusal. The real shape is a container the engine cannot
     * settle (a wedged runtime, a busy device); the release that just took traffic must
     * survive it.
     */
    void refuseStopOf(@NonNull String handle) {
        this.stopFails.add(handle);
    }

    /** Let this handle be stopped again; the refusal is per-daemon and must not leak. */
    void allowStopOf(@NonNull String handle) {
        this.stopFails.remove(handle);
    }

    /** Every recorded act against ONE handle, in order: the workload's whole life. */
    @NonNull List<String> callsFor(@NonNull String handle) {
        List<String> mine = new ArrayList<>();
        for (String call : List.copyOf(this.calls)) {
            if (call.endsWith(":" + handle)) {
                mine.add(call);
            }
        }
        return mine;
    }

    /** How many times one exact recorded act happened. */
    int callCount(@NonNull String call) {
        int count = 0;
        for (String recorded : List.copyOf(this.calls)) {
            if (recorded.equals(call)) {
                count++;
            }
        }
        return count;
    }

    /** Whether the daemon still holds a container under this handle. */
    boolean exists(@NonNull String handle) {
        return this.workloads.containsKey(handle);
    }

    /** Whether the container under this handle is running. */
    boolean isRunning(@NonNull String handle) {
        Workload workload = this.workloads.get(handle);
        return workload != null && workload.running;
    }

    /** Release every real loopback listener this daemon opened. */
    void close() {
        for (Workload workload : this.workloads.values()) {
            unbind(workload);
        }
        this.workloads.clear();
    }

    // -- the runtime face -----------------------------------------------------

    /** The runtime the site kind deploys through while {@link #install()} is in force. */
    @NonNull InstanceRuntime runtime() {
        return new FakeSiteRuntime();
    }

    private final class FakeSiteRuntime implements InstanceRuntime {

        @Override
        public @NonNull String create(@NonNull InstanceSpec spec) throws IOException {
            OwnerLabels.Owner owner = OwnerLabels.parse(spec.ownerLabels());
            if (owner == null) {
                throw new IOException("spec without owner labels");
            }
            Workload existing = FakeDockerDaemon.this.workloads.get(spec.handle());
            if (existing != null) {
                OwnerLabels.Owner held = OwnerLabels.parse(existing.labels);
                if (held == null || !held.model().equals(owner.model())
                        || !held.id().equals(owner.id())) {
                    throw new IOException("REFUSED: foreign workload under " + spec.handle());
                }
                unbind(existing);
                FakeDockerDaemon.this.workloads.remove(spec.handle());
            }
            int containerPort = spec.publications().isEmpty()
                ? 0 : spec.publications().get(0).containerPort();
            FakeDockerDaemon.this.workloads.put(spec.handle(),
                new Workload(spec.handle(), Map.copyOf(spec.ownerLabels()), spec.image(),
                    containerPort));
            // A recreate is what CLEARS the daemon's OOMKilled flag, so a fake that kept
            // it would make the redeploy of a dead workload look like it changed nothing.
            FakeDockerDaemon.this.workloadDead.remove(spec.handle());
            Integer scripted = FakeDockerDaemon.this.nextHealth;
            if (scripted != null) {
                FakeDockerDaemon.this.health.put(spec.handle(), scripted);
                FakeDockerDaemon.this.nextHealth = null;
            }
            FakeDockerDaemon.this.calls.add("create:" + spec.handle());
            return spec.handle();
        }

        @Override
        public void start(@NonNull String handle) throws IOException {
            Workload workload = require(handle);
            if (workload.containerPort > 0 && workload.responder == null) {
                bind(workload);
            }
            workload.running = true;
            FakeDockerDaemon.this.calls.add("start:" + handle);
            Runnable during = FakeDockerDaemon.this.duringNextStart;
            FakeDockerDaemon.this.duringNextStart = null;
            if (during != null) {
                during.run();
            }
        }

        @Override
        public void stop(@NonNull String handle, int graceSeconds) throws IOException {
            Workload workload = require(handle);
            if (FakeDockerDaemon.this.stopFails.contains(handle)) {
                FakeDockerDaemon.this.calls.add("stop-refused:" + handle);
                throw new IOException("REFUSED: the daemon will not stop " + handle);
            }
            unbind(workload);
            workload.running = false;
            FakeDockerDaemon.this.calls.add("stop:" + handle);
        }

        @Override
        public void destroy(@NonNull String handle) {
            Workload workload = FakeDockerDaemon.this.workloads.remove(handle);
            if (workload != null) {
                unbind(workload);
            }
            FakeDockerDaemon.this.calls.add("destroy:" + handle);
        }

        @Override
        public @NonNull InstanceStatus status(@NonNull String handle) {
            Workload workload = FakeDockerDaemon.this.workloads.get(handle);
            if (workload == null) {
                return new InstanceStatus(ContainerState.ABSENT, null);
            }
            if (!workload.running) {
                return new InstanceStatus(ContainerState.STOPPED, null);
            }
            WorkloadLiveness liveness = FakeDockerDaemon.this.workloadDead.contains(handle)
                ? WorkloadLiveness.WORKLOAD_DEAD : WorkloadLiveness.SERVING;
            if (workload.containerPort <= 0) {
                return new InstanceStatus(ContainerState.RUNNING, List.of(), liveness);
            }
            return new InstanceStatus(ContainerState.RUNNING,
                List.of(new InstanceStatus.PublishedPort(workload.containerPort,
                    PortPublication.TCP, workload.hostPort, "127.0.0.1")),
                liveness);
        }

        private @NonNull Workload require(@NonNull String handle) throws IOException {
            Workload workload = FakeDockerDaemon.this.workloads.get(handle);
            if (workload == null) {
                throw new IOException("no workload " + handle);
            }
            return workload;
        }
    }

    /** A REAL loopback listener, so the engine's health probe is a real HTTP round trip. */
    private void bind(@NonNull Workload workload) throws IOException {
        HttpServer server = HttpServer.create(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            int status = this.health.getOrDefault(workload.name, 200);
            byte[] body = ("release of " + workload.name).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        workload.responder = server;
        workload.hostPort = server.getAddress().getPort();
    }

    private static void unbind(@NonNull Workload workload) {
        HttpServer responder = workload.responder;
        if (responder != null) {
            responder.stop(0);
            workload.responder = null;
        }
    }

    // -- the kind face --------------------------------------------------------

    /**
     * Point {@code hohenheim:release} and the local daemon at this fake, and
     * return the restore hook. The kind registry maps identifier to handler, so a
     * re-register REPLACES -- and {@link #restore()} puts the production pair back.
     */
    void install() {
        InstanceKinds.register(new FakeSiteKind(this));
        DockerClient.overrideLocalTransportForTest(() -> this);
    }

    /** Undo {@link #install()}; safe to call when it was never installed. */
    static void restore() {
        InstanceKinds.register(new ReleaseKind());
        DockerClient.overrideLocalTransportForTest(null);
    }

    /**
     * The real site kind in every respect but its runtime: schema, spec derivation,
     * generated-only refusal and footprint all come from production, so a spec bug still
     * fails here.
     */
    private static final class FakeSiteKind implements InstanceKindHandler {

        private final ReleaseKind real = new ReleaseKind();
        private final FakeDockerDaemon daemon;

        FakeSiteKind(@NonNull FakeDockerDaemon daemon) {
            this.daemon = daemon;
        }

        @Override
        public @NonNull Identifier typeId() { return ReleaseKind.ID; }

        @Override
        public @NonNull String getDisplayName() { return this.real.getDisplayName(); }

        @Override
        public @NonNull Microcopy getLabel() { return this.real.getLabel(); }

        @Override
        public @NonNull Microcopy getDescription() { return this.real.getDescription(); }

        @Override
        public Icon getIcon() { return this.real.getIcon(); }

        @Override
        public String getColor() { return this.real.getColor(); }

        @Override
        public Schema getSchema() { return this.real.getSchema(); }

        @Override
        public boolean tenantAuthored() { return this.real.tenantAuthored(); }

        // The wrapper answers for the REAL kind on both trust axes. Leaving one to the
        // interface default is how a fake quietly answers SHARED_KERNEL for a kind that
        // is not, and a placement test then passes for the wrong reason.
        @Override
        public @NonNull WorkloadIsolation isolation() { return this.real.isolation(); }

        @Override
        public boolean generatedOnly() { return this.real.generatedOnly(); }

        @Override
        public @NonNull InstanceRuntime runtimeFor(@NonNull String serverName) {
            return this.daemon.runtime();
        }

        @Override
        public @NonNull InstanceSpec specFor(int instanceId,
                                             @NonNull Map<String, Object> settings) {
            return this.real.specFor(instanceId, settings);
        }

        @Override
        public int defaultFootprintMb(@NonNull Map<String, Object> settings) {
            return this.real.defaultFootprintMb(settings);
        }
    }

    // -- the transport face ---------------------------------------------------

    @Override
    public byte[] roundTrip(byte[] request, long timeoutMs) throws IOException {
        return roundTrip(request, timeoutMs, Long.MAX_VALUE);
    }

    @Override
    public byte[] roundTrip(byte[] request, long timeoutMs, long maxResponseBytes)
            throws IOException {
        String text = new String(request, StandardCharsets.ISO_8859_1);
        int headEnd = text.indexOf("\r\n\r\n");
        String requestLine = text.substring(0, text.indexOf("\r\n"));
        String[] parts = requestLine.split(" ");
        String method = parts[0];
        String target = parts.length > 1 ? parts[1] : "/";
        String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
        String query = target.contains("?") ? target.substring(target.indexOf('?') + 1) : "";
        String body = headEnd < 0 ? "" : text.substring(headEnd + 4);
        return dispatch(method, path, query, body);
    }

    private byte[] dispatch(@NonNull String method, @NonNull String path,
                            @NonNull String query, @NonNull String body) throws IOException {
        this.calls.add("api:" + method + " " + path);

        if ("GET".equals(method) && "/_ping".equals(path)) {
            return response(200, "OK", "text/plain");
        }
        if ("GET".equals(method) && ("/version".equals(path) || "/info".equals(path))) {
            return json(200, Map.of("Version", "fake", "ApiVersion", "1.45"));
        }

        // -- images ----------------------------------------------------------
        if ("GET".equals(method) && "/images/json".equals(path)) {
            List<Object> listed = new ArrayList<>();
            for (String image : this.images) {
                listed.add(Map.of("Id", digestOf(image), "RepoTags", List.of(image)));
            }
            return json(200, listed);
        }
        if ("POST".equals(method) && "/images/create".equals(path)) {
            String reference = param(query, "fromImage") + ":" + param(query, "tag");
            this.images.add(reference);
            return response(200, "{\"status\":\"Downloaded " + reference + "\"}",
                "application/json");
        }
        if ("GET".equals(method) && path.startsWith("/images/") && path.endsWith("/json")) {
            String reference = path.substring("/images/".length(),
                path.length() - "/json".length());
            if (!this.images.contains(reference)) {
                return json(404, Map.of("message", "no such image: " + reference));
            }
            return json(200, Map.of("Id", digestOf(reference),
                "RepoTags", List.of(reference)));
        }
        if ("DELETE".equals(method) && path.startsWith("/images/")) {
            this.images.remove(path.substring("/images/".length()));
            return json(200, List.of());
        }

        // -- volumes ---------------------------------------------------------
        if ("POST".equals(method) && "/volumes/create".equals(path)) {
            String name = String.valueOf(parse(body).get("Name"));
            this.volumes.put(name, Map.of());
            return json(200, Map.of("Name", name, "Mountpoint", "/fake/volumes/" + name));
        }
        if ("GET".equals(method) && "/volumes".equals(path)) {
            List<Object> listed = new ArrayList<>();
            for (String name : this.volumes.keySet()) {
                listed.add(Map.of("Name", name));
            }
            return json(200, Map.of("Volumes", listed));
        }
        if ("GET".equals(method) && path.startsWith("/volumes/")) {
            String name = path.substring("/volumes/".length());
            return this.volumes.containsKey(name)
                ? json(200, Map.of("Name", name, "Mountpoint", "/fake/volumes/" + name))
                : json(404, Map.of("message", "no such volume: " + name));
        }
        if ("DELETE".equals(method) && path.startsWith("/volumes/")) {
            this.volumes.remove(path.substring("/volumes/".length()));
            return json(204, Map.of());
        }

        // -- containers ------------------------------------------------------
        if ("GET".equals(method) && "/containers/json".equals(path)) {
            List<Object> listed = new ArrayList<>();
            for (Workload workload : this.workloads.values()) {
                listed.add(Map.of("Id", workload.name, "Names", List.of("/" + workload.name),
                    "Image", workload.image, "Labels", workload.labels,
                    "State", workload.running ? "running" : "exited"));
            }
            return json(200, listed);
        }
        if ("GET".equals(method) && path.startsWith("/containers/") && path.endsWith("/json")) {
            String handle = path.substring("/containers/".length(),
                path.length() - "/json".length());
            Workload workload = this.workloads.get(handle);
            if (workload == null) {
                return json(404, Map.of("message", "No such container: " + handle));
            }
            return json(200, inspectOf(workload));
        }
        if ("POST".equals(method) && path.startsWith("/containers/") && path.endsWith("/stop")) {
            String handle = path.substring("/containers/".length(),
                path.length() - "/stop".length());
            Workload workload = this.workloads.get(handle);
            if (workload == null) {
                return json(404, Map.of("message", "No such container: " + handle));
            }
            // The two faces must AGREE: a refused stop is refused here too, as the
            // daemon's own 500, never a 204 that leaves the caller believing it settled.
            if (this.stopFails.contains(handle)) {
                this.calls.add("stop-refused:" + handle);
                return json(500, Map.of("message",
                    "REFUSED: the daemon will not stop " + handle));
            }
            unbind(workload);
            workload.running = false;
            this.calls.add("stop:" + handle);
            return response(204, "", "application/json");
        }
        if ("DELETE".equals(method) && path.startsWith("/containers/")) {
            String handle = path.substring("/containers/".length());
            Workload workload = this.workloads.remove(handle);
            if (workload == null) {
                return json(404, Map.of("message", "No such container: " + handle));
            }
            unbind(workload);
            this.calls.add("destroy:" + handle);
            return response(204, "", "application/json");
        }

        // -- networks --------------------------------------------------------
        // The fake RUNTIME owns deploys, so nothing here creates a network; the sweeps
        // that merely LOOK are answered honestly with "no such network".
        if ("GET".equals(method) && "/networks".equals(path)) {
            return json(200, List.of());
        }
        if ("GET".equals(method) && path.startsWith("/networks/")) {
            return json(404, Map.of("message", "network not found"));
        }

        throw new IOException("FakeDockerDaemon: unhandled " + method + " " + path);
    }

    private @NonNull Map<String, Object> inspectOf(@NonNull Workload workload) {
        Map<String, Object> ports = new LinkedHashMap<>();
        if (workload.containerPort > 0 && workload.running) {
            ports.put(workload.containerPort + "/tcp", List.of(Map.of(
                "HostIp", "127.0.0.1", "HostPort", String.valueOf(workload.hostPort))));
        }
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("Running", workload.running);
        // The daemon's own answer to "is the process inside still alive": a container it
        // reports RUNNING with this flag set is exactly what the reuse lane must refuse.
        state.put("OOMKilled", this.workloadDead.contains(workload.name));
        Map<String, Object> inspect = new LinkedHashMap<>();
        inspect.put("Id", workload.name);
        inspect.put("Name", "/" + workload.name);
        inspect.put("Image", digestOf(workload.image));
        inspect.put("State", state);
        inspect.put("Config", Map.of("Image", workload.image, "Labels", workload.labels));
        inspect.put("NetworkSettings", Map.of("Ports", ports));
        return inspect;
    }

    /** A stable content-addressed identity per reference; the digest pin reads this. */
    static @NonNull String digestOf(@NonNull String reference) {
        if (reference.startsWith("sha256:")) {
            return reference;
        }
        StringBuilder hex = new StringBuilder("sha256:");
        int hash = reference.hashCode();
        for (int i = 0; i < 8; i++) {
            hex.append(String.format("%08x", hash * (i + 1) + reference.length()));
        }
        return hex.toString();
    }

    @SuppressWarnings("unchecked")
    private static @NonNull Map<String, Object> parse(@NonNull String json) throws IOException {
        try {
            return (Map<String, Object>) new Dry().parse(json);
        } catch (Exception malformed) {
            throw new IOException("FakeDockerDaemon: bad JSON body: " + json, malformed);
        }
    }

    private static @NonNull String param(@NonNull String query, @NonNull String name) {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1),
                    StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static byte[] json(int status, @NonNull Object payload) {
        return response(status, Json.stringify(payload), "application/json");
    }

    private static byte[] response(int status, @NonNull String body,
                                   @NonNull String contentType) {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " " + (status < 400 ? "OK" : "Error") + "\r\n"
            + "Content-Type: " + contentType + "\r\n"
            + "Content-Length: " + payload.length + "\r\n"
            + "Connection: close\r\n\r\n";
        byte[] headBytes = head.getBytes(StandardCharsets.ISO_8859_1);
        byte[] raw = new byte[headBytes.length + payload.length];
        System.arraycopy(headBytes, 0, raw, 0, headBytes.length);
        System.arraycopy(payload, 0, raw, headBytes.length, payload.length);
        return raw;
    }

    /** The instance handle spelling every assertion here shares. */
    static @NonNull String handleOf(int instanceId) {
        return be.elevenways.hohenheim.server.ControllerScope.handle(
            be.elevenways.hohenheim.server.ControllerScope.KIND_INSTANCE, instanceId);
    }
}
