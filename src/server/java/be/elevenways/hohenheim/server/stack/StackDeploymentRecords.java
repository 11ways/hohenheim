package be.elevenways.hohenheim.server.stack;

import be.elevenways.hohenheim.model.StackDeploymentModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.List;

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
            row.set(StackDeploymentModel.STATUS, StackDeploymentModel.STATUS_RUNNING);
            row.set(StackDeploymentModel.REASON, reason);
            row.set(StackDeploymentModel.STARTED_AT, Instant.now());
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
            Instant finished = Instant.now();
            row.set(StackDeploymentModel.STATUS,
                success ? StackDeploymentModel.STATUS_SUCCESS : StackDeploymentModel.STATUS_FAILED);
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
            prune(model, row.get(StackDeploymentModel.STACK_ID));
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
                    .where(StackDeploymentModel.STATUS.eq(StackDeploymentModel.STATUS_RUNNING))
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

    private static void prune(StackDeploymentModel model, Integer stackId) {
        if (stackId == null) {
            return;
        }
        List<Row> stale = model.find()
            .where(StackDeploymentModel.STACK_ID.eq(stackId))
            .orderBy(StackDeploymentModel.ID, SortOrder.DESC)
            .offset(KEEP_PER_STACK)
            .limit(1000)
            .all();
        for (Row old : stale) {
            model.delete(old.get(StackDeploymentModel.ID));
        }
    }
}
