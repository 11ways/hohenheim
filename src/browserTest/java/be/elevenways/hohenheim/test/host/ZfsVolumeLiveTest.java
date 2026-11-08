package be.elevenways.hohenheim.test.host;

import be.elevenways.hohenheim.host.VolumeBackend;
import be.elevenways.hohenheim.server.host.HostShell;
import be.elevenways.hohenheim.server.host.VolumeBackends;
import be.elevenways.hohenheim.server.host.VolumeOperations;
import be.elevenways.hohenheim.server.host.ZfsVolumeOperations;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.protoblast.server.process.ProcessOutcome;
import be.elevenways.protoblast.server.process.Subprocess;
import be.elevenways.protoblast.server.process.Termination;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ZfsVolumeOperations} against a REAL ZFS pool: that a volume really is a dataset mounted at its path, that
 * its refquota is really enforced by the kernel, that a snapshot really keeps the pre-snapshot bytes read-only, that
 * an archive reads a snapshot and leaves none behind, and that a destroy really removes the dataset.
 *
 * AIDEV-NOTE: the host is reached over plain ssh, as BtrfsVolumeLiveTest does, and the test makes its OWN dataset
 * below the pool's dataset at {@link #POOL_PATH}, mounted exactly where the volume root is, then destroys exactly it.
 *
 * @author Jelle De Loecker
 * @since 0.10.0
 */
@Tag("slow")
class ZfsVolumeLiveTest {

    /** The ZFS host; override with {@code -Dhohenheim.live.zfs.host=user@host}. */
    private static final String HOST = System.getProperty("hohenheim.live.zfs.host", "root@starfleet.life");

    /** The mountpoint of a dataset on that host the test makes its own dataset below. */
    private static final String POOL_PATH = System.getProperty("hohenheim.live.zfs.path", "/srv/hohenheim-zfs");

    private static String dataset;
    private static String root;
    private static HostShell shell;

    @BeforeAll
    static void setUp() {
        shell = new SshShell();
        HostShell.Result probe = shell.run("zfs list -H -o name " + HostShell.quote(POOL_PATH));
        LiveLane.require(LiveLane.Need.REMOTE_HOST, probe.ok() && !probe.text().isBlank(),
            POOL_PATH + " on " + HOST + " is not a ZFS dataset: " + probe.text());
        String name = "hohenheim-livetest-" + UUID.randomUUID().toString().substring(0, 8);
        dataset = probe.text().trim() + "/" + name;
        root = POOL_PATH + "/" + name;
        HostShell.Result made = shell.run("zfs create -o mountpoint=" + HostShell.quote(root) + " "
            + HostShell.quote(dataset));
        LiveLane.require(LiveLane.Need.REMOTE_HOST, made.ok(), "could not create the test dataset: " + made.text());
    }

    /** Remove exactly the dataset this test made, every volume and snapshot below it included. */
    @AfterAll
    static void tearDown() {
        if (shell != null && dataset != null) {
            shell.run("zfs destroy -r " + HostShell.quote(dataset));
        }
    }

    @Test
    void aZfsVolumeIsADatasetWithAnEnforcedQuotaAndARealSnapshot() throws IOException {
        VolumeOperations zfs = VolumeOperations.forBackend(VolumeBackend.ZFS, shell);
        String volume = root + "/1/home";

        // 1. CREATE makes a DATASET mounted exactly at the volume's path.
        zfs.create(volume);
        assertThat(shell.run("zfs get -H -o value mountpoint " + HostShell.quote(dataset + "/1/home")).text())
            .as("step 1: the volume is a dataset mounted at its own path").isEqualTo(volume);

        // 2. CREATE is idempotent and keeps the contents.
        shell.run("echo hello > " + HostShell.quote(volume + "/keep.txt"));
        zfs.create(volume);
        assertThat(shell.run("cat " + HostShell.quote(volume + "/keep.txt")).text())
            .as("step 2: a repeated create keeps the volume and its contents").isEqualTo("hello");

        // 3. The quota is ENFORCED by the kernel: a write past it fails.
        zfs.setQuota(volume, 16L * 1024 * 1024);
        HostShell.Result overflow = shell.run("dd if=/dev/urandom of=" + HostShell.quote(volume + "/fill.bin")
            + " bs=1M count=64 2>&1; sync; echo exit=$?");
        assertThat(overflow.text()).as("step 3: writing past the refquota is refused by the filesystem")
            .containsIgnoringCase("quota");
        shell.run("rm -f " + HostShell.quote(volume + "/fill.bin") + "; sync");

        // 4. USAGE answers in bytes.
        assertThat(zfs.usage(volume)).as("step 4: usage is a real number of bytes").isGreaterThan(0L);

        // 5. A SNAPSHOT keeps the pre-snapshot bytes after the volume moves on, read-only.
        String snapshot = zfs.snapshot(volume, "predeploy");
        shell.run("echo changed > " + HostShell.quote(volume + "/keep.txt"));
        assertThat(shell.run("cat " + HostShell.quote(snapshot + "/keep.txt")).text())
            .as("step 5: the snapshot still holds what the volume held").isEqualTo("hello");
        assertThat(shell.run("touch " + HostShell.quote(snapshot + "/nope") + " 2>&1").ok())
            .as("step 5: and it is read-only").isFalse();

        // 6. An ARCHIVE reads a snapshot of its own and leaves none behind; a restore unpacks it into a new volume.
        Path tar = Files.createTempFile("zfs-live", ".tar");
        try {
            long size = zfs.archive(volume, tar, 64L * 1024 * 1024);
            assertThat(size).as("step 6: the archive holds the volume").isGreaterThan(0L);
            assertThat(shell.run("zfs list -H -t snapshot -o name -r " + HostShell.quote(dataset + "/1/home")
                + " | grep -c backup || true").text())
                .as("step 6: and its snapshot is gone again").isEqualTo("0");
            String copy = root + "/2/home";
            zfs.create(copy);
            zfs.restore(copy, tar, null);
            assertThat(shell.run("cat " + HostShell.quote(copy + "/keep.txt")).text())
                .as("step 6: the restored volume holds the archived contents").isEqualTo("changed");
            zfs.destroy(copy);
            zfs.pruneOwner(root + "/2");
            assertThat(shell.run("zfs list " + HostShell.quote(dataset + "/2")).ok())
                .as("step 6: an owner with no volume left goes too").isFalse();
        } finally {
            Files.deleteIfExists(tar);
        }

        // 7. DESTROY removes the dataset and its snapshots.
        zfs.deleteSnapshot(snapshot);
        zfs.destroy(volume);
        assertThat(shell.run("zfs list " + HostShell.quote(dataset + "/1/home")).ok())
            .as("step 7: the volume's dataset is gone").isFalse();
    }

