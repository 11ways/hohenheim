package be.elevenways.hohenheim.test.host;

import be.elevenways.hohenheim.host.VolumeBackend;
import be.elevenways.hohenheim.server.host.BtrfsVolumeOperations;
import be.elevenways.hohenheim.server.host.HostShell;
import be.elevenways.hohenheim.server.host.VolumeBackends;
import be.elevenways.hohenheim.server.host.VolumeOperations;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link BtrfsVolumeOperations} against a REAL btrfs filesystem: the half
 * {@code VolumeOperationsTest} structurally cannot prove.
 *
 * <p>What only this test can say: that {@code subvolume create} really makes a subvolume
 * (not a directory), that a qgroup limit is really ENFORCED by the kernel against a write,
 * that a read-only snapshot really refuses a write and really keeps the pre-snapshot bytes,
 * and that {@code subvolume delete} really removes it. Every one of those is a kernel
 * behaviour a command-text assertion can only assume.
 *
 * AIDEV-NOTE: the host is reached over plain ssh rather than through an enrolled
 * {@code servers} row, deliberately. What is under test is the OPERATIONS, and an enrolled
 * host would drag host-key pinning, admission and a whole datasource into a test about
 * three btrfs commands. The {@link HostShell} seam is the same one production uses.
 *
 * AIDEV-NOTE: the test carves its OWN subvolume tree under a uuid-named directory and
 * removes exactly what it made. It must never touch the Incus pool's own {@code images/},
 * {@code containers/} or {@code virtual-machines/} subvolumes, which share the filesystem.
 */
@Tag("slow")
class BtrfsVolumeLiveTest {

    /** The btrfs host; override with {@code -Dhohenheim.live.btrfs.host=user@host}. */
    private static final String HOST =
        System.getProperty("hohenheim.live.btrfs.host", "root@10.47.1.99");

    /**
     * A btrfs filesystem on that host to carve the test tree out of.
     *
     * AIDEV-NOTE: this is the Incus storage pool's mountpoint on the twins, which is the
     * only btrfs filesystem they have (their root is ext4). The test creates a sibling
     * directory of the pool's own subvolumes and never descends into them.
     */
    private static final String FILESYSTEM = System.getProperty(
        "hohenheim.live.btrfs.path", "/var/lib/incus/storage-pools/default");

    private static String base;
    private static String root;
    private static HostShell shell;

    @BeforeAll
    static void setUp() {
        shell = BtrfsVolumeLiveTest::ssh;

        HostShell.Result reachable = shell.run("echo ok");
        LiveLane.require(LiveLane.Need.REMOTE_HOST, reachable.ok(),
            "no ssh to " + HOST + ": " + reachable.text());

        HostShell.Result probe = shell.run("stat -f -c %T " + HostShell.quote(FILESYSTEM)
            + "; command -v btrfs >/dev/null && echo has-btrfs");
        boolean usable = probe.ok() && probe.text().contains("btrfs")
            && probe.text().contains("has-btrfs");
        LiveLane.require(LiveLane.Need.REMOTE_HOST, usable,
            FILESYSTEM + " on " + HOST + " is not a btrfs filesystem with btrfs-progs: "
                + probe.text());

        base = FILESYSTEM + "/hohenheim-livetest-" + UUID.randomUUID();
        root = base + "/volumes";
        HostShell.Result made = shell.run("mkdir -p " + HostShell.quote(root));
        LiveLane.require(LiveLane.Need.REMOTE_HOST, made.ok(),
            "could not create the test volume root: " + made.text());
    }

    /** Remove exactly what this test made: its subvolumes first, then its own directory. */
    @AfterAll
    static void tearDown() {
        if (shell == null || base == null) {
            return;
        }
        // Subvolumes cannot be rmdir'd, so they go by name first; the listing is scoped to
        // this test's own directory, which is what keeps the pool's subvolumes out of it.
        shell.run("btrfs subvolume list -o " + HostShell.quote(FILESYSTEM)
            + " 2>/dev/null | awk '{print $NF}' | grep -F "
            + HostShell.quote(baseName()) + " | sort -r | while read -r p; do"
            + " btrfs subvolume delete " + HostShell.quote(FILESYSTEM) + "/\"$p\" >/dev/null;"
            + " done");
        shell.run("rm -rf " + HostShell.quote(base));
    }

