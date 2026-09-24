package be.elevenways.hohenheim.server.docker;

/**
 * The images the controller itself runs on a daemon (probes, not workloads), each pinned by tag AND digest.
 *
 * AIDEV-NOTE: a floating tag here means the image a host's admission verdict rests on can change
 * between two preflight runs with no commit. The compose-style {@code repo:tag@digest} spelling is
 * what {@link DockerClient#ensureImage} pulls BY DIGEST (the tag is informational), so a host
 * holds the image as a RepoDigests entry and not necessarily under the tag; nothing may look
 * one of these up by tag. Bump both halves together, never the tag alone.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class PinnedImages {

    /** Alpine 3.24.1, pinned by the digest of its multi-arch index: the Docker preflight's probe container. */
    public static final String ALPINE =
        "alpine:3.24.1@sha256:28bd5fe8b56d1bd048e5babf5b10710ebe0bae67db86916198a6eec434943f8b";

    private PinnedImages() {
    }
}
