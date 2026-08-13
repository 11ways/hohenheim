package be.elevenways.hohenheim.test.backup;

import be.elevenways.hohenheim.server.backup.BackupTarget;
import be.elevenways.hohenheim.server.backup.FilesystemBackupTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The target seam's contract against a real directory: staging-then-commit, a sha taken
 * from the TARGET's own bytes, and a key that cannot escape the root.
 *
 * AIDEV-NOTE: this lived in {@code BackupTargetsTest} until 2026-08-13 and was evicted
 * from the default lane by its sibling, which needs a real sshd on loopback. It needs
 * nothing but a temp directory -- no daemon, no database, no runtime boot -- so a
 * @Tag("slow") on the class it shared was pure coverage loss. Do not merge it back, and
 * do not give it a database: the moment it needs one it stops being the cheap check that
 * the filesystem target still honours the seam.
 */
class FilesystemBackupTargetTest {

    @TempDir
    Path tmp;

    @Test
    void filesystemTargetStoresVerifiesRetrievesAndDeletes() throws IOException {
        BackupTarget target = new FilesystemBackupTarget(tmp.resolve("store"));
        Path file = tmp.resolve("payload.bin");
        Files.write(file, "artifact-bytes".getBytes(StandardCharsets.UTF_8));

        // 1. Health + store + committed visibility.
        target.healthCheck();
        target.store("instance-1/a.hib", file);
        assertThat(target.exists("instance-1/a.hib"))
            .as("step 1: committed key exists after store").isTrue();
        assertThat(Files.exists(tmp.resolve("store/instance-1/a.hib.part")))
            .as("step 1: no staging debris survives a clean store").isFalse();

        // 2. The stored sha is computed from the TARGET's own bytes.
        String sha = target.storedSha256("instance-1/a.hib");
        assertThat(sha).as("step 2: sha256 shape").matches("[0-9a-f]{64}");

        // 3. Retrieve round-trips.
        Path back = tmp.resolve("back.bin");
        target.retrieve("instance-1/a.hib", back);
        assertThat(Files.readString(back)).as("step 3: retrieved bytes").isEqualTo("artifact-bytes");

        // 4. Delete removes committed AND staging names.
        target.delete("instance-1/a.hib");
        assertThat(target.exists("instance-1/a.hib")).as("step 4: gone after delete").isFalse();

        // 5. Traversal refusal: a key cannot escape the root.
        Throwable escape = catchThrowable(() -> target.store("../escape.hib", file));
        assertThat(escape).as("step 5: traversal keys are refused")
            .isInstanceOf(IOException.class).hasMessageContaining("escapes");
    }
}
