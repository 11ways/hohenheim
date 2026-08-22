package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.build.BuildQuota;
import be.elevenways.hohenheim.server.build.BuildRequest;
import be.elevenways.hohenheim.server.build.SandboxedBuilds;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.host.HostShell;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * THE runtime image ("yolk") lane: resolve the row a record runs from, and make that image
 * actually present on the host that has to start it.
 *
 * AIDEV-NOTE: there is no registry (Jelle's decision on phase-0 open question 5), so
 * "present" means BUILT HERE. The Docker variant is built through the same sandboxed build
 * lane a tenant Dockerfile rides ({@code SandboxedBuilds}) rather than through the daemon's
 * {@code /build} endpoint -- {@code DockerClient}'s docblock forbids reintroducing that
 * endpoint because it has no sandbox to add, and a repository-owned Dockerfile is not a
 * reason to open a door that stays open for everyone.
 *
 * AIDEV-NOTE: the Incus variant is the SAME image, converted. {@code docker export} of a
 * container created from the built image is a rootfs tarball, and that plus a two-line
 * metadata tarball is exactly what {@code incus image import} takes. The alternatives were
 * measured against this: an {@code oci:} remote needs a registry we deliberately do not
 * have, and distrobuilder would mean a SECOND package list to keep in step with the
 * Dockerfile -- the vocabulary duplication the yolk/egg split exists to avoid. Both live
 * hosts run Docker and Incus side by side, which is what makes the conversion local.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class RuntimeImages {

    /** Where {@code processServerResources} packs the in-repo {@code images/} tree. */
    static final String RESOURCE_ROOT = "hohenheim-images/";

    /** Long enough for an image build plus an export on a small host. */
    private static final long HOST_TIMEOUT_SECONDS = 15 * 60;

    private RuntimeImages() {
    }

    /**
     * The runtime image a record declares.
     *
     * @throws Violations {@code runtime_image_required} when the record names none,
     *         {@code runtime_image_unknown} when it names one that is gone or disabled
     */
    public static @NonNull Row requireFor(@NonNull Row instance) {

        Integer imageId = instance.get(InstanceModel.RUNTIME_IMAGE_ID);

        if (imageId == null) {
            throw Violations.ofField(InstanceModel.RUNTIME_IMAGE_ID.getName(), null,
                Microcopy.of("runtime_image_required").withFilter("scope", "violations"));
        }

        Row image = Models.get(RuntimeImageModel.class).findById(imageId);

        if (image == null || !Boolean.TRUE.equals(image.get(RuntimeImageModel.ENABLED))) {
            throw Violations.ofField(InstanceModel.RUNTIME_IMAGE_ID.getName(), imageId,
                Microcopy.of("runtime_image_unknown").withFilter("scope", "violations")
                    .withArg("id", String.valueOf(imageId)));
        }

        return image;
    }

    /**
     * The image reference for one host runtime.
     *
     * @throws Violations when the image declares no variant for that runtime -- a null
     *         {@code incus_image} is the picker's "cannot run on an Incus host", and this
     *         is the same refusal arriving at deploy time for a record that got past it
     */
    public static @NonNull String referenceFor(@NonNull Row image, @NonNull String runtime) {

        String reference = ServerModel.RUNTIME_INCUS.equals(runtime)
            ? image.get(RuntimeImageModel.INCUS_IMAGE)
            : image.get(RuntimeImageModel.DOCKER_IMAGE);

        if (reference == null || reference.isBlank()) {
            throw Violations.ofForm(Microcopy.of("runtime_image_no_variant")
                .withFilter("scope", "violations")
                .withArg("name", String.valueOf((Object) image.get(RuntimeImageModel.NAME)))
                .withArg("runtime", runtime));
        }

        return reference.trim();
    }

    /**
     * Make this runtime image present on a host, building it there if it is not.
     *
     * <p>Idempotent and cheap on the common path: an image the host already has costs one
     * inspect (Docker) or one {@code incus image info} (Incus).</p>
     *
     * @throws Violations naming the failure; a workspace must never start from an image
     *         nobody built
     */
    public static void ensurePresent(@NonNull Row image, @NonNull String serverName,
                                     @NonNull String runtime) {

        if (ServerModel.RUNTIME_INCUS.equals(runtime)) {
            ensureIncusImage(image, serverName);
        } else {
            ensureDockerImage(image, serverName);
        }
    }

    // -- docker ----------------------------------------------------------------

    /** Build the Docker variant on the host when its daemon does not have it yet. */
    private static void ensureDockerImage(@NonNull Row image, @NonNull String serverName) {

        String reference = referenceFor(image, ServerModel.RUNTIME_DOCKER);
        DockerClient docker = new ServerService().clientFor(serverName);

        try {
            docker.inspectImage(reference);
            return;
        } catch (IOException absent) {
            Blast.log("RUNTIME IMAGE:", reference, "is not on", serverName,
                "- building it from its packaged context");
        }

        Path context = materializeContext(image);

        try {
            SandboxedBuilds.Result result = new SandboxedBuilds(docker, serverName)
                .run(new BuildRequest(RuntimeImageModel.MODEL_ID, idOf(image),
                    BuildOperationModel.KIND_DOCKERFILE, context, null, reference,
                    java.util.Map.of(), null, null, BuildQuota.fromSettings()));

            if (!result.succeeded()) {
                throw Violations.ofForm(Microcopy.of("runtime_image_build_failed")
                    .withFilter("scope", "violations")
                    .withArg("name", String.valueOf((Object) image.get(RuntimeImageModel.NAME)))
                    .withArg("reason", String.valueOf(result.failureReason())));
            }
        } finally {
            deleteTree(context);
        }
    }

    // -- incus -----------------------------------------------------------------

    /**
     * Import this runtime image into the host's Incus image store, building its rootfs on
     * that same host.
     *
     * AIDEV-NOTE: entirely over {@link HostShell}, and NOT through {@code SandboxedBuilds}
     * like the Docker lane. A host that declares the incus runtime addresses no Docker
     * daemon at all ({@code ServerService.transportFor} refuses by name, deliberately, so
     * a "docker" call for an Incus host cannot land on the controller's own daemon), so
     * there is no client to hand a build to. The context is shipped to the host and built
     * with its own docker CLI instead.
     *
     * AIDEV-NOTE: that makes Docker a REQUIREMENT on an Incus host that carries workspaces,
     * and this refuses by name when it is missing rather than failing inside the import.
     * The alternative was a distrobuilder definition per image -- a SECOND package list
     * beside the Dockerfile, which is exactly the vocabulary duplication the yolk/egg split
     * exists to remove. Both live twins run Docker and Incus side by side.
     */
    private static void ensureIncusImage(@NonNull Row image, @NonNull String serverName) {

        String alias = referenceFor(image, ServerModel.RUNTIME_INCUS);
        Row server = Models.get(ServerModel.class).findByName(serverName);

        if (server == null) {
            throw Violations.ofForm(Microcopy.of("volume_host_unknown")
                .withFilter("scope", "violations").withArg("name", serverName));
        }

        HostShell shell = HostShell.forServer(server);

        HostShell.Result present = shell.run("incus image info " + HostShell.quote(alias)
            + " >/dev/null 2>&1");

        if (present.ok()) {
            return;
        }

        Blast.log("RUNTIME IMAGE:", alias, "is not in the Incus store on", serverName,
            "- converting it there");

        if (!shell.run("command -v docker >/dev/null 2>&1").ok()) {
            throw Violations.ofForm(refusal("runtime_image_no_builder", image, serverName));
        }

        String dockerReference = referenceFor(image, ServerModel.RUNTIME_DOCKER);
        String description = String.valueOf((Object) image.get(RuntimeImageModel.NAME));
        Path context = materializeContext(image);

        try {
            // One snippet, one failure mode: `set -e` makes any step's failure the script's
            // exit code, and the temp directory is removed on every path.
            String script = "set -e\n"
                + "tmp=$(mktemp -d)\n"
                + "trap 'rm -rf \"$tmp\"' EXIT\n"
                + "mkdir -p \"$tmp/ctx\"\n"
                + shipContext(context)
                + "docker build -q -t " + HostShell.quote(dockerReference) + " \"$tmp/ctx\"\n"
                + "cid=$(docker create " + HostShell.quote(dockerReference) + " /bin/true)\n"
                + "docker export \"$cid\" | gzip > \"$tmp/rootfs.tar.gz\"\n"
                + "docker rm \"$cid\" >/dev/null\n"
                + "printf 'architecture: %s\\ncreation_date: %s\\nproperties:\\n"
                + "  description: %s\\n  os: hohenheim\\n  release: runtime\\n' "
                + "\"$(uname -m)\" \"$(date +%s)\" " + HostShell.quote(description)
                + " > \"$tmp/metadata.yaml\"\n"
                + "tar -C \"$tmp\" -czf \"$tmp/metadata.tar.gz\" metadata.yaml\n"
                + "incus image import \"$tmp/metadata.tar.gz\" \"$tmp/rootfs.tar.gz\" --alias "
                + HostShell.quote(alias) + "\n";

            HostShell.Result imported = shell.run(script, HOST_TIMEOUT_SECONDS);

            if (!imported.ok()) {
                throw Violations.ofForm(Microcopy.of("runtime_image_import_failed")
                    .withFilter("scope", "violations")
                    .withArg("name", description)
                    .withArg("reason", imported.text()));
            }
        } finally {
            deleteTree(context);
        }
    }

    /**
     * The shell lines that recreate a materialized context under {@code $tmp/ctx} on the
     * host: one base64 heredoc per file, so no path, mode or byte depends on the transport.
     */
    private static @NonNull String shipContext(@NonNull Path context) {

        StringBuilder script = new StringBuilder();

        try (var walk = Files.walk(context)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String relative = context.relativize(file).toString();
                String encoded = Base64.getEncoder().encodeToString(Files.readAllBytes(file));
                script.append("mkdir -p \"$(dirname \"$tmp/ctx/")
                    .append(relative).append("\")\"\n")
                    .append("base64 -d > \"$tmp/ctx/").append(relative)
                    .append("\" <<'HOHEOF'\n").append(encoded).append("\nHOHEOF\n");
            }
        } catch (IOException unreadable) {
            throw new IllegalStateException("Unreadable runtime image context "
                + context + ": " + unreadable.getMessage(), unreadable);
        }

        return script.toString();
    }

    private static @NonNull Microcopy refusal(@NonNull String key, @NonNull Row image,
                                              @NonNull String serverName) {
        return Microcopy.of(key).withFilter("scope", "violations")
            .withArg("name", String.valueOf((Object) image.get(RuntimeImageModel.NAME)))
            .withArg("host", serverName);
    }

    // -- the packaged build context -------------------------------------------

    /**
     * Unpack this image's build context into a fresh temporary directory.
     *
     * AIDEV-NOTE: from the CLASSPATH, not from the working directory. The contexts ride
     * the server jar (build.gradle packs {@code images/} under {@code hohenheim-images/}),
     * so a deployed controller with no checkout beside it can still build; a jar cannot
     * list a directory, which is why the packaged manifest exists.
     *
     * @throws Violations {@code runtime_image_context_missing} when the image names a
     *         context nothing packaged
     */
    static @NonNull Path materializeContext(@NonNull Row image) {

        String context = image.get(RuntimeImageModel.BUILD_CONTEXT);

        if (context == null || context.isBlank()) {
            throw Violations.ofForm(contextMissing(image, "no build context declared"));
        }

        String prefix = context.trim();
        if (prefix.startsWith("images/")) {
            prefix = prefix.substring("images/".length());
        }
        if (!prefix.endsWith("/")) {
            prefix = prefix + "/";
        }

        // AIDEV-NOTE: a file at the ROOT of images/ is SHARED and lands in every context
        // (images/README.md declares the convention). That is what lets the Incus lane's
        // hohenheim-init exist once instead of once per Dockerfile, while a Docker build
        // context still cannot reach outside itself.
        List<String> entries = new ArrayList<>();
        List<String> shared = new ArrayList<>();

        for (String entry : manifest()) {
            if (entry.startsWith(prefix)) {
                entries.add(entry);
            } else if (!entry.contains("/") && !"manifest.txt".equals(entry)) {
                shared.add(entry);
            }
        }

        if (entries.isEmpty()) {
            throw Violations.ofForm(contextMissing(image, "no packaged files under " + prefix));
        }

        Path directory;

        try {
            directory = Files.createTempDirectory("hohenheim-runtime-image-");
            for (String entry : shared) {
                copyResource(entry, directory.resolve(entry));
            }
            for (String entry : entries) {
                copyResource(entry, directory.resolve(entry.substring(prefix.length())));
            }
        } catch (IOException failed) {
            throw Violations.ofForm(contextMissing(image, String.valueOf(failed.getMessage())));
        }

        return directory;
    }

    /** The packaged relative paths of every runtime-image context file. */
    static @NonNull List<String> manifest() {

        List<String> entries = new ArrayList<>();

        try (InputStream in = resource("manifest.txt")) {
            if (in == null) {
                return entries;
            }
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    entries.add(trimmed);
                }
            }
        } catch (IOException unreadable) {
            Blast.log("RUNTIME IMAGE: the packaged manifest is unreadable -",
                unreadable.getMessage());
        }

        return entries;
    }

    /** Write one packaged entry to disk, creating the parents it needs. */
    private static void copyResource(@NonNull String entry, @NonNull Path target)
            throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (InputStream in = resource(entry)) {
            if (in == null) {
                throw new IOException("packaged entry vanished: " + entry);
            }
            Files.write(target, in.readAllBytes());
        }
    }

    private static @Nullable InputStream resource(@NonNull String relative) {
        return RuntimeImages.class.getClassLoader()
            .getResourceAsStream(RESOURCE_ROOT + relative);
    }

    private static @NonNull Microcopy contextMissing(@NonNull Row image,
                                                     @NonNull String reason) {
        return Microcopy.of("runtime_image_context_missing").withFilter("scope", "violations")
            .withArg("name", String.valueOf((Object) image.get(RuntimeImageModel.NAME)))
            .withArg("reason", reason);
    }

    private static int idOf(@NonNull Row image) {
        Integer id = image.get(RuntimeImageModel.ID);
        return id == null ? 0 : id;
    }

    /** Remove a materialized context; a leftover would keep a whole image tree on disk. */
    private static void deleteTree(@NonNull Path directory) {
        try (var walk = Files.walk(directory)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    Blast.log("RUNTIME IMAGE: could not remove", path.toString());
                }
            });
        } catch (IOException unreadable) {
            Blast.log("RUNTIME IMAGE: could not clean up", directory.toString(),
                "-", unreadable.getMessage());
        }
    }
}
