package be.elevenways.hohenheim.test.backup;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.backup.BackupTarget;
import be.elevenways.hohenheim.server.backup.FilesystemBackupTarget;
import be.elevenways.hohenheim.server.backup.SshBackupTarget;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The target seam's contracts against real destinations: the filesystem target on a
 * real directory, and the SSH target against sshd on localhost (REAL transport,
 * SIMULATED failure domain -- one machine cannot prove two; LiveOffHostBackupTest is
 * where the second machine is real). Staging-then-commit is the load-bearing
 * behavior: nothing under the committed key until every byte landed.
 */
class BackupTargetsTest {

    @TempDir
    Path tmp;

    private String previousDataPath;

    /**
     * The ssh target materialises its pinned known_hosts under Hohenheim's OWN store, so
     * a test that builds one must own that store too.
     */
    @BeforeEach
    void isolateTheSshStore() throws Exception {
        HohenheimTestRuntime.ensureBooted();
        this.previousDataPath = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Storage.DATA_PATH);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.DATA_PATH,
            this.tmp.resolve("controller-data").toString());
    }

    @AfterEach
    void restoreTheSshStore() {
        if (this.previousDataPath != null) {
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.DATA_PATH,
                this.previousDataPath);
        }
    }

    private Path payload(String content) throws IOException {
        Path file = tmp.resolve("payload.bin");
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    void filesystemTargetStoresVerifiesRetrievesAndDeletes() throws IOException {
        BackupTarget target = new FilesystemBackupTarget(tmp.resolve("store"));
        Path file = payload("artifact-bytes");

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

    /** The key a host currently offers, in the spelling the target settings take. */
    private static String scanHostKey(String host) throws IOException {
        try {
            Process scan = new ProcessBuilder("ssh-keyscan", "-T", "10", "--", host).start();
            String output = new String(scan.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
            scan.waitFor(20, TimeUnit.SECONDS);
            for (String line : output.split("\n")) {
                if (line.contains("ssh-ed25519 ")) {
                    return line.trim();
                }
            }
            throw new IOException("ssh-keyscan offered no ed25519 key for " + host);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted scanning " + host);
        }
    }

    private static boolean sshLocalhostWorks() {
        try {
            Process probe = new ProcessBuilder("ssh", "-o", "BatchMode=yes",
                "-o", "StrictHostKeyChecking=yes", "localhost", "--", "true").start();
            return probe.waitFor(10, TimeUnit.SECONDS) && probe.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void sshTargetSpeaksTheSameContractOverARealSshTransport() throws IOException {
        Assumptions.assumeTrue(sshLocalhostWorks(),
            "no key-authenticated sshd on localhost: ssh target live test skipped");
        Path remoteBase = tmp.resolve("remote");

        // 0. There is NO unpinned mode: a target without a pin is refused at
        //    construction, before any argv exists, let alone any connection.
        Throwable unpinned = catchThrowable(() ->
            new SshBackupTarget("localhost", remoteBase.toString(), null));
        assertThat(unpinned).as("step 0: an unpinned ssh target is refused outright")
            .isInstanceOf(IOException.class).hasMessageContaining("no pinned host key");

        BackupTarget target = new SshBackupTarget("localhost", remoteBase.toString(),
            scanHostKey("localhost"));
        Path file = payload("ssh-artifact");

        // 1. Health creates the base dir remotely.
        target.healthCheck();
        assertThat(Files.isDirectory(remoteBase))
            .as("step 1: health check created the remote base directory").isTrue();

        // 2. Store commits atomically; the .part staging name is gone afterwards.
        target.store("instance-9/b.hib", file);
        assertThat(Files.exists(remoteBase.resolve("instance-9/b.hib")))
            .as("step 2: committed artifact exists on the remote filesystem").isTrue();
        assertThat(Files.exists(remoteBase.resolve("instance-9/b.hib.part")))
            .as("step 2: no staging debris after a clean store").isFalse();

        // 3. The sha comes from the REMOTE side (sha256sum over there).
        assertThat(target.storedSha256("instance-9/b.hib"))
            .as("step 3: remote sha equals a local recomputation")
            .isEqualTo(be.elevenways.hohenheim.server.backup.BackupArchive.sha256Of(
                remoteBase.resolve("instance-9/b.hib")));

        // 4. Retrieve + exists + delete round-trip.
        Path back = tmp.resolve("ssh-back.bin");
        target.retrieve("instance-9/b.hib", back);
        assertThat(Files.readString(back)).as("step 4: retrieved bytes").isEqualTo("ssh-artifact");
        target.delete("instance-9/b.hib");
        assertThat(target.exists("instance-9/b.hib")).as("step 4: gone after delete").isFalse();

        // 5. An unsafe key is refused before any remote command runs.
        Throwable escape = catchThrowable(() -> target.store("../escape.hib", file));
        assertThat(escape).as("step 5: unsafe keys are refused")
            .isInstanceOf(IOException.class).hasMessageContaining("not a safe remote path");

        // 6. A failing store leaves NO committed artifact (unwritable destination).
        Path blocked = remoteBase.resolve("blocked");
        Files.createDirectories(blocked);
        blocked.toFile().setWritable(false);
        try {
            BackupTarget blockedTarget = new SshBackupTarget("localhost",
                blocked.resolve("sub").toString(), scanHostKey("localhost"));
            Throwable failure = catchThrowable(() -> blockedTarget.store("c.hib", file));
            assertThat(failure).as("step 6: store into an unwritable base fails loudly")
                .isInstanceOf(IOException.class);
            assertThat(Files.exists(blocked.resolve("sub/c.hib")))
                .as("step 6: and commits nothing").isFalse();
        } finally {
            blocked.toFile().setWritable(true);
        }
    }
}
