package be.elevenways.hohenheim.server.task;

import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.hohenheim.model.AuditLogModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Deletes audit log entries older than the retention period. Runs daily.
 */
public class CleanOldAuditLogs extends ScheduledTask {

    public static final String STATIC_DESCRIPTION = "Delete old audit logs";

    private static final int RETENTION_DAYS = 90;

    public static List<ScheduleDeclaration> defaultSchedules() {
        return List.of(ScheduleDeclaration.fallback("0 5 * * *"));
    }

    @Override
    public void executor(TaskContext ctx) {
        clean();
    }

    /** Delete audit-log rows older than the retention window. */
    public static void clean() {
        try {
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
