package be.elevenways.hohenheim.server.docker;

/**
 * The images Hohenheim itself names on a daemon or in a build it generates (probes, a static site's server and its
 * default build stage), each pinned by tag AND digest.
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

    /** static-web-server 2.44.1, pinned by its multi-arch index: what a static site's release serves its output with. */
    public static final String STATIC_WEB_SERVER =
        "joseluisq/static-web-server:2.44.1@sha256:94618e3d17802265a21926217e6f9e7832b311b6fbf5933684001739fafed06a";

    /** Node.js 22.20.0 on Alpine 3.22, pinned by its multi-arch index: a static site's build stage unless it names one. */
    public static final String NODE_BUILD =
        "node:22.20.0-alpine3.22@sha256:dbcedd8aeab47fbc0f4dd4bffa55b7c3c729a707875968d467aaaea42d6225af";

    private PinnedImages() {
    }
}
