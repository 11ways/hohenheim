package be.elevenways.hohenheim.server.build;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * THE build entry point: one durable operation record and one log stream, shared by
 * every builder kind, around the one sandbox.
 *
 * <p>Durability contract, the {@code InstanceInstalls} shape: the {@code running} row
 * lands BEFORE any daemon work, so a controller that dies mid-build leaves visible
 * evidence rather than a clean-looking absence; every ending -- success, non-zero exit,
 * timeout, disk kill, refusal -- stamps a terminal status with its reason and its log;
 * and the credential leases are revoked in a finally block that no path skips.</p>
 *
 * AIDEV-NOTE: a build NEVER promotes a partial artifact. Success requires all three of
 * {@code exited}, {@code exit 0} and {@code artifact read within the cap}
 * ({@link BuildSandbox.Outcome#succeeded()}), and only a succeeded build gets an
 * {@code image_id} -- which is the only column a release may pin. A build killed by a
 * quota therefore has nothing promotable by construction, not by convention.
 */
public final class SandboxedBuilds {

    private final @NonNull DockerClient docker;
    private final @NonNull WorkloadNetworkPolicy policy;

    /** Test seam: runs after the durable running stamp, before any daemon work. */
    private final @NonNull Runnable beforeSandbox;

    public SandboxedBuilds(@NonNull DockerClient docker) {
        this(docker, WorkloadNetworkPolicy.current(), () -> {});
    }

    public SandboxedBuilds(@NonNull DockerClient docker, @NonNull WorkloadNetworkPolicy policy,
                           @NonNull Runnable beforeSandbox) {
        this.docker = docker;
        this.policy = policy;
        this.beforeSandbox = beforeSandbox;
    }

    /** What a caller needs from a finished build. */
    public record Result(int buildId, boolean succeeded, @Nullable String imageId,
                         @NonNull String tag, @NonNull String status,
                         @Nullable String failureReason) {}

    /**
     * Run one build to completion and record it.
     *
     * @return the outcome; a failed build is a RESULT, not an exception -- the caller
     *         decides whether a site stays on its previous release or refuses to deploy
     */
    public @NonNull Result run(@NonNull BuildRequest request) {
        BuildQuota quota = request.quota();
        BuildLog log = new BuildLog(quota.logBytes());
        int buildId = start(request, quota);
        long startedAt = System.currentTimeMillis();
        Path artifact = null;
        Builders builder = null;
        try {
            this.beforeSandbox.run();
            builder = Builders.forKind(request.builderKind());
            BuildSandbox sandbox = new BuildSandbox(this.docker, this.policy);
            BuildPlan plan = builder.plan(request, buildId, log, sandbox);
            recordDetection(buildId, builder);
            // A builder pre-phase (detection) spends the build's OWN wall clock: the
            // image build gets what remains, so the total stays quota-bound.
            quota = quota.afterElapsed(System.currentTimeMillis() - startedAt);
            BuildSandbox.Outcome outcome = sandbox
                .run(buildId, plan, quota, request.contextDir(), log);
            artifact = outcome.artifact();
            if (!outcome.succeeded()) {
                String status = switch (outcome.ending()) {
                    case TIMED_OUT -> BuildOperationModel.STATUS_TIMED_OUT;
                    case DISK_EXCEEDED -> BuildOperationModel.STATUS_QUOTA_EXCEEDED;
                    case EXITED -> BuildOperationModel.STATUS_FAILED;
                };
                return finish(buildId, request, status, null, outcome.exitCode(),
                    reasonOf(outcome), log, outcome.peakDiskBytes(), 0, startedAt);
            }
            BuildArtifacts.Artifact loaded = BuildArtifacts.load(this.docker, artifact,
                request.tag(), outcome.artifactBytes());
            log.line("[hohenheim] artifact loaded as " + loaded.imageId());
            // Before the record names the new artifact: the predecessors this owner's
            // history still names, minus anything a container holds.
            BuildArtifacts.pruneSuperseded(this.docker, request.forModel().toString(),
                request.forId(), loaded.imageId());
            return finish(buildId, request, BuildOperationModel.STATUS_SUCCEEDED,
                loaded.imageId(), 0, null, log, outcome.peakDiskBytes(), loaded.bytes(),
                startedAt);
        } catch (IOException refused) {
            String reason = refused.getMessage() != null ? refused.getMessage() : refused.toString();
            log.line("[hohenheim] " + reason);
            // A refused detection still records what the detector SAW -- "why was this
            // refused" must never be log archaeology.
            recordDetection(buildId, builder);
            return finish(buildId, request, BuildOperationModel.STATUS_REFUSED, null, -1,
                reason, log, 0, 0, startedAt);
        } catch (RuntimeException unexpected) {
            String reason = unexpected.getMessage() != null
                ? unexpected.getMessage() : unexpected.toString();
            log.line("[hohenheim] build aborted: " + reason);
            finish(buildId, request, BuildOperationModel.STATUS_FAILED, null, -1, reason, log,
                0, 0, startedAt);
            throw unexpected;
        } finally {
            // Unconditional: a credential outliving its build is the leak this lane exists
            // to prevent, and every failure path above runs through here.
            BuildCredentials.revokeAll(buildId);
            BuildSandbox.deleteQuietly(artifact);
        }
    }

    /** Persist a builder kind's inspectable detection result onto the operation. */
    private static void recordDetection(int buildId, @Nullable Builders builder) {
        String detection = builder != null ? builder.detection() : null;
        if (detection == null) {
            return;
        }
        BuildOperationModel model = Models.get(BuildOperationModel.class);
        Row row = model.findById(buildId);
        if (row != null) {
            row.set(BuildOperationModel.DETECTION, detection);
            model.save(row);
        }
    }

    /** The durable in-flight mark, written before anything reaches the daemon. */
    private int start(@NonNull BuildRequest request, @NonNull BuildQuota quota) {
        BuildOperationModel model = Models.get(BuildOperationModel.class);
        Row row = model.createEmptyRow();
        row.set(BuildOperationModel.BUILDER_KIND, request.builderKind());
        row.set(BuildOperationModel.FOR_MODEL, request.forModel().toString());
        row.set(BuildOperationModel.FOR_ID, request.forId());
        row.set(BuildOperationModel.STATUS, BuildOperationModel.STATUS_RUNNING);
        row.set(BuildOperationModel.SOURCE_REF, request.sourceRef());
        row.set(BuildOperationModel.TAG, request.tag());
        row.set(BuildOperationModel.CPU_LIMIT, quota.cpus());
        row.set(BuildOperationModel.MEMORY_LIMIT_MB, quota.memoryMb());
        row.set(BuildOperationModel.DISK_LIMIT_MB, quota.diskLimitMb());
        row.set(BuildOperationModel.PIDS_LIMIT, quota.effectivePidsLimit());
        row.set(BuildOperationModel.TIMEOUT_SECONDS, quota.timeoutSeconds());
        row.set(BuildOperationModel.STARTED_AT, Instant.now());
        model.save(row);
        return row.get(BuildOperationModel.ID);
    }

    private @NonNull Result finish(int buildId, @NonNull BuildRequest request,
                                   @NonNull String status, @Nullable String imageId,
                                   int exitCode, @Nullable String reason, @NonNull BuildLog log,
                                   long peakDisk, long artifactBytes, long startedAt) {
        BuildOperationModel model = Models.get(BuildOperationModel.class);
        Row row = model.findById(buildId);
        if (row != null) {
            Instant finished = Instant.now();
            row.set(BuildOperationModel.STATUS, status);
            row.set(BuildOperationModel.IMAGE_ID, imageId);
            row.set(BuildOperationModel.EXIT_CODE, exitCode);
            row.set(BuildOperationModel.FAILURE_REASON, reason);
            row.set(BuildOperationModel.LOG, log.text());
            row.set(BuildOperationModel.PEAK_DISK_BYTES, peakDisk);
            row.set(BuildOperationModel.ARTIFACT_BYTES, artifactBytes);
            row.set(BuildOperationModel.FINISHED_AT, finished);
            row.set(BuildOperationModel.DURATION_MS,
                (int) (finished.toEpochMilli() - startedAt));
            model.save(row);
            prune(model, request);
        }
        boolean succeeded = BuildOperationModel.STATUS_SUCCEEDED.equals(status);
        if (!succeeded) {
            Blast.log("BUILD:", buildId, "for", request.forModel() + " #" + request.forId(),
                "ended", status, "-", reason != null ? reason : "exit " + exitCode);
        }
        return new Result(buildId, succeeded, imageId, request.tag(), status, reason);
    }

    private static @Nullable String reasonOf(BuildSandbox.@NonNull Outcome outcome) {
        return switch (outcome.ending()) {
            case TIMED_OUT -> "The build exceeded its wall-clock quota and was killed";
            case DISK_EXCEEDED -> "The build exceeded its disk quota (peak "
                + outcome.peakDiskBytes() + " bytes) and was killed";
            case EXITED -> outcome.exitCode() == 0
                ? "The build exited 0 but produced no readable artifact"
                : "The build exited " + outcome.exitCode();
        };
    }

    /** Keep the newest N operations per owning record; older rows go. */
    private static void prune(@NonNull BuildOperationModel model, @NonNull BuildRequest request) {
        Integer keep = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Builds.HISTORY_PER_OWNER);
        int limit = keep != null && keep > 0 ? keep : 50;
        List<Row> stale = model.find()
            .where(BuildOperationModel.FOR_MODEL.eq(request.forModel().toString()))
            .where(BuildOperationModel.FOR_ID.eq(request.forId()))
            .orderBy(BuildOperationModel.ID, SortOrder.DESC)
            .offset(limit)
            .limit(1000)
            .all();
        for (Row old : stale) {
            model.delete(old.get(BuildOperationModel.ID));
        }
    }
}
