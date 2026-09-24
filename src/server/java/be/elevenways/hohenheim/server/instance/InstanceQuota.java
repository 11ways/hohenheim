package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.orm.GeneratedRows;
import be.elevenways.hohenheim.server.quota.ChargedDimension;
import be.elevenways.hohenheim.server.quota.ChargedModel;
import be.elevenways.hohenheim.server.quota.OwnerBudget;
import be.elevenways.hohenheim.server.quota.OwnerDimension;
import be.elevenways.hohenheim.server.quota.OwnerQuota;
import be.elevenways.hohenheim.server.quota.QuotaReconciler;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.quota.Quotas;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Set;

/**
 * The instance-count and owner-MEMORY quotas: hohenheim's policy layer over the core
 * reservation ledger (zenit {@code Quotas}). THIS class owns two dimensions -- live
 * instances per owner, and the workload memory those instances hold.
 *
 * AIDEV-NOTE: corrected 2026-08-08 -- this used to say disk was "explicitly out of
 * scope". Per-owner DISK GB is enforced, by {@link InstanceDeviceQuota} and
 * {@link InstanceRootDiskQuota} into the same owner bucket, and extra NICs alongside
 * it. Still genuinely out of scope, per owner: cpu and ports.
 *
 * THE MEMORY DECISION (2026-08-08), and it is the one this dimension was deferred on
 * once: an owner is charged for EVERY live workload, including one that declares no
 * {@code memory_limit_mb}, at its kind's declared footprint
 * ({@code InstanceKindHandler.defaultFootprintMb}). The alternative -- charge only
 * DECLARED limits -- was rejected because {@code ResourceLimits} treats memory as
 * optional, so the budget of a fleet of unbounded workloads sums to ZERO: a host full of
 * tenant work reads as an empty budget, which is the silent-success shape rather than a
 * conservative one. Charge == cap survives the choice intact: the number booked here is
 * the number {@code ResourceLimits.fromSettings(settings, defaultFootprintMb(settings))}
 * hands the driver as the cgroup / VM memory cap, so nothing is booked that is not also
 * enforced. A kind whose handler class is gone prices at 0 and books nothing: a
 * declaration nothing enforces is charged nothing.
 *
 * AIDEV-NOTE: the owner budget and {@code InstanceCapacity}'s host budget book the SAME
 * number into DIFFERENT buckets, and both must hold: the host answers "is there RAM here",
 * the owner answers "may this tenant have it". Neither subtracts from the other, because
 * they ration different things -- an owner's budget is not per host, and a host's budget is
 * shared by every owner on it.
 *
 * The owner is the grant-derived manage-subject SET packed by
 * {@link HohenheimAccess#packSubjects} -- the same derivation sameOwner and the released
 * -claim quarantine answer from, never a principal column. The empty set is the
 * operator, so EVERY operator-owned instance shares one bucket, deliberately. An OWNED
 * instance (a site's container, a managed database's engine) answers to the owner of the
 * record that OWNS it -- see {@link #creationOwnerPackOf}.
 *
 * AIDEV-NOTE: enforcement rides a beforeWrite hook and the hook is ADJACENT to the
 * write, not transactional -- Model.save opens a transaction only for revisionable
 * schemas, and the CMS create is wrapped in inMutationTransaction only because the
 * resource's access function returns a predicate. The reservation is therefore ONE
 * guarded statement (the core ledger's contract) and stays correct with no ambient
 * transaction at all; with one, it rolls back alongside the refused create. The
 * createPermission boolean is deliberately NOT the enforcement: it is evaluated at
 * form render and submit with persistence after, so under the concurrency a quota
 * exists for it is a check that cannot fail.
 *
 * AIDEV-NOTE: memory needs one transition the COUNT does not: a live row that STAYS live
 * can change its declared memory (or its kind), so the fourth branch charges or releases
 * the DELTA against the bucket the row was already charged to (the InstanceDeviceQuota
 * size-update idiom). A count has no delta -- one live row is one slot whatever it says.
 *
 * AIDEV-NOTE: the lifecycle (create, the deleted_at transitions detected against the
 * STORED row, the live-to-live delta, the hard-delete pairing) is {@link ChargedModel}'s,
 * shared with every other dimension; this class only declares the two dimensions. The
 * release rides the deleted_at transition because InstanceService.destroy soft-deletes
 * via save() and the remove hooks never fire there -- keep destroys on the save path.
 *
 * Localization: refusals are Microcopy-backed violations; bucket keys are machine
 * tokens and never localized content.
 */
