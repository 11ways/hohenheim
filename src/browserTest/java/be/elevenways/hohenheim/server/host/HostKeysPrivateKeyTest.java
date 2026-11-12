package be.elevenways.hohenheim.server.host;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A host's private SSH key is its owner's alone from its first byte: written through an owner-only staged file and
 * moved into place, narrowed in place when it already says the same, and never written through a link. Where the
 * filesystem cannot keep it to its owner the write is refused in words ({@code host_identity_not_private}); that needs
 * a mount refusing chmod, which this journey cannot make.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class HostKeysPrivateKeyTest {

    private static final String KEY = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAA\n"
        + "-----END OPENSSH PRIVATE KEY-----\n";

    @TempDir
    Path tempDir;

    @Test
    void aPrivateKeyIsWrittenOwnerOnlyOrNotAtAllJourney() throws Exception {
        // 1. Written fresh: the key, readable by its owner only.
        Path file = this.tempDir.resolve("id-7");
        assertThat(HostKeys.writePrivateKey(file, KEY)).as("step 1: the key's file").isEqualTo(file);
        assertThat(Files.readString(file)).as("step 1: holding the key").isEqualTo(KEY);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
            .as("step 1: its owner's alone").isEqualTo("rw-------");

        // 2. Already there with the same key but readable by others (an older write, a copied file): narrowed.
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        HostKeys.writePrivateKey(file, KEY);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
            .as("step 2: narrowed in place").isEqualTo("rw-------");

        // 3. A symbolic link standing at the key's path is replaced by the owner-only key itself, never written
        //    through: what it pointed at keeps its content and its mode.
        Path elsewhere = Files.writeString(this.tempDir.resolve("elsewhere"), "not a key");
        Files.setPosixFilePermissions(elsewhere, PosixFilePermissions.fromString("rw-r--r--"));
        Path linked = Files.createSymbolicLink(this.tempDir.resolve("id-8"), elsewhere);
        HostKeys.writePrivateKey(linked, KEY);
        assertThat(Files.isSymbolicLink(linked)).as("step 3: the link is gone").isFalse();
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(linked)))
            .as("step 3: the key in its place is its owner's alone").isEqualTo("rw-------");
        assertThat(Files.readString(elsewhere)).as("step 3: nothing written through the link").isEqualTo("not a key");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(elsewhere)))
            .as("step 3: and its target keeps its mode").isEqualTo("rw-r--r--");
    }
}
