package be.elevenways.hohenheim.server.stack;

import be.elevenways.hohenheim.model.OperationStatus;
import be.elevenways.hohenheim.model.StackDeploymentModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;

/**
 * Persists stack deploy history. Record-keeping must never take a deploy down
 * with it, so every write degrades to a log line on failure.
 */
final class StackDeploymentRecords {

    /** Newest deployments kept per stack; older rows are pruned on completion. */
    private static final int KEEP_PER_STACK = 50;

    private StackDeploymentRecords() {}

    /** Insert the running row; null when persistence is unavailable. */
    static @Nullable Integer started(int stackId, String reason) {
        try {
            StackDeploymentModel model = Models.get(StackDeploymentModel.class);
            Row row = model.createEmptyRow();
            row.set(StackDeploymentModel.STACK_ID, stackId);
            row.set(StackDeploymentModel.STATUS, StackDeploymentModel.LIFECYCLE.stored(OperationStatus.RUNNING));
            row.set(StackDeploymentModel.REASON, reason);
            row.set(StackDeploymentModel.STARTED_AT, Now.instant());
            model.save(row);
            return row.get(StackDeploymentModel.ID);
        } catch (RuntimeException e) {
            Blast.log("STACK: could not record deployment start for stack", stackId, "-", e.getMessage());
            return null;
        }
    }

    /** Stamp the outcome (and the spec snapshot on success) onto the running row. */
    static void finished(@Nullable Integer recordId, boolean success, @Nullable String error,
                         String log, @Nullable String specSnapshot) {
        if (recordId == null) {
            return;
        }
        try {
            StackDeploymentModel model = Models.get(StackDeploymentModel.class);
            Row row = model.find().where(StackDeploymentModel.ID.eq(recordId)).first();
            if (row == null) {
                return;
            }
            Instant started = row.get(StackDeploymentModel.STARTED_AT);
            Instant finished = Now.instant();
            row.set(StackDeploymentModel.STATUS,
                success ? StackDeploymentModel.LIFECYCLE.stored(OperationStatus.SUCCEEDED)
                    : StackDeploymentModel.LIFECYCLE.stored(OperationStatus.FAILED));
            row.set(StackDeploymentModel.ERROR, error);
            row.set(StackDeploymentModel.LOG, log);
            if (specSnapshot != null) {
                row.set(StackDeploymentModel.SPEC, specSnapshot);
            }
            row.set(StackDeploymentModel.FINISHED_AT, finished);
            if (started != null) {
                row.set(StackDeploymentModel.DURATION_MS,
                    (int) (finished.toEpochMilli() - started.toEpochMilli()));
            }
            model.save(row);
            Integer stackId = row.get(StackDeploymentModel.STACK_ID);
            if (stackId != null) {
                model.pruneHistory(stackId, KEEP_PER_STACK);
            }
        } catch (RuntimeException e) {
            Blast.log("STACK: could not record deployment outcome", recordId, "-", e.getMessage());
        }
    }

    /**
     * Fail every row of one stack still claiming "running": the boot sweep's half of the
     * "every deployment settles" rule, for a deploy the process died under.
     *
     * @return how many rows were finalized
     */
    static int failRunning(int stackId, @NonNull String error) {
        int finalized = 0;
        try {
            StackDeploymentModel model = Models.get(StackDeploymentModel.class);
            for (Row row : model.find()
                    .where(StackDeploymentModel.STACK_ID.eq(stackId))
                    .where(StackDeploymentModel.STATUS.eq(
                        StackDeploymentModel.LIFECYCLE.stored(OperationStatus.RUNNING)))
                    .all()) {
                String log = row.get(StackDeploymentModel.LOG);
                finished(row.get(StackDeploymentModel.ID), false, error,
                    (log != null ? log : "") + "FAILED: " + error + '\n', null);
                finalized++;
            }
        } catch (RuntimeException e) {
            Blast.log("STACK: could not finalize interrupted deployments of stack", stackId, "-", e.getMessage());
        }
        return finalized;
    }
}
