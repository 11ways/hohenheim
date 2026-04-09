package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.model.ProclogModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.protoblast.common.Blast;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Deletes process log entries older than the retention period.
 */
public class CleanOldProclogs implements Runnable {

    private static final int RETENTION_DAYS = 30;

    @Override
    public void run() {
        try {
            var ds = HohenheimDatabase.datasource();
            var model = new ProclogModel(ds);
            Instant cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);

            long deleted = model.find()
                .where(ProclogModel.CREATED_AT.lte(cutoff))
                .delete();

            if (deleted > 0) {
                Blast.log("TASK: CleanOldProclogs removed", deleted, "entries older than", RETENTION_DAYS, "days");
            }
        } catch (Exception e) {
            Blast.log("TASK: CleanOldProclogs failed:", e.getMessage());
        }
    }
}
