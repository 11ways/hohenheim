package be.elevenways.hohenheim.server.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A checkout replaced through the git lane goes as a tree whose links go as links: the old File walk listed a
 * symlinked directory's target and emptied it.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class GitCheckoutTreeTest {

    @Test
    void aCheckoutIsRemovedWithoutEverFollowingALink(@TempDir Path sandbox) throws IOException {
        // 1. A checkout holding a file, a nested directory and a link to a directory OUTSIDE it.
        Path outside = Files.createDirectories(sandbox.resolve("outside"));
        Path precious = Files.writeString(outside.resolve("precious.txt"), "keep me");
        Path checkout = Files.createDirectories(sandbox.resolve("checkout/nested"));
        Files.writeString(checkout.resolve("file.txt"), "scratch");
        Files.createSymbolicLink(sandbox.resolve("checkout/linked"), outside);

        // 2. The checkout goes, and the directory it linked to still holds its file.
        GitCheckout.deleteTree(sandbox.resolve("checkout").toFile());
        assertThat(Files.exists(sandbox.resolve("checkout"), LinkOption.NOFOLLOW_LINKS))
            .as("step 2: the checkout is gone").isFalse();
        assertThat(Files.readString(precious))
            .as("step 2: the link's target survived untouched").isEqualTo("keep me");

        // 3. A null or absent checkout is nothing to remove.
        GitCheckout.deleteTree(null);
        GitCheckout.deleteTree(sandbox.resolve("never-existed").toFile());
    }
}
