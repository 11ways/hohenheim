package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.DockerReclaim;
import be.elevenways.hohenheim.test.live.LiveLane;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Disk reclaim: the decision matrix as pure logic, then the same rules driving a
 * real Docker daemon.
 *
 * @author Jelle De Loecker
 * @since  0.2.0
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
class DockerReclaimTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String BASE_IMAGE = "alpine:latest";
    // Built the same way production builds it, so the set is hub-normalized.
    private static final Set<String> MANAGED =
        DockerReclaim.repositoriesOf(Set.of("ghcr.io/org/app", "alpine"));
    private static final String DIGEST = "sha256:" + "a".repeat(64);

    @Test
    void repositoryOfStripsTagsAndDigestsButNotRegistryPorts() {
        assertThat(DockerReclaim.repositoryOf("alpine:latest")).isEqualTo("alpine");
        assertThat(DockerReclaim.repositoryOf("alpine")).isEqualTo("alpine");
        assertThat(DockerReclaim.repositoryOf("ghcr.io/org/app:1.2.3")).isEqualTo("ghcr.io/org/app");
        // A colon inside the registry host is a PORT, not a tag separator.
        assertThat(DockerReclaim.repositoryOf("registry:5000/app")).isEqualTo("registry:5000/app");
        assertThat(DockerReclaim.repositoryOf("registry:5000/app:v2")).isEqualTo("registry:5000/app");
        // A digest pin addresses the same repository as its tagged form.
        assertThat(DockerReclaim.repositoryOf("org/app@sha256:" + "0".repeat(64))).isEqualTo("org/app");
    }

    /**
     * One comparable spelling per reference: the daemon says "nginx:1.27" where a
     * stack may declare "docker.io/library/nginx:1.27", and attribution must see
     * those as the same thing.
     */
    @Test
    void canonicalReferenceNormalizesEverySpellingToOneForm() {
        assertThat(DockerReclaim.canonicalReference("nginx:1.27"))
            .isEqualTo(DockerReclaim.canonicalReference("docker.io/library/nginx:1.27"))
            .isEqualTo(DockerReclaim.canonicalReference("library/nginx:1.27"));
        // Docker implies the latest tag: the bare repo and :latest are one reference.
        assertThat(DockerReclaim.canonicalReference("alpine"))
            .isEqualTo(DockerReclaim.canonicalReference("alpine:latest"));
        // A compose-style pin (repo:tag@digest) canonicalizes to its digest form,
        // which is how the daemon stores it in RepoDigests.
        assertThat(DockerReclaim.canonicalReference("org/app:1.2@" + DIGEST))
            .isEqualTo(DockerReclaim.canonicalReference("org/app@" + DIGEST));
        // A registry port is never mistaken for a tag, and non-hub repos keep their host.
        assertThat(DockerReclaim.canonicalReference("registry:5000/app"))
            .isEqualTo("registry:5000/app:latest");
    }

    /**
     * The whole decision matrix. Attribution is the rule: an image is ours only
     * when EVERY reference it carries names a managed repository, and it is kept
     * whenever a DECLARED reference still resolves to it.
     */
    @Test
    void reclaimsOnlyWhatItCanAttributeToAManagedRepository() {
        // Inside a managed repository but no declared reference resolves to it:
        // the previous pin of a re-pinned image.
        assertThat(DockerReclaim.isReclaimable(
            List.of("ghcr.io/org/app:1.0"), false, MANAGED, false)).isTrue();

        // A declared reference still resolves to it, so it stays even though its
        // repository is managed.
        assertThat(DockerReclaim.isReclaimable(
            List.of("ghcr.io/org/app:2.0"), true, MANAGED, false)).isFalse();

        // A digest-only reference attributes just as well as a tag: this is what a
        // superseded digest PIN looks like once the stack pinned a newer one.
        assertThat(DockerReclaim.isReclaimable(
            List.of("ghcr.io/org/app@" + DIGEST), false, MANAGED, false)).isTrue();

        // One reference outside the managed set makes the image someone else's,
        // however many managed references it also carries.
        assertThat(DockerReclaim.isReclaimable(
            List.of("ghcr.io/org/app:1.0", "someone/else:1.0"), false, MANAGED, false)).isFalse();
        assertThat(DockerReclaim.isReclaimable(
            List.of("someone/else:1.0"), false, MANAGED, false)).isFalse();

        // No reference at all cannot be attributed to anyone: kept by default,
        // swept only when the operator opts in.
        assertThat(DockerReclaim.isReclaimable(List.of(), false, MANAGED, false)).isFalse();
        assertThat(DockerReclaim.isReclaimable(List.of(), false, MANAGED, true)).isTrue();

        // Spelling never decides attribution: the hub-prefixed form of a managed
        // repository is the same repository.
        assertThat(DockerReclaim.isReclaimable(
            List.of("docker.io/library/alpine:3.19"), false, MANAGED, false)).isTrue();
    }

    /**
     * Journey against a real daemon: two versions in one repository of which the
     * stacks declare only the newer, and proof that the in-use,
     * unmanaged-repository and age guards each keep an image alive.
     *
     * AIDEV-NOTE: the sweep runs with includeUnattributed=false throughout, which
     * is also what makes it safe to run on a developer's machine -- the opt-in
     * mode would remove unrelated build leftovers, and its decision is pinned by
     * the matrix test above instead.
     */
    @Test
    void reclaimsSupersededImagesOnARealDaemon() throws IOException {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, BASE_IMAGE);

        String repository = "hohenheim-reclaim-test-" + System.nanoTime();
        String supersededTag = repository + ":one";
        String currentTag = repository + ":two";
        String unmanagedTag = repository + "-unmanaged:one";
        // Only the newer tag is DECLARED, exactly as a stack that re-pinned its
        // image would have it.
        Set<String> declared = new LinkedHashSet<>(List.of(currentTag));
        Path context = Files.createTempDirectory("hohenheim-reclaim-test");
        String containerId = null;

        try {
            // 1. Two versions in the declared repository, plus one in a repository
            // nobody manages. Both keep a reference, so both are attributable --
            // this is the shape a re-pin leaves behind, not a nameless leftover.
            String superseded = build(docker, context, supersededTag, "one");
            String current = build(docker, context, currentTag, "two");
            String unmanaged = build(docker, context, unmanagedTag, "unmanaged");
            assertThat(superseded).as("each build is its own image").isNotEqualTo(current);

            // 2. The age guard alone keeps everything: these images are seconds old
            // (a BUILD stamps Created with the build time, so the guard is real here).
            DockerReclaim.Outcome guarded = new DockerReclaim(docker, Duration.ofHours(1), false, null)
                .reclaimImages(declared, Instant.now());
            assertThat(guarded.removed()).as("nothing may be removed under the age guard").isZero();
            assertThat(guarded.skipped()).as("the guard reports what it kept").isPositive();
            assertThat(imageIds(docker)).contains(superseded, current, unmanaged);

            // 3. A container pins the older image, so even unreferenced it stays.
            containerId = docker.createContainer("hohenheim-reclaim-test-" + System.nanoTime(),
                Map.of("Image", supersededTag, "Cmd", List.of("sleep", "30")), ContainerHardening.STRICT);
            new DockerReclaim(docker, Duration.ZERO, false, null).reclaimImages(declared, Instant.now());
            assertThat(imageIds(docker))
                .as("an image a container references is never removed")
                .contains(superseded);

            // 4. A deploy in flight halts every removal, whatever else would decide.
            docker.removeContainer(containerId, true);
            containerId = null;
            DockerReclaim.Outcome halted = new DockerReclaim(docker, Duration.ZERO, false, () -> true)
                .reclaimImages(declared, Instant.now());
            assertThat(halted.removed()).as("a deploy in flight halts the sweep").isZero();
            assertThat(imageIds(docker)).contains(superseded);

            // 5. A second reference in the SAME repository must not save the image:
            // removal goes by reference, so a multi-tagged superseded image still
            // goes (removal by id would 409 on it).
            docker.tagImage(supersededTag, repository, "one-alias");
            DockerReclaim.Outcome swept = new DockerReclaim(docker, Duration.ZERO, false, null)
                .reclaimImages(declared, Instant.now());
            assertThat(swept.removed()).as("the superseded image was reclaimed").isPositive();
            assertThat(swept.bytes()).as("reclaimed size is reported").isPositive();
            List<String> remaining = imageIds(docker);
            assertThat(remaining).as("the superseded image is gone").doesNotContain(superseded);
            assertThat(remaining).as("the repository's current image stays").contains(current);
            assertThat(remaining).as("an unmanaged repository is never touched").contains(unmanaged);
        } finally {
            String created = containerId;
            if (created != null) {
                remove(() -> docker.removeContainer(created, true));
            }
            remove(() -> docker.removeImage(supersededTag, true));
            remove(() -> docker.removeImage(repository + ":one-alias", true));
            remove(() -> docker.removeImage(currentTag, true));
            remove(() -> docker.removeImage(unmanagedTag, true));
            Files.deleteIfExists(context);
        }
    }

    /** Loads {@code tag} with a marker that makes the layer unique, returning its id. */
    private static String build(DockerClient docker, Path context, String tag, String marker)
            throws IOException {
        return TestImages.load(docker, tag, marker);
    }

    private static List<String> imageIds(DockerClient docker) throws IOException {
        List<String> ids = new ArrayList<>();
        for (Object entry : docker.listImages()) {
            ids.add(String.valueOf(((Map<?, ?>) entry).get("Id")));
        }
        return ids;
    }

    /** Best-effort cleanup: a failed teardown must not mask the assertion that ran. */
    private static void remove(ThrowingRunnable body) {
        try {
            body.run();
        } catch (IOException ignored) {
            // already gone, or held by something this test does not own
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws IOException;
    }
}
