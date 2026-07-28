package be.elevenways.hohenheim.server.docker;

import be.elevenways.protoblast.common.Blast;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reclaims disk on one Docker daemon by removing images nothing can reach any more:
 * every pull that supersedes a tag or a digest leaves the previous image behind
 * forever, which is how a long-lived host fills up.
 *
 * Reclaim is ATTRIBUTION-based, because Hohenheim shares its daemon. The input is
 * the set of image references the managed stacks DECLARE. An image is removed only
 * when every reference it carries -- tags and repo digests both -- names one of
 * those repositories, while the image itself is not what any declared reference
 * resolves to. That is precisely the previous pin of a re-pinned image. One
 * reference into a repository we do not manage makes the image someone else's, and
 * it stays.
 *
 * An image with NO reference left (a locally built layer, or on some daemon
 * versions a re-pulled tag's predecessor) cannot be attributed to anyone, so it
 * is only reclaimed when the operator opts in: it may just as well be an external
 * build's. That opt-in is what {@code docker image prune} does unconditionally.
 *
 * Three guards on top: an image referenced by ANY container (running or stopped)
 * is never removed, an image younger than the age guard is never removed (a
 * concurrent deploy has just pulled it and has not created its container yet),
 * and a failure to remove one image never aborts the sweep -- a layer held by
 * something the daemon knows about and we do not is a skip, not an incident.
 *
 * Volumes are deliberately NOT reclaimed here. An unreferenced volume is the one
 * Docker resource whose removal destroys data that cannot be re-fetched, so it
 * stays an explicit per-stack operator action (StackRuntime.purgeVolumes).
 */
public final class DockerReclaim {

    /** What one sweep removed. */
    public record Outcome(int removed, long bytes, int skipped) {

        public static final Outcome EMPTY = new Outcome(0, 0, 0);

        public @NonNull Outcome plus(@NonNull Outcome other) {
            return new Outcome(this.removed + other.removed,
                this.bytes + other.bytes,
                this.skipped + other.skipped);
        }

        /** @return the reclaimed size in whole mebibytes, for operator-facing copy */
        public long megabytes() {
            return this.bytes / (1024L * 1024L);
        }
    }

    private final DockerClient docker;
    private final Duration minimumAge;
    private final boolean includeUnattributed;

    /**
     * @param minimumAge images created more recently than this are never removed
     * @param includeUnattributed also remove images carrying no reference at all,
     *        which cannot be proven to be ours
     */
    public DockerReclaim(@NonNull DockerClient docker, @NonNull Duration minimumAge,
                         boolean includeUnattributed) {
        this.docker = docker;
        this.minimumAge = minimumAge;
        this.includeUnattributed = includeUnattributed;
    }

    /**
     * Remove reclaimable images.
     *
     * @param declaredReferences every image reference managed stacks declare on this
     *        daemon ({@code repo:tag} or {@code repo@sha256:...}); their repositories
     *        are what reclaim may touch, and the images they resolve to are kept
     * @param now the clock reading the age guard compares against
     * @throws IOException when the daemon cannot be listed at all -- a sweep that
     *         cannot see the truth removes nothing rather than guessing
     */
    public @NonNull Outcome reclaimImages(@NonNull Set<String> declaredReferences,
                                          @NonNull Instant now) throws IOException {
        List<Map<String, Object>> images = imageSummaries();
        Set<String> inUse = imageIdsInUse();
        Set<String> managedRepositories = repositoriesOf(declaredReferences);
        Set<String> current = declaredImageIds(images, declaredReferences);

        int removed = 0;
        int skipped = 0;
        long bytes = 0;

        for (Map<String, Object> image : images) {
            String id = stringOf(image.get("Id"));
            if (id == null || inUse.contains(id)) {
                continue;
            }
            if (!isReclaimable(image, id, managedRepositories, current, this.includeUnattributed)) {
                continue;
            }
            if (isTooYoung(image, now)) {
                Blast.log("DOCKER RECLAIM: keeping", shortId(id), "- younger than the age guard");
                skipped++;
                continue;
            }

            long size = longOf(image.get("Size"));
            try {
                docker.removeImage(id, false);
                removed++;
                bytes += size;
                Blast.log("DOCKER RECLAIM: removed image", shortId(id), "(" + size / (1024L * 1024L) + " MiB)");
            } catch (IOException e) {
                // A layer still shared with something the daemon knows about, or a
                // race with a pull: skip it and keep sweeping.
                Blast.log("DOCKER RECLAIM: could not remove image", shortId(id), "-", e.getMessage());
                skipped++;
            }
        }

        return new Outcome(removed, bytes, skipped);
    }

    /**
     * The decision, pure so the whole matrix is unit-testable: reclaim an image
     * whose every reference names a managed repository while no DECLARED reference
     * resolves to it, or an unreferenced image when the caller opted into those.
     *
     * @param references every tag and repo digest the image carries
     * @param isDeclared whether a declared reference resolves to this image
     */
    public static boolean isReclaimable(@NonNull List<String> references,
                                        boolean isDeclared,
                                        @NonNull Set<String> managedRepositories,
                                        boolean includeUnattributed) {
        if (references.isEmpty()) {
            return includeUnattributed;
        }
        if (isDeclared) {
            return false;
        }
        // Every reference must sit inside a repository we manage: one pointing into
        // someone else's repository makes this image theirs as well as ours.
        for (String reference : references) {
            if (!managedRepositories.contains(repositoryOf(reference))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isReclaimable(@NonNull Map<String, Object> image,
                                         @NonNull String id,
                                         @NonNull Set<String> managedRepositories,
                                         @NonNull Set<String> current,
                                         boolean includeUnattributed) {
        return isReclaimable(references(image), current.contains(id),
            managedRepositories, includeUnattributed);
    }

    /**
     * The image ids the DECLARED references resolve to locally -- the images the
     * stacks are actually running or about to run, whatever else sits in their
     * repositories.
     */
    private static @NonNull Set<String> declaredImageIds(@NonNull List<Map<String, Object>> images,
                                                         @NonNull Set<String> declaredReferences) {
        Set<String> declared = new HashSet<>();
        for (Map<String, Object> image : images) {
            String id = stringOf(image.get("Id"));
            if (id == null) {
                continue;
            }
            for (String reference : references(image)) {
                if (declaredReferences.contains(reference)
                    || declaredReferences.contains(withoutImpliedLatest(reference))) {
                    declared.add(id);
                    break;
                }
            }
        }
        return declared;
    }

    /** The repositories the declared references live in. */
    public static @NonNull Set<String> repositoriesOf(@NonNull Set<String> references) {
        Set<String> repositories = new LinkedHashSet<>();
        for (String reference : references) {
            repositories.add(repositoryOf(reference));
        }
        return repositories;
    }

    /**
     * {@code repo:latest} also answers to the bare {@code repo} a stack may declare:
     * Docker implies the latest tag, so both spellings name one reference.
     */
    private static @NonNull String withoutImpliedLatest(@NonNull String reference) {
        return reference.endsWith(":latest")
            ? reference.substring(0, reference.length() - ":latest".length())
            : reference;
    }

    /** Every image id a container references, running or stopped. */
    private @NonNull Set<String> imageIdsInUse() throws IOException {
        Set<String> inUse = new LinkedHashSet<>();
        for (Object entry : this.docker.listContainers(true)) {
            if (!(entry instanceof Map<?, ?> summary)) {
                continue;
            }
            String imageId = stringOf(summary.get("ImageID"));
            if (imageId != null) {
                inUse.add(imageId);
            }
        }
        return inUse;
    }

    @SuppressWarnings("unchecked")
    private @NonNull List<Map<String, Object>> imageSummaries() throws IOException {
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (Object entry : this.docker.listImages()) {
            if (entry instanceof Map<?, ?> image) {
                summaries.add((Map<String, Object>) image);
            }
        }
        return summaries;
    }

    private boolean isTooYoung(@NonNull Map<String, Object> image, @NonNull Instant now) {
        long created = longOf(image.get("Created"));
        if (created <= 0) {
            // No creation stamp: treat as young, because the guard exists to protect
            // an image a concurrent deploy just pulled.
            return true;
        }
        return Instant.ofEpochSecond(created).isAfter(now.minus(this.minimumAge));
    }

    /**
     * The repository half of a {@code repo:tag} reference, stripping a tag but not a
     * registry port ({@code registry:5000/app} has no tag).
     */
    public static @NonNull String repositoryOf(@NonNull String reference) {
        int digest = reference.indexOf('@');
        String withoutDigest = digest >= 0 ? reference.substring(0, digest) : reference;
        int colon = withoutDigest.lastIndexOf(':');
        if (colon < 0 || withoutDigest.indexOf('/', colon) >= 0) {
            return withoutDigest;
        }
        return withoutDigest.substring(0, colon);
    }

    /** Every tag and repo digest the image carries, placeholders dropped. */
    private static @NonNull List<String> references(@NonNull Map<String, Object> image) {
        List<String> references = new ArrayList<>();
        collectReferences(image.get("RepoTags"), references);
        collectReferences(image.get("RepoDigests"), references);
        return references;
    }

    private static void collectReferences(@Nullable Object raw, @NonNull List<String> into) {
        if (!(raw instanceof List<?> entries)) {
            return;
        }
        for (Object entry : entries) {
            String reference = stringOf(entry);
            // The daemon reports an untagged image's list as ["<none>:<none>"] on
            // some versions and as null/[] on others.
            if (reference != null && !reference.startsWith("<none>")) {
                into.add(reference);
            }
        }
    }

    private static @Nullable String stringOf(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static long longOf(@Nullable Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static @NonNull String shortId(@NonNull String id) {
        String bare = id.startsWith("sha256:") ? id.substring("sha256:".length()) : id;
        return bare.length() > 12 ? bare.substring(0, 12) : bare;
    }
}