public final class InstanceQuota {

    /**
     * One slot per live instance, charged to the owner the create is ABOUT TO HAVE (see
     * {@link #creationOwnerPackOf}); a restore is a new claim against the owner derived NOW.
     */
    public static final ChargedDimension COUNT =
        new OwnerDimension("instances", OwnerBudget.INSTANCES, InstanceModel.QUOTA_BUCKET) {

            @Override
            protected long heldAmount(@NonNull Row stored) {
                return 1;
            }

            @Override
            public @Nullable Charge claim(@NonNull Row row, @Nullable Row stored,
                                          @NonNull Transition transition) {
                return switch (transition) {
                    // AIDEV-NOTE: this used to charge the operator bucket unconditionally,
                    // with a comment saying that was "always" the owner because grants land
                    // after the record. That held only while creates were admin-only; the
                    // moment a tenant can create (InstanceTemplates.createFromTemplate), every
                    // tenant shared ONE bucket with the operator. The derivation is shared
                    // with the grant that follows, so the charged bucket and the record's
                    // real owner are one answer.
                    case CREATE -> new Charge(creationBucket(), 1, false);
                    case RESTORE -> this.forCurrentOwner(InstanceModel.MODEL_ID, stored,
                        stored.get(InstanceModel.ID), 1);
                    // A count has no delta: one live row is one slot whatever it says.
                    case REBOOK -> this.held(stored);
                };
            }
        };

    /**
     * The workload memory an owner's live instances hold, booked in the owner-memory bucket
     * that matches the COUNT bucket the row is charged to, and stamped as
     * {@code quota_memory_mb} so the release hands back exactly what was booked.
     *
     * AIDEV-NOTE: declared AFTER {@link #COUNT} in {@link ChargedModel#INSTANCES} on
     * purpose: a create or restore books into the bucket COUNT just stamped, so a folded
     * owner's two dimensions can never land in different buckets. The count is reserved
     * first, and a memory refusal unwinds it (the ChargedModel journal) -- an instance save
     * opens no transaction, and an uncompensated refusal once cost the owner one slot per
     * refusal, forever (production robbedoes, {@code instances} 15 against 14 live rows).
     */
    public static final ChargedDimension MEMORY = new ChargedDimension() {

        @Override
        public @NonNull String key() {
            return "owner_memory";
        }

        @Override
        public @NonNull String prefix() {
            return OwnerBudget.OWNER_MEMORY.prefix();
        }

        @Override
        public @Nullable Charge held(@NonNull Row stored) {
            Charge count = COUNT.held(stored);
            Integer stamped = stored.get(InstanceModel.QUOTA_MEMORY_MB);
            long amount = Math.max(0, stamped != null ? stamped : InstanceCapacity.footprintMbOf(stored));
            return new Charge(memoryBucketOfChargedBucket(count.bucket()), amount,
                stamped == null || count.derived());
        }

        @Override
        public @Nullable Charge claim(@NonNull Row row, @Nullable Row stored,
                                      @NonNull Transition transition) {
            long amount = Math.max(0, InstanceCapacity.effectiveFootprintMb(row, stored));
            String countBucket = switch (transition) {
                case CREATE, RESTORE -> {
                    String stamped = row.get(InstanceModel.QUOTA_BUCKET);
                    if (stamped == null) {
                        throw new IllegalStateException(
                            "owner memory is charged after the instance count stamps its bucket");
                    }
                    yield stamped;
                }
                case REBOOK -> COUNT.held(stored).bucket();
            };
            return new Charge(memoryBucketOfChargedBucket(countBucket), amount, false);
        }

        @Override
        public void reserve(@NonNull String bucket, long amount) {
            OwnerBudget.OWNER_MEMORY.reserve(bucket, amount);
        }

        @Override
        public void stamp(@NonNull Row row, @Nullable Charge charge) {
            if (charge != null) {
                row.set(InstanceModel.QUOTA_MEMORY_MB, (int) charge.amount());
            }
        }
    };

