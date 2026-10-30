package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BackupStatus;
import be.elevenways.hohenheim.model.DatabaseDumpModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.cms.DatabaseAttention;
import be.elevenways.hohenheim.server.database.DatabaseBackups;
import be.elevenways.hohenheim.server.database.DumpOverCap;
import be.elevenways.hohenheim.server.database.ReservedDiskOutput;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.test.support.DatasourceProbe;
import be.elevenways.zenit.test.support.ServedApp;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.ByteArrayOutputStream;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A database dump that fails, or a database whose backups have gone quiet, reads as attention on the panel, not only
 * as an alert a mail transport may never deliver; and a dump its disk has no room for is refused before it starts.
 *
 * AIDEV-NOTE: the incident (Robbedoes, 2026-09-09 to 2026-10-30): invulassistent's dump failed nightly for "no space
 * left on device" with no mail transport configured, unseen until the full disk took the shared Mongo engine down.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class DatabaseBackupAttentionTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.standalone();

    private static final long GIB = 1024L * 1024 * 1024;

    private static SqlDatasource datasource;

    @BeforeAll
    static void setUp() {
        datasource = APP.database();
    }

    @Test
    void aFailingOrQuietDatabaseBackupIsAttentionInFlatReads() {
        Db.run(datasource, () -> {
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Backup.STALE_AFTER_DAYS, 7);
            int shop = database("attn-dump-shop", false, InstanceModel.STATUS_RUNNING);
            database("attn-dump-scratch", true, InstanceModel.STATUS_RUNNING);
            database("attn-dump-stopped", false, InstanceModel.STATUS_STOPPED);

            // 1. Never dumped: the durable database that runs reads "never backed up". A temporary one, which the
            //    nightly backup skips by design, and a stopped one, which it cannot dump and whose own item says it
            //    is not running, say nothing here.
            assertThat(raised()).as("step 1: only the durable running database, never backed up")
                .containsExactly("database_backup_never warning /admin/databases/" + shop + "/page/backups");

            // 2. Its newest attempt failed: the failure, in its own words, as an error.
            dump(shop, BackupStatus.FAILED, "No space left on device");
            List<AttentionItem> failing = collect();
            assertThat(failing).extracting(item -> item.title().key() + " " + item.severity().key())
                .as("step 2: failing and never backed up").containsExactlyInAnyOrder(
                    "database_backup error", "database_backup_never warning");
            assertThat(failing).filteredOn(item -> item.title().key().equals("database_backup"))
                .extracting(item -> item.detail().key())
                .as("step 2: in the failure's own words").containsExactly("No space left on device");

            // 3. A fresh complete dump: silence.
            dump(shop, BackupStatus.COMPLETE, null);
            assertThat(raised()).as("step 3: a fresh dump silences both").isEmpty();

            // 4. That dump aged past the window: stale, even while nothing failed since.
            Models.get(DatabaseDumpModel.class).find().where(DatabaseDumpModel.DATABASE_ID.eq(shop))
                .assign(DatabaseDumpModel.CREATED_AT, Now.instant().minus(30, ChronoUnit.DAYS)).updateAll();
            assertThat(raised()).as("step 4: stale")
                .containsExactly("database_backup_stale warning /admin/databases/" + shop + "/page/backups");

            // 5. Flat reads: three times the databases, the same round trips.
            DatasourceProbe probe = DatasourceProbe.on(datasource);
            probe.reset();
            collect();
            int few = probe.roundTrips();
            for (int i = 0; i < 4; i++) {
                int more = database("attn-dump-more-" + i, false, InstanceModel.STATUS_RUNNING);
                dump(more, BackupStatus.FAILED, "target refused");
            }
            probe.reset();
            collect();
            assertThat(probe.roundTrips()).as("step 5: flat as databases grow").isEqualTo(few);
        });
    }

    @Test
    void aDumpItsDiskHasNoRoomForIsRefusedBeforeItStarts() throws Exception {
        // 1. A step must leave the 2 GiB reserve free: a dump estimated at 10 GiB (twice a 5 GiB previous one) needs
        //    12 GiB, so 11 is refused with the operator's words naming both sizes.
        Throwable refused = catchThrowable(() -> DatabaseBackups.judgeRoom("invulassistent", 11 * GIB, 10 * GIB));
        assertThat(refused).as("step 1: refused").isInstanceOf(DumpOverCap.class);
        assertThat(DumpOverCap.wordsOf(refused)).as("step 1: in words").isNotNull()
            .satisfies(words -> {
                assertThat(words.key()).isEqualTo("dump_no_room");
                assertThat(words.args().get("free")).isEqualTo(11 * GIB);
                assertThat(words.args().get("needed")).isEqualTo(12 * GIB);
            });

        // 2. With room, nothing is refused; a database never dumped needs only the reserve.
        DatabaseBackups.judgeRoom("invulassistent", 12 * GIB, 10 * GIB);
        DatabaseBackups.judgeRoom("new-database", 2 * GIB, 0);
        assertThat(catchThrowable(() -> DatabaseBackups.judgeRoom("new-database", GIB, 0)))
            .as("step 2: below the reserve even an empty database is refused").isInstanceOf(DumpOverCap.class);

        // 3. THE EXACT HALF: a database that grew since its last dump is stopped WHILE it streams, before the disk
        //    drops under the reserve: here the disk shrinks by what is written, and the write stops in time.
        long chunk = ReservedDiskOutput.CHECK_EVERY_BYTES;
        long[] free = {2 * GIB + 3 * chunk};
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        byte[] block = new byte[(int) chunk];
        Throwable stopped = catchThrowable(() -> {
            try (ReservedDiskOutput out = new ReservedDiskOutput(sink, () -> free[0], "growing", 2 * GIB)) {
                for (int i = 0; i < 10; i++) {
                    out.write(block, 0, block.length);
                    free[0] -= block.length;
                }
            }
        });
        assertThat(stopped).as("step 3: the write is stopped by name").isInstanceOf(DumpOverCap.class);
        assertThat(free[0]).as("step 3: and the reserve was never touched").isGreaterThanOrEqualTo(2 * GIB);
    }

    /** A database whose dedicated engine instance reads {@code engineStatus}. */
    private static int database(String name, boolean ephemeral, String engineStatus) {
        Row row = Models.get(DatabaseModel.class).createEmptyRow();
        row.set(DatabaseModel.NAME, name);
        row.set(DatabaseModel.ENGINE, "postgres");
        row.set(DatabaseModel.STATUS, DatabaseModel.STATUS_ACTIVE);
        row.set(DatabaseModel.EPHEMERAL, ephemeral);
        row.set(DatabaseModel.SERVER_ID, ServerModel.localServerId());
        Models.get(DatabaseModel.class).save(row);
        int id = row.get(DatabaseModel.ID);
        EngineHandles.plant(id, name, "postgres", engineStatus);
        return id;
    }

    private static void dump(int databaseId, BackupStatus status, String error) {
        Row row = Models.get(DatabaseDumpModel.class).createEmptyRow();
        row.set(DatabaseDumpModel.DATABASE_ID, databaseId);
        row.set(DatabaseDumpModel.STATUS, status.key());
        row.set(DatabaseDumpModel.ERROR, error);
        if (status == BackupStatus.COMPLETE) {
            row.set(DatabaseDumpModel.REMOTE_KEY, "attention/" + databaseId + "-" + System.nanoTime() + ".enc");
        }
        Models.get(DatabaseDumpModel.class).save(row);
    }

    private static List<AttentionItem> collect() {
        List<AttentionItem> items = new ArrayList<>();
        DatabaseAttention.databaseBackups(items);
        return items;
    }

    /** Each item as "titleKey severity url". */
    private static List<String> raised() {
        List<String> rendered = new ArrayList<>();
        for (AttentionItem item : collect()) {
            rendered.add(item.title().key() + " " + item.severity().key() + " "
                + (item.target() == null ? "" : item.target().toUrl()));
        }
        return rendered;
    }
}
