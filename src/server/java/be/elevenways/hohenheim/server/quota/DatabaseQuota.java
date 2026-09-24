package be.elevenways.hohenheim.server.quota;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The per-owner MANAGED DATABASE count quota: one slot per database record, charged through
 * {@link ChargedModel#DATABASES}.
 *
 * WHY it is not redundant with the instance quota, which a database already spends: the
 * engine container IS an instance and IS charged as one ({@code TenantDatabases.allocate}
 * -> {@code DatabaseInstances.reserveEngineRow}, attributed to the database's owner since
 * ce8ccb5). So a database costs one instance slot -- but an instance slot is a WORKLOAD
 * slot, and an owner spends those on game servers and stacks too, so "five databases per
 * tenant" is not expressible through it and never will be. A managed database is also a
 * different KIND of cost: credentials, a data volume, backups and a restore surface, none
 * of which the instance count knows about. The two dimensions are charged together and
 * both must fit; neither substitutes for the other.
 *
 * AIDEV-NOTE: databases have NO deleted_at (verified on DatabaseModel) -- every removal is
 * a HARD delete ({@code DatabaseService.destroy}, and {@code TenantDatabases.abandon}'s
 * criteria delete for a half-finished allocation). The remove pairing is therefore the ONE
 * release lane here, the mirror image of InstanceQuota, whose real lane is a soft-delete
 * transition.
 *
 * AIDEV-NOTE: the charge happens at the row INSERT, which in the tenant funnel is BEFORE
 * the creator's manage grant is planted -- so the owner is derived from the ACTING context
 * ({@code HohenheimAccess.creationOwnerSubjects}), which is the same answer
 * {@code grantCreatorManage} plants a moment later, deliberately one derivation.
 *
 * Localization: the refusal is a Microcopy-backed violation; bucket keys are machine tokens.
 */
public final class DatabaseQuota {

    /** One slot per database record; no soft delete, so no restore ever reaches it. */
    public static final ChargedDimension DATABASES =
        new OwnerDimension("databases", OwnerBudget.DATABASES, DatabaseModel.QUOTA_BUCKET) {

            @Override
            protected long heldAmount(@NonNull Row stored) {
                return 1;
            }

            @Override
            public @Nullable Charge claim(@NonNull Row row, @Nullable Row stored,
                                          @NonNull Transition transition) {
                return switch (transition) {
                    case CREATE, RESTORE -> this.forCreationOwner(1);
                    case REBOOK -> this.held(stored);
                };
            }
        };

    private DatabaseQuota() {
    }

    /** The database bucket for a packed subject set (the 191-char fold, one owner). */
    public static @NonNull String bucketKeyOf(@NonNull String packedSubjects) {
        return OwnerBudget.DATABASES.bucketOf(packedSubjects);
    }

    /** The database cap for one owner; override 0 = nothing allowed, global 0-or-less = uncapped. */
    public static @Nullable Integer limitFor(@NonNull String packedSubjects) {
        return OwnerBudget.DATABASES.limitFor(packedSubjects);
    }

    /** How many of an owner's database slots are spent (admin surfaces, tests). */
    public static long usedBy(@NonNull String packedSubjects) {
        return OwnerBudget.DATABASES.usedBy(packedSubjects);
    }
}