    @Test
    void theProbeClassifiesTheRealFilesystemAsZfs() {
        HostShell.Result probe = shell.run("p=" + HostShell.quote(root) + "; echo \"$p\"; stat -f -c %T \"$p\";"
            + " findmnt -no OPTIONS --target \"$p\" || true");
        VolumeBackends.Detection detection = VolumeBackends.classify(root, probe.text());
        assertThat(detection.backend()).as("step 1: a real ZFS dataset classifies as ZFS").isEqualTo(VolumeBackend.ZFS);
        assertThat(detection.backend().supportsQuota() && detection.backend().supportsSnapshot())
            .as("step 2: and therefore admits an application and its pre-deploy snapshot").isTrue();
    }

    @Test
    void aVolumeRootThatIsNoDatasetMountpointIsRefused() {
        VolumeOperations zfs = VolumeOperations.forBackend(VolumeBackend.ZFS, shell);
        shell.run("mkdir -p " + HostShell.quote(root + "/plain/volumes"));
        assertThatThrownBy(() -> zfs.create(root + "/plain/volumes/1/home"))
            .as("a volume root inside a dataset, not its mountpoint, refuses by name")
            .isInstanceOf(Violations.class).hasMessageContaining("volume_create_failed");
    }

    /** The ssh lane to the test host, streaming a payload as the production process shell does. */
    private static final class SshShell implements HostShell {

        @Override
        public @NonNull Result run(@NonNull String script, long timeoutSeconds) {
            return outcome(Subprocess.of(argv(script)).mergeStderr(), false);
        }

        @Override
        public @NonNull Result runReading(@NonNull String script, @NonNull Path target, long maxBytes,
                                          long timeoutSeconds) {
            try (OutputStream sink = Files.newOutputStream(target)) {
                return outcome(Subprocess.of(argv(script)).stdoutTo(sink), true);
            } catch (IOException failed) {
                return new Result(1, String.valueOf(failed.getMessage()));
            }
        }

        @Override
        public @NonNull Result runFeeding(@NonNull String script, @NonNull Path source, long timeoutSeconds) {
            try (InputStream input = Files.newInputStream(source)) {
                return outcome(Subprocess.of(argv(script)).stdin(input).mergeStderr(), false);
            } catch (IOException failed) {
                return new Result(1, String.valueOf(failed.getMessage()));
            }
        }

        private static @NonNull List<String> argv(@NonNull String script) {
            return List.of("ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", "-o",
                "StrictHostKeyChecking=accept-new", "-o", "LogLevel=ERROR", HOST, "sh", "-c", HostShell.quote(script));
        }

        private static @NonNull Result outcome(@NonNull Subprocess process, boolean stderrText) {
            try {
                ProcessOutcome outcome = process.timeout(Duration.ofSeconds(120)).runChecked();
                if (outcome.termination() == Termination.TIMED_OUT) {
                    return new Result(1, "the ssh command timed out");
                }
                return new Result(outcome.exitCode(),
                    (stderrText ? outcome.stderr() : outcome.stdout()).text().trim());
            } catch (IOException failed) {
                return new Result(1, String.valueOf(failed.getMessage()));
            }
        }
    }
}
