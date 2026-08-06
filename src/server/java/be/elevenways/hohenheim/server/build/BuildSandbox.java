package be.elevenways.hohenheim.server.build;

import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.runtime.ConsoleStream;
import be.elevenways.hohenheim.server.runtime.Egress;
import be.elevenways.hohenheim.server.runtime.WorkloadNetworks;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.protoblast.common.Blast;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * THE sandbox a tenant build runs in -- the most hostile execution surface in the
 * product, and the one place its isolation is argued.
 *
 * <p>Why each thing is unreachable from inside a build:</p>
 * <ul>
 *   <li><b>The Docker daemon.</b> Nothing mounts its socket, and nothing CAN: the socket
 *       is a host path, {@link ContainerHardening} refuses every bind mount structurally
 *       at the single {@code createContainer} funnel, and the builder is daemonless by
 *       requirement ({@code builds.builder_image}) so it never asks for one. The daemon
 *       also listens on that unix socket only; there is no TCP endpoint to reach. And if
 *       an operator gave it one, the network policy's input chain drops packets from the
 *       build's subnet to the host anyway. Three independent reasons, none of which is a
 *       promise the build has to keep.</li>
 *   <li><b>The control plane.</b> Its database and its HTTP surface live on the host or on
 *       a private address. The build sits on its OWN network whose forward chain drops
 *       every RFC1918 destination except its own subnet, and whose input chain drops the
 *       host entirely -- both applied to the kernel and READ BACK before the container
 *       exists.</li>
 *   <li><b>The host.</b> Same input chain, plus drop-ALL capabilities,
 *       no-new-privileges, no host namespaces, no devices, no sysctls, no {@code /proc}
 *       unmasking -- all structural refusals at the funnel, none of them a setting.</li>
 *   <li><b>Other tenants.</b> Every other workload is on some other private network in
 *       RFC1918 space, which the forward chain drops. A {@code container:<id>} namespace
 *       join, the one string that would defeat this, is refused at the funnel.</li>
 *   <li><b>Cloud metadata.</b> {@code 169.254.0.0/16} is denied explicitly by the same
 *       policy, which is why it is a named range and not an afterthought.</li>
 * </ul>
 *
 * <p>What a build CAN reach is the public internet, deliberately: fetching base images
 * and dependencies is what a build is. The policy is egress-RESTRICTIVE, not
 * egress-closed, and a per-build egress allowlist is a named future tightening.</p>
 *
 * AIDEV-NOTE: the network is established and VERIFIED IN THE KERNEL before the container
 * exists, and an unenforceable host REFUSES the build. That is inherited from
 * {@link WorkloadNetworks}/{@link WorkloadNetworkPolicy} on purpose: a build that starts
 * unprotected is worse than a build that does not start, and the never-throwing
 * {@code NftService} would have let exactly that happen.
 *
 * AIDEV-NOTE: quotas. CPU and memory are cgroup caps; PIDs is a TIGHTENING of the
 * hardening baseline; TIME is this class's deadline (kill + remove, recorded as
 * timed_out); DISK is a watchdog over the daemon's own {@code SizeRw} accounting plus
 * hard caps on the context pushed in and the artifact read out. Five quotas, five
 * enforcement points, and none of them reports success without acting.
 */
public final class BuildSandbox {

    /**
     * The measured profile. Kaniko unpacks a base image's rootfs, which means chowning
     * files to uids it does not run as -- under {@link ContainerHardening#STRICT} that
     * fails on any base image with non-root-owned files. This is the SAME set every other
     * container authority declares; the build's isolation comes from the network policy,
     * the escape refusals and the quotas, not from a narrower capability list.
     */
    public static final ContainerHardening.Profile HARDENING = ContainerHardening.SERVICE;

    /** How often the sandbox re-inspects the build for liveness and the deadline. */
    private static final long POLL_MS = 500;

    /** How often the disk watchdog asks the daemon for the writable-layer size. */
    private static final long DISK_POLL_MS = 2000;

    /** Consecutive blind disk probes before an unobservable build is killed. */
    private static final int MAX_DISK_PROBE_FAILURES = 10;

    /** Why a build ended. */
    public enum Ending {
        /** The builder exited on its own (its exit code decides success). */
        EXITED,
        /** The wall-clock quota bound; the sandbox killed it. */
        TIMED_OUT,
        /** The disk quota bound; the sandbox killed it. */
        DISK_EXCEEDED
    }

