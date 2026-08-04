package be.elevenways.hohenheim.server.build;

import be.elevenways.hohenheim.model.BuildOperationModel;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;

/**
 * The builder-kind seam: one method that turns a {@link BuildRequest} into the
 * {@link BuildPlan} the shared sandbox runs. Both kinds the plan names go through the
 * SAME operation record, the SAME sandbox, the SAME log and the SAME digest pinning --
 * only the plan differs.
 */
public interface Builders {

    /** @return the {@code BuildOperationModel.KIND_*} this builder answers for */
    @NonNull String kind();

    /**
     * @throws IOException when the request cannot be planned (a missing Dockerfile, an
     *                     unbuildable context) -- a named failure, never a plan that runs
     *                     and fails obscurely inside the sandbox
     */
    @NonNull BuildPlan plan(@NonNull BuildRequest request, int buildId, @NonNull BuildLog log)
            throws IOException;

    /**
     * Resolve a builder kind.
     *
     * @throws IOException naming the kind for an unknown one, and naming the REMAINING
     *                     WORK for the declared-but-unimplemented Nixpacks kind
     */
    static @NonNull Builders forKind(@NonNull String kind) throws IOException {
        if (BuildOperationModel.KIND_DOCKERFILE.equals(kind)) {
            return new DockerfileBuilder();
        }
        if (BuildOperationModel.KIND_NIXPACKS.equals(kind)) {
            // AIDEV-NOTE: DECLARED, not implemented, and the path is named rather than
            // hand-waved. Nixpacks/buildpacks detect a language and EMIT a Dockerfile; the
            // work is a plan phase that runs the detector in this same sandbox over the
            // same context, writes its Dockerfile into that context, and then returns the
            // DockerfileBuilder's plan verbatim. Nothing below this interface changes:
            // not the operation record, not the sandbox, not the log, not the digest
            // pinning. What is missing is the detector image and its own quota'd phase.
            throw new IOException("The nixpacks builder kind is declared but not implemented."
                + " Its named path is a detection phase running in this same sandbox that"
                + " emits a Dockerfile into the build context, after which the dockerfile"
                + " builder runs unchanged; use the dockerfile kind until it ships.");
        }
        throw new IOException("Unknown builder kind '" + kind + "'");
    }
}