    /**
     * The whole owned-volume lifecycle on a real filesystem: subvolume, enforced quota,
     * read-only snapshot that survives a later write, and a verified delete.
     */
    @Test
    void aBtrfsVolumeIsASubvolumeWithAnEnforcedQuotaAndARealSnapshot() {

        VolumeOperations btrfs = VolumeOperations.forBackend(VolumeBackend.BTRFS, shell);
        String volume = root + "/1/home";

        // 1. CREATE really makes a SUBVOLUME, not a directory. `subvolume show` is the
        //    kernel's own answer to "is this a subvolume"; a mkdir would fail it.
        btrfs.create(volume);
        assertThat(shell.run("btrfs subvolume show " + HostShell.quote(volume)).ok())
            .as("step 1: the volume is a real btrfs subvolume").isTrue();

        // 2. CREATE is idempotent: a second call over an existing subvolume succeeds and
        //    keeps the data. A converge runs it on every deploy.
        shell.run("echo hello > " + HostShell.quote(volume + "/keep.txt"));
        btrfs.create(volume);
        assertThat(shell.run("cat " + HostShell.quote(volume + "/keep.txt")).text())
            .as("step 2: a repeated create keeps the volume and its contents")
            .isEqualTo("hello");

        // 3. QUOTA is ENFORCED by the kernel, not merely recorded: a write past the cap
        //    fails. This is the assertion the whole placement refusal rests on.
        btrfs.setQuota(volume, 16L * 1024 * 1024);
        shell.run("sync; btrfs quota rescan -w " + HostShell.quote(root) + " >/dev/null 2>&1");
        HostShell.Result overflow = shell.run("dd if=/dev/zero of="
            + HostShell.quote(volume + "/fill.bin")
            + " bs=1M count=64 oflag=direct 2>&1; sync; echo exit=$?");
        assertThat(overflow.text())
            .as("step 3: writing past the qgroup limit is refused by the filesystem")
            .containsIgnoringCase("quota");
        shell.run("rm -f " + HostShell.quote(volume + "/fill.bin"));

        // 4. USAGE answers in bytes off the same qgroup the limit is on.
        shell.run("btrfs quota rescan -w " + HostShell.quote(root) + " >/dev/null 2>&1");
        assertThat(btrfs.usage(volume))
            .as("step 4: usage is a real number of bytes, never the -1 of an unreadable"
                + " backend").isGreaterThanOrEqualTo(0L);

        // 5. SNAPSHOT is a point-in-time READ-ONLY copy that lands outside the volume, and
        //    keeps the pre-snapshot bytes after the volume moves on. That pair is what a
        //    pre-deploy snapshot promises.
        String snapshot = btrfs.snapshot(volume, "predeploy");
        assertThat(snapshot).as("step 5: the snapshot is outside the volume")
            .doesNotStartWith(volume + "/");
        shell.run("echo changed > " + HostShell.quote(volume + "/keep.txt"));
        assertThat(shell.run("cat " + HostShell.quote(snapshot + "/keep.txt")).text())
            .as("step 5: the snapshot still holds what the volume held when it was taken")
            .isEqualTo("hello");
        assertThat(shell.run("touch " + HostShell.quote(snapshot + "/nope") + " 2>&1").ok())
            .as("step 5: and it is read-only, so nothing can rewrite the evidence")
            .isFalse();

        // 6. DESTROY removes the subvolume; the kernel stops answering for it.
        btrfs.deleteSnapshot(snapshot);
        btrfs.destroy(volume);
        assertThat(shell.run("btrfs subvolume show " + HostShell.quote(volume)).ok())
            .as("step 6: the volume is gone from the filesystem").isFalse();
        assertThat(shell.run("test -e " + HostShell.quote(snapshot)).ok())
            .as("step 6: and so is its snapshot").isFalse();
    }

    /**
     * The probe reads THIS filesystem correctly, which is what stores {@code btrfs} on a
     * host record and lets an application be placed there at all.
     */
    @Test
    void theProbeClassifiesTheRealFilesystemAsBtrfs() {

        HostShell.Result probe = shell.run("p=" + HostShell.quote(root) + "; "
            + "while [ ! -e \"$p\" ] && [ \"$p\" != \"/\" ]; do p=$(dirname \"$p\"); done; "
            + "echo \"$p\"; stat -f -c %T \"$p\"; findmnt -no OPTIONS --target \"$p\" || true");

        assertThat(probe.ok()).as("step 1: the probe script runs on the host").isTrue();

        VolumeBackends.Detection detection = VolumeBackends.classify(root, probe.text());
        assertThat(detection.backend())
            .as("step 2: a real btrfs filesystem classifies as BTRFS")
            .isEqualTo(VolumeBackend.BTRFS);
        assertThat(detection.backend().supportsQuota() && detection.backend().supportsSnapshot())
            .as("step 3: and therefore admits both an application and its pre-deploy"
                + " snapshot").isTrue();
    }

    /** A path the host refuses is a NAMED violation, never a quiet no-op. */
    @Test
    void anImpossibleVolumeRefusesByName() {
        VolumeOperations btrfs = VolumeOperations.forBackend(VolumeBackend.BTRFS, shell);
        assertThatThrownBy(() -> btrfs.create("/proc/hohenheim-cannot-exist/volume"))
            .as("a create the kernel refuses surfaces as volume_create_failed")
            .isInstanceOf(Violations.class)
            .hasMessageContaining("volume_create_failed");
    }

    private static @NonNull String baseName() {
        return base.substring(base.lastIndexOf('/') + 1);
    }

    /** One ssh round trip; the same argv shape {@code HostShell} builds for an ssh host. */
    private static HostShell.@NonNull Result ssh(@NonNull String script) {
        List<String> argv = List.of("ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10",
            "-o", "StrictHostKeyChecking=accept-new", HOST, "sh", "-c",
            HostShell.quote(script));
        try {
            Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
            try {
                byte[] output = process.getInputStream().readAllBytes();
                if (!process.waitFor(60, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return new HostShell.Result(1, "the ssh command timed out");
                }
                return new HostShell.Result(process.exitValue(),
                    new String(output, StandardCharsets.UTF_8).trim());
            } finally {
                process.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new HostShell.Result(1, "interrupted");
        } catch (IOException failed) {
            return new HostShell.Result(1, String.valueOf(failed.getMessage()));
        }
    }
}
