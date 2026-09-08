package be.elevenways.hohenheim.server.application;

import be.elevenways.protoblast.common.thread.JobRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArtifactStagingTest {
    @Test
    void simultaneousDistinctAndDuplicateUploadsNeverMixBytes(@TempDir Path tmp) throws Exception {
        Path first = jar(tmp.resolve("one.jar"), "one");
        Path second = jar(tmp.resolve("two.jar"), "two");
        Path duplicate = Files.copy(first, tmp.resolve("duplicate.jar"));
        byte[] firstBytes = Files.readAllBytes(first);
        byte[] secondBytes = Files.readAllBytes(second);
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<Path> one = ownAsync(first, tmp.resolve("app"), start);
        CompletableFuture<Path> two = ownAsync(second, tmp.resolve("app"), start);
        CompletableFuture<Path> same = ownAsync(duplicate, tmp.resolve("app"), start);
        start.countDown();
        assertThat(one.get()).isEqualTo(same.get()).isNotEqualTo(two.get());
        ArtifactDeploys.deleteUpload(first);
        ArtifactDeploys.deleteUpload(second);
        ArtifactDeploys.deleteUpload(duplicate);
        assertThat(Files.readAllBytes(one.get())).isEqualTo(firstBytes);
        assertThat(Files.readAllBytes(two.get())).isEqualTo(secondBytes);
    }

    @Test
    void provenanceIsOptionalRawAndBoundedByInflatedBytes(@TempDir Path tmp) throws Exception {
        String stamp = "zenit\tzenit\tabc\tmaster\tfalse\t2026-09-08\n";
        Path valid = jar(tmp.resolve("valid.jar"), stamp);
        ArtifactDeploys.validateJar(valid);
        assertThat(ArtifactDeploys.readStamp(valid)).isEqualTo(stamp);
        Path oversized = jar(tmp.resolve("oversized.jar"), "x".repeat(ArtifactDeploys.MAX_STAMP_BYTES + 1));
        assertThat(ArtifactDeploys.readStamp(oversized)).isNull();
        Path junk = Files.writeString(tmp.resolve("junk.jar"), "not a jar");
        assertThatThrownBy(() -> ArtifactDeploys.validateJar(junk)).isInstanceOf(IOException.class);
        assertThat(ArtifactDeploys.readStamp(junk)).isNull();
    }

    @Test
    void corruptCompressedMemberIsRejectedWithoutExecutingAnything(@TempDir Path tmp) throws Exception {
        Path artifact = jar(tmp.resolve("bad.jar"), "a".repeat(1024));
        byte[] bytes = Files.readAllBytes(artifact);
        // The local member starts after the 30-byte header and its UTF-8 filename.
        int payload = 30 + ArtifactDeploys.BUILD_STAMP.getBytes(StandardCharsets.UTF_8).length;
        bytes[payload] = 7; // invalid DEFLATE block type
        Files.write(artifact, bytes);
        assertThatThrownBy(() -> ArtifactDeploys.validateJar(artifact)).isInstanceOf(IOException.class);
    }

    private static CompletableFuture<Path> ownAsync(Path upload, Path root, CountDownLatch start) {
        CompletableFuture<Path> result = new CompletableFuture<>();
        JobRunner.startVirtualThread(() -> {
            try {
                start.await();
                result.complete(ArtifactDeploys.own(upload, root, ArtifactDeploys.digestOf(upload)));
            } catch (Exception failed) { result.completeExceptionally(failed); }
        });
        return result;
    }

    static Path jar(Path path, String stamp) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry(ArtifactDeploys.BUILD_STAMP));
            zip.write(stamp.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return path;
    }
}
