package be.elevenways.hohenheim.test.migration;

import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.migration.M061_InstanceBackupSchedules;
import be.elevenways.hohenheim.server.schedule.InstanceBackupAction;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.common.task.record.RecordScheduleStepModel;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The M061 data step against a simulated pre-M061 install: the doomed backup_enabled
 * flag column is re-created as a fixture, populated, and the REAL backfill converts
 * exactly the live flagged instances into one-step backup schedules.
 */
class RecordScheduleBackfillTest {

    private static SqliteDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-backfill-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        // ONE database per test class: the controller identity (and therefore every
        // daemon resource name) resolves through the CURRENT datasource, and a Db scope
        // is thread-local -- so a second, unregistered database would hand any
        // thread-hopping work a different controller's token than the records came from.
        Datasources.register(Datasources.DEFAULT, datasource);
    }

    @AfterAll
    static void tearDown() {
        datasource.close();
    }

    private static int instance(String name, boolean flagged, boolean deleted) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine")));
        if (deleted) {
            row.set(InstanceModel.DELETED_AT, Instant.now());
        }
        Models.get(InstanceModel.class).save(row);
        int id = row.get(InstanceModel.ID);
        if (flagged) {
            datasource.unwrap(SqlDatasource.class).rawUpdate(
                "UPDATE instances SET backup_enabled = 1 WHERE id = ?", id);
        }
        return id;
    }

    @Test
    void backfillConvertsExactlyTheLiveFlaggedInstancesJourney() {
        Db.run(datasource, () -> {
            // 1. Simulate the pre-M061 shape: the flag column exists again.
            datasource.unwrap(SqlDatasource.class).rawUpdate(
                "ALTER TABLE instances ADD COLUMN backup_enabled INTEGER NOT NULL DEFAULT 0");

            // 2. One live flagged instance, one soft-DELETED flagged one, one unflagged.
            int live = instance("backfill-live", true, false);
            instance("backfill-deleted", true, true);
            instance("backfill-off", false, false);

            // 3. Run the REAL data step.
            M061_InstanceBackupSchedules.backfill(datasource);

            // 4. Exactly the live flagged instance got a schedule, with the M061 shape:
            //    nightly cron, system authority, one abort-policy backup step.
            List<Row> schedules = Models.get(RecordScheduleModel.class).find().all();
            assertThat(schedules)
                .as("step 4: exactly one schedule was created").hasSize(1);
            Row schedule = schedules.get(0);
            assertThat(schedule.get(RecordScheduleModel.RECORD_ID))
                .as("step 4: for the LIVE flagged instance")
                .isEqualTo(String.valueOf(live));
            assertThat(schedule.get(RecordScheduleModel.CRON))
                .as("step 4: with the old nightly cadence").isEqualTo("0 4 * * *");
            assertThat(schedule.get(RecordScheduleModel.RUN_AS))
                .as("step 4: under system authority (the old task was operator config)")
                .isNull();
            assertThat(Boolean.TRUE.equals(schedule.get(RecordScheduleModel.ENABLED)))
                .as("step 4: enabled").isTrue();

            List<Row> steps = Models.get(RecordScheduleStepModel.class)
                .findChain(schedule.get(RecordScheduleModel.ID));
            assertThat(steps).as("step 4: with exactly one step").hasSize(1);
            assertThat(steps.get(0).get(RecordScheduleStepModel.ACTION))
                .as("step 4: the backup action")
                .isEqualTo(InstanceBackupAction.ID.toString());
        });
    }
}