    /**
     * @param artifact null unless the build exited 0 AND its artifact was read back
     *                 within the artifact cap
     */
    public record Outcome(@NonNull Ending ending, int exitCode, long peakDiskBytes,
                          @Nullable Path artifact, long artifactBytes) {

        /** Success is EXITED with 0 AND an artifact -- never two of the three. */
        public boolean succeeded() {
            return this.ending == Ending.EXITED && this.exitCode == 0 && this.artifact != null;
        }
    }

    private final @NonNull DockerClient docker;
    private final @NonNull WorkloadNetworkPolicy policy;

    public BuildSandbox(@NonNull DockerClient docker, @NonNull WorkloadNetworkPolicy policy) {
        this.docker = docker;
        this.policy = policy;
    }

    /** The container name of one build; also its private network's stem. */
    public static @NonNull String handleOf(int buildId) {
        return ControllerScope.handle(ControllerScope.KIND_BUILD, buildId);
    }

    /**
     * Run one build to completion, streaming its output into {@code log}.
     *
     * @throws IOException when the sandbox cannot be established (unenforceable host,
     *                     oversized context, daemon refusal) -- the build never started
     */
    public @NonNull Outcome run(int buildId, @NonNull BuildPlan plan, @NonNull BuildQuota quota,
                                @NonNull Path contextDir, @NonNull BuildLog log)
            throws IOException {
        String handle = handleOf(buildId);
        Map<String, String> ownerLabels = OwnerLabels.of(BuildOperationModel.MODEL_ID, buildId);

        // Cheapest, loudest refusal first: an oversized context never reaches the daemon,
        // and a host that cannot enforce the policy never gets a container at all.
        long contextBytes = directorySize(contextDir);
        if (contextBytes > quota.diskBytes()) {
            throw new IOException("REFUSED to build: the build context is " + contextBytes
                + " bytes, over the " + quota.diskBytes() + " byte disk quota");
        }
        // Builds declare OPEN egress: fetching base images, packages and dependencies
        // is what a build does; the tenant-range denies still apply.
        String network = WorkloadNetworks.ensure(this.docker, this.policy, handle, ownerLabels,
            Egress.OPEN);
        log.line("[hohenheim] sandbox network " + network
            + " created with a verified host/metadata/tenant deny policy");

        this.docker.ensureImage(plan.builderImage(), null);
        // Idempotent resume: a leftover build container WE own is replaced; a same-named
        // foreign container stays a refusal inside removeIfOwnedBy.
        OwnerLabels.removeIfOwnedBy(this.docker, handle, BuildOperationModel.MODEL_ID, buildId);

        this.docker.createContainer(handle, containerSpec(plan, quota, network, ownerLabels),
            HARDENING, quota.effectivePidsLimit());
        try {
            pushContext(handle, plan, contextDir);
            stageFiles(handle, plan);
            // Attach BEFORE start (docker run's own create-attach-start order), so the
            // first line the builder prints cannot be missed.
            ConsoleStream stream = this.docker.attach(handle);
            Thread reader = drainInto(stream, log, handle);
            this.docker.startContainer(handle);
            log.line("[hohenheim] build started under quota: "
                + quota.cpus() + " cpus, " + quota.memoryMb() + " MiB memory, "
                + quota.diskLimitMb() + " MiB disk, " + quota.timeoutSeconds() + "s, "
                + quota.effectivePidsLimit() + " pids");
            Outcome outcome = watch(buildId, handle, plan, quota, log);
            stream.close();
            joinQuietly(reader);
            return outcome;
        } finally {
            // The container and its network are debris the moment the build ends; the
            // artifact was already read out of it, so there is nothing in them to lose.
            try {
                this.docker.removeContainer(handle, true);
            } catch (IOException ignored) {
                // A leftover is re-removed by the next build's removeIfOwnedBy; never
                // mask the run's real outcome with cleanup noise.
            }
            try {
                WorkloadNetworks.teardown(this.docker, this.policy, handle);
            } catch (IOException e) {
                Blast.log("BUILD: could not tear down sandbox network of", handle, "-",
                    e.getMessage());
            }
        }
    }

    /**
     * The watch loop: liveness, the wall-clock deadline and the disk watchdog, with the
     * artifact read while the (exited) container still exists.
     */
    private @NonNull Outcome watch(int buildId, @NonNull String handle, @NonNull BuildPlan plan,
                                   @NonNull BuildQuota quota, @NonNull BuildLog log)
            throws IOException {
        Supervision watched = supervise(buildId, handle, quota, log);
        if (watched.ending() != Ending.EXITED) {
            return new Outcome(watched.ending(), -1, watched.peakDiskBytes(), null, 0);
        }
        if (watched.exitCode() != 0) {
            log.line("[hohenheim] build exited " + watched.exitCode());
            return new Outcome(Ending.EXITED, watched.exitCode(), watched.peakDiskBytes(), null, 0);
        }
        return collectArtifact(handle, plan, quota, log, watched.peakDiskBytes());
    }

