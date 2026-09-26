package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.AttentionSeverity;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static be.elevenways.hohenheim.server.cms.AttentionItems.copy;
import static be.elevenways.hohenheim.server.cms.AttentionItems.item;
import static be.elevenways.hohenheim.server.cms.AttentionItems.literal;

/**
 * The instance tier's attention items: crashes, backups, disk pressure and failed application deploys.
 *
 * Every projection reads a STORED fact (the status crash detection stamped, backup rows, the disk
 * sweeper's observation, release operations); asking every daemon whether each workload is alive
 * is what the attention surface's no-per-render-probe rule forbids.
 *
 * AIDEV-NOTE: every link to an instance row goes through {@link InstanceResource#recordRoute}, because
 * release rows are not served by the instance list (its accessFunction) and a direct link to one
 * 404s; their surface is their application's Deploys tab.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class InstanceAttention {

    private static final String ADMIN = HohenheimSlugs.ADMIN;

    /** Above this fraction of an ENFORCED root-disk ceiling an instance needs attention. */
    private static final double DISK_HIGH = 0.85;

    /** ... and above this it is about to break rather than merely worth watching. */
    private static final double DISK_CRITICAL = 0.95;

    private InstanceAttention() {
    }

    // AIDEV-NOTE: the instance collectors are PUBLIC for the same reason
    // stuckReleasingPorts is -- a test proves each projection directly, positive and
    // negative, instead of asserting against whatever the whole dashboard happens to hold.

    /**
     * Instances the runtime gave up on: the status CRASH DETECTION already stamped
     * (an unobserved exit under crash policy none, or a crash loop that tripped flap
     * protection).
     */
    public static void crashedInstances(List<AttentionItem> items) {
        for (Row instance : Models.get(InstanceModel.class).find()
                .where(InstanceModel.STATUS.eq(InstanceModel.STATUS_ERROR))
                .all()) {
            items.add(item(AttentionSeverity.ERROR, "box",
                copy("instance_crashed", "attention_title",
                    "name", instance.get(InstanceModel.NAME)),
                copy("instance_crashed", "attention_detail"),
                InstanceResource.recordRoute(ADMIN, instance, InstanceConsolePage.SLUG)));
        }
    }

    /**
     * Instances whose LATEST backup failed -- the failedDeployments shape: only the most
     * recent attempt per instance speaks, so one old failure followed by successes is not
     * an alarm and a currently-failing schedule is.
     */
    public static void failedInstanceBackups(List<AttentionItem> items) {
        var backups = Models.get(InstanceBackupModel.class);
        for (Row instance : Models.get(InstanceModel.class).find()
                .all()) {
            Integer id = instance.get(InstanceModel.ID);
            if (id == null) {
                continue;
            }
            Row latest = backups.find()
                .where(InstanceBackupModel.INSTANCE_ID.eq(id))
                .orderBy(InstanceBackupModel.ID, SortOrder.DESC)
                .first();
            if (latest == null || !InstanceBackupModel.STATUS_FAILED
                    .equals(latest.get(InstanceBackupModel.STATUS))) {
                continue;
            }
            items.add(item(AttentionSeverity.ERROR, "box-archive",
                copy("instance_backup", "attention_title",
                    "name", instance.get(InstanceModel.NAME)),
                literal(latest.get(InstanceBackupModel.ERROR)),
                InstanceResource.recordRoute(ADMIN, instance, InstanceBackupsPage.SLUG)));
        }
    }

    /**
     * Instances whose backup signal has degraded to SILENCE: a backup target is declared
     * on the record (the operator's statement that this instance is supposed to be backed
     * up) but no COMPLETE backup exists, or the newest one is older than
     * {@code backup.stale_after_days}. The latest-FAILED collector above answers "is it
     * failing right now"; this one answers the question that collector structurally
     * cannot -- "when did it last SUCCEED" -- so an instance never backed up, or failing
     * so long its failures predate its rows, stops reading as green. Both may fire for
     * one instance (failing nightly AND stale); that is escalation, not duplication.
     */
    public static void staleInstanceBackups(List<AttentionItem> items) {
        Integer days = Zenit.SETTINGS_VALUES.getValue(
            HohenheimSettings.Backup.STALE_AFTER_DAYS);
        if (days == null || days <= 0) {
            return;
        }
        Instant threshold = Now.instant().minus(Duration.ofDays(days));
        var backups = Models.get(InstanceBackupModel.class);
        for (Row instance : Models.get(InstanceModel.class).find()
                .where(InstanceModel.BACKUP_TARGET_ID.isNotNull())
                .all()) {
            Integer id = instance.get(InstanceModel.ID);
            if (id == null) {
                continue;
            }
            Row newestComplete = backups.find()
                .where(InstanceBackupModel.INSTANCE_ID.eq(id))
                .where(InstanceBackupModel.STATUS.eq(InstanceBackupModel.STATUS_COMPLETE))
                .orderBy(InstanceBackupModel.ID, SortOrder.DESC)
                .first();
            if (newestComplete == null) {
                items.add(item(AttentionSeverity.WARNING, "box-archive",
                    copy("instance_backup_never", "attention_title",
                        "name", instance.get(InstanceModel.NAME)),
                    copy("instance_backup_never", "attention_detail"),
                    InstanceResource.recordRoute(ADMIN, instance, InstanceBackupsPage.SLUG)));
                continue;
            }
            Instant completedAt = newestComplete.get(InstanceBackupModel.CREATED_AT);
            if (completedAt == null || completedAt.isBefore(threshold)) {
                long age = completedAt == null
                    ? -1 : Duration.between(completedAt, Now.instant()).toDays();
                items.add(item(AttentionSeverity.WARNING, "box-archive",
                    copy("instance_backup_stale", "attention_title",
                        "name", instance.get(InstanceModel.NAME)),
                    copy("instance_backup_stale", "attention_detail", "days", age),
                    InstanceResource.recordRoute(ADMIN, instance, InstanceBackupsPage.SLUG)));
            }
        }
    }

    /**
     * Instances close to filling their root disk, from the STORED observation
     * ({@code ObserveInstanceDisk}).
     *
     * AIDEV-NOTE: a null observation is silence, never zero, and a zero LIMIT is silence
     * too. Both mean "nothing is rationing this disk, or nothing measured it" -- Docker's
     * whole tier is in that state by design, because it enforces no root quota at all. An
     * item here therefore always names a real ceiling a real number is approaching.
     */
    public static void instancesLowOnDisk(List<AttentionItem> items) {
        for (Row instance : Models.get(InstanceModel.class).find()
                .where(InstanceModel.DISK_OBSERVED_AT.isNotNull())
                .all()) {
            Long used = instance.get(InstanceModel.DISK_USED_BYTES);
            Long limit = instance.get(InstanceModel.DISK_LIMIT_BYTES);
            if (used == null || limit == null || limit <= 0) {
                continue;
            }
            double fraction = (double) used / limit;
            if (fraction < DISK_HIGH) {
                continue;
            }
            items.add(item(fraction >= DISK_CRITICAL ? AttentionSeverity.ERROR : AttentionSeverity.WARNING,
                "hard-drive",
                copy("instance_disk", "attention_title",
                    "name", instance.get(InstanceModel.NAME)),
                copy("instance_disk", "attention_detail",
                    "percent", Math.round(fraction * 100),
                    "limit", Math.round(limit / (1024.0 * 1024 * 1024))),
                InstanceResource.recordRoute(ADMIN, instance, null)));
        }
    }

    /**
     * The newest release operation of every application, when it FAILED.
     *
     * AIDEV-NOTE: this used to read the {@code deployments} table of the deleted host-slot
     * lane. The release engine's own {@code release_operations} row IS the deploy history
     * now -- one record of what was attempted, with its step log -- so there is no second
     * table to keep in step with it.
     */
    static void failedDeployments(List<AttentionItem> items) {
        var instanceModel = Models.get(InstanceModel.class);
        var operations = Models.get(ReleaseOperationModel.class);
        if (instanceModel == null || operations == null) {
            return;
        }
        for (Row application : instanceModel.find()
                .where(InstanceModel.KIND.eq(ApplicationKind.ID.toString()))
                .all()) {
            Integer applicationId = application.get(InstanceModel.ID);
            if (applicationId == null) {
                continue;
            }
            List<Row> latest = operations.findForOwner(InstanceModel.MODEL_ID.toString(),
                applicationId, 1);
            if (latest.isEmpty()) {
                continue;
            }
            Row operation = latest.get(0);
            if (ReleaseOperationModel.STATUS_FAILED.equals(
                    operation.get(ReleaseOperationModel.STATUS))) {
                items.add(item(AttentionSeverity.ERROR, "rocket",
                    copy("deploy", "attention_title",
                        "name", application.get(InstanceModel.NAME)),
                    literal(operation.get(ReleaseOperationModel.FAILURE_REASON)),
                    CmsRoutes.subpage(ADMIN, InstanceResource.SLUG, applicationId,
                        InstanceDeploymentsPage.SLUG)));
            }
        }
    }
}
