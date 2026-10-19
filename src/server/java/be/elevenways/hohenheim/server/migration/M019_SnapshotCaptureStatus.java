package be.elevenways.hohenheim.server.migration;

import be.elevenways.hohenheim.migration.HohenheimMigration;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.migration.FrozenModel;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * A snapshot's row reads {@code capturing} while its capture runs, instead of {@code failed} from the moment it is
 * created: {@code failed} no longer doubles as the in-flight marker.
 *
 * AIDEV-NOTE: up() changes nothing stored. The status is a STRING column, and its {@code failed} default stays the
 * fail-closed answer for a row no capture stamped, as instance_backups' does. Existing {@code failed} rows are LEFT
 * failed: a row an older release left mid-capture reads exactly like a capture that failed, and nothing stored tells
 * the two apart; the boot settle (InstanceSnapshots.recoverInterrupted) still reclaims the payload either may hold.
 * down() is the real step: it hands every {@code capturing} row back to {@code failed}, the older release's in-flight
 * marker, so that release reads it as its own and its boot settle reclaims the payload.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public class M019_SnapshotCaptureStatus extends HohenheimMigration {

    /** A running capture's status, as InstanceSnapshotModel.Status.CAPTURING spells it today. */
    static final String CAPTURING = "capturing";

    /** The status an older release stamped from a capture's start, as it stored it. */
    static final String FAILED = "failed";

    public M019_SnapshotCaptureStatus() {
        super("019", "Snapshot capture status");
    }

    @Override
    public void up(@NonNull MigrationBuilder schema) {
        // Nothing stored changes: see the class note.
    }

    @Override
    public void down(@NonNull MigrationBuilder schema) {
        schema.data("store every capturing snapshot as failed again, the older release's in-flight marker", "1",
            datasource -> Db.run(datasource, () -> {
                IntegerField id = IntegerField.builder().name("id").build();
                StringField status = StringField.builder().name("status").build();
                new FrozenModel("instance_snapshots", id, status).find()
                    .where(status.eq(CAPTURING))
                    .assign(status, FAILED)
                    .updateAll();
            }));
    }
}