    /** How one supervised container ended; the ending decides what the caller may read. */
    private record Supervision(@NonNull Ending ending, int exitCode, long peakDiskBytes) {}

    /**
     * Supervise one container to its end: liveness polling, the wall-clock deadline and
     * the disk watchdog, shared by the image build and every builder pre-phase.
     */
    private @NonNull Supervision supervise(int buildId, @NonNull String handle,
                                           @NonNull BuildQuota quota, @NonNull BuildLog log)
            throws IOException {
        long deadline = System.currentTimeMillis() + quota.timeoutMs();
        long nextDiskPoll = System.currentTimeMillis();
        long peakDisk = 0;
        int diskProbeFailures = 0;

        while (true) {
            Map<String, Object> inspect = this.docker.inspectContainer(handle);
            Object state = inspect.get("State");
            boolean running = state instanceof Map<?, ?> s && Boolean.TRUE.equals(s.get("Running"));
            if (!running) {
                int exitCode = state instanceof Map<?, ?> s && s.get("ExitCode") instanceof Number code
                    ? code.intValue() : -1;
                return new Supervision(Ending.EXITED, exitCode, peakDisk);
            }

            long now = System.currentTimeMillis();
            if (now > deadline) {
                log.line("[hohenheim] build exceeded its " + quota.timeoutSeconds()
                    + "s wall-clock quota and was killed");
                kill(handle);
                return new Supervision(Ending.TIMED_OUT, -1, peakDisk);
            }
            if (now >= nextDiskPoll) {
                nextDiskPoll = now + DISK_POLL_MS;
                long written;
                try {
                    written = this.docker.containerWritableBytes(handle);
                    diskProbeFailures = 0;
                } catch (IOException flaky) {
                    // The snapshotter races its own usage walk against a busy build
                    // (files vanish mid-scan and it answers 500). ONE blind observation
                    // is not a quota decision -- but a watchdog that stays blind would be
                    // an unmetered build, so persistent blindness kills it by name.
                    if (++diskProbeFailures >= MAX_DISK_PROBE_FAILURES) {
                        kill(handle);
                        throw new IOException("The disk watchdog could not observe build "
                            + buildId + " " + diskProbeFailures + " times in a row ("
                            + flaky.getMessage() + "); refusing to run unmetered");
                    }
                    continue;
                }
                if (written > peakDisk) {
                    peakDisk = written;
                }
                if (written > quota.diskBytes()) {
                    log.line("[hohenheim] build wrote " + written + " bytes, over its "
                        + quota.diskLimitMb() + " MiB disk quota, and was killed");
                    kill(handle);
                    return new Supervision(Ending.DISK_EXCEEDED, -1, peakDisk);
                }
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                kill(handle);
                throw new IOException("Build " + buildId + " was interrupted and killed");
            }
        }
    }

    /**
     * A builder kind's sandboxed pre-phase (detection): what to run, what to stage in,
     * and what to read back out.
     *
     * @param image          the phase's tool image; pulled by the sandbox, never the phase
     * @param command        argv inside that image
     * @param env            phase-facing environment (build args a detector may honor)
     * @param contextPath    directory inside the container the build context is pushed into
     * @param stagedBinaries files staged in BEFORE start (real host files, so binaries
     *                       survive -- unlike {@link BuildPlan#stagedFiles} text content)
     * @param collectFiles   container paths read back after the phase ends, whatever its
     *                       exit (absent = omitted)
     * @param collectDir     container directory read back as a raw tar, or null
     */
    public record Phase(@NonNull String image, @NonNull List<String> command,
                        @NonNull Map<String, String> env,
                        @NonNull String contextPath,
                        @NonNull List<StagedBinary> stagedBinaries,
                        @NonNull List<String> collectFiles,
                        @Nullable String collectDir) {

        /** Files copied from {@code hostDir} into {@code targetDir} inside the phase. */
        public record StagedBinary(@NonNull String targetDir, @NonNull Path hostDir,
                                   @NonNull List<String> files) {}
    }

    /**
     * @param collectedDirTar raw tar of {@link Phase#collectDir} (docker's envelope:
     *                        entries rooted at the directory's basename), or null; the
     *                        CALLER owns deleting it
     */
    public record PhaseOutcome(@NonNull Ending ending, int exitCode,
                               @NonNull Map<String, byte[]> files,
                               @Nullable Path collectedDirTar) {

        public boolean succeeded() {
            return this.ending == Ending.EXITED && this.exitCode == 0;
        }
    }

