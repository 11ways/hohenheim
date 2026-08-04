package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.BuildOperationModel;
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
 * SQLite, so record ids restart at 1 and every class's Docker resources share the daemon
 * under the SAME names -- {@code hohenheim-instance-1..n}, {@code hohenheim-build-1..n}
 * and the networks derived from them. Seeding a random high-id marker row per
 * name-bearing model makes each class's ids (and therefore handles, volumes and networks)
 * globally unique.
 *
 * AIDEV-NOTE: the owner LABELS are derived from the same ids, so a collision defeats
 * OwnerLabels.removeIfOwnedBy -- two forks' build #1 are attributably the same record, so
 * one fork force-removes the other's RUNNING container instead of refusing. Observed at
 * the daemon: create/extract-to-dir/attach then destroy with no start, and a network
 * torn down under a live container. That is why this covers BUILD ids too and not only
 * instance ids.
 *
 * AIDEV-NOTE: the instance marker STAYS (soft-deleted) on purpose. SQLite's rowid
 * allocation is max(rowid)+1, so hard-deleting a marker would hand its id right back.
 * BuildOperationModel has no soft delete, so its marker stays a live row -- harmless
 * because every query over builds is scoped to an owner pair, and the marker's owner is
 * a model nothing else names.
 */
public final class LiveIdOffsets {

    /** Owner model of the build marker; no real record ever carries it. */
    private static final String MARKER_OWNER = "hohenheim:id_offset_marker";

    private LiveIdOffsets() {}

    /** Seed the per-class id base; call once after migrate, before any instance or build row. */
    public static void apply(@NonNull Datasource datasource) {
        Db.run(datasource, () -> {
            // 6-7 digits: unique enough per run, short enough for every derived name
            // (volumes, networks, nft chain suffixes) to stay well inside its limits.
            int base = ThreadLocalRandom.current().nextInt(100_000, 10_000_000);

            Row instance = Models.get(InstanceModel.class).createEmptyRow();
            instance.set(InstanceModel.ID, base);
            instance.set(InstanceModel.NAME, "id-offset-marker");
            instance.set(InstanceModel.KIND, "hohenheim:docker_container");
            instance.set(InstanceModel.DELETED_AT, Instant.now());
            Models.get(InstanceModel.class).save(instance);

            Row build = Models.get(BuildOperationModel.class).createEmptyRow();
            build.set(BuildOperationModel.ID, base);
            build.set(BuildOperationModel.BUILDER_KIND, BuildOperationModel.KIND_DOCKERFILE);
            build.set(BuildOperationModel.FOR_MODEL, MARKER_OWNER);
            build.set(BuildOperationModel.FOR_ID, 0);
            build.set(BuildOperationModel.STATUS, BuildOperationModel.STATUS_REFUSED);
            build.set(BuildOperationModel.FAILURE_REASON, "id-offset-marker");
            Models.get(BuildOperationModel.class).save(build);
        });
    }
}