    private InstanceQuota() {
    }

    /**
     * The quota bucket for a packed subject set; over-long packs fold through a sha256
     * digest so the key always fits the ledger's 191-char primary key.
     */
    public static @NonNull String bucketKeyOf(@NonNull String packedSubjects) {
        return OwnerBudget.INSTANCES.bucketOf(packedSubjects);
    }

    /** The owner-memory bucket for a packed subject set; same 191-char fold. */
    public static @NonNull String memoryBucketOf(@NonNull String packedSubjects) {
        return OwnerBudget.OWNER_MEMORY.bucketOf(packedSubjects);
    }

    /**
     * The owner-memory bucket matching the COUNT bucket a row was charged to -- the one
     * derivation the release paths and the M088 heal share, so a folded owner's two
     * dimensions can never land in different buckets.
     */
    public static @NonNull String memoryBucketOfChargedBucket(@NonNull String countBucket) {
        return memoryBucketOf(OwnerBudget.INSTANCES.packOf(countBucket));
    }

    /**
     * The cap for one owner: the per-owner override row when one exists (0 is a real
     * cap: nothing allowed), else the global default, where 0 or less means NO cap.
     *
     * @return the cap, or null for uncapped
     */
    public static @Nullable Integer limitFor(@NonNull String packedSubjects) {
        return OwnerBudget.INSTANCES.limitFor(packedSubjects);
    }

    /** The owner's workload-memory cap in MB; same override/default semantics. */
    public static @Nullable Integer memoryLimitFor(@NonNull String packedSubjects) {
        return OwnerBudget.OWNER_MEMORY.limitFor(packedSubjects);
    }

    /** How much of an owner's cap is spent (admin surfaces, tests). */
    public static long usedBy(@NonNull String packedSubjects) {
        return OwnerBudget.INSTANCES.usedBy(packedSubjects);
    }

    /** How much workload memory (MB) an owner is holding (admin surfaces, tests). */
    public static long memoryUsedBy(@NonNull String packedSubjects) {
        return OwnerBudget.OWNER_MEMORY.usedBy(packedSubjects);
    }

    /**
     * Move one live row's OWNER charges -- the instance slot AND the workload memory --
     * from the bucket it is charged to into {@code newPack}'s, uncapped.
     *
     * AIDEV-NOTE: both dimensions, through ONE call, because the count lane moved alone
     * for a while: ProjectAdoption released and re-reserved the slot and never touched the
     * M088 owner-memory bucket, so the old owner stayed charged for a workload it no longer
     * held and the new owner was charged nothing -- and the eventual release then landed on
     * the NEW bucket, which is the over-release that can clamp a bucket to zero and wipe
     * every other workload's booking in it. A heal never REFUSES (uncapped, the
     * MAX_INSTANCES_PER_OWNER stance): it is bookkeeping about records that already exist.
     *
     * @return 1 when the charges moved, 0 when the row is already in that bucket
     */
    public static int moveOwnerCharges(@NonNull Row stored, @NonNull String newPack) {
        String newBucket = bucketKeyOf(newPack);
        String oldBucket = chargedBucketOf(stored);
        if (newBucket.equals(oldBucket)) {
            return 0;
        }
        long memory = MEMORY.held(stored).amount();
        Quotas.release(oldBucket, 1);
        Quotas.reserve(newBucket, 1, Long.MAX_VALUE);
        if (memory > 0) {
            Quotas.release(memoryBucketOfChargedBucket(oldBucket), memory);
            Quotas.reserve(memoryBucketOf(newPack), memory, Long.MAX_VALUE);
        }
        return 1;
    }

