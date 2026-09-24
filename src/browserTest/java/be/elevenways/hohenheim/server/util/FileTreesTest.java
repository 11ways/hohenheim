package be.elevenways.hohenheim.server.util;

import be.elevenways.hohenheim.server.source.GitCheckout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE recursive delete: a tree goes and a symlink inside it goes as a LINK with its target
 * untouched. The reporting variant's refusal path needs a filesystem that can refuse this
 * process and is driven by {@code InstanceSnapshotRetentionTest} on the live lane.
 */
class FileTreesTest {

    @Test
    void aTreeIsRemovedWithoutEverFollowingALink(@TempDir Path sandbox) throws IOException {
        // 1. A tree holding a file, a nested directory and a link to a directory OUTSIDE it.
        Path outside = Files.createDirectories(sandbox.resolve("outside"));
        Path precious = Files.writeString(outside.resolve("precious.txt"), "keep me");
        Path tree = Files.createDirectories(sandbox.resolve("tree/nested"));
        Files.writeString(tree.resolve("file.txt"), "scratch");
        Files.createSymbolicLink(sandbox.resolve("tree/escape"), outside);

        assertThat(FileTrees.delete(sandbox.resolve("tree")))
            .as("step 1: the tree was removed without a failure").isNull();
        assertThat(Files.exists(sandbox.resolve("tree"), LinkOption.NOFOLLOW_LINKS))
            .as("step 1: the tree is gone").isFalse();
        assertThat(Files.readString(precious))
            .as("step 1: the link's target survived untouched").isEqualTo("keep me");

        // 2. An absent or null root is a no-op that reports nothing.
        assertThat(FileTrees.delete(sandbox.resolve("never-existed")))
            .as("step 2: an absent root is already gone").isNull();
        assertThat(FileTrees.delete(null)).as("step 2: a null root too").isNull();

        // 3. A checkout replaced through the git lane obeys the same rule: the old File walk
        //    listed a symlinked directory's target and emptied it.
        Path checkout = Files.createDirectories(sandbox.resolve("checkout"));
        Files.createSymbolicLink(checkout.resolve("linked"), outside);
        GitCheckout.deleteTree(checkout.toFile());
        assertThat(Files.exists(checkout, LinkOption.NOFOLLOW_LINKS))
            .as("step 3: the checkout is gone").isFalse();
        assertThat(Files.readString(precious))
            .as("step 3: the directory the checkout linked to still holds its file")
            .isEqualTo("keep me");
    }
}
