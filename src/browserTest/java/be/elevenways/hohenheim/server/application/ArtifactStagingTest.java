package be.elevenways.hohenheim.server.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an artifact deploy stages, and why each property matters to the release that follows.
 *
 * AIDEV-NOTE: the release is pinned by the DIGEST of the built image, so the staged context
 * has to be a pure function of the artifact. Two uploads of one jar that produced two
 * contexts would produce two digests, and the site convergence would then see a settings
 * change and roll the container on every deploy of identical bytes. That is what the fixed
 * staged mtime is for, and step 3 is the test that would catch its removal.
 */
class ArtifactStagingTest {

    private static final String BASE_IMAGE = "hohenheim/java-25:1";

    @Test
    void stagingAnArtifactProducesADeterministicBuildContext(@TempDir Path tmp) throws Exception {

        // 1. The generated Dockerfile is the whole build: one COPY onto the runtime image.
        String dockerfile = ArtifactDeploys.dockerfileFor(BASE_IMAGE);
        assertThat(dockerfile)
            .as("the runtime image is the base, so the image's workdir/entrypoint/command still apply")
            .contains("FROM " + BASE_IMAGE)
            .contains("COPY app.jar /home/site/app.jar");
        assertThat(dockerfile.lines().filter(line -> !line.startsWith("#")).count())
            .as("nothing beyond FROM and COPY: anything else would re-declare what the image says")
            .isEqualTo(2);

        // 2. Staging writes exactly the artifact and the Dockerfile, nothing else.
        Path jar = jarWithStamp(tmp.resolve("first.jar"), "zenit\tzenit\tabc123\tmaster\tfalse\t2026-09-08\n");
        Path context = tmp.resolve("context");
        ArtifactDeploys.stage(jar, context.toFile(), BASE_IMAGE);

        assertThat(context.resolve("app.jar")).as("the artifact lands under its in-image name").exists();
        assertThat(context.resolve("Dockerfile")).as("the generated Dockerfile lands beside it").exists();
        assertThat(Files.list(context).count()).as("no other file enters the build context").isEqualTo(2);
        assertThat(Files.readAllBytes(context.resolve("app.jar")))
            .as("the artifact is copied byte for byte").isEqualTo(Files.readAllBytes(jar));

        // 3. Staging the SAME artifact twice is byte-identical, mtimes included. This is
        //    what keeps the built image's digest stable across redeploys of one jar.
        byte[] firstJar = Files.readAllBytes(context.resolve("app.jar"));
        var firstJarTime = Files.getLastModifiedTime(context.resolve("app.jar"));
        var firstDockerTime = Files.getLastModifiedTime(context.resolve("Dockerfile"));
        Thread.sleep(10);
        ArtifactDeploys.stage(jar, context.toFile(), BASE_IMAGE);
        assertThat(Files.readAllBytes(context.resolve("app.jar")))
            .as("identical bytes on a restage").isEqualTo(firstJar);
        assertThat(Files.getLastModifiedTime(context.resolve("app.jar")))
            .as("a fixed staged mtime, or an identical jar would build to a different digest")
            .isEqualTo(firstJarTime);
        assertThat(Files.getLastModifiedTime(context.resolve("Dockerfile")))
            .as("the generated Dockerfile is pinned to the same instant")
            .isEqualTo(firstDockerTime);

        // 4. A DIFFERENT artifact replaces the previous one; nothing of it survives.
        Path second = jarWithStamp(tmp.resolve("second.jar"), "zenit\tzenit\tdef456\tmaster\tfalse\t2026-09-08\n");
        ArtifactDeploys.stage(second, context.toFile(), BASE_IMAGE);
        assertThat(Files.readAllBytes(context.resolve("app.jar")))
            .as("a redeploy overwrites the staged artifact rather than leaving the old one")
            .isEqualTo(Files.readAllBytes(second))
            .isNotEqualTo(firstJar);

        // 5. The digest is the artifact's real SHA-256, and it distinguishes the two jars.
        String digestOne = ArtifactDeploys.digestOf(jar);
        String digestTwo = ArtifactDeploys.digestOf(second);
        assertThat(digestOne).as("a full hex SHA-256").hasSize(64).matches("[0-9a-f]{64}");
        assertThat(digestOne).as("different artifacts are different releases").isNotEqualTo(digestTwo);
        assertThat(ArtifactDeploys.digestOf(jar)).as("and it is stable").isEqualTo(digestOne);
    }

    @Test
    void theBuildStampIsSummarisedForHumansAndNeverBecomesTheIdentity(@TempDir Path tmp) throws Exception {

        // 1. A clean multi-repo stamp is summarised by repo count and cleanliness.
        Path clean = jarWithStamp(tmp.resolve("clean.jar"),
            "zenit\tzenit\tabc\tmaster\tfalse\t2026-09-08\n"
                + "hawkeye\thawkeye\tdef\tmaster\tfalse\t2026-09-08\n");
        assertThat(ArtifactDeploys.describeStamp(clean))
            .as("both repositories are counted and the tree is reported clean")
            .isEqualTo("2 repos, clean");

        // 2. A dirty row is called out, because a dirty build is undiffable by construction.
        Path dirty = jarWithStamp(tmp.resolve("dirty.jar"),
            "zenit\tzenit\tabc\tmaster\ttrue\t2026-09-08\n");
        assertThat(ArtifactDeploys.describeStamp(dirty))
            .as("a dirty stamp must be visible in the deploy log").isEqualTo("1 repos, DIRTY");

        // 3. A jar without a stamp degrades honestly instead of inventing provenance.
        Path bare = tmp.resolve("bare.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(bare))) {
            zip.putNextEntry(new ZipEntry("nothing.txt"));
            zip.write("x".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertThat(ArtifactDeploys.describeStamp(bare))
            .as("absence is reported, never guessed at").isEqualTo("(no build stamp)");

        // 4. Something that is not a zip at all does not throw the deploy off.
        Path junk = Files.writeString(tmp.resolve("junk.jar"), "not a jar");
        assertThat(ArtifactDeploys.describeStamp(junk))
            .as("an unreadable stamp is a log detail, not a deploy failure")
            .isEqualTo("(build stamp unreadable)");
    }

    private static Path jarWithStamp(Path path, String stamp) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry(ArtifactDeploys.BUILD_STAMP));
            zip.write(stamp.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return path;
    }
}
