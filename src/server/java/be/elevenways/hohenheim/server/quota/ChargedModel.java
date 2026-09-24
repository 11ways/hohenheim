package be.elevenways.hohenheim.server.quota;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.DoomedRows;
import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.hohenheim.server.instance.InstanceCapacity;
import be.elevenways.hohenheim.server.instance.InstanceDeviceQuota;
import be.elevenways.hohenheim.server.instance.InstanceQuota;
import be.elevenways.hohenheim.server.instance.InstanceRootDiskQuota;
import be.elevenways.hohenheim.server.preview.PreviewQuota;
import be.elevenways.hohenheim.server.quota.ChargedDimension.Charge;
import be.elevenways.hohenheim.server.quota.ChargedDimension.Transition;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.behaviour.SoftDeleteBehaviour;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.context.RemoveFromDatasource;
import be.elevenways.zenit.common.orm.datasource.context.SaveToDatasource;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.quota.Quotas;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * THE declaration of every model the quota ledger charges, with its dimensions in charge
 * order, and THE one lifecycle that books them: installed once per model, read by the
 * {@link QuotaReconciler}, so a new dimension is one declaration here.
 *
 * The lifecycle, for every dimension alike: a CREATE written live reserves the write's claim
 * and stamps it; live to trashed releases what the stored row holds; trashed to live
 * reserves a fresh claim against the owner derived now; live to live moves only the
 * difference (the delta in one bucket, or release-then-reserve when the bucket itself moves);
 * a hard delete releases what every live doomed row held, after the rows are gone.
 *
 * AIDEV-NOTE: the release rides the deleted_at TRANSITION because the destroy paths
 * soft-delete through save() -- SoftDeleteBehaviour's delete stamps deleted_at through the
 * normal save path, and the remove hooks never fire there. The remove pairing exists so a
 * hard delete (tests, TenantDatabases.abandon, device detach, a forceDelete) cannot leak
 * either. Known bypass, by the ledger's own contract: updateAll() fires no hooks, so a bulk
 * edit that stamps deleted_at set-based skips the release (the migration handoff moves its
 * host charge explicitly for the same reason).
 *
 * AIDEV-NOTE: whether a model is soft-deletable, and whether a row is trashed, are the
 * model's own SoftDeleteBehaviour facts (read off the schema), never a column named here.
 * The STORED row of a write is read trashed included ({@link StoredRows}): the behaviour's
 * find hook hides a trashed row, and reading a restore's stored row as absent would book it
 * as a CREATE instead of a RESTORE.
 *
 * AIDEV-NOTE: ONE before-write hook per model, dimensions in declared order, and a refusal
 * anywhere in it UNWINDS every ledger move the earlier dimensions made. Before this, the
 * instance count, owner memory, host memory and root disk were four sibling hooks, and a hook
 * cannot see a sibling's throw: a host_capacity_reached (or a root_disk_invalid) left the
 * owner's slot and memory spent for a workload that never landed -- the drift production
 * robbedoes carried on 2026-09-01. What still cannot be unwound is a refusal AFTER this hook
 * (a later hook, the insert itself) on a write with no ambient transaction; the reconciler
 * covers that residue. Order matters within a model: {@link InstanceQuota#MEMORY} books into
 * the bucket {@link InstanceQuota#COUNT} just stamped.
 *
 * Localization: bucket keys are machine tokens and every line here is an operator log.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public enum ChargedModel {

    /** Instances (soft-deleted): the owner's slot and memory, the host's memory, the root disk. */
    INSTANCES(InstanceModel.class, InstanceModel.SCHEMA,
        InstanceQuota.COUNT, InstanceQuota.MEMORY, InstanceCapacity.HOST_MEMORY,
        InstanceRootDiskQuota.ROOT_DISK),

    /** Attached devices: disk GB and extra NICs; hard-deleted only. */
    DEVICES(InstanceDeviceModel.class, InstanceDeviceModel.SCHEMA,
        InstanceDeviceQuota.DISK, InstanceDeviceQuota.NICS),

    /** Preview deployments (soft-deleted): one slot per live preview. */
    PREVIEWS(PreviewDeploymentModel.class, PreviewDeploymentModel.SCHEMA, PreviewQuota.PREVIEWS),

    /** Site records (soft-deleted): one slot per live site. */
    SITES(SiteModel.class, SiteModel.SCHEMA, SiteQuota.SITES),

    /** Managed databases: one slot per record; hard-deleted only. */
    DATABASES(DatabaseModel.class, DatabaseModel.SCHEMA, DatabaseQuota.DATABASES);

    private final @NonNull Class<? extends Model> modelClass;
    private final @NonNull Schema schema;
    private final @NonNull List<ChargedDimension> dimensions;
    private volatile boolean installed;

    ChargedModel(@NonNull Class<? extends Model> modelClass, @NonNull Schema schema,
                 @NonNull ChargedDimension... dimensions) {
        this.modelClass = modelClass;
        this.schema = schema;
        this.dimensions = List.of(dimensions);
    }

    /** @return the dimensions this model charges, in charge order */
    public @NonNull List<ChargedDimension> dimensions() {
        return this.dimensions;
    }

    /** @return whether {@link #install} registered this model's hooks */
    public boolean isInstalled() {
        return this.installed;
    }

    /** @return every distinct ledger prefix any declared dimension books under */
    public static @NonNull Set<String> prefixes() {
        Set<String> prefixes = new LinkedHashSet<>();
        for (ChargedModel model : values()) {
            for (ChargedDimension dimension : model.dimensions) {
                prefixes.add(dimension.prefix());
            }
        }
        return prefixes;
    }

    /**
     * Register the charge hook and the remove pairing on this model's write funnel
     * (MODULES stage, at the position HohenheimWriteHooks gives it); idempotent.
     */
    public synchronized void install() {
        if (this.installed) {
            return;
        }
        this.installed = true;
        this.schema.addBeforeWriteHook(this::charge);
        DoomedRows.handOver(this.schema, this::doomedReleases, this::releaseDoomed);
    }

    /** @return every live row of this model (the reconciler's truth; trashed rows are hidden) */
    public @NonNull List<Row> liveRows() {
        return this.model().find().all();
    }

    // -- the write lifecycle --------------------------------------------------

    private void charge(@NonNull SaveToDatasource context) {
        Row row = context.getRow();
        if (row == null) {
            return;
        }
        Row stored = this.storedOf(row);
        boolean storedLive = stored != null && this.isLive(stored);
        boolean willBeLive = this.willBeLive(row, stored);
        if (!storedLive && !willBeLive) {
            // Written trashed, or trashed and staying trashed: nothing is held, nothing claimed.
            return;
        }
        Journal journal = new Journal();
        try {
            for (ChargedDimension dimension : this.dimensions) {
                if (willBeLive) {
                    dimension.validate(row, stored);
                }
                if (stored == null) {
                    this.claim(dimension, row, null, Transition.CREATE, journal);
                } else if (!willBeLive) {
                    this.release(dimension, stored, dimension.held(stored), journal);
                } else if (!storedLive) {
                    this.claim(dimension, row, stored, Transition.RESTORE, journal);
                } else {
                    this.rebook(dimension, row, stored, journal);
                }
            }
        } catch (RuntimeException | Error refused) {
            journal.undo();
            throw refused;
        }
    }

    /** Reserve a fresh claim (create, restore) and stamp it. */
    private void claim(@NonNull ChargedDimension dimension, @NonNull Row row, @Nullable Row stored,
                       @NonNull Transition transition, @NonNull Journal journal) {
        Charge claim = dimension.claim(row, stored, transition);
        if (claim == null) {
            return;
        }
        this.reserve(dimension, claim.bucket(), claim.amount(), journal);
        dimension.stamp(row, claim);
    }

    /** A live row staying live: move only what changed, then stamp the new holding. */
    private void rebook(@NonNull ChargedDimension dimension, @NonNull Row row, @NonNull Row stored,
                        @NonNull Journal journal) {
        Charge before = dimension.held(stored);
        Charge after = dimension.claim(row, stored, Transition.REBOOK);
        if (before == null ? after == null : before.sameBooking(after)) {
            return;
        }
        if (before != null && after != null && before.bucket().equals(after.bucket())) {
            this.reportDerived(dimension, stored, before);
            long delta = after.amount() - before.amount();
            if (delta > 0) {
                this.reserve(dimension, after.bucket(), delta, journal);
            } else {
                this.releaseAmount(before.bucket(), -delta, journal);
            }
        } else {
            // The bucket itself moves (a host change): release first, so moving between two
            // equally full buckets does not need the destination's headroom twice.
            this.release(dimension, stored, before, journal);
            if (after != null) {
                this.reserve(dimension, after.bucket(), after.amount(), journal);
            }
        }
        dimension.stamp(row, after);
    }

    private void reserve(@NonNull ChargedDimension dimension, @NonNull String bucket, long amount,
                         @NonNull Journal journal) {
        if (amount <= 0) {
            return;
        }
        dimension.reserve(bucket, amount);
        journal.reserved(bucket, amount);
    }

    private void release(@NonNull ChargedDimension dimension, @NonNull Row stored,
                         @Nullable Charge held, @NonNull Journal journal) {
        if (held == null || held.amount() <= 0) {
            return;
        }
        this.reportDerived(dimension, stored, held);
        this.releaseAmount(held.bucket(), held.amount(), journal);
    }

    private void releaseAmount(@NonNull String bucket, long amount, @NonNull Journal journal) {
        if (amount <= 0) {
            return;
        }
        Quotas.release(bucket, amount);
        journal.released(bucket, amount);
    }

    // -- the remove pairing ---------------------------------------------------

    /** One release a hard delete owes, captured while the row still exists. */
    private record Release(@NonNull ChargedDimension dimension, @Nullable Object rowId,
                           @NonNull Charge charge) {}

    private @Nullable List<Release> doomedReleases(@NonNull RemoveFromDatasource context) {
        Model model = this.model();
        List<Release> releases = new ArrayList<>();
        for (Row row : context.doomedRows()) {
            // A trashed row already released on its soft-delete transition.
            if (!this.isLive(row)) {
                continue;
            }
            for (ChargedDimension dimension : this.dimensions) {
                Charge held = dimension.held(row);
                if (held != null && held.amount() > 0) {
                    releases.add(new Release(dimension, model.getPrimaryKeyValue(row), held));
                }
            }
        }
        return releases.isEmpty() ? null : releases;
    }

    private void releaseDoomed(@NonNull RemoveFromDatasource context, @NonNull List<Release> releases) {
        for (Release release : releases) {
            if (release.charge().derived()) {
                this.logDerived(release.dimension(), release.rowId(), release.charge());
            }
            Quotas.release(release.charge().bucket(), release.charge().amount());
        }
    }

    // -- helpers --------------------------------------------------------------

    private @NonNull Model model() {
        return Models.get(this.modelClass);
    }

    /** The stored row a write targets, trashed included, or null on a create. */
    private @Nullable Row storedOf(@NonNull Row row) {
        return StoredRows.of(this.model(), row);
    }

    /** The model's soft delete, or null for a model that is only ever hard-deleted. */
    private @Nullable SoftDeleteBehaviour softDelete() {
        return this.schema.getBehaviour(SoftDeleteBehaviour.class);
    }

    private boolean isLive(@NonNull Row stored) {
        SoftDeleteBehaviour softDelete = this.softDelete();
        return softDelete == null || !softDelete.isTrashed(stored);
    }

    /** Whether the write leaves the row live: the staged deleted_at when carried, else the stored one. */
    private boolean willBeLive(@NonNull Row row, @Nullable Row stored) {
        SoftDeleteBehaviour softDelete = this.softDelete();
        if (softDelete == null) {
            return true;
        }
        String deletedAt = softDelete.deletedAtField().getName();
        if (row.has(deletedAt)) {
            return row.get(deletedAt) == null;
        }
        return stored == null || !softDelete.isTrashed(stored);
    }

    private void reportDerived(@NonNull ChargedDimension dimension, @NonNull Row stored,
                               @NonNull Charge held) {
        if (held.derived()) {
            this.logDerived(dimension, this.model().getPrimaryKeyValue(stored), held);
        }
    }

    /**
     * A release computed from a fallback rather than a stamp is exactly the accounting drift
     * the stamps prevent, so it is logged rather than silent.
     */
    private void logDerived(@NonNull ChargedDimension dimension, @Nullable Object rowId,
                            @NonNull Charge held) {
        Blast.log("QUOTA:", dimension.key(), "of", this.model().getTableName(), "row", rowId,
            "carries no charged stamp; moving", held.amount(), "against", held.bucket());
    }

    /**
     * Every ledger move one write made, so a refusal later in the same write hands them back.
     *
     * AIDEV-NOTE: the undo of a release re-reserves UNCAPPED: it restores a booking the row
     * still holds, and a cap refusing it would leave the ledger below the rows. A failing undo
     * is logged and skipped so the refusal that caused it still reaches the caller; the
     * reconciler repairs what an undo could not.
     */
    private static final class Journal {

        private record Move(@NonNull String bucket, long amount, boolean reserved) {}

        private final List<Move> moves = new ArrayList<>();

        void reserved(@NonNull String bucket, long amount) {
            this.moves.add(new Move(bucket, amount, true));
        }

        void released(@NonNull String bucket, long amount) {
            this.moves.add(new Move(bucket, amount, false));
        }

        void undo() {
            for (int i = this.moves.size() - 1; i >= 0; i--) {
                Move move = this.moves.get(i);
                try {
                    if (move.reserved()) {
                        Quotas.release(move.bucket(), move.amount());
                    } else {
                        Quotas.reserve(move.bucket(), move.amount(), Long.MAX_VALUE);
                    }
                } catch (RuntimeException failed) {
                    Blast.log("QUOTA: could not unwind", move.amount(), "on", move.bucket(),
                        "after a refused write -", failed.getMessage());
                }
            }
        }
    }
}
