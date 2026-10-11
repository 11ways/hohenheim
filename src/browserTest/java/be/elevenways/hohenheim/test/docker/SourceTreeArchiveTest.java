package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.server.util.Tar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A build context leaves the controller as the tree its repository describes: root-owned, files 0644 or 0755,
 * directories 0755, whatever owner and umask the checkout was written with.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class SourceTreeArchiveTest {

    @TempDir Path directory;

    @Test
    void aSourceTreeArchivesAsGitRecordsItWhateverTheCheckoutWroteOnDisk() throws Exception {
        Path page = this.directory.resolve("index.html");
        Files.writeString(page, "<h1>hi</h1>");
        Files.setPosixFilePermissions(page, PosixFilePermissions.fromString("rw-r-----"));
        Path script = this.directory.resolve("start.sh");
        Files.writeString(script, "#!/bin/sh\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-x---"));
        Path folder = Files.createDirectory(this.directory.resolve("assets"));
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"));

        // 1. A file the service account wrote under its umask (0640) archives as 0644, owned by root.
        byte[] pageTar = archive(page, Tar.Ownerships.SOURCE_TREE);
        assertThat(modeOf(pageTar)).as("step 1: readable by everyone, as git checks it out").isEqualTo(0644);
        assertThat(ownerOf(pageTar)).as("step 1: owned by root, never the controller's account").isZero();

        // 2. An executable keeps being executable, for everyone who may read it.
        assertThat(modeOf(archive(script, Tar.Ownerships.SOURCE_TREE))).as("step 2: 0750 archives as 0755")
            .isEqualTo(0755);

        // 3. A directory is 0755, whatever it was created with.
        ByteArrayOutputStream folderTar = new ByteArrayOutputStream();
        Tar.Writer writer = new Tar.Writer(folderTar, Tar.Ownerships.SOURCE_TREE);
        writer.addDirectoryHeader(folder, "assets/");
        writer.finish();
        assertThat(modeOf(folderTar.toByteArray())).as("step 3: a 0700 directory archives as 0755").isEqualTo(0755);

        // 4. As on disk, the file keeps what the disk says: the policy is the caller's choice, never implied.
        assertThat(modeOf(archive(page, Tar.Ownerships.AS_ON_DISK))).as("step 4: as on disk stays 0640")
            .isEqualTo(0640);
    }

    private static byte[] archive(Path path, Tar.Ownerships ownerships) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Tar.Writer writer = new Tar.Writer(out, ownerships);
        writer.add(path, path.getFileName().toString());
        writer.finish();
        return out.toByteArray();
    }

    private static int modeOf(byte[] tar) throws Exception {
        return new Tar.Reader(new ByteArrayInputStream(tar)).next().mode();
    }

    /** @return the first header's numeric owner (ustar octal field at offset 108) */
    private static long ownerOf(byte[] tar) {
        String octal = new String(tar, 108, 8, StandardCharsets.US_ASCII).replace("\0", "").trim();
        return Long.parseLong(octal, 8);
    }
}
