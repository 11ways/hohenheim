package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceQuotaModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.hohenheim.server.orm.GeneratedRows;
import be.elevenways.hohenheim.server.quota.OwnerQuota;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.context.RemoveFromDatasource;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.orm.query.QueryContext;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.orm.quota.QuotaExceeded;
import be.elevenways.zenit.common.orm.quota.Quotas;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
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
 * record that OWNS it -- see {@link #creationOwnerOf}.
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
 * AIDEV-NOTE: the RELEASE rides the deleted_at null -> non-null TRANSITION in this
 * same hook, detected against the STORED row (a partial CMS update carries only the
 * changed keys -- the SiteDomainModel.effective idiom). It must NOT ride the remove
 * hooks for the destroy path: InstanceService.destroy soft-deletes via save(), so
 * beforeRemove/afterRemove never fire there (the only hard-delete call sites in the
 * repo are tests). The remove-hook pairing below exists ONLY for hard deletes, so a
 * criteria delete cannot leak reservations either. Known bypass, by the ledger's own
 * contract: updateAll() fires no hooks, so a future bulk edit that stamps deleted_at
 * set-based would skip the release -- keep destroys on the save path.
 *
 * Localization: refusals are Microcopy-backed violations; bucket keys are machine
 * tokens and never localized content.
 */
public final class InstanceQuota {

    /** The consumer-namespaced bucket prefix; the packed subject set follows it. */
    static final String BUCKET_PREFIX = "hohenheim:instances:";

    /** The owner-memory bucket prefix; the same packed subject set follows it. */
    static final String MEMORY_PREFIX = "hohenheim:owner_mem_mb:";

    /** Where the before-remove hook stashes the buckets the after-remove hook releases. */
    private static final String DOOMED_BUCKETS = "hohenheim.quota.doomed-buckets";

    private static boolean installed;

    private InstanceQuota() {
    }

    /**
     * The quota bucket for a packed subject set; over-long packs fold through a sha256
     * digest so the key always fits the ledger's 191-char primary key.
     */
    public static @NonNull String bucketKeyOf(@NonNull String packedSubjects) {
        return OwnerQuota.bucketOf(BUCKET_PREFIX, packedSubjects);
    }

    /** The owner-memory bucket for a packed subject set; same 191-char fold. */
    public static @NonNull String memoryBucketOf(@NonNull String packedSubjects) {
        return OwnerQuota.bucketOf(MEMORY_PREFIX, packedSubjects);
    }

    /**
     * The owner-memory bucket matching the COUNT bucket a row was charged to -- the one
     * derivation the release paths and the M088 heal share, so a folded owner's two
     * dimensions can never land in different buckets.
     */
    public static @NonNull String memoryBucketOfChargedBucket(@NonNull String countBucket) {
        return memoryBucketOf(OwnerQuota.packOf(BUCKET_PREFIX, countBucket));
    }

    /**
     * The cap for one owner: the per-owner override row when one exists (0 is a real
     * cap: nothing allowed), else the global default, where 0 or less means NO cap.
     *
     * @return the cap, or null for uncapped
     */
    public static @Nullable Integer limitFor(@NonNull String packedSubjects) {
        return OwnerQuota.limitOf(packedSubjects, InstanceQuotaModel.MAX_INSTANCES,
            HohenheimSettings.Quota.MAX_INSTANCES_PER_OWNER);
    }

    /** The owner's workload-memory cap in MB; same override/default semantics. */
    public static @Nullable Integer memoryLimitFor(@NonNull String packedSubjects) {
        return OwnerQuota.limitOf(packedSubjects, InstanceQuotaModel.MAX_MEMORY_MB,
            HohenheimSettings.Quota.MAX_MEMORY_MB_PER_OWNER);
    }

    /** How much of an owner's cap is spent (admin surfaces, tests). */
    public static long usedBy(@NonNull String packedSubjects) {
        return Quotas.usedOf(bucketKeyOf(packedSubjects));
    }

    /** How much workload memory (MB) an owner is holding (admin surfaces, tests). */
    public static long memoryUsedBy(@NonNull String packedSubjects) {
        return Quotas.usedOf(memoryBucketOf(packedSubjects));
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
        long memory = bookedMemoryOf(stored);
        Quotas.release(oldBucket, 1);
        Quotas.reserve(newBucket, 1, Long.MAX_VALUE);
        if (memory > 0) {
            Quotas.release(memoryBucketOfChargedBucket(oldBucket), memory);
            Quotas.reserve(memoryBucketOf(newPack), memory, Long.MAX_VALUE);
        }
        return 1;
    }

    /** Install the reserve/release hooks on the instance write funnel (MODULES stage). */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;

        InstanceModel.SCHEMA.addBeforeWriteHook(context -> {
            Row row = context.getRow();
            if (row == null) {
                return;
            }
            Row stored = storedOf(row);
            boolean storedLive = stored != null && stored.get(InstanceModel.DELETED_AT) == null;
            boolean willBeLive = effectiveDeletedAt(row, stored) == null;

            if (stored == null) {
                // CREATE (no stored row; covers the explicit-PK insert fallback too). The
                // instance itself holds no grant yet, so the charge goes to the owner it
                // is ABOUT TO HAVE -- see creationOwnerOf for the two ways that is derived.
                //
                // AIDEV-NOTE: this used to charge the operator bucket unconditionally,
                // with a comment saying that was "always" the owner because grants land
                // after the record. That held only while creates were admin-only. The
                // moment a tenant can create (InstanceTemplates.createFromTemplate, which
                // plants the creator's manage grant right after), charging the operator
                // meant every tenant shared ONE bucket with the operator: a per-owner cap
                // that could not bind the thing it exists for. The derivation is shared
                // with the grant that follows (HohenheimAccess.creationOwnerSubjects), so
                // the charged bucket and the record's real owner are one answer.
                if (willBeLive) {
                    reserveInto(row, HohenheimAccess.packSubjects(creationOwnerOf()), stored);
                }
            } else if (storedLive && !willBeLive) {
                // The soft-delete transition: hand the CHARGED buckets back.
                Quotas.release(chargedBucketOf(stored), 1);
                releaseMemoryOf(stored);
            } else if (!storedLive && willBeLive) {
                // The restore transition: a restore is a new claim on headroom, judged
                // against the owner as derived NOW (the record and its grants exist).
                Set<String> subjects =
                    HohenheimAccess.manageSubjectsOf(InstanceModel.MODEL_ID, stored.get(InstanceModel.ID));
                if (subjects == null) {
                    // Unreadable grants: fail toward the bucket that was charged before.
                    reserveIntoBucket(row, chargedBucketOf(stored), stored);
                } else {
                    reserveInto(row, HohenheimAccess.packSubjects(subjects), stored);
                }
            } else if (storedLive) {
                // A live row that stays live: only MEMORY can change, and it changes on a
                // settings edit, so the delta rides the bucket already charged.
                rebookMemory(row, stored);
            }
        });

        // Hard deletes only (tests, future bulk cleanup): the destroy path soft-deletes
        // and never reaches these. The criteria-context capture/release pairing is the
        // framework's documented seam (PortLedger.captureDoomedOwners is the reference).
        InstanceModel.SCHEMA.addBeforeRemoveHook(InstanceQuota::captureDoomedBuckets);
        InstanceModel.SCHEMA.addAfterRemoveHook(InstanceQuota::releaseDoomedBuckets);
    }

    // -- hook internals -------------------------------------------------------

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
     * @return the manage-subject set to charge; empty IS the operator, never an error
     */
    /**
     * The count bucket a record created RIGHT NOW would be charged to -- the same
     * derivation the create hook below uses, exposed for a caller that must judge a host
     * against the owner before the record exists (the archive-restore placement gate).
     *
     * AIDEV-NOTE: exposed rather than re-derived at the call site on purpose. "Who will
     * this be charged to" has exactly one answer, and a second spelling of it is how a
     * dedicated host ends up admitting a workload the ledger then charges to somebody
     * else.
     */
    public static @NonNull String creationBucket() {
        return bucketKeyOf(HohenheimAccess.packSubjects(creationOwnerOf()));
    }

    private static @NonNull Set<String> creationOwnerOf() {
        GeneratedRows.Attribution attribution = GeneratedRows.currentAttribution();
        if (attribution != null && attribution.forModel() != null && attribution.forId() != null) {
            Identifier ownerModel = Identifier.tryParse(attribution.forModel());
            if (ownerModel != null) {
                Set<String> owner =
                    HohenheimAccess.manageSubjectsOf(ownerModel, attribution.forId());
                if (owner != null) {
                    return owner;
                }
                // Unreadable grants: fall through to the ambient derivation and say so,
                // rather than silently inventing an owner for a record we cannot read.
                Blast.log("QUOTA: cannot read the manage grants of", attribution.forModel(),
                    attribution.forId(), "- charging the owned instance by write scope");
            }
        }
        return HohenheimAccess.creationOwnerSubjects(
            TenantWrites.isTenantOriginated() ? TenantWrites.acting() : null);
    }

    private static void reserveInto(@NonNull Row row, @NonNull String packedSubjects,
                                    @Nullable Row stored) {
        reserveIntoBucket(row, bucketKeyOf(packedSubjects), stored);
    }

    /**
     * Reserve one slot AND the workload's memory in the owner's buckets, and stamp both on
     * the row -- usage is counted even when no cap is configured, so enabling a cap later
     * starts from honest numbers.
     *
     * AIDEV-NOTE: the count is reserved first, and the memory reservation is UNWOUND onto
     * it -- corrected 2026-09-01, and the note that stood here was the bug. It claimed a
     * count spent without its memory "cannot outlive the refusal" because the ledger rides
     * the caller's transaction; an instance save opens NO transaction (Model.save opens one
     * only for a revisionable schema, and this model is not one), so a memory refusal left
     * the slot spent forever. Every such refusal cost the owner one instance from their cap
     * with no record to release it, which is a lockout that only ever grows -- measured on
     * production robbedoes, whose {@code instances} bucket read 15 against 14 live rows.
     *
     * The compensation is LOCAL to this method and cannot reach further: a refusal in a
     * LATER hook (the host budget's {@code host_capacity_reached} is the reachable one,
     * since InstanceCapacity installs after this) still leaves both owner reservations
     * spent, because a before-write hook cannot see a sibling throw. That residue is what
     * {@link be.elevenways.hohenheim.server.quota.QuotaReconciler} exists for.
     */
    private static void reserveIntoBucket(@NonNull Row row, @NonNull String bucket,
                                          @Nullable Row stored) {
        String packed = OwnerQuota.packOf(BUCKET_PREFIX, bucket);
        Integer limit = limitFor(packed);
        try {
            Quotas.reserve(bucket, 1, limit == null ? Long.MAX_VALUE : limit);
        } catch (QuotaExceeded full) {
            throw Violations.ofForm(Microcopy.of("quota_reached")
                .withFilter("scope", "violations")
                .withArg("used", full.getUsed())
                .withArg("limit", full.getLimit()));
        }
        row.set(InstanceModel.QUOTA_BUCKET, bucket);
        try {
            reserveMemory(row, packed, InstanceCapacity.effectiveFootprintMb(row, stored));
        } catch (RuntimeException | Error refused) {
            Quotas.release(bucket, 1);
            throw refused;
        }
    }

    /** Book {@code amountMb} against the owner's memory budget and stamp what was taken. */
    private static void reserveMemory(@NonNull Row row, @NonNull String packed, int amountMb) {
        OwnerQuota.reserve(memoryBucketOf(packed), amountMb, memoryLimitFor(packed),
            "memory_quota_reached");
        row.set(InstanceModel.QUOTA_MEMORY_MB, Math.max(0, amountMb));
    }

    /** Hand a stored row's booked memory back to the bucket it was charged to. */
    private static void releaseMemoryOf(@NonNull Row stored) {
        long booked = bookedMemoryOf(stored);
        if (booked > 0) {
            Quotas.release(memoryBucketOf(chargedPackOf(stored)), booked);
        }
    }

    /**
     * A live row that stays live: charge or release only the DIFFERENCE between what it
     * holds and what its new settings declare, against the bucket it is already in.
     */
    private static void rebookMemory(@NonNull Row row, @NonNull Row stored) {
        long booked = bookedMemoryOf(stored);
        int amount = InstanceCapacity.effectiveFootprintMb(row, stored);
        if (booked == amount) {
            return;
        }
        String packed = chargedPackOf(stored);
        if (amount > booked) {
            OwnerQuota.reserve(memoryBucketOf(packed), amount - booked, memoryLimitFor(packed),
                "memory_quota_reached");
        } else {
            Quotas.release(memoryBucketOf(packed), booked - amount);
        }
        row.set(InstanceModel.QUOTA_MEMORY_MB, Math.max(0, amount));
    }

    /**
     * What a stored row is holding against its owner's memory budget: the STAMP when it has
     * one, else the footprint its settings imply.
     *
     * AIDEV-NOTE: the fallback covers rows written before the stamp column existed and is
     * SLOGGED rather than silent -- a release computed from settings that changed since is
     * exactly the drift the stamp prevents (the InstanceCapacity.bookedOf twin).
     */
    private static long bookedMemoryOf(@NonNull Row stored) {
        Integer stamped = stored.get(InstanceModel.QUOTA_MEMORY_MB);
        if (stamped != null) {
            return Math.max(0, stamped);
        }
        int derived = InstanceCapacity.footprintMbOf(stored);
        Blast.log("QUOTA: instance", stored.get(InstanceModel.ID),
            "carries no booked owner memory; releasing the derived footprint", derived);
        return Math.max(0, derived);
    }

    /** The packed subject set behind the bucket a stored row was charged to. */
    private static @NonNull String chargedPackOf(@NonNull Row stored) {
        return OwnerQuota.packOf(BUCKET_PREFIX, chargedBucketOf(stored));
    }


    /**
     * The bucket a stored row was charged to; pre-quota rows fall to the operator bucket.
     *
     * Public because the reconcile lane must ask the SAME question the release paths ask:
     * a second spelling of "which bucket is this row's" is how a recompute corrects a
     * bucket the row was never charged to.
     */
    public static @NonNull String chargedBucketOf(@NonNull Row stored) {
        String bucket = stored.get(InstanceModel.QUOTA_BUCKET);
        if (bucket != null && !bucket.isBlank()) {
            return bucket;
        }
        Blast.log("QUOTA: instance", stored.get(InstanceModel.ID),
            "carries no charged bucket; releasing against the operator bucket");
        return bucketKeyOf("");
    }

    /** The value deleted_at will END UP with: staged when carried, else stored. */
    private static @Nullable Object effectiveDeletedAt(@NonNull Row row, @Nullable Row stored) {
        if (row.has(InstanceModel.DELETED_AT.getName())) {
            return row.get(InstanceModel.DELETED_AT.getName());
        }
        return stored != null ? stored.get(InstanceModel.DELETED_AT) : null;
    }

    private static @Nullable Row storedOf(@NonNull Row row) {
        if (!row.has(InstanceModel.ID.getName()) || row.get(InstanceModel.ID) == null) {
            return null;
        }
        return Models.get(InstanceModel.class).findById(row.get(InstanceModel.ID));
    }

    private static void captureDoomedBuckets(@NonNull RemoveFromDatasource context) {
        Model model = context.getModel();
        if (model == null) {
            return;
        }
        QueryContext queryContext = context.getQueryContext();
        Criteria criteria = queryContext != null ? queryContext.getCriteria() : null;
        QueryBuilder<Row> builder = model.find();
        if (criteria != null) {
            builder.where(criteria);
        }
        List<Doomed> doomed = new ArrayList<>();
        for (Row row : builder.all()) {
            // Trashed rows already released on their soft-delete transition.
            if (row.get(InstanceModel.DELETED_AT) == null) {
                doomed.add(new Doomed(chargedBucketOf(row), bookedMemoryOf(row)));
            }
        }
        if (!doomed.isEmpty()) {
            context.setAttribute(DOOMED_BUCKETS, doomed);
        }
    }

    /** One doomed row's release: the count bucket it held, and the memory it booked. */
    private record Doomed(@NonNull String bucket, long memoryMb) {}

    private static void releaseDoomedBuckets(@NonNull RemoveFromDatasource context) {
        if (!(context.getAttribute(DOOMED_BUCKETS) instanceof List<?> doomed)) {
            return;
        }
        for (Object entry : doomed) {
            if (entry instanceof Doomed release) {
                Quotas.release(release.bucket(), 1);
                if (release.memoryMb() > 0) {
                    Quotas.release(memoryBucketOfChargedBucket(release.bucket()),
                        release.memoryMb());
                }
            }
        }
    }
}
