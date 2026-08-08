package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceQuotaModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.hohenheim.server.orm.GeneratedRows;
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
import be.elevenways.zenit.server.security.SecureTokens;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The instance-count quota: hohenheim's policy layer over the core reservation ledger
 * (zenit {@code Quotas}). THIS class owns one dimension -- live instances per owner.
 *
 * AIDEV-NOTE: corrected 2026-08-08 -- this used to say disk was "explicitly out of
 * scope". Per-owner DISK GB is enforced, by {@link InstanceDeviceQuota} and
 * {@link InstanceRootDiskQuota} into the same owner bucket, and extra NICs alongside
 * it. Still genuinely out of scope, per owner: memory (booked per HOST by
 * {@code InstanceCapacity}), cpu, ports, sites and databases.
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
        String key = BUCKET_PREFIX + packedSubjects;
        if (key.length() > 191) {
            key = BUCKET_PREFIX + "sha256:" + SecureTokens.sha256Hex(packedSubjects);
        }
        return key;
    }

    /**
     * The cap for one owner: the per-owner override row when one exists (0 is a real
     * cap: nothing allowed), else the global default, where 0 or less means NO cap.
     *
     * @return the cap, or null for uncapped
     */
    public static @Nullable Integer limitFor(@NonNull String packedSubjects) {
        Row override = Models.get(InstanceQuotaModel.class).find()
            .where(InstanceQuotaModel.SUBJECTS.eq(packedSubjects))
            .first();
        if (override != null) {
            Integer max = override.get(InstanceQuotaModel.MAX_INSTANCES);
            if (max != null) {
                return max;
            }
        }
        Integer fallback = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Quota.MAX_INSTANCES_PER_OWNER);
        return fallback != null && fallback > 0 ? fallback : null;
    }

    /** How much of an owner's cap is spent (admin surfaces, tests). */
    public static long usedBy(@NonNull String packedSubjects) {
        return Quotas.usedOf(bucketKeyOf(packedSubjects));
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
                    reserveInto(row, HohenheimAccess.packSubjects(creationOwnerOf()));
                }
            } else if (storedLive && !willBeLive) {
                // The soft-delete transition: hand the CHARGED bucket back.
                Quotas.release(chargedBucketOf(stored), 1);
            } else if (!storedLive && willBeLive) {
                // The restore transition: a restore is a new claim on headroom, judged
                // against the owner as derived NOW (the record and its grants exist).
                Set<String> subjects =
                    HohenheimAccess.manageSubjectsOf(InstanceModel.MODEL_ID, stored.get(InstanceModel.ID));
                if (subjects == null) {
                    // Unreadable grants: fail toward the bucket that was charged before.
                    reserveIntoBucket(row, chargedBucketOf(stored));
                } else {
                    reserveInto(row, HohenheimAccess.packSubjects(subjects));
                }
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

    private static void reserveInto(@NonNull Row row, @NonNull String packedSubjects) {
        reserveIntoBucket(row, bucketKeyOf(packedSubjects));
    }

    /**
     * Reserve one slot in the bucket and stamp it on the row -- usage is counted even
     * when no cap is configured, so enabling a cap later starts from honest numbers.
     */
    private static void reserveIntoBucket(@NonNull Row row, @NonNull String bucket) {
        String packed = bucket.startsWith(BUCKET_PREFIX)
            ? bucket.substring(BUCKET_PREFIX.length()) : bucket;
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
    }

    /** The bucket a stored row was charged to; pre-quota rows fall to the operator bucket. */
    private static @NonNull String chargedBucketOf(@NonNull Row stored) {
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
        List<String> doomed = new ArrayList<>();
        for (Row row : builder.all()) {
            // Trashed rows already released on their soft-delete transition.
            if (row.get(InstanceModel.DELETED_AT) == null) {
                doomed.add(chargedBucketOf(row));
            }
        }
        if (!doomed.isEmpty()) {
            context.setAttribute(DOOMED_BUCKETS, doomed);
        }
    }

    private static void releaseDoomedBuckets(@NonNull RemoveFromDatasource context) {
        if (!(context.getAttribute(DOOMED_BUCKETS) instanceof List<?> doomed)) {
            return;
        }
        for (Object bucket : doomed) {
            if (bucket instanceof String key) {
                Quotas.release(key, 1);
            }
        }
    }
}
