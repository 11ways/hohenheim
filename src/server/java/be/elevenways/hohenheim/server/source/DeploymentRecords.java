package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.model.DeploymentModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * Persists deploy history rows around GitSiteRequestHandler's deploy execution.
 * Record-keeping must never take a deploy down with it, so every write degrades
 * to a log line on failure.
 */
final class DeploymentRecords {

    /** Newest deployments kept per site; older rows are pruned on completion. */
    private static final int KEEP_PER_SITE = 50;

    private DeploymentRecords() {}

    /** Insert the running row; null when persistence is unavailable. */
    static @Nullable Integer started(int siteId, String reason) {
        try {
            DeploymentModel model = Models.get(DeploymentModel.class);
            Row row = model.createEmptyRow();
            row.set(DeploymentModel.SITE_ID, siteId);
            row.set(DeploymentModel.STATUS, DeploymentModel.STATUS_RUNNING);
            row.set(DeploymentModel.REASON, reason);
            row.set(DeploymentModel.STARTED_AT, Instant.now());
            model.save(row);
            return row.get(DeploymentModel.ID);
        } catch (RuntimeException e) {
            Blast.log("GIT: could not record deployment start for site", siteId, "-", e.getMessage());
            return null;
        }
    }

    /** Stamp the outcome onto the row created by {@link #started} and prune old history. */
    static void finished(@Nullable Integer recordId, GitDeployment.DeployResult result, String log) {
        if (recordId == null) {
            return;
        }
        try {
            DeploymentModel model = Models.get(DeploymentModel.class);
            Row row = model.findById(recordId);
            if (row == null) {
                return;
            }
            Instant started = row.get(DeploymentModel.STARTED_AT);
            Instant finished = Instant.now();
            row.set(DeploymentModel.STATUS, statusOf(result));
            row.set(DeploymentModel.COMMIT_SHA, result.commitSha());
            row.set(DeploymentModel.SLOT, result.slot());
            row.set(DeploymentModel.ERROR, result.error());
            row.set(DeploymentModel.LOG, log);
            row.set(DeploymentModel.FINISHED_AT, finished);
            if (started != null) {
                row.set(DeploymentModel.DURATION_MS, (int) (finished.toEpochMilli() - started.toEpochMilli()));
            }
            model.save(row);
            prune(model, row.get(DeploymentModel.SITE_ID));
        } catch (RuntimeException e) {
            Blast.log("GIT: could not record deployment outcome", recordId, "-", e.getMessage());
        }
    }

    private static String statusOf(GitDeployment.DeployResult result) {
        if (result.success()) {
            return DeploymentModel.STATUS_SUCCESS;
        }
        String error = result.error();
        if (error != null && error.endsWith("cancelled")) {
            return DeploymentModel.STATUS_CANCELLED;
        }
        return DeploymentModel.STATUS_FAILED;
    }

    private static void prune(DeploymentModel model, Integer siteId) {
        if (siteId == null) {
            return;
        }
        List<Row> stale = model.find()
            .where(DeploymentModel.SITE_ID.eq(siteId))
            .orderBy(DeploymentModel.ID, SortOrder.DESC)
            .offset(KEEP_PER_SITE)
            .limit(1000)
            .all();
        for (Row old : stale) {
            model.delete(old.get(DeploymentModel.ID));
        }
    }
}
