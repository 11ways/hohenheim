package be.elevenways.hohenheim.server.application;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.server.instance.DeployStartPolicy;
import be.elevenways.hohenheim.server.instance.DeployTrigger;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * THE deploy verb for an application whose artifact is BUILT ELSEWHERE and uploaded:
 * stage the artifact as a build context, then converge a release exactly like a
 * git-sourced one.
 *
 * AIDEV-NOTE: this deliberately adds NO builder kind and NO release lane. A release is
 * already "build whatever is in {@code build_context} and pin the digest"
 * ({@link ApplicationReleases#desiredSettings}), and {@link ApplicationDeploys} is
 * already "produce that directory, then converge". The only thing an artifact deploy
 * does differently is HOW the directory comes to exist: a git deploy checks a repository
 * out, this one writes the uploaded file plus a two-line Dockerfile. Everything after
 * that -- the sandbox, the reproducible build, the digest pin, the health-gated swap and
 * the retained previous release -- is unchanged.
 *
 * AIDEV-NOTE: the sandbox therefore never compiles anything. It copies one file onto a
 * runtime image, which is the point: the toolchain that produced the artifact (a Zenit
 * chain spanning six repositories that resolve through mavenLocal) cannot exist inside a
 * build sandbox, and reproducing it there would be rebuilding the world on every deploy.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class ArtifactDeploys {

    /** The artifact's name inside the build context AND inside the image. */
    static final String ARTIFACT_NAME = "app.jar";

    /** Generated, never authored: an uploaded artifact has no repository to carry one. */
    static final String DOCKERFILE = "Dockerfile";

    /** The provenance every Zenit artifact carries; read for the log, never trusted as identity. */
    static final String BUILD_STAMP = "META-INF/blast/build-info.tsv";

    /**
     * A fixed staged mtime, so two uploads of an IDENTICAL artifact produce an identical
     * build context and therefore an identical digest. Kaniko's {@code --reproducible}
     * already rewrites layer metadata; this keeps the input side honest too.
     */
    private static final FileTime STAGED_AT = FileTime.fromMillis(0);

    private ArtifactDeploys() {
    }

    /** Where an application's uploaded artifact is staged; the {@code checkouts} sibling. */
    public static @NonNull File directoryFor(int applicationId) {
        String dataPath = HohenheimSettings.VALUES.getValue(HohenheimSettings.Storage.DATA_PATH);
        String base = dataPath == null || dataPath.isBlank() ? "/opt/hohenheim/data" : dataPath;
        return new File(new File(new File(base, "artifacts"), InstanceModel.MODEL_ID.getPath()),
            String.valueOf(applicationId));
    }

    /**
     * Deploy an uploaded artifact: stage it as a build context, then converge a release.
     *
     * @param  artifact a readable file on the CONTROL PLANE; the caller owns its lifetime
     * @return the release that ends up serving
     * @throws Violations naming the refusal (declined start, unusable artifact, no runtime
     *         image, staging, build, probe)
     */
    public static ApplicationReleases.@NonNull Release deploy(int applicationId,
                                                              @NonNull Path artifact,
                                                              @NonNull DeployTrigger trigger) {

        Row application = ApplicationReleases.requireApplication(applicationId);
        Map<String, Object> settings = ApplicationReleases.storedSettings(application);

        // The SAME policy gate a git deploy runs, on the STORED status of the release the
        // operator's stop settled on, and before any work is spent.
        Microcopy declined = DeployStartPolicy.declineToStartStored(trigger,
            ApplicationReleases.ownedServing(applicationId), application);
        if (declined != null) {
            throw Violations.ofForm(declined);
        }

        if (!Files.isRegularFile(artifact)) {
            throw Violations.ofForm(refusal("artifact_unreadable", String.valueOf(artifact)));
        }

        String baseImage = runtimeImageOf(application);
        String digest = digestOf(artifact);
        File context = directoryFor(applicationId);

        try {
            stage(artifact, context, baseImage);
        } catch (IOException failed) {
            throw Violations.ofForm(refusal("artifact_staging_failed",
                String.valueOf(failed.getMessage())));
        }

        Blast.log("Artifact deploy of application", applicationId, "on", baseImage,
            "sha256:" + digest, describeStamp(artifact));

        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("build_context", context.getAbsolutePath());
        overrides.put("dockerfile", DOCKERFILE);
        // Set EXPLICITLY: a stored `builder` of nixpacks would send its detector at a
        // directory holding one jar and a generated Dockerfile, and refuse.
        overrides.put("builder", BuildOperationModel.KIND_DOCKERFILE);
        // The artifact IS the source identity here. BuildRequest.sourceRef documents this
        // as "commit sha or another source identity"; inventing a commit for a jar built
        // from six repositories would be a guess, and the digest is exact.
        overrides.put("commit_sha", digest);

        ApplicationReleases.Release release =
            ApplicationReleases.converge(applicationId, overrides);
        ActivityLog.record(Models.get(InstanceModel.class), applicationId,
            InstanceService.ACTIVITY_DEPLOY_ACTION, trigger.word());
        return release;
    }

    // -- staging --------------------------------------------------------------

    /** Replaces the context wholesale, so a previous artifact never survives into a build. */
    static void stage(@NonNull Path artifact, @NonNull File context, @NonNull String baseImage)
            throws IOException {
        Path dir = context.toPath();
        Files.createDirectories(dir);

        Path staged = dir.resolve(ARTIFACT_NAME);
        Files.copy(artifact, staged, StandardCopyOption.REPLACE_EXISTING);
        Files.setPosixFilePermissions(staged, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));

        Path dockerfile = dir.resolve(DOCKERFILE);
        Files.writeString(dockerfile, dockerfileFor(baseImage), StandardCharsets.UTF_8);

        Files.setLastModifiedTime(staged, STAGED_AT);
        Files.setLastModifiedTime(dockerfile, STAGED_AT);
    }

    /**
     * The whole build: one COPY onto the runtime image.
     *
     * The runtime image already declares the workdir, the tini entrypoint and the default
     * command ({@code java -jar app.jar}), so there is nothing else to say here -- and
     * saying it again would be a second declaration of the same fact.
     */
    static @NonNull String dockerfileFor(@NonNull String baseImage) {
        return "# Generated by Hohenheim for an uploaded artifact. Do not edit: rewritten per deploy.\n"
            + "FROM " + baseImage + "\n"
            + "COPY " + ARTIFACT_NAME + " /home/site/" + ARTIFACT_NAME + "\n";
    }

    /** @throws Violations when the application names no runtime image to copy the artifact onto */
    static @NonNull String runtimeImageOf(@NonNull Row application) {
        Integer imageId = application.get(InstanceModel.RUNTIME_IMAGE_ID);
        Row image = imageId == null ? null
            : Models.get(RuntimeImageModel.class).find().where(RuntimeImageModel.ID.eq(imageId)).first();
        String docker = image == null ? null : image.get(RuntimeImageModel.DOCKER_IMAGE);

        if (docker == null || docker.isBlank()) {
            throw Violations.ofForm(refusal("artifact_no_runtime_image",
                String.valueOf((Object) application.get(InstanceModel.NAME))));
        }
        return docker.trim();
    }

    // -- provenance -----------------------------------------------------------

    /** SHA-256 of the artifact: its exact, reproducible identity. */
    static @NonNull String digestOf(@NonNull Path artifact) {
        try (InputStream in = Files.newInputStream(artifact)) {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) > 0) {
                sha.update(buffer, 0, read);
            }
            StringBuilder out = new StringBuilder();
            for (byte b : sha.digest()) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (Exception failed) {
            throw Violations.ofForm(refusal("artifact_unreadable",
                String.valueOf(failed.getMessage())));
        }
    }

    /**
     * A one-line human summary of the artifact's build stamp for the deploy log.
     *
     * AIDEV-NOTE: NEVER the release identity. The stamp names every repository the jar was
     * built from, so there is no single commit to promote, and a reader that picked one
     * would be asserting something the artifact does not claim.
     */
    static @NonNull String describeStamp(@NonNull Path artifact) {
        try (ZipFile jar = new ZipFile(artifact.toFile())) {
            ZipEntry entry = jar.getEntry(BUILD_STAMP);
            if (entry == null) {
                return "(no build stamp)";
            }
            try (InputStream in = jar.getInputStream(entry)) {
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                long repos = text.lines().filter(line -> !line.isBlank()).count();
                boolean dirty = text.lines().anyMatch(line -> line.contains("\ttrue\t"));
                return repos + " repos" + (dirty ? ", DIRTY" : ", clean");
            }
        } catch (IOException unreadable) {
            return "(build stamp unreadable)";
        }
    }

    private static @NonNull Microcopy refusal(@NonNull String key, @Nullable String reason) {
        return Microcopy.of(key).withFilter("scope", "violations")
            .withArg("reason", String.valueOf(reason));
    }
}
