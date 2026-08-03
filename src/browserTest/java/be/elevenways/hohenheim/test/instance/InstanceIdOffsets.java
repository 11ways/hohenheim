package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Kills the cross-class live-test collision at its root: every live class boots a FRESH
 * SQLite, so instance ids restart at 1 and every class's containers share the daemon
 * under the SAME {@code hohenheim-instance-1..n} handles -- which is how one class's
 * deploy 409s on another's leftover, and one class's crash watcher "restarts" a
 * container that belongs to a different test. Seeding a random high-id marker row makes
 * each class's ids (and therefore handles, volumes and networks) globally unique.
 *
 * AIDEV-NOTE: the marker row STAYS (soft-deleted) on purpose. SQLite's rowid allocation
 * is max(rowid)+1, so hard-deleting the marker would hand its id right back.
 */
public final class InstanceIdOffsets {

    private InstanceIdOffsets() {}

    /** Seed the per-class id base; call once after migrate, before any instance row. */
    public static void apply(@NonNull Datasource datasource) {
        Db.run(datasource, () -> {
            // 6-7 digits: unique enough per run, short enough for every derived name
            // (volumes, networks, nft chain suffixes) to stay well inside its limits.
            int base = ThreadLocalRandom.current().nextInt(100_000, 10_000_000);
            Row marker = Models.get(InstanceModel.class).createEmptyRow();
            marker.set(InstanceModel.ID, base);
            marker.set(InstanceModel.NAME, "id-offset-marker");
            marker.set(InstanceModel.KIND, "hohenheim:docker_container");
            marker.set(InstanceModel.DELETED_AT, Instant.now());
            Models.get(InstanceModel.class).save(marker);
        });
    }
}
