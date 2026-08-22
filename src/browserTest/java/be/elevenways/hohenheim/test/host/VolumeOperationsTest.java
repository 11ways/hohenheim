package be.elevenways.hohenheim.test.host;

import be.elevenways.hohenheim.host.VolumeBackend;
import be.elevenways.hohenheim.server.host.BtrfsVolumeOperations;
import be.elevenways.hohenheim.server.host.HostShell;
import be.elevenways.hohenheim.server.host.VolumeOperations;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What each volume backend DOES, over a fake host shell: the btrfs member's real commands,
 * and every other member's refusal by name.
 *
 * AIDEV-NOTE: the assertions are on the COMMANDS, not on a filesystem. That is the whole
 * point of the {@link HostShell} seam -- a btrfs kernel is not available on every machine
 * that runs this suite, and the defects this tier can ship (a quota that was never applied,
 * a snapshot taken inside the volume it copies, a destroy that rm -rf's a subvolume) are all
 * visible in the command text. {@code BtrfsVolumeLiveTest} is the half that proves the
 * commands are the RIGHT commands on a real filesystem; neither half replaces the other.
 */
class VolumeOperationsTest {

    /** A shell that records what it was asked and answers whatever the test decides. */
    private static final class FakeShell implements HostShell {

        private final List<String> scripts = new ArrayList<>();
        private final Function<String, Result> answer;

        FakeShell(@NonNull Function<String, Result> answer) {
            this.answer = answer;
        }

        static FakeShell succeeding() {
            return new FakeShell(script -> new Result(0, ""));
        }

        @Override
        public @NonNull Result run(@NonNull String script) {
            this.scripts.add(script);
            return this.answer.apply(script);
        }

        String last() {
            return this.scripts.get(this.scripts.size() - 1);
        }

        String all() {
            return String.join("\n", this.scripts);
        }
    }

    /**
     * The btrfs lane end to end: a subvolume per volume, a qgroup limit that is enabled
     * first, a snapshot that lands OUTSIDE the volume, and a destroy that deletes the
     * subvolume rather than walking it.
     */
    @Test
    void theBtrfsMemberSpeaksSubvolumesQuotasAndSnapshots() {

        FakeShell shell = FakeShell.succeeding();
        VolumeOperations btrfs = VolumeOperations.forBackend(VolumeBackend.BTRFS, shell);
        String volume = "/srv/data/volumes/42/home";

        // 1. CREATE is idempotent by construction: it makes the parent, then creates the
        //    subvolume only when `subvolume show` does not already answer for it.
        btrfs.create(volume);
        assertThat(shell.last())
            .as("step 1: create makes the parent, probes, and creates a SUBVOLUME")
            .contains("mkdir -p '/srv/data/volumes/42'")
            .contains("btrfs subvolume show '" + volume + "'")
            .contains("btrfs subvolume create '" + volume + "'");
        assertThat(shell.last())
            .as("step 1: and never falls back to a plain directory, which would take no"
                + " quota and no snapshot while looking identical")
            .doesNotContain("mkdir -p '" + volume + "'");

        // 2. QUOTA: enabled on the filesystem first (a no-op where it already is), then
        //    limited on the subvolume. Without the enable the FIRST volume on a fresh host
        //    silently has no cap.
        btrfs.setQuota(volume, 5L * 1024 * 1024 * 1024);
        assertThat(shell.last())
            .as("step 2: quota enable precedes the limit, and the limit is in bytes")
            .contains("btrfs quota enable '/srv/data/volumes'")
            .contains("btrfs qgroup limit 5368709120 '" + volume + "'");

        // 3. A cleared cap is the word btrfs uses, not a zero.
        btrfs.setQuota(volume, null);
        assertThat(shell.last())
            .as("step 3: a null cap clears the limit")
            .contains("btrfs qgroup limit none '" + volume + "'");

        // 4. SNAPSHOT lands in a sibling .snapshots tree, read-only. A snapshot inside the
        //    volume would be copied by the next snapshot, forever.
        String snapshot = btrfs.snapshot(volume, "predeploy");
        assertThat(snapshot)
            .as("step 4: the snapshot path is outside the volume it copies")
            .startsWith("/srv/data/volumes/" + BtrfsVolumeOperations.SNAPSHOT_DIRECTORY
                + "/42/home@predeploy-")
            .doesNotContain(volume + "/");
        assertThat(shell.last())
            .as("step 4: taken read-only")
            .contains("btrfs subvolume snapshot -r '" + volume + "'");

        // 5. DESTROY deletes the subvolume; only a path that is NOT a subvolume falls back
        //    to rm -rf, so a stray directory is still reclaimed.
        btrfs.destroy(volume);
        assertThat(shell.last())
            .as("step 5: destroy deletes the subvolume, with rm -rf only as the fallback")
            .contains("btrfs subvolume delete '" + volume + "'")
            .contains("rm -rf '" + volume + "'");

        // 6. A refusing host is a NAMED violation carrying the command's own words, never
        //    a silent success -- a create nobody checked is a deploy onto no storage.
        FakeShell failing = new FakeShell(script ->
            new HostShell.Result(1, "ERROR: cannot create subvolume: Read-only file system"));
        VolumeOperations broken = VolumeOperations.forBackend(VolumeBackend.BTRFS, failing);
        assertThatThrownBy(() -> broken.create(volume))
            .as("step 6: a failed create refuses by name and quotes the host")
            .isInstanceOf(Violations.class)
            .hasMessageContaining("volume_create_failed");
    }

    /** Usage is parsed off the qgroup report, and an unreadable answer is -1, never 0. */
    @Test
    void usageIsParsedFromTheQgroupReport() {

        FakeShell shell = new FakeShell(script -> new HostShell.Result(0,
            "qgroupid         rfer         excl \n"
                + "--------         ----         ---- \n"
                + "0/258        73400320     73400320 "));
        VolumeOperations btrfs = VolumeOperations.forBackend(VolumeBackend.BTRFS, shell);

        assertThat(btrfs.usage("/srv/data/volumes/42/home"))
            .as("step 1: the referenced column is the answer, header lines and all")
            .isEqualTo(73400320L);

        FakeShell unreadable = new FakeShell(script ->
            new HostShell.Result(1, "ERROR: quotas not enabled"));
        assertThat(VolumeOperations.forBackend(VolumeBackend.BTRFS, unreadable)
                .usage("/srv/data/volumes/42/home"))
            .as("step 2: an unreadable usage is -1 -- reporting 0 would read as an empty"
                + " volume, which is the number an operator acts on")
            .isEqualTo(-1L);
    }

    /**
     * Every backend nothing implements yet refuses BY NAME, on every operation.
     *
     * AIDEV-NOTE: this is the falsification of the fail-closed claim. A degrading stub
     * (create a plain directory for ZFS, skip the quota for XFS) would pass every other
     * test in this suite and hand a workspace a cap nothing enforces.
     */
    @Test
    void everyUnimplementedBackendRefusesByName() {

        for (VolumeBackend backend : List.of(VolumeBackend.ZFS, VolumeBackend.XFS_PRJQUOTA,
                VolumeBackend.NONE)) {
            FakeShell shell = FakeShell.succeeding();
            VolumeOperations operations = VolumeOperations.forBackend(backend, shell);
            String path = "/srv/data/volumes/1/data";

            assertThatThrownBy(() -> operations.create(path))
                .as(backend + ": create refuses")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("volume_backend_unimplemented");
            assertThatThrownBy(() -> operations.setQuota(path, 1L))
                .as(backend + ": setQuota refuses").isInstanceOf(Violations.class);
            assertThatThrownBy(() -> operations.usage(path))
                .as(backend + ": usage refuses").isInstanceOf(Violations.class);
            assertThatThrownBy(() -> operations.snapshot(path, "x"))
                .as(backend + ": snapshot refuses").isInstanceOf(Violations.class);
            assertThatThrownBy(() -> operations.deleteSnapshot(path))
                .as(backend + ": deleteSnapshot refuses").isInstanceOf(Violations.class);
            assertThatThrownBy(() -> operations.destroy(path))
                .as(backend + ": destroy refuses").isInstanceOf(Violations.class);

            assertThat(shell.all())
                .as(backend + ": and it never ran a command on the host to find out")
                .isEmpty();
        }
    }
}
