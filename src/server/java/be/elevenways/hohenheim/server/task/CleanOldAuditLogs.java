package be.elevenways.hohenheim.server.task;

import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.hohenheim.model.AuditLogModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.protoblast.common.Blast;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Deletes audit log entries older than the retention period.
 */
public class CleanOldAuditLogs implements Runnable {

    private static final int RETENTION_DAYS = 90;

    @Override
    public void run() {
        try {
            var ds = HohenheimDatabase.datasource();
            var model = Models.get(AuditLogModel.class);
            Instant cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);

            long deleted = model.find()
                .where(AuditLogModel.CREATED_AT.lte(cutoff))
                .delete();

            if (deleted > 0) {
                Blast.log("TASK: CleanOldAuditLogs removed", deleted, "entries older than", RETENTION_DAYS, "days");
            }
        } catch (Exception e) {
            Blast.log("TASK: CleanOldAuditLogs failed:", e.getMessage());
        }
    }
}
