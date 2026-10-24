package be.elevenways.hohenheim.server.backup;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.model.BackupStatus;
import be.elevenways.hohenheim.server.BootSettle;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.TextField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The columns of a ledger of artifacts on a backup target (instance backups, database dumps), the boot settle every
 * such ledger needs (a row a killed controller left {@link BackupStatus#UPLOADING} becomes FAILED, its
 * possibly-committed artifact removed) and the delete of a row together with its artifact.
 *
 * AIDEV-NOTE: a row is settled only when its last write predates THIS process's start ({@link BootSettle}): an upload
 * is a synchronous in-process operation, so none recorded before this process existed can still run. A target that
 * refuses the removal keeps the key in the FAILED row's error, so the evidence still names what may survive remotely.
 *
 * @param lane the log prefix ("BACKUP", "DUMP")
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public record UploadLedger(@NonNull Class<? extends Model> model, @NonNull IntegerField id,
                           @NonNull IntegerField targetId,
                           @NonNull EnumField status, @NonNull StringField remoteKey, @NonNull TextField error,
                           @NonNull DateTimeField createdAt, @NonNull DateTimeField updatedAt,
                           @NonNull String lane) {

    /** Settles every row of this ledger an earlier process left uploading. */
    public void settleInterrupted() {
        Model ledger = Models.get(this.model);
        List<Row> stuck = ledger.find().where(this.status.eq(BackupStatus.UPLOADING.key())).all();
        for (Row row : stuck) {
            Instant written = row.get(this.updatedAt);
            if (written == null) {
                written = row.get(this.createdAt);
            }
            if (BootSettle.writtenByThisProcess(written)) {
                continue;   // written by THIS process: a live upload, not a corpse
            }
            String key = row.get(this.remoteKey);
            String error = "Interrupted by a controller restart mid-upload";
            if (key != null && !key.isBlank()) {
                try {
                    BackupTargetKinds.targetFor(row.get(this.targetId)).delete(key);
                } catch (IOException | Violations unreachable) {
                    error += "; the possibly-committed artifact could not be removed"
                        + " and may remain on the target under " + key;
                    Blast.log(this.lane + ": could not remove interrupted upload", key, ":",
                        HohenheimViolations.reasonOf(unreachable));
                }
            }
            row.set(this.status, BackupStatus.FAILED.key());
            row.set(this.error, error);
            ledger.saveWritten(row);
            Blast.log(this.lane + ": settled interrupted upload row", key, "as failed");
        }
    }

    /**
     * Removes a row's artifact from its target, then the row.
     *
     * @throws Violations {@code backup_delete_failed} when the target cannot be reached; the row stays
     */
    public void delete(@NonNull Row row) {
        delete(row, null, null);
    }

    /**
     * Removes a row's artifact from its target, through the caller's in-hand target when the row was written to it.
     *
     * AIDEV-NOTE: {@code currentTarget} exists for the explicit-target flow (InstanceBackups' retention sweep): a row
     * whose target id cannot be re-resolved (null, from the tests/re-target overload) made {@code targetFor} throw,
     * so retention kept the row FOREVER while its artifact stayed on the target. When the sweep runs inside a backup
     * that holds the very target such a row was written to, that in-hand handle is used instead.
     *
     * @param currentTargetId the target id the calling operation used, or null
     * @param currentTarget   the resolved target of the calling operation, or null
     * @throws Violations {@code backup_delete_failed} when the target cannot be reached; the row stays
     */
    public void delete(@NonNull Row row, @Nullable Integer currentTargetId, @Nullable BackupTarget currentTarget) {
        String key = row.get(this.remoteKey);
        if (key != null && !key.isBlank()) {
            try {
                Integer rowTargetId = row.get(this.targetId);
                BackupTarget target = currentTarget != null && Objects.equals(rowTargetId, currentTargetId)
                    ? currentTarget
                    : BackupTargetKinds.targetFor(rowTargetId);
                target.delete(key);
            } catch (IOException | Violations unreachable) {
                throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("backup_delete_failed")
                    .withArg("reason", HohenheimViolations.reasonOf(unreachable)));
            }
        }
        Models.get(this.model).find().where(this.id.eq(row.get(this.id))).delete();
    }
}
