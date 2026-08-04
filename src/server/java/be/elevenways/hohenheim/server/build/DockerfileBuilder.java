package be.elevenways.hohenheim.server.build;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BuildOperationModel;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The shipped builder kind: a Dockerfile built by a DAEMONLESS builder inside the
 * sandbox, exporting a docker-format image tar the control plane loads afterwards.
 *
 * AIDEV-NOTE: {@code --no-push --tarPath} is the load-bearing choice. Pushing would need
 * a registry the build can reach and credentials it can hold; exporting a tar means the
 * artifact crosses the boundary as INERT DATA the control plane inspects and loads, and
 * the build never authenticates to anything of ours. The cost is that the tar is read
 * through controller memory (bounded by {@code builds.max_artifact_mb}); a streaming
 * archive read is the named follow-up, and it is a DockerClient concern, not this class's.
 */
public final class DockerfileBuilder implements Builders {

    /** Where the sandbox pushes the build context; kaniko's own default workdir. */
    static final String CONTEXT_PATH = "/workspace";

    /** Where the finished image tar lands; read back out through the archive API. */
    static final String ARTIFACT_PATH = CONTEXT_PATH + "/hohenheim-artifact.tar";

    /** Where a daemonless builder reads registry auth from. */
    static final String DOCKER_CONFIG_PATH = "/kaniko/.docker/config.json";

    @Override
    public @NonNull String kind() {
        return BuildOperationModel.KIND_DOCKERFILE;
    }

    @Override
    public @NonNull BuildPlan plan(@NonNull BuildRequest request, int buildId,
                                   @NonNull BuildLog log) throws IOException {
        String dockerfile = request.dockerfile() == null || request.dockerfile().isBlank()
            ? "Dockerfile" : request.dockerfile().trim();
        requireInsideContext(request.contextDir(), dockerfile);

        List<String> command = new ArrayList<>(List.of(
            "--context", "dir://" + CONTEXT_PATH,
            "--dockerfile", CONTEXT_PATH + "/" + dockerfile,
            "--destination", request.tag(),
            "--no-push",
            "--no-push-cache",
            "--tar-path", ARTIFACT_PATH,
            // AIDEV-NOTE: REPRODUCIBLE is load-bearing, not a nicety. Without it the
            // builder stamps a wall-clock timestamp into the image config, so two builds
            // of an IDENTICAL context produce two different digests -- and since the
            // release is pinned by digest, the site convergence would see a settings
            // change and roll the container on every routing reload. It also makes the
            // digest mean what it says: same context in, same identity out. The cost is
            // that the builder rewrites layer metadata at the end, which is slower and
            // heavier on a large image; that is the known trade-off, not a bug to fix by
            // dropping the flag.
            "--reproducible",
            "--verbosity", "info"));
        request.buildArgs().forEach((name, value) ->
            command.addAll(List.of("--build-arg", name + "=" + value)));

        Map<String, String> stagedFiles = new LinkedHashMap<>();
        BuildRequest.RegistryCredential registry = request.registry();
        if (registry != null) {
            // The password reaches the sandbox ONLY through a lease, and the lease is
            // resolved here, once, into the file the builder reads. The value is also
            // registered for redaction so it cannot come back out through the log.
            BuildCredentials.Lease lease = BuildCredentials.issue(buildId,
                BuildCredentials.Purpose.REGISTRY_AUTH, registry.password(),
                BuildCredentials.DEFAULT_TTL_MS);
            log.redact(lease.secretForRedaction());
            String config = BuildCredentials.dockerConfigJson(buildId, lease.token(),
                registry.registry(), registry.username());
            if (config == null) {
                throw new IOException("The registry credential lease of build " + buildId
                    + " did not resolve; refusing to run a build that would silently"
                    + " authenticate as nobody");
            }
            stagedFiles.put(DOCKER_CONFIG_PATH, config);
            log.line("[hohenheim] registry credential leased for "
                + (registry.registry().isBlank() ? "docker.io" : registry.registry()));
        }

        return new BuildPlan(builderImage(), List.copyOf(command), Map.of(),
            CONTEXT_PATH, ARTIFACT_PATH, stagedFiles);
    }

    /** The configured daemonless builder image. */
    static @NonNull String builderImage() {
        String configured = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Builds.BUILDER_IMAGE);
        return configured == null || configured.isBlank()
            ? "gcr.io/kaniko-project/executor:v1.23.2" : configured.trim();
    }

    /**
     * @throws IOException when the Dockerfile escapes the context or does not exist -- a
     *         named plan-time refusal instead of a builder failure nobody can read
     */
    private static void requireInsideContext(@NonNull Path contextDir, @NonNull String dockerfile)
            throws IOException {
        Path root = contextDir.toAbsolutePath().normalize();
        Path target = root.resolve(dockerfile).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("Refusing dockerfile path '" + dockerfile
                + "': it escapes the build context");
        }
        if (!Files.isRegularFile(target)) {
            throw new IOException("The build context has no '" + dockerfile + "'");
        }
    }
}
