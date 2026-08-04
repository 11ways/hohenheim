package be.elevenways.hohenheim.server.build;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;
import java.util.Map;

/**
 * What a builder kind wants the sandbox to run. The sandbox owns isolation, quotas,
 * streaming and the artifact hand-off; a builder kind owns only this.
 *
 * AIDEV-NOTE: a plan carries no HostConfig, no network, no mounts and no privileges --
 * a builder kind cannot weaken the sandbox by describing itself differently. That is the
 * whole reason the seam is a data record and not a "the builder creates its container"
 * interface.
 *
 * @param builderImage the daemonless builder image; the sandbox pulls it, the build does not
 * @param command      the builder's argv inside that image
 * @param env          builder-facing environment (build args, builder configuration)
 * @param contextPath  directory inside the container the build context is pushed into
 * @param artifactPath file inside the container the finished image tar is read from
 * @param stagedFiles  extra files pushed in before start (container path to content)
 */
public record BuildPlan(@NonNull String builderImage,
                        @NonNull List<String> command,
                        @NonNull Map<String, String> env,
                        @NonNull String contextPath,
                        @NonNull String artifactPath,
                        @NonNull Map<String, String> stagedFiles) {
}