    /**
     * The count bucket a record created RIGHT NOW would be charged to -- the same
     * derivation the create claim uses, exposed for a caller that must judge a host
     * against the owner before the record exists (the archive-restore placement gate).
     *
     * AIDEV-NOTE: exposed rather than re-derived at the call site on purpose. "Who will
     * this be charged to" has exactly one answer, and a second spelling of it is how a
     * dedicated host ends up admitting a workload the ledger then charges to somebody
     * else.
     */
    public static @NonNull String creationBucket() {
        return bucketKeyOf(creationOwnerPackOf());
    }

    /**
     * WHO a brand-new instance is charged to.
     *
     * An OWNED instance (a site's lowered release container, a managed database's engine)
     * answers to the OWNER OF THE RECORD THAT OWNS IT -- read from the active
     * {@link GeneratedRows} attribution, which is the same fact the row's
     * {@code generated_for_*} columns are stamped from. Everything else answers to the
     * creation owner derived from the acting principal.
     *
     * AIDEV-NOTE: this used to charge the AMBIENT WRITE SCOPE -- operator for anything
     * inside a GeneratedRows scope, on the reasoning that "the tenant authored the SITE
     * write, the instance write is its system consequence". That reasoning is only ever
     * right by accident: it is the same answer as the owner's for an OPERATOR-owned site,
     * and the wrong one for every tenant-held record. The moment a tenant may allocate a
     * managed database of their own, the ambient rule let them mint unlimited engine
     * instances -- each one really booked on a host, each one charged to the OPERATOR's
     * bucket -- so the per-owner cap could not bind the thing it exists for. Ownership is
     * a property of the owning RECORD, never of whichever thread happens to be writing.
     *
     * AIDEV-NOTE: the owning record's manage grants must already exist when its first
     * owned instance is written -- the InstanceTemplates.createFromTemplate lesson one tier
     * up: plant ownership BEFORE the writes that answer to it. A funnel that granted after
     * converging the runtime would charge the operator and then hand the record to a
     * tenant, leaving the reservation attributed to a bucket nobody will ever release.
     *
     * @return the packed manage-subject set to charge; "" IS the operator, never an error
     */
    private static @NonNull String creationOwnerPackOf() {
        GeneratedRows.Attribution attribution = GeneratedRows.currentAttribution();
        if (attribution != null && attribution.forModel() != null && attribution.forId() != null) {
            Identifier ownerModel = Identifier.tryParse(attribution.forModel());
            if (ownerModel != null) {
                Set<String> owner =
                    HohenheimAccess.manageSubjectsOf(ownerModel, attribution.forId());
                if (owner != null) {
                    return HohenheimAccess.packSubjects(owner);
                }
                // Unreadable grants: fall through to the ambient derivation and say so,
                // rather than silently inventing an owner for a record we cannot read.
                Blast.log("QUOTA: cannot read the manage grants of", attribution.forModel(),
                    attribution.forId(), "- charging the owned instance by write scope");
            }
        }
        return OwnerQuota.creationOwnerPack();
    }

    /**
     * The bucket a stored row was charged to; pre-quota rows fall to the operator bucket.
     *
     * Public because every reader must ask the SAME question the release paths ask (the
     * {@link QuotaReconciler} reads it through {@link #COUNT}): a second spelling of "which
     * bucket is this row's" is how a recompute corrects a bucket the row was never charged to.
     */
    public static @NonNull String chargedBucketOf(@NonNull Row stored) {
        return COUNT.held(stored).bucket();
    }
}
