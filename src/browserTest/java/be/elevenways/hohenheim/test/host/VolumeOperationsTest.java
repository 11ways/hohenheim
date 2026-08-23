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
import static org.assertj.core.api.Assertions.assertThatCode;
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
        public @NonNull Result run(@NonNull String script, long timeoutSeconds) {
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
     * THE drift guard: what {@link VolumeBackend#isImplemented} declares is what this
     * factory actually does, member by member, in BOTH directions.
     *
     * AIDEV-NOTE: this is deliberately not a comparison against a list of member names
     * beside the enum -- such a test is circular and catches nothing. It walks
     * {@code values()} and DRIVES each member through the factory: a member that declares
     * itself implemented must perform a real command, and one that does not must refuse by
     * name having touched no host. Declaring ZFS implemented without writing
     * ZfsVolumeOperations fails here; writing them and forgetting the flag fails here too.
     *
     * AIDEV-NOTE: this is also the falsification of the fail-closed claim. A degrading stub
     * (create a plain directory for ZFS, skip the quota for XFS) would pass every other
     * test in this suite and hand a workspace a cap nothing enforces.
     */
    @Test
    void theImplementedFlagIsWhatTheOperationsActuallyDo() {

        String path = "/srv/data/volumes/1/data";
        List<VolumeBackend> implemented = new ArrayList<>();
        List<VolumeBackend> refusing = new ArrayList<>();

        for (VolumeBackend backend : VolumeBackend.values()) {
            FakeShell shell = FakeShell.succeeding();
            VolumeOperations operations = VolumeOperations.forBackend(backend, shell);

            if (backend.isImplemented()) {
                // 1. A member that DECLARES itself implemented must actually act: create
                //    runs a command on the host and does not answer with the refusal.
                implemented.add(backend);
                assertThatCode(() -> operations.create(path))
                    .as("step 1: %s declares itself implemented, so create must not answer"
                        + " with the unimplemented refusal", backend)
                    .doesNotThrowAnyException();
                assertThat(shell.all())
                    .as("step 1: %s declares itself implemented, so create must run a"
                        + " command on the host", backend)
                    .isNotEmpty();
                continue;
            }

            // 2. A member that does NOT is refused by name on every single operation --
            //    never a create that quietly makes a plain directory, which is a volume
            //    with no cap that looks exactly like one with a cap.
            refusing.add(backend);
            assertThatThrownBy(() -> operations.create(path))
                .as("step 2: %s: create refuses", backend)
                .isInstanceOf(Violations.class)
                .hasMessageContaining("volume_backend_unimplemented");
            assertThatThrownBy(() -> operations.setQuota(path, 1L))
                .as("step 2: %s: setQuota refuses", backend).isInstanceOf(Violations.class);
            assertThatThrownBy(() -> operations.usage(path))
                .as("step 2: %s: usage refuses", backend).isInstanceOf(Violations.class);
            assertThatThrownBy(() -> operations.snapshot(path, "x"))
                .as("step 2: %s: snapshot refuses", backend).isInstanceOf(Violations.class);
            assertThatThrownBy(() -> operations.deleteSnapshot(path))
                .as("step 2: %s: deleteSnapshot refuses", backend).isInstanceOf(Violations.class);
            assertThatThrownBy(() -> operations.destroy(path))
                .as("step 2: %s: destroy refuses", backend).isInstanceOf(Violations.class);
            assertThatThrownBy(() -> operations.own(path, 200001))
                .as("step 2: %s: own refuses", backend).isInstanceOf(Violations.class);

            assertThat(shell.all())
                .as("step 2: %s: and it never ran a command on the host to find out", backend)
                .isEmpty();
        }

        // 3. Both halves are non-empty, so neither branch above can pass by never running:
        //    an all-refusing enum would make step 1 vacuous, and an all-implemented one
        //    would make step 2 vacuous.
        assertThat(implemented)
            .as("step 3: at least one backend is actually implemented")
            .isNotEmpty();
        assertThat(refusing)
            .as("step 3: and the refusing half is exercised too")
            .isNotEmpty();

        // 4. The capability facts placement narrows on FOLD implementedness in, so the
        //    picker can never offer a host whose first deploy would refuse. This is the
        //    exact lie this test was written for: ZFS declared a quota, placement believed
        //    it, and VolumeOperations refused the deploy.
        for (VolumeBackend backend : refusing) {
            assertThat(backend.supportsQuota())
                .as("step 4: %s is not implemented, so it enforces no quota for us", backend)
                .isFalse();
            assertThat(backend.supportsSnapshot())
                .as("step 4: nor can it snapshot for us", backend)
                .isFalse();
            assertThat(VolumeBackend.quotaCapableTokens())
                .as("step 4: and a picker narrowing on the tokens never offers it", backend)
                .doesNotContain(backend.token());
        }
    }
}