    /**
     * Run one builder pre-phase under the SAME hardening, quota enforcement and log
     * stream as the build itself, but with NO network at all ({@code NetworkMode: none}):
     * a detection phase parses hostile tenant files and has no business fetching
     * anything, so it gets a strictly TIGHTER posture than the build.
     *
     * @throws IOException when the phase cannot be established -- it never started
     */
    public @NonNull PhaseOutcome runPhase(int buildId, @NonNull String phaseName,
                                          @NonNull Phase phase, @NonNull BuildQuota quota,
                                          @NonNull Path contextDir, @NonNull BuildLog log)
            throws IOException {
        String handle = handleOf(buildId) + "-" + phaseName;
        Map<String, String> ownerLabels = OwnerLabels.of(BuildOperationModel.MODEL_ID, buildId);

        long contextBytes = directorySize(contextDir);
        if (contextBytes > quota.diskBytes()) {
            throw new IOException("REFUSED to build: the build context is " + contextBytes
                + " bytes, over the " + quota.diskBytes() + " byte disk quota");
        }

        this.docker.ensureImage(phase.image(), null);
        OwnerLabels.removeIfOwnedBy(this.docker, handle, BuildOperationModel.MODEL_ID, buildId);

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("Image", phase.image());
        spec.put("Labels", ownerLabels);
        spec.put("Cmd", phase.command());
        List<String> env = new ArrayList<>();
        phase.env().forEach((name, value) -> env.add(name + "=" + value));
        if (!env.isEmpty()) {
            spec.put("Env", env);
        }
        Map<String, Object> hostConfig = new LinkedHashMap<>();
        hostConfig.put("NetworkMode", "none");
        quota.limits().applyTo(hostConfig);
        spec.put("HostConfig", hostConfig);

        this.docker.createContainer(handle, spec, HARDENING, quota.effectivePidsLimit());
        try {
            // putArchiveCreating, not putArchiveFromDirectory: a phase image is a plain
            // base image with no /workspace (or tool dir) baked in, and Docker's archive
            // PUT refuses a target directory that does not exist.
            this.docker.putArchiveCreating(handle, phase.contextPath(), contextDir, null);
            for (Phase.StagedBinary staged : phase.stagedBinaries()) {
                this.docker.putArchiveCreating(handle, staged.targetDir(), staged.hostDir(),
                    staged.files());
            }
            ConsoleStream stream = this.docker.attach(handle);
            Thread reader = drainInto(stream, log, handle);
            this.docker.startContainer(handle);
            Supervision watched = supervise(buildId, handle, quota, log);
            stream.close();
            joinQuietly(reader);
            // Outputs are collected EVEN from a failed phase: the builder kind judges the
            // outcome, and what a detector observed before its tool exited nonzero is
            // exactly what turns a mute failure into a named refusal.
            Map<String, byte[]> files = new LinkedHashMap<>();
            for (String path : phase.collectFiles()) {
                try {
                    files.put(path, this.docker.getArchiveFile(handle, path, quota.artifactBytes()));
                } catch (IOException absent) {
                    // An absent output is the PHASE's result to judge, not the sandbox's:
                    // the builder kind decides whether a missing file is a refusal.
                }
            }
            Path dirTar = null;
            if (phase.collectDir() != null) {
                dirTar = Files.createTempFile("hohenheim-build-phase", ".tar");
                try {
                    this.docker.getArchiveTar(handle, phase.collectDir(), dirTar,
                        quota.artifactBytes());
                } catch (IOException absent) {
                    deleteQuietly(dirTar);
                    dirTar = null;
                }
            }
            return new PhaseOutcome(watched.ending(), watched.exitCode(), files, dirTar);
        } finally {
            try {
                this.docker.removeContainer(handle, true);
            } catch (IOException ignored) {
                // re-removed by the next build's removeIfOwnedBy
            }
        }
    }

    /**
     * Read the finished image tar out of the exited container.
     *
     * AIDEV-NOTE: a missing or oversized artifact is a FAILED build, never a successful
     * one with nothing to deploy -- {@link Outcome#succeeded()} requires the artifact, so
     * there is no path where a build reports success and the release has no image.
     */
    private @NonNull Outcome collectArtifact(@NonNull String handle, @NonNull BuildPlan plan,
                                             @NonNull BuildQuota quota,
                                             @NonNull BuildLog log, long peakDisk) {
        Path artifact = null;
        try {
            byte[] tar = this.docker.getArchiveFile(handle, plan.artifactPath(),
                quota.artifactBytes());
            artifact = Files.createTempFile("hohenheim-build-artifact", ".tar");
            Files.write(artifact, tar);
            return new Outcome(Ending.EXITED, 0, peakDisk, artifact, tar.length);
        } catch (IOException e) {
            log.line("[hohenheim] the build exited 0 but its artifact could not be read: "
                + (e.getMessage() != null ? e.getMessage() : e.toString()));
            deleteQuietly(artifact);
            return new Outcome(Ending.EXITED, 0, peakDisk, null, 0);
        }
    }

