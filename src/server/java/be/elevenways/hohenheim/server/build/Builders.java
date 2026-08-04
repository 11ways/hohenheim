package be.elevenways.hohenheim.server.build;

import be.elevenways.hohenheim.model.BuildOperationModel;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;

/**
 * The builder-kind seam: one method that turns a {@link BuildRequest} into the
 * {@link BuildPlan} the shared sandbox runs. Both kinds go through the SAME operation
 * record, the SAME sandbox, the SAME log and the SAME digest pinning -- only the plan
 * differs.
 *
 * AIDEV-NOTE: a builder kind may run a sandboxed PRE-PHASE through the {@code sandbox}
 * parameter (the nixpacks kind runs its detector there), but it still returns exactly one
 * {@link BuildPlan} and the phase time is charged against the build's own wall-clock
 * quota by {@link SandboxedBuilds} -- the phase is part of the build, not a second build.
 */
public interface Builders {

    /** @return the {@code BuildOperationModel.KIND_*} this builder answers for */
    @NonNull String kind();

    /**
     * @param sandbox the shared sandbox, for builder kinds that need a quota'd
     *                pre-phase (detection); the Dockerfile kind never touches it
     * @throws IOException when the request cannot be planned (a missing Dockerfile, an
     *                     undetectable repository, an unbuildable context) -- a named
     *                     failure, never a plan that runs and fails obscurely inside the
     *                     sandbox
     */
    @NonNull BuildPlan plan(@NonNull BuildRequest request, int buildId, @NonNull BuildLog log,
                            @NonNull BuildSandbox sandbox) throws IOException;

    /**
     * The inspectable detection record of the last {@link #plan} call, or null for
     * builder kinds that detect nothing. Recorded on the build operation even when the
     * plan REFUSED, so "what did the detector actually see" is never log archaeology.
     */
    default @Nullable String detection() {
        return null;
    }

    /**
     * Resolve a builder kind.
     *
     * @throws IOException naming the kind for an unknown one
     */
    static @NonNull Builders forKind(@NonNull String kind) throws IOException {
        if (BuildOperationModel.KIND_DOCKERFILE.equals(kind)) {
            return new DockerfileBuilder();
        }
        if (BuildOperationModel.KIND_NIXPACKS.equals(kind)) {
            return new NixpacksBuilder();
        }
        throw new IOException("Unknown builder kind '" + kind + "'");
    }
}
