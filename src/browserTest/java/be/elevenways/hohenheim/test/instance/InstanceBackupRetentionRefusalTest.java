package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.server.backup.BackupTarget;
import be.elevenways.hohenheim.server.instance.InstanceBackups;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retention over an artifact the target REFUSES to release.
 *
 * AIDEV-NOTE: this is the one backup journey that cannot be hermetic, which is why it is
 * a class of its own. Making a real filesystem refuse a delete needs a process that is
 * not root, and "am I root" is a HOST capability -- a {@code LiveLane.Need}, and therefore
 * @Tag("slow"). It used to live in {@code InstanceBackupsTest}, where the tag it forced
 * onto the whole class evicted seven daemon-free journeys from the default lane. Both
 * classes install the same {@link BackupLaneFixture}, so nothing about the setup drifted
 * in the split; keep any new LIVE backup journey here and any hermetic one there.
 */
@Tag("slow") // live lane: needs a non-root process (a filesystem that can refuse a delete)
class InstanceBackupRetentionRefusalTest {

    private static SqlDatasource datasource;
    private static int hostId;
    private static Path targetRoot;
    private static int targetId;
    private static BackupTarget target;

    @BeforeAll
    static void setUp() throws Exception {
        BackupLaneFixture fixture = BackupLaneFixture.install();
        datasource = fixture.datasource;
        hostId = fixture.hostId;
        targetRoot = fixture.targetRoot;
        targetId = fixture.targetId;
        target = fixture.target;
    }

    @AfterAll
    static void tearDown() {
        BackupLaneFixture.uninstall();
    }

    /**
     * The row must SURVIVE (the snapshot lanes' rule -- a row deleted while its payload
     * survives is a prune reporting success for work it did not do), and the next sweep
     * retries.
     */
    @Test
    void retentionKeepsTheRowOfAnArtifactTheTargetCouldNotDelete() throws IOException {
        // A denied directory is the only honest way to make a real filesystem refuse,
        // and root is denied nothing -- a DECLARED host need, reported when unmet.
        Path probe = Files.createTempDirectory("hohenheim-backup-perm-probe");
        Files.writeString(probe.resolve("child"), "x");
        Files.setPosixFilePermissions(probe, PosixFilePermissions.fromString("r-xr-xr-x"));
        boolean canBeRefused;
        try {
            Files.delete(probe.resolve("child"));
            canBeRefused = false;
        } catch (IOException refused) {
            canBeRefused = true;
        }
        Files.setPosixFilePermissions(probe, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.deleteIfExists(probe.resolve("child"));
        Files.deleteIfExists(probe);
        LiveLane.require(LiveLane.Need.UNPRIVILEGED_FS, canBeRefused,
            "this process can delete anything (running as root): a refused artifact"
                + " removal cannot be produced here");

        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();
            InstanceBackups backups = new InstanceBackups();
            int instanceId = BackupLaneFixture.instanceRecord("backup-stuck-prune", hostId);
            service.deploy(instanceId);

            // 1. Two completed backups, retention 1: the older one's artifact is made
            //    undeletable by denying writes on its directory.
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Backup.RETENTION, 0);
            int older = backups.backupNow(instanceId, targetId, target);
            int newer = backups.backupNow(instanceId, targetId, target);
            Path olderArtifact = targetRoot.resolve(BackupLaneFixture.remoteKeyOf(older));
            assertThat(Files.isRegularFile(olderArtifact))
                .as("step 1: the older backup really committed an artifact").isTrue();
            Path artifactDir = olderArtifact.getParent();
            denyWrites(artifactDir);
            try {
                // 2. The prune cannot remove the artifact, so the ROW stays: what
                //    remains is still named by a record a later sweep can act on.
                HohenheimSettings.VALUES.setValue(HohenheimSettings.Backup.RETENTION, 1);
                backups.pruneForRetention(instanceId);
                assertThat(Map.of(
                        "row", String.valueOf(
                            Models.get(InstanceBackupModel.class).findById(older) != null),
                        "artifact", String.valueOf(Files.isRegularFile(olderArtifact))))
                    .as("step 2: an artifact the target could not delete KEEPS its row")
                    .isEqualTo(Map.of("row", "true", "artifact", "true"));
            } finally {
                allowWrites(artifactDir);
            }

            // 3. Once the target lets go the very next sweep removes both: the row was
            //    kept for a retry, not kept forever.
            backups.pruneForRetention(instanceId);
            assertThat(Map.of(
                    "row", String.valueOf(
                        Models.get(InstanceBackupModel.class).findById(older) != null),
                    "artifact", String.valueOf(Files.exists(olderArtifact))))
                .as("step 3: the retry sweep removed the row AND the artifact")
                .isEqualTo(Map.of("row", "false", "artifact", "false"));
            assertThat(Models.get(InstanceBackupModel.class).findById(newer))
                .as("step 3: the newest backup was never touched").isNotNull();

            HohenheimSettings.VALUES.setValue(HohenheimSettings.Backup.RETENTION, 7);
            service.destroy(instanceId);
        });
    }

    private static void denyWrites(Path directory) {
        try {
            Files.setPosixFilePermissions(directory,
                PosixFilePermissions.fromString("r-xr-xr-x"));
        } catch (IOException failed) {
            throw new IllegalStateException(failed);
        }
    }

    private static void allowWrites(Path directory) {
        try {
            Files.setPosixFilePermissions(directory,
                PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (IOException failed) {
            throw new IllegalStateException(failed);
        }
    }
}
