package be.elevenways.hohenheim.server.task;

import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.model.Model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Shared delete-rows-older-than logic for the retention cleanup tasks.
 */
final class RetentionSweep {

    private RetentionSweep() {
    }

    /**
     * Delete rows whose {@code createdAt} is past the retention window.
     *
     * @throws RuntimeException on failure -- the task system records it as a
     *         FAILED history row; swallowing here made repeated failures invisible
     */
    static void clean(String taskName, Model model, DateTimeField createdAt, int retentionDays) {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);

        long deleted = model.find()
            .where(createdAt.lte(cutoff))
            .delete();

        if (deleted > 0) {
            Blast.log("TASK: " + taskName + " removed", deleted, "entries older than",
                retentionDays, "days");
        }
    }
}
