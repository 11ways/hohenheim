package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.ProclogModel;
import be.elevenways.hohenheim.server.task.CleanOldProclogs;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proclog retention over UPSERTED rows: a proclog row lives for the whole life of ONE
 * process ({@code ManagedProcessSiteHandler.persistProclog} re-finds it by id on every
 * flush), so the sweep must age rows by their LAST write, never by the episode's start
 * -- the same rule {@code CleanOldInstanceLogs} already spells out for the instance
 * tier. What this class cannot prove: no real child process writes here, so the
 * flush-recreates-a-deleted-row half of the failure is asserted only as the surviving
 * row, not as an observed re-insert.
 */
class ProclogRetentionTest {

    private static SqliteDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-proclog-retention-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        Datasources.register(Datasources.DEFAULT, datasource);
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    void theSweepAgesALogByItsLastWriteNotByItsProcessStartTime() {
        Db.run(datasource, () -> {
            Instant now = Instant.now();
            Instant beyondRetention = now.minus(40, ChronoUnit.DAYS);

            // 1. Three episodes: a LIVE one whose process started 40 days ago but whose
            //    log was flushed moments ago, a FINISHED recent one, and a finished one
            //    whose last write is beyond the window.
            int live = proclog(1, beyondRetention, now);
            int recent = proclog(2, now.minus(1, ChronoUnit.DAYS), now);
            int retired = proclog(3, beyondRetention, beyondRetention);

            // 2. The sweep. Before the fix it swept created_at, which deleted the LIVE
            //    row mid-write -- the next flush silently started a fresh row and the
            //    month of history the window promised was gone.
            CleanOldProclogs.clean();

            assertThat(Map.of(
                    "live", String.valueOf(Models.get(ProclogModel.class).findById(live) != null),
                    "recent", String.valueOf(Models.get(ProclogModel.class).findById(recent) != null),
                    "retired", String.valueOf(Models.get(ProclogModel.class).findById(retired) != null)))
                .as("step 2: a log still being written to SURVIVES no matter how old its"
                    + " process is, a recent finished log survives, and only the log whose"
                    + " LAST write left the window is swept")
                .isEqualTo(Map.of("live", "true", "recent", "true", "retired", "false"));
        });
    }

    private static int proclog(int pid, Instant createdAt, Instant savedAt) {
        Row row = Models.get(ProclogModel.class).createEmptyRow();
        row.set(ProclogModel.SITE_ID, 1);
        row.set(ProclogModel.PID, pid);
        row.set(ProclogModel.LOG_HTML, "line\n");
        row.set(ProclogModel.LINE_COUNT, 1);
        row.set(ProclogModel.SAVED_AT, savedAt);
        Models.get(ProclogModel.class).save(row);
        int id = row.get(ProclogModel.ID);
        // created_at is behaviour-stamped at save; force the episode start explicitly.
        Models.get(ProclogModel.class).find()
            .where(ProclogModel.ID.eq(id))
            .assign(ProclogModel.CREATED_AT, createdAt)
            .assign(ProclogModel.SAVED_AT, savedAt)
            .updateAll();
        return id;
    }
}
