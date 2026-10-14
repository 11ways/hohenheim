package be.elevenways.hohenheim.server.build;

import be.elevenways.protoblast.common.registry.Identifier;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.file.Path;
import java.util.Map;

/**
 * Everything a build is allowed to know.
 *
 * AIDEV-NOTE: there is deliberately NO member for the workload's runtime environment,
 * and that absence is the mechanism -- not a rule someone has to remember. The instance's
 * variables (its database password, its API keys, every {@code .secret().encrypted()}
 * column) have no path into a sandbox because no type between the site convergence and
 * the container spec can carry them. {@link #buildArgs} is a SEPARATE, build-time-only
 * map that a caller populates from build-time settings; if a future caller wants runtime
 * secrets at build time, it has to change this record, and that is the review this
 * separation exists to force.
 *
 * @param forModel    the owning record's model (the OwnerLabels/attribution spelling)
 * @param forId       the owning record's id
 * @param builderKind {@code BuildOperationModel.KIND_*}
 * @param contextDir  build context on the CONTROL PLANE, pushed into the sandbox through
 *                    the archive API -- never bind-mounted (a bind mount is not a boundary)
 * @param dockerfile  path of the Dockerfile inside the context, or null for "Dockerfile"
 * @param tag         human-findable name for the artifact; never its identity
 * @param buildArgs   build-time arguments; NOT the workload's runtime environment
 * @param sourceRef   commit sha or another source identity, recorded on the operation
 * @param registry    optional registry credential, leased for the build's lifetime only
 * @param quota       the CPU/memory/disk/time/PID contract this build runs under
 * @param site        what a static site build runs and serves; {@link StaticSite#NONE} for every other builder kind
 */
public record BuildRequest(@NonNull Identifier forModel, int forId,
                           @NonNull String builderKind,
                           @NonNull Path contextDir,
                           @Nullable String dockerfile,
                           @NonNull String tag,
                           @NonNull Map<String, String> buildArgs,
                           @Nullable String sourceRef,
                           @Nullable RegistryCredential registry,
                           @NonNull BuildQuota quota,
                           @NonNull StaticSite site) {

    /** A request of a builder kind that reads no static site. */
    public BuildRequest(@NonNull Identifier forModel, int forId, @NonNull String builderKind,
                        @NonNull Path contextDir, @Nullable String dockerfile, @NonNull String tag,
                        @NonNull Map<String, String> buildArgs, @Nullable String sourceRef,
                        @Nullable RegistryCredential registry, @NonNull BuildQuota quota) {
        this(forModel, forId, builderKind, contextDir, dockerfile, tag, buildArgs, sourceRef, registry, quota,
            StaticSite.NONE);
    }

    /**
     * Registry auth a build may use for base-image pulls.
     *
     * @param registry registry host ({@code ghcr.io}); blank targets Docker Hub
     */
    public record RegistryCredential(@NonNull String registry, @NonNull String username,
                                     @NonNull String password) {}

    /**
     * What a static site build runs and serves, as its record stores it (unvalidated: the static builder judges it).
     *
     * @param buildImage      the image the build command runs in; blank for the pinned default
     * @param buildCommand    the shell command that produces the output; blank when the repository holds it as is
     * @param buildDirectory  where the command runs, relative to the repository root; blank for the root
     * @param outputDirectory the folder served, relative to the repository root; blank for the root
     */
    public record StaticSite(@NonNull String buildImage, @NonNull String buildCommand,
                             @NonNull String buildDirectory, @NonNull String outputDirectory) {

        /** No static site: what every other builder kind carries. */
        public static final StaticSite NONE = new StaticSite("", "", "", "");
    }
}
