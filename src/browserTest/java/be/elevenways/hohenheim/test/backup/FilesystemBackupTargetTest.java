package be.elevenways.hohenheim.test.backup;

import be.elevenways.hohenheim.server.backup.BackupTarget;
import be.elevenways.hohenheim.server.backup.FilesystemBackupTarget;
import be.elevenways.protoblast.common.thread.JobRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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

    /**
     * A delete takes the folders its key's namespace is left with empty (an instance's last backup used to leave
     * {@code <controller>/instance-N} behind), and a store that races such a delete recreates its folder.
     */
    @Test
    void aDeleteLeavesNoEmptyFolderAndAStoreRecreatesIt() throws Exception {
        Path root = tmp.resolve("store");
        BackupTarget target = new FilesystemBackupTarget(root);
        Path file = tmp.resolve("payload.bin");
        Files.write(file, "artifact-bytes".getBytes(StandardCharsets.UTF_8));

        // 1. Two artifacts in one instance folder: deleting one keeps the folder.
        target.store("owner/instance-1/a.hib", file);
        target.store("owner/instance-1/b.hib", file);
        target.delete("owner/instance-1/a.hib");
        assertThat(Files.isDirectory(root.resolve("owner/instance-1")))
            .as("step 1: a folder that still holds an artifact stays").isTrue();

        // 2. Deleting the last one removes the instance folder and the owner folder it emptied, never the base.
        target.delete("owner/instance-1/b.hib");
        assertThat(Files.exists(root.resolve("owner/instance-1")))
            .as("step 2: the emptied instance folder is gone").isFalse();
        assertThat(Files.exists(root.resolve("owner")))
            .as("step 2: and the owner folder it emptied").isFalse();
        assertThat(Files.isDirectory(root)).as("step 2: the base stays").isTrue();

        // 3. A delete of an absent key is still success and still leaves the base.
        target.delete("owner/instance-1/b.hib");
        assertThat(Files.isDirectory(root)).as("step 3: an absent key's delete keeps the base").isTrue();

        // 3b. An earlier version's empty folder in the namespace goes with the next delete there; another
        //     namespace's (another controller's) empty folder is not this delete's to judge.
        Files.createDirectories(root.resolve("owner/instance-19"));
        Files.createDirectories(root.resolve("other/instance-1"));
        target.store("owner/instance-3/c.hib", file);
        target.delete("owner/instance-3/c.hib");
        assertThat(Files.exists(root.resolve("owner")))
            .as("step 3b: the namespace's leftover folder went with the delete").isFalse();
        assertThat(Files.isDirectory(root.resolve("other/instance-1")))
            .as("step 3b: another namespace's folder stays").isTrue();

        // 4. Stores and deletes racing in one folder: every store lands, though each delete may take the folder.
        int rounds = 200;
        JobRunner racers = JobRunner.create("backup-target-lanes");
        try {
            List<CompletableFuture<Void>> lanes = new ArrayList<>();
            for (String lane : List.of("x", "y")) {
                lanes.add(racers.runAsync(() -> {
                    try {
                        for (int round = 0; round < rounds; round++) {
                            String key = "owner/instance-2/" + lane + "-" + round + ".hib";
                            target.store(key, file);
                            assertThat(target.exists(key)).as("step 4: " + key + " landed").isTrue();
                            target.delete(key);
                        }
                    } catch (IOException failed) {
                        throw new UncheckedIOException(failed);
                    }
                }));
            }
            CompletableFuture.allOf(lanes.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);
        } finally {
            racers.shutdownNow();
        }
        assertThat(Files.exists(root.resolve("owner")))
            .as("step 4: the last delete left no empty folder").isFalse();
    }
}
