package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.util.BlastString;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.setting.SettingDefinition;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Count-based retention: the rows of a newest-first query past its newest N are removed, a thousand per sweep.
 *
 * AIDEV-NOTE: callers order by ID, not created_at: two rows written inside one second share a created_at, and which
 * row survives must not be arbitrary.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class Retention {

    private static final int SWEEP_LIMIT = 1000;

    private Retention() {
    }

    /**
     * Removes one row past the newest N; it blocks (a delete).
     *
     * AIDEV-NOTE: a standalone SAM on purpose, never a java.util.function type: on the browser it suspends, and a
     * suspending JDK callback lets every call through that JDK interface in the bundle suspend (protoblast's
     * SyncFamilyVerifierPlugin fails such a bundle).
     */
    @FunctionalInterface
    public interface Removal {
        void remove(@NonNull Row row);
    }

    /** Hands every row of {@code newestFirst} past its newest {@code keep} to {@code remove}. */
    public static void keepNewest(@NonNull QueryBuilder<Row> newestFirst, int keep, @NonNull Removal remove) {
        for (Row old : newestFirst.offset(keep).limit(SWEEP_LIMIT).all()) {
            remove.remove(old);
        }
    }

    /** Deletes every row of {@code newestFirst} past its newest {@code keep} by its id. */
    public static void keepNewest(@NonNull Model model, @NonNull QueryBuilder<Row> newestFirst,
                                  @NonNull IntegerField idField, int keep) {
        keepNewest(newestFirst, keep, old -> model.delete(old.get(idField)));
    }

    /**
     * A per-instance capture lane (backups, snapshots) whose COMPLETE rows are kept to the newest N of its retention
     * setting; FAILED rows are never counted and never removed, they are the evidence.
     *
     * @param lane  the log prefix, its lower case the row's noun ("BACKUP")
     */
    public record InstanceCaptures(@NonNull Class<? extends Model> model, @NonNull IntegerField idField,
                                   @NonNull IntegerField instanceIdField, @NonNull EnumField statusField,
                                   @NonNull String completeStatus, @NonNull SettingDefinition<Integer> retention,
                                   @NonNull String lane) {

        /**
         * Sweeps one instance after a completed capture: a removal that refuses or fails is logged and the row kept
         * for the next sweep, never thrown, because the capture it follows already succeeded. A null or non-positive
         * retention keeps everything.
         */
        public void sweep(int instanceId, @NonNull Removal remove) {
            Integer keep = Zenit.SETTINGS_VALUES.getValue(this.retention);
            if (keep == null || keep <= 0) {
                return;
            }
            String noun = BlastString.lower(this.lane);
            QueryBuilder<Row> newestFirst = Models.get(this.model).find()
                .where(this.instanceIdField.eq(instanceId))
                .where(this.statusField.eq(this.completeStatus))
                .orderBy(this.idField, SortOrder.DESC);
            keepNewest(newestFirst, keep, old -> {
                Integer id = old.get(this.idField);
                try {
                    remove.remove(old);
                } catch (Violations refused) {
                    Blast.log(this.lane + ": retention could not remove " + noun, id, "- kept for a later sweep");
                } catch (RuntimeException unexpected) {
                    Blast.log(this.lane + ": retention hit an unexpected failure on " + noun, id,
                        "- kept for a later sweep:", HohenheimViolations.reasonOf(unexpected));
                }
            });
        }
    }
}
