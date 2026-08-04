package be.elevenways.hohenheim.server.build;

import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.DockerReclaim;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The artifact boundary: an inert image tar produced by a sandbox becomes an image on
 * the daemon, IDENTIFIED BY ITS DIGEST.
 *
 * AIDEV-NOTE: the digest -- the content-addressed image ID, {@code sha256:...} over the
 * image config -- is the only identity a release may be pinned to, and this class exists
 * to make that the only thing it CAN return. A tag is a mutable pointer: retagging
 * {@code myapp:latest} at the daemon changes what that string means without changing one
 * byte of any record, so a release pinned to a tag runs whatever last claimed the name.
 * The tag is applied anyway, because a human needs to find the artifact -- but it is a
 * LABEL, never the identity, and {@code SiteContainerKind} deploys the id.
 */
public final class BuildArtifacts {

    private BuildArtifacts() {}

    /** A loaded artifact: its digest, and the tag a human can find it under. */
    public record Artifact(@NonNull String imageId, @NonNull String tag, long bytes) {}

    /**
     * Load a build's image tar into the daemon and resolve its digest.
     *
     * @throws IOException when the daemon refuses the load, or when the loaded image
     *                     cannot be resolved to a digest -- an unidentifiable artifact is
     *                     NOT deployable, and saying so is the point
     */
    public static @NonNull Artifact load(@NonNull DockerClient docker, @NonNull Path imageTar,
                                         @NonNull String tag, long bytes) throws IOException {
        docker.loadImage(imageTar);
        String imageId = digestOf(docker, tag);
        if (imageId == null) {
            throw new IOException("The daemon loaded the build artifact but no image carries"
                + " the tag '" + tag + "'; an artifact without a resolvable digest cannot be"
                + " deployed and this build does not report success");
        }
        return new Artifact(imageId, tag, bytes);
    }

    /**
     * Remove the artifacts of an owner's SUPERSEDED builds.
     *
     * AIDEV-NOTE: this wave PRODUCES IMAGES, which makes it the most disk-hungry thing in
     * the product, and without this every build would leave its predecessor behind
     * forever. It is deliberately NOT a second reclaim mechanism: {@link DockerReclaim} is
     * scoped to stack-declared references and knows nothing about build artifacts, so
     * rather than widening that authority, this removes exactly what THIS owner's own
     * build history names and nothing else. An image a container still uses makes the
     * daemon refuse (409), which is treated as "keep" -- the running release is never
     * reclaimed out from under itself.
     *
     * @param keepImageId the just-built artifact, never removed
     */
    public static void pruneSuperseded(@NonNull DockerClient docker, @NonNull String forModel,
                                       int forId, @NonNull String keepImageId) {
        for (Row build : Models.get(BuildOperationModel.class).findForOwner(forModel, forId, 200)) {
            String imageId = build.get(BuildOperationModel.IMAGE_ID);
            if (imageId == null || imageId.isBlank() || imageId.equals(keepImageId)) {
                continue;
            }
            try {
                docker.removeImage(imageId, false);
                Blast.log("BUILD: reclaimed superseded artifact", imageId.substring(0,
                    Math.min(19, imageId.length())));
            } catch (IOException stillReferenced) {
                // In use by a container, or already gone: both are "keep", both are quiet.
            }
        }
    }

    /**
     * Resolve a local image reference to its content-addressed id.
     *
     * @return {@code sha256:...}, or null when nothing local carries the reference
     */
    public static String digestOf(@NonNull DockerClient docker, @NonNull String reference)
            throws IOException {
        for (Object entry : docker.listImages()) {
            if (!(entry instanceof Map<?, ?> image)) {
                continue;
            }
            if (image.get("RepoTags") instanceof List<?> tags && tags.contains(reference)
                    && image.get("Id") instanceof String id) {
                return id;
            }
        }
        return null;
    }
}
