package be.elevenways.hohenheim.test.host;

import be.elevenways.hohenheim.host.VolumeBackend;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.VolumeBackends;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the volume probe concludes from real {@code stat -f} / {@code findmnt} output, and
 * that it fails CLOSED for everything it does not recognise.
 *
 * AIDEV-NOTE: the classification is tested against LITERAL probe output rather than a
 * mocked filesystem because that string IS the contract with the host -- the defect this
 * guards is reading "xfs" and promising a quota that only a {@code prjquota} mount can
 * enforce. The live btrfs lane runs on daystrom/nightstrom; here the local controller's own
 * root is probed for real and asserted only on the properties every filesystem must have.
 */
class VolumeBackendProbeTest {

    @BeforeAll
    static void bootDatasource() {
        be.elevenways.hohenheim.test.HohenheimTestRuntime.ensureDatasource();
    }

    @Test
    @DisplayName("the probe reads the filesystem, and an unrecognised one refuses")
    void classificationIsEvidenceBased() {

        // 1. The probe names the FILESYSTEM it found. Whether this build can drive it is
        //    a separate fact on the member (isImplemented), never the probe's business.
        assertThat(classify("/data\nbtrfs\nrw,relatime,subvol=/x").backend())
            .as("step 1: btrfs is recognised").isEqualTo(VolumeBackend.BTRFS);
        assertThat(classify("/data\nzfs\nrw,xattr,noacl").backend())
            .as("step 1: zfs is recognised too, unimplemented as it is")
            .isEqualTo(VolumeBackend.ZFS);

        // 2. XFS is the whole reason the mount options are read: the SAME fstype answers
        //    differently depending on whether project quota is actually on.
        assertThat(classify("/data\nxfs\nrw,relatime,attr2,prjquota").backend())
            .as("step 2: xfs WITH prjquota can enforce a quota")
            .isEqualTo(VolumeBackend.XFS_PRJQUOTA);
        assertThat(classify("/data\nxfs\nrw,relatime,attr2,inode64").backend())
            .as("step 2: xfs WITHOUT it cannot, and must not claim it")
            .isEqualTo(VolumeBackend.NONE);
        assertThat(classify("/data\nxfs\nrw,relatime,attr2,inode64").detail())
            .as("step 2: and the row says why, so an operator can fix it")
            .contains("prjquota");

        // 3. Everything else fails CLOSED. ext4 and tmpfs are what the controller and every
        //    CI runner actually have, so this is the common answer, not the exotic one.
        for (String fstype : List.of("ext2/ext3", "tmpfs", "overlayfs", "wat")) {
            assertThat(classify("/data\n" + fstype + "\nrw,relatime").backend())
                .as("step 3: '%s' promises nothing", fstype)
                .isEqualTo(VolumeBackend.NONE);
        }

        // 4. A probe that could not run at all is NONE too, never an exception and never a
        //    guess: a host nobody could read must refuse workspaces like a host with no quota.
        assertThat(classify("").backend())
            .as("step 4: empty probe output is not a filesystem")
            .isEqualTo(VolumeBackend.NONE);
    }

    @Test
    @DisplayName("only quota-capable backends declare a quota, and the token round-trips")
    void capabilitiesAndTokensAgree() {

        // 1. The facts placement reads are declared per member, with NONE as the
        //    fail-closed floor.
        assertThat(VolumeBackend.NONE.supportsQuota()).as("step 1: none enforces nothing").isFalse();
        assertThat(VolumeBackend.NONE.supportsSnapshot()).isFalse();
        assertThat(VolumeBackend.NONE.filesystemEnforcesQuota())
            .as("step 1: and its filesystem could not either").isFalse();

        // 1b. XFS-with-prjquota is the member that separates the two questions: the mount
        //     CAN enforce a size cap, and this build still cannot apply one -- so the fact
        //     placement reads says no while the fact the host page explains with says yes.
        assertThat(VolumeBackend.XFS_PRJQUOTA.filesystemEnforcesQuota())
            .as("step 1b: an xfs prjquota mount can enforce a size cap").isTrue();
        assertThat(VolumeBackend.XFS_PRJQUOTA.isImplemented())
            .as("step 1b: but nothing here drives it").isFalse();
        assertThat(VolumeBackend.XFS_PRJQUOTA.supportsQuota())
            .as("step 1b: so placement must not be told it can, which is the promise the"
                + " first deploy used to break").isFalse();
        assertThat(VolumeBackend.XFS_PRJQUOTA.supportsSnapshot())
            .as("step 1b: and it cannot snapshot either way").isFalse();

        // 2. The stored token is the spelling, not the enum name -- the DatabaseEngine
        //    lesson: valueOf would bind the column to a Java identifier.
        for (VolumeBackend backend : VolumeBackend.values()) {
            assertThat(VolumeBackend.forToken(backend.token()))
                .as("step 2: '%s' round-trips", backend.token()).isEqualTo(backend);
        }
        assertThat(VolumeBackend.forToken("BTRFS")).as("step 2: the enum name is not a token").isNull();
        assertThat(VolumeBackend.forToken("ext4")).as("step 2: an unknown token resolves to nothing").isNull();
        assertThat(VolumeBackend.forToken(null)).isNull();

        // 3. resolve() is the read a host row takes, and it folds absent and unknown onto
        //    the refusing member instead of onto a capable one.
        assertThat(VolumeBackend.resolve(null)).as("step 3: never probed is not capable")
            .isEqualTo(VolumeBackend.NONE);
        assertThat(VolumeBackend.resolve("ext4")).isEqualTo(VolumeBackend.NONE);
    }

    @Test
    @DisplayName("the controller's own root is probed for real and stored on the host row")
    void theLocalHostIsProbedAndStored() {

        Row local = Models.get(ServerModel.class).findById(ServerModel.localServerId());
        assertThat(local).as("the local host row exists").isNotNull();

        // 1. A REAL probe of a real path: whatever this machine runs, the probe answers a
        //    declared member and says what it saw.
        VolumeBackends.Detection detection = VolumeBackends.probe(local);
        assertThat(detection.backend()).as("step 1: the probe answers a declared member").isNotNull();
        assertThat(detection.detail()).as("step 1: and carries its evidence").isNotBlank();
        assertThat(detection.root()).as("step 1: naming the directory it probed")
            .isEqualTo(VolumeBackends.volumeRoot());

        // 2. Storing it makes the answer readable without re-probing, which is what
        //    placement depends on.
        VolumeBackends.store(local, detection);
        Row reread = Models.get(ServerModel.class).findById(ServerModel.localServerId());
        assertThat(ServerModel.volumeBackendOf(reread))
            .as("step 2: the stored token reads back as the same member")
            .isEqualTo(detection.backend());
        assertThat(reread.get(ServerModel.VOLUME_PROBED_AT))
            .as("step 2: and a never-probed host stays distinguishable from a probed one")
            .isNotNull();
    }

    private static VolumeBackends.Detection classify(String probeOutput) {
        return VolumeBackends.classify("/data", probeOutput);
    }
}