    private void kill(@NonNull String handle) {
        try {
            this.docker.stopContainer(handle, 0);
        } catch (IOException e) {
            Blast.log("BUILD: could not stop", handle, "-", e.getMessage());
        }
    }

    private @NonNull Map<String, Object> containerSpec(@NonNull BuildPlan plan,
                                                       @NonNull BuildQuota quota,
                                                       @NonNull String network,
                                                       @NonNull Map<String, String> ownerLabels) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("Image", plan.builderImage());
        spec.put("Labels", ownerLabels);
        // Entrypoint override rather than Cmd: the builder image's own entrypoint IS the
        // builder, and appending arguments to it is exactly what the plan describes.
        spec.put("Cmd", plan.command());
        List<String> env = new ArrayList<>();
        plan.env().forEach((name, value) -> env.add(name + "=" + value));
        if (!env.isEmpty()) {
            spec.put("Env", env);
        }
        spec.put("NetworkingConfig", Map.of("EndpointsConfig", Map.of(network, Map.of())));

        Map<String, Object> hostConfig = new LinkedHashMap<>();
        hostConfig.put("NetworkMode", network);
        // No Mounts and no Binds, ever: the context comes in through the archive API and
        // the artifact goes out through it. A build with a host path is not sandboxed,
        // and ContainerHardening refuses the bind anyway.
        quota.limits().applyTo(hostConfig);
        spec.put("HostConfig", hostConfig);
        return spec;
    }

    private void pushContext(@NonNull String handle, @NonNull BuildPlan plan,
                             @NonNull Path contextDir) throws IOException {
        this.docker.putArchiveFromDirectory(handle, plan.contextPath(), contextDir);
    }

    private void stageFiles(@NonNull String handle, @NonNull BuildPlan plan) throws IOException {
        if (plan.stagedFiles().isEmpty()) {
            return;
        }
        Path staging = Files.createTempDirectory("hohenheim-build-staging");
        try {
            List<String> relative = new ArrayList<>();
            for (Map.Entry<String, String> file : plan.stagedFiles().entrySet()) {
                String path = file.getKey().startsWith("/")
                    ? file.getKey().substring(1) : file.getKey();
                Path target = staging.resolve(path).normalize();
                if (path.isBlank() || !target.startsWith(staging)) {
                    throw new IOException("Refusing staged build file path '" + file.getKey() + "'");
                }
                Files.createDirectories(target.getParent());
                Files.writeString(target, file.getValue());
                relative.add(path);
            }
            this.docker.putArchiveFiles(handle, "/", staging, relative);
        } finally {
            deleteRecursively(staging);
        }
    }

    /**
     * Drain the attached stream into the log on its own thread.
     *
     * AIDEV-NOTE: draining must not stop when the log fills -- {@link BuildLog} bounds
     * memory, this thread keeps reading, and the writer on the daemon side never blocks.
     * A build that hangs because its output stopped being read would be the sandbox
     * causing the failure it is supposed to observe.
     */
    private static @NonNull Thread drainInto(@NonNull ConsoleStream stream, @NonNull BuildLog log,
                                             @NonNull String handle) {
        Thread reader = new Thread(() -> {
            ConsoleStream.Chunk chunk;
            while ((chunk = stream.next()) != null) {
                log.append(new String(chunk.data(), StandardCharsets.UTF_8));
            }
        }, "build-log-" + handle);
        reader.setDaemon(true);
        reader.start();
        return reader;
    }

    private static void joinQuietly(@NonNull Thread thread) {
        try {
            thread.join(2000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Total bytes of a directory tree; the context cap's input. */
    static long directorySize(@NonNull Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException("Build context '" + root + "' is not a directory");
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).mapToLong(file -> {
                try {
                    return Files.size(file);
                } catch (IOException unreadable) {
                    return 0;
                }
            }).sum();
        }
    }

    static void deleteQuietly(@Nullable Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best-effort temp cleanup
        }
    }

    private static void deleteRecursively(@NonNull Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort temp cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort temp cleanup
        }
    }
}
