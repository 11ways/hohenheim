package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.DockerStreamConnection;
import be.elevenways.hohenheim.server.docker.DockerStreamTransport;
import be.elevenways.hohenheim.server.docker.DockerTransport;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ReleaseKind;
import be.elevenways.hohenheim.server.database.DatabaseContainerKind;
import be.elevenways.hohenheim.server.instance.DockerContainerKind;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.Egress;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.LinkNetworkSupport;
import be.elevenways.hohenheim.test.instance.ForwardingKindHandler;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.server.runtime.PortPublication;
import be.elevenways.hohenheim.server.runtime.WorkloadLiveness;
import be.elevenways.hohenheim.server.util.Json;
import be.elevenways.protoblast.common.dry.Dry;
import be.elevenways.protoblast.common.i18n.Microcopy;
import com.sun.net.httpserver.HttpServer;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The hermetic Docker daemon of the site tier: ONE in-memory container/volume/image store
 * with TWO faces onto the same state -- an {@link InstanceRuntime} the release kind deploys
 * through, and a {@link DockerTransport} every {@code new DockerClient()} in the release
 * engine speaks raw HTTP/1.1 to. The two faces must agree, because the product itself
 * reads BOTH: a release deploys through the runtime and the fast-reuse lane
 * ({@code ApplicationReleases.reusableStatus}) inspects the daemon directly.
 *
 * AIDEV-NOTE: this is the FakeIncusTransport doctrine applied to the docker lane, and it
 * generalizes FakeNativeDaemons rather than duplicating it: FakeNativeDaemons fakes a
 * whole KIND of its own (nothing production-shaped points at it), while an application release is
 * always a {@code hohenheim:release}, so the fake has to stand in for that kind's
 * runtime instead -- {@link #install()} re-registers the real kind's identifier with a
 * handler that differs in {@code runtimeFor} alone. An unhandled HTTP path throws loudly
 * rather than answering success: a gap here must fail the test, never hide in a default.
 *
 * EVERY FAKE MUST BE ABLE TO SAY NO. The failures this can produce, each one a real
 * product path: an unhealthy candidate ({@link #answerWithForNextWorkload}), a container
 * the daemon reports RUNNING while its workload is dead ({@link #reportWorkloadDead},
 * which is the daemon's {@code OOMKilled} the reuse lane refuses to reuse), a create
 * that refuses a foreign workload under our handle, a stop the daemon will not
 * settle ({@link #refuseStopOf}, the drain's stop-failure branch) and an exec the engine
 * refuses ({@link #refuseNextExec}) or answers slowly ({@link #duringNextExec}), a streamed exec (a database dump)
 * included.
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
 * and the kernel policy are NOT exercised here -- ApplicationReleaseLiveTest remains their
 * only proof, along with "zero dropped requests through a real proxy".
 */
public final class FakeDockerDaemon implements DockerTransport, DockerStreamTransport {

    /** One fake container; its published port is a REAL loopback listener. */
    static final class Workload {
        final @NonNull String name;
        final @NonNull Map<String, String> labels;
        final @NonNull String image;
        final int containerPort;
        boolean running;
        int hostPort;
        /** The host port the spec asked for (a pre-allocated or remembered number), 0 for the kernel's pick. */
        int requestedPort;
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

    /** The link networks the runtime face holds; see {@link FakeReleaseRuntime#ensureLinkNetwork}. */
    private final Set<String> linkNetworks = new LinkedHashSet<>();

    /** link network -> the container handles joined to it; a recreate leaves every one of them. */
    private final Map<String, Set<String>> linkMembers = new LinkedHashMap<>();

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

    /** One exec the daemon ran inside a container: the container, the command and its environment. */
    public record Exec(@NonNull String handle, @NonNull List<String> command, @NonNull List<String> env) {
    }

    /** What an exec answers: its exit code and the text on each stream. */
    private record ExecAnswer(int exitCode, @NonNull String stdout, @NonNull String stderr) {
    }

    /** Every exec that was started, in order; written from whichever thread drives the engine. */
    private final List<Exec> execs = Collections.synchronizedList(new ArrayList<>());

    /** exec id -> the exec created under it and not yet started. */
    private final Map<String, Exec> createdExecs = new ConcurrentHashMap<>();

    /** exec id -> the exit code its start recorded. */
    private final Map<String, Integer> execExits = new ConcurrentHashMap<>();

    /** Numbers the exec ids. */
    private final AtomicInteger execIds = new AtomicInteger();

    /** Consumed by the next exec start: the answer it gives instead of success. */
    private volatile @Nullable ExecAnswer nextExecAnswer;

    /** Consumed by the next exec start: run once the engine took the command, before the daemon answers. */
    private volatile @Nullable Runnable duringNextExec;

    // -- scripting ------------------------------------------------------------

    /**
     * The status the NEXT workload created answers with. A release engine mints its own
     * candidate row, so the handle to script does not exist yet when the test decides the
     * candidate must be unhealthy -- this is the only honest way to say it in advance.
     */
    public void answerWithForNextWorkload(int httpStatus) {
        this.nextHealth = httpStatus;
    }

    /**
     * Run {@code work} INSIDE the next start, on the caller's thread and inside its
     * datasource scope: the only honest way to place a rival writer in the middle of an
     * operation, where a real one lands while the daemon spends its minutes.
     */
    public void duringNextStart(@NonNull Runnable work) {
        this.duringNextStart = work;
    }

    /** Report this workload as RUNNING with a dead process inside (the OOM-kill shape). */
    public void reportWorkloadDead(@NonNull String handle) {
        this.workloadDead.add(handle);
    }

    /**
     * Refuse every stop of this handle, on BOTH faces: the workload keeps running and the
     * caller gets the daemon's refusal. The real shape is a container the engine cannot
     * settle (a wedged runtime, a busy device); the release that just took traffic must
     * survive it.
     */
    public void refuseStopOf(@NonNull String handle) {
        this.stopFails.add(handle);
    }

    /** Let this handle be stopped again; the refusal is per-daemon and must not leak. */
    public void allowStopOf(@NonNull String handle) {
        this.stopFails.remove(handle);
    }

    /**
     * The workload's process EXITS with nobody watching: the container is down and its
     * published port is unbound, and no control-plane path was told.
     *
     * AIDEV-NOTE: deliberately not the runtime's {@code stop}, which is what an operator
     * stop calls and therefore what releases the port claims and stamps the record. This is
     * the crash the status reconciler exists for -- the record still says running, the
     * ledger still holds an OBSERVED claim, and only a daemon question can tell.
     */
    public void killWorkload(@NonNull String handle) {
        Workload workload = this.workloads.get(handle);
        if (workload == null) {
            throw new IllegalStateException("no workload " + handle);
        }
        unbind(workload);
        workload.running = false;
        this.calls.add("killed:" + handle);
    }

    /** Every recorded act against ONE handle, in order: the workload's whole life. */
    public @NonNull List<String> callsFor(@NonNull String handle) {
        List<String> mine = new ArrayList<>();
        for (String call : List.copyOf(this.calls)) {
            if (call.endsWith(":" + handle)) {
                mine.add(call);
            }
        }
        return mine;
    }

    /** How many times one exact recorded act happened. */
    public int callCount(@NonNull String call) {
        int count = 0;
        for (String recorded : List.copyOf(this.calls)) {
            if (recorded.equals(call)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Place a named volume with the given labels, as a controller (or an older build, or a
     * colliding stack) left it; what a purge must tell apart by those labels.
     */
    public void seedVolume(@NonNull String name, @NonNull Map<String, String> labels) {
        this.volumes.put(name, Map.copyOf(labels));
    }

    /** Whether the daemon still holds a volume under this name. */
    public boolean hasVolume(@NonNull String name) {
        return this.volumes.containsKey(name);
    }

    /** Whether the daemon still holds a container under this handle. */
    public boolean exists(@NonNull String handle) {
        return this.workloads.containsKey(handle);
    }

    /** Whether the container under this handle is running. */
    public boolean isRunning(@NonNull String handle) {
        Workload workload = this.workloads.get(handle);
        return workload != null && workload.running;
    }

    /**
     * The containers currently joined to one link network. A create REPLACES the container under its handle, and
     * the replacement is joined to nothing, exactly as a real daemon's fresh container sits on its own network only.
     */
    public @NonNull Set<String> linkMembersOf(@NonNull String linkHandle) {
        return Set.copyOf(this.linkMembers.getOrDefault(linkHandle, Set.of()));
    }

    /** The link networks one container is currently joined to. */
    public @NonNull Set<String> linkNetworksOf(@NonNull String containerHandle) {
        Set<String> joined = new LinkedHashSet<>();
        this.linkMembers.forEach((network, members) -> {
            if (members.contains(containerHandle)) {
                joined.add(network);
            }
        });
        return joined;
    }

    /**
     * Place a RUNNING container with no published port under this handle, as a controller's earlier deploy left it:
     * the engine an exec runs in when the test planted the engine's instance row without deploying it.
     */
    public void seedRunning(@NonNull String handle) {
        Workload workload = new Workload(handle, Map.of(), "fake", 0);
        workload.running = true;
        this.workloads.put(handle, workload);
    }

    /** The NEXT exec exits with this code and writes {@code stderr}: the engine refusing the command. */
    public void refuseNextExec(int exitCode, @NonNull String stderr) {
        this.nextExecAnswer = new ExecAnswer(exitCode, "", stderr);
    }

    /** The NEXT exec exits zero and prints {@code stdout}: an engine answering a probe. */
    public void answerNextExec(@NonNull String stdout) {
        this.nextExecAnswer = new ExecAnswer(0, stdout, "");
    }

    /**
     * The NEXT exec runs {@code work} after it is recorded (the engine took the command) and before the daemon
     * answers, on the thread that started it: a slow engine another caller can race.
     */
    public void duringNextExec(@NonNull Runnable work) {
        this.duringNextExec = work;
    }

    /** Every exec started inside one container, in order. */
    public @NonNull List<Exec> execsOn(@NonNull String handle) {
        List<Exec> mine = new ArrayList<>();
        synchronized (this.execs) {
            for (Exec exec : this.execs) {
                if (exec.handle().equals(handle)) {
                    mine.add(exec);
                }
            }
        }
        return mine;
    }

    /** Release every real loopback listener this daemon opened. */
    public void close() {
        for (Workload workload : this.workloads.values()) {
            unbind(workload);
        }
        this.workloads.clear();
    }

    // -- the runtime face -----------------------------------------------------

    /** The runtime the release kind deploys through while {@link #install()} is in force. */
    @NonNull InstanceRuntime runtime() {
        return new FakeReleaseRuntime();
    }

    private final class FakeReleaseRuntime implements InstanceRuntime, LinkNetworkSupport {

        /**
         * A database attachment's link network joins the workload to its engine before start
         * (InstanceDatabaseNetworks): the fake keeps the networks and refuses a member it does not hold, as the
         * daemon refuses a container that does not exist.
         */
        @Override
        public @NonNull String ensureLinkNetwork(@NonNull String linkHandle, @NonNull Map<String, String> ownerLabels,
                                                 @NonNull Egress egress) {
            FakeDockerDaemon.this.linkNetworks.add(linkHandle);
            FakeDockerDaemon.this.calls.add("link-network:" + linkHandle);
            return linkHandle;
        }

        @Override
        public void connectToLinkNetwork(@NonNull String linkHandle, @NonNull String containerHandle,
                                         @NonNull List<String> aliases) throws IOException {
            if (!FakeDockerDaemon.this.linkNetworks.contains(linkHandle)) {
                throw new IOException("no link network " + linkHandle);
            }
            require(containerHandle);
            FakeDockerDaemon.this.linkMembers.computeIfAbsent(linkHandle, key -> new LinkedHashSet<>())
                .add(containerHandle);
            FakeDockerDaemon.this.calls.add("link-connect:" + linkHandle + ":" + containerHandle);
        }

        @Override
        public void removeLinkNetwork(@NonNull String linkHandle) {
            FakeDockerDaemon.this.linkNetworks.remove(linkHandle);
            FakeDockerDaemon.this.linkMembers.remove(linkHandle);
            FakeDockerDaemon.this.calls.add("link-remove:" + linkHandle);
        }

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
                leaveLinkNetworks(spec.handle());
            }
            int containerPort = spec.publications().isEmpty()
                ? 0 : spec.publications().get(0).containerPort();
            Workload created = new Workload(spec.handle(), Map.copyOf(spec.ownerLabels()), spec.image(),
                containerPort);
            Integer requested = spec.publications().isEmpty() ? null : spec.publications().get(0).preallocatedPort();
            created.requestedPort = requested == null ? 0 : requested;
            FakeDockerDaemon.this.workloads.put(spec.handle(), created);
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
            leaveLinkNetworks(handle);
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
            new InetSocketAddress(InetAddress.getLoopbackAddress(), workload.requestedPort), 0);
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

    /** The container is gone or replaced: whatever networks the old one had joined, it holds no more. */
    private void leaveLinkNetworks(@NonNull String handle) {
        this.linkMembers.values().forEach(members -> members.remove(handle));
    }

    // -- the kind face --------------------------------------------------------

    /**
     * Point {@code hohenheim:release} and the local daemon at this fake, and
     * return the restore hook. The kind registry maps identifier to handler, so this is a
     * deliberate replace (a plain register of a held id is a conflict) -- and
     * {@link #restore()} puts the production pair back.
     */
    public void install() {
        InstanceKinds.replace(new FakeRuntimeKind(new ReleaseKind(), this));
        DockerClient.overrideLocalTransportForTest(() -> this);
    }

    /**
     * ALSO point {@code hohenheim:docker_container} at this fake, so a NON-release-managed
     * workload can be deployed hermetically.
     *
     * AIDEV-NOTE: opt-in rather than folded into {@link #install()}, because the two
     * existing consumers are release journeys and a fake standing in for a kind their
     * subject never uses would be scaffolding in their environment. It arrives with its
     * first consumer ({@code InstanceUpstreamStalenessTest}), which needs the ONE shape the
     * release kind cannot produce: an ephemeral loopback publication on a record a site
     * points at DIRECTLY, whose host port really moves when the workload restarts.
     */
    public void installContainerKind() {
        InstanceKinds.replace(new FakeRuntimeKind(new DockerContainerKind(), this));
    }

    /**
     * ALSO point {@code hohenheim:database_container} at this fake, so a managed database's engine instance (a
     * shared engine's or a dedicated record's) is deployed hermetically through the instance contract.
     *
     * AIDEV-NOTE: arrives with its first consumer ({@code DatabaseLinkRejoinTest}): the shape no other kind can
     * produce is a database container REPLACED by a generic deploy while workloads hold link networks to it.
     */
    public void installDatabaseKind() {
        InstanceKinds.replace(new FakeRuntimeKind(new DatabaseContainerKind(), this));
    }

    /** Undo {@link #install()}, {@link #installContainerKind()} and {@link #installDatabaseKind()}; safe either way. */
    public static void restore() {
        InstanceKinds.replace(new ReleaseKind());
        InstanceKinds.replace(new DockerContainerKind());
        InstanceKinds.replace(new DatabaseContainerKind());
        DockerClient.overrideLocalTransportForTest(null);
    }

    /**
     * A real kind with its runtime swapped for this fake: schema, spec derivation, publication shape, generated-only
     * refusal, reach and footprint all come from production, so a spec bug still fails here.
     */
    private static final class FakeRuntimeKind extends ForwardingKindHandler {

        private final FakeDockerDaemon daemon;

        FakeRuntimeKind(@NonNull InstanceKindHandler real, @NonNull FakeDockerDaemon daemon) {
            super(real);
            this.daemon = daemon;
        }

        @Override
        public @NonNull InstanceRuntime runtimeFor(@NonNull String serverName) {
            return this.daemon.runtime();
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

    /**
     * The streaming face, for an exec whose output is streamed (a database dump): it answers what {@link #roundTrip}
     * answers the same exec start, from a buffer. Any other stream is unhandled.
     */
    @Override
    public @NonNull DockerStreamConnection openStream(byte @NonNull [] request, long connectTimeoutMs)
            throws IOException {
        String text = new String(request, StandardCharsets.ISO_8859_1);
        String[] parts = text.substring(0, text.indexOf("\r\n")).split(" ");
        String target = parts.length > 1 ? parts[1] : "/";
        if (!"POST".equals(parts[0]) || !target.startsWith("/exec/") || !target.endsWith("/start")) {
            throw new IOException("FakeDockerDaemon: unhandled stream " + parts[0] + " " + target);
        }
        ByteArrayInputStream answer = new ByteArrayInputStream(roundTrip(request, connectTimeoutMs));
        return new DockerStreamConnection() {
            private volatile boolean closed;

            @Override
            public int read(byte @NonNull [] buffer, int offset, int length) {
                return answer.read(buffer, offset, length);
            }

            @Override
            public void write(byte @NonNull [] data) throws IOException {
                throw new IOException("FakeDockerDaemon: a streamed exec start takes no body");
            }

            @Override
            public void close() {
                this.closed = true;
            }

            @Override
            public boolean isReleased() {
                return this.closed;
            }

            @Override
            public @NonNull String diagnostics() {
                return "";
            }
        };
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
            Map<String, Object> request = parse(body);
            String name = String.valueOf(request.get("Name"));
            this.volumes.put(name, stringLabels(request.get("Labels")));
            return json(200, Map.of("Name", name, "Mountpoint", "/fake/volumes/" + name));
        }
        if ("GET".equals(method) && "/volumes".equals(path)) {
            List<Object> listed = new ArrayList<>();
            this.volumes.forEach((name, labels) ->
                listed.add(Map.of("Name", name, "Labels", labels)));
            return json(200, Map.of("Volumes", listed));
        }
        if ("GET".equals(method) && path.startsWith("/volumes/")) {
            String name = path.substring("/volumes/".length());
            return this.volumes.containsKey(name)
                ? json(200, Map.of("Name", name, "Mountpoint", "/fake/volumes/" + name,
                    "Labels", this.volumes.get(name)))
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

        // -- execs -----------------------------------------------------------
        // A container that is absent or stopped refuses an exec the way the daemon does; a started exec answers
        // success with "hohenheim-ok" unless refuseNextExec scripted a refusal.
        if ("POST".equals(method) && path.startsWith("/containers/") && path.endsWith("/exec")) {
            String handle = path.substring("/containers/".length(), path.length() - "/exec".length());
            Workload workload = this.workloads.get(handle);
            if (workload == null) {
                return json(404, Map.of("message", "No such container: " + handle));
            }
            if (!workload.running) {
                return json(409, Map.of("message", "Container " + handle + " is not running"));
            }
            Map<String, Object> spec = parse(body);
            String id = "exec-" + this.execIds.incrementAndGet();
            this.createdExecs.put(id, new Exec(handle, strings(spec.get("Cmd")), strings(spec.get("Env"))));
            return json(201, Map.of("Id", id));
        }
        if ("POST".equals(method) && path.startsWith("/exec/") && path.endsWith("/start")) {
            String id = path.substring("/exec/".length(), path.length() - "/start".length());
            Exec exec = this.createdExecs.remove(id);
            if (exec == null) {
                return json(404, Map.of("message", "No such exec instance: " + id));
            }
            ExecAnswer answer = this.nextExecAnswer != null ? this.nextExecAnswer
                : new ExecAnswer(0, "hohenheim-ok", "");
            this.nextExecAnswer = null;
            this.execs.add(exec);
            this.execExits.put(id, answer.exitCode());
            this.calls.add("exec:" + exec.handle());
            Runnable during = this.duringNextExec;
            this.duringNextExec = null;
            if (during != null) {
                during.run();
            }
            byte[] out = FakeContainerFiles.frame(1, answer.stdout());
            byte[] err = answer.stderr().isEmpty() ? new byte[0] : FakeContainerFiles.frame(2, answer.stderr());
            byte[] stream = new byte[out.length + err.length];
            System.arraycopy(out, 0, stream, 0, out.length);
            System.arraycopy(err, 0, stream, out.length, err.length);
            return FakeContainerFiles.raw(200, Map.of("Content-Type", "application/vnd.docker.raw-stream"), stream);
        }
        if ("GET".equals(method) && path.startsWith("/exec/") && path.endsWith("/json")) {
            Integer exit = this.execExits.get(path.substring("/exec/".length(), path.length() - "/json".length()));
            return exit == null ? json(404, Map.of("message", "No such exec instance"))
                : json(200, Map.of("ExitCode", exit, "Running", false));
        }

        // -- networks --------------------------------------------------------
        // The fake RUNTIME owns deploys, so nothing here creates a network; the sweeps
        // that LOOK and the teardowns that REMOVE are answered honestly with "no such network".
        if ("GET".equals(method) && "/networks".equals(path)) {
            return json(200, List.of());
        }
        if ("GET".equals(method) && path.startsWith("/networks/")) {
            return json(404, Map.of("message", "network not found"));
        }
        if ("DELETE".equals(method) && path.startsWith("/networks/")) {
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
    public static @NonNull String digestOf(@NonNull String reference) {
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
    private static @NonNull Map<String, String> stringLabels(@Nullable Object labels) {
        Map<String, String> copy = new LinkedHashMap<>();
        if (labels instanceof Map<?, ?> map) {
            map.forEach((key, value) -> copy.put(String.valueOf(key), String.valueOf(value)));
        }
        return copy;
    }

    /** @return a JSON array's entries as text, empty for an absent one */
    private static @NonNull List<String> strings(@Nullable Object array) {
        List<String> entries = new ArrayList<>();
        if (array instanceof List<?> list) {
            for (Object entry : list) {
                entries.add(String.valueOf(entry));
            }
        }
        return entries;
    }

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
    public static @NonNull String handleOf(int instanceId) {
        return be.elevenways.hohenheim.server.ControllerScope.handle(
            be.elevenways.hohenheim.server.ControllerScope.KIND_INSTANCE, instanceId);
    }
}
