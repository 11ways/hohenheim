package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.ControllerIdentity;
import be.elevenways.hohenheim.server.backup.BackupTarget;
import be.elevenways.hohenheim.server.backup.FilesystemBackupTarget;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.host.IncusPreflight;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.crypto.EncryptionKeyring;
import be.elevenways.zenit.server.orm.crypto.FieldEncryption;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The backup lane's shared fixture: a private migrated database, a fake native daemon, an
 * admitted incus host and a filesystem backup target on a temp directory.
 *
 * AIDEV-NOTE: this exists because the backup journeys had to be SPLIT ACROSS LANES.
 * {@code InstanceBackupsTest} is daemon-free and belongs in the default lane; exactly one
 * of its journeys needs a filesystem that can refuse a delete (i.e. a non-root process),
 * which is a {@code LiveLane} need and therefore a @Tag("slow") CLASS -- so keeping them
 * together cost the default lane seven hermetic journeys. Both classes install THIS, so
 * the split cost no fixture drift; a change to the shape belongs here and nowhere else.
 */
final class BackupLaneFixture {

    final SqlDatasource datasource;
    final int hostId;
    final int targetId;
    final Path targetRoot;
    final BackupTarget target;

    private BackupLaneFixture(SqlDatasource datasource, int hostId, int targetId,
                              Path targetRoot, BackupTarget target) {
        this.datasource = datasource;
        this.hostId = hostId;
        this.targetId = targetId;
        this.targetRoot = targetRoot;
        this.target = target;
    }

    /**
     * Boot the lane: private database, booted runtime, staging + target directories, a
     * keyring, the fake native kind, one admitted incus host and one target record.
     */
    static BackupLaneFixture install() throws Exception {
        // TestDatabases, never a hand-rolled SqliteDatasource: it copies the migrated
        // template instead of re-running the migration set, remints the controller
        // identity and re-points the services that CAPTURE a datasource (zenit-auth's
        // models and session store, the task service) -- the three things the hand-rolled
        // shape skipped, and the reason a class can share a JVM at all.
        SqlDatasource datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();

        Path staging = Files.createTempDirectory("hohenheim-backup-staging");
        staging.toFile().deleteOnExit();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Backup.STAGING_PATH,
            staging.toAbsolutePath().toString());
        Path targetRoot = Files.createTempDirectory("hohenheim-backup-target");
        targetRoot.toFile().deleteOnExit();
        BackupTarget target = new FilesystemBackupTarget(targetRoot);
        FieldEncryption.installKeyring(EncryptionKeyring.loadOrCreate(
            staging.resolve("test-ring.keys")));

        FakeNativeDaemons.register();
        int[] ids = new int[2];
        Db.run(datasource, () -> {
            ids[0] = incusHost("backup-test-host");
            Row record = Models.get(BackupTargetModel.class).createEmptyRow();
            record.set(BackupTargetModel.NAME, "backup-test-target");
            record.set(BackupTargetModel.KIND, "hohenheim:filesystem");
            record.set(BackupTargetModel.SETTINGS,
                Map.of("path", targetRoot.toAbsolutePath().toString()));
            Models.get(BackupTargetModel.class).save(record);
            ids[1] = record.get(BackupTargetModel.ID);
        });
        return new BackupLaneFixture(datasource, ids[0], ids[1], targetRoot, target);
    }

    /** Hand the process-global keyring back; the install is the only thing that took one. */
    static void uninstall() {
        FieldEncryption.installKeyring(null);
    }

    /** An instance record of the fake native kind, placed on the fixture's host. */
    static int instanceRecord(String name, int serverId) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, FakeNativeDaemons.FakeNativeKind.ID.toString());
        row.set(InstanceModel.SETTINGS, Map.of("image", "fake/image"));
        row.set(InstanceModel.SERVER_ID, serverId);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    /** The remote key of one backup row, with the controller-token prefix asserted. */
    static String remoteKeyOf(int backupId) {
        Row row = Models.get(InstanceBackupModel.class).findById(backupId);
        assertThat(row).as("backup row %s exists", backupId).isNotNull();
        String key = row.get(InstanceBackupModel.REMOTE_KEY);
        assertThat(key).as("backup row %s carries a remote key", backupId).isNotBlank();
        assertThat(key)
            .as("the controller token still leads the key (two CONTROLLERS sharing a"
                + " target was the first collision, and it must stay fixed)")
            .startsWith(ControllerIdentity.token() + "/");
        return key;
    }

    private static int incusHost(String name) {
        Row row = Models.get(ServerModel.class).createEmptyRow();
        row.set(ServerModel.NAME, name);
        row.set(ServerModel.RUNTIME, ServerModel.RUNTIME_INCUS);
        row.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        row.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        Models.get(ServerModel.class).save(row);
        HostFixtures.acknowledgePosture(row);
        HostPreflight.store(name, new HostPreflight.Report(List.of(
            new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true, "fake daemon"),
            new HostPreflight.Check(IncusPreflight.KERNEL_LANE_CHECK,
                HostPreflight.STATUS_PASS, true, "fake kernel-truth lane")),
            Map.of("mem_total", 16L * 1024 * 1024 * 1024), true, Instant.now(), null));
        return Models.get(ServerModel.class).findByName(name).get(ServerModel.ID);
    }
}
