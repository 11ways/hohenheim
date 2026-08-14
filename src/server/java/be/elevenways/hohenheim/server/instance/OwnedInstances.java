package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.orm.GeneratedRows;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Set;

/**
 * THE substrate every product tier that OWNS instances shares: the GeneratedRows
 * attribution guard on {@link InstanceModel}, the refusal that keeps a
 * {@link InstanceKindHandler#generatedOnly() generated-only} kind unwritable outside its
 * owning tier's system scope, the scope entry itself, and the owned-row lookups.
 *
 * The canonical relation this implements (instance-tier-plan, Phase 7): the INSTANCE
 * TIER IS the runtime-resource contract, and a product record (a Site, a managed
 * Database) stays a product record that OWNS its runtime through
 * {@code instances.generated_for_model/_for_id}. Ownership itself stays grant-derived --
 * the attribution is a structural parent link, never an owner column.
 *
 * AIDEV-NOTE: this class exists because the second tier to lower (managed databases)
 * would otherwise have copied SiteInstances' install/scope/lookup trio verbatim, which
 * is the four-copies-of-the-discipline outcome the plan rejected a narrower abstraction
 * to avoid. The tier-SPECIFIC parts (what a release is, what converge means) stay in
 * SiteInstances and DatabaseInstances; only the attribution mechanics live here.
 */
public final class OwnedInstances {

    private static volatile boolean installed;

    private OwnedInstances() {
    }

    /**
     * Install the ownership funnel on the instance write pipeline (MODULES stage): the
     * GeneratedRows attribution guard plus the generated-only kind refusal. Idempotent,
     * because more than one owning tier calls it.
     */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        GeneratedRows.installGuards(InstanceModel.SCHEMA, InstanceModel.class,
            new GeneratedRows.Columns(InstanceModel.GENERATED_BY,
                InstanceModel.GENERATED_FOR_MODEL, InstanceModel.GENERATED_FOR_ID,
                InstanceModel.GENERATED_AT),
            "instance_generated_readonly", "instance_generated_attribution");
        InstanceModel.SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row == null || GeneratedRows.inSystemScope()) {
                return;
            }
            // The refusal itself lives in InstanceKinds, beside the OFFER derived from the
            // same generatedOnly() declaration -- this hook is the authoritative gate but
            // deliberately not a second copy of the rule.
            InstanceKinds.requireAuthorable(row.get(InstanceModel.KIND));
        });
    }

    /** A scope body that may fail the way the write it wraps fails. */
    @FunctionalInterface
    public interface ScopedWork<T> {
        T run() throws Exception;
    }

    /**
     * Run {@code work} inside the owning record's GeneratedRows attribution scope, so
     * every instance row written inside is stamped {@code (source, model, id)} and the
     * instance-tier tenant gates judge it as the system consequence it is.
     */
    public static <T> T inScope(@NonNull String source, @NonNull Identifier model, int recordId,
                                @NonNull ScopedWork<T> work) throws Exception {
        Object[] result = new Object[1];
        GeneratedRows.as(new GeneratedRows.Attribution(source, model.toString(), recordId),
            () -> result[0] = work.run());
        @SuppressWarnings("unchecked")
        T value = (T) result[0];
        return value;
    }

    /** {@link #inScope} for callers with no checked exceptions to surface. */
    public static void inScopeUnchecked(@NonNull String source, @NonNull Identifier model,
                                        int recordId, @NonNull Runnable work) {
        try {
            inScope(source, model, recordId, () -> {
                work.run();
                return null;
            });
        } catch (RuntimeException | Error unchecked) {
            throw unchecked;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every live instance owned by one product record, newest first. */
    public static @NonNull List<Row> ownedBy(@NonNull Identifier model, int recordId) {
        return Models.get(InstanceModel.class).find()
            .where(InstanceModel.GENERATED_FOR_MODEL.eq(model.toString()))
            .where(InstanceModel.GENERATED_FOR_ID.eq(recordId))
            .where(InstanceModel.DELETED_AT.isNull())
            .orderBy(InstanceModel.ID, SortOrder.DESC)
            .all();
    }

    /** The single live instance owned by one product record (newest wins), or null. */
    public static @Nullable Row soleOwnedBy(@NonNull Identifier model, int recordId) {
        List<Row> owned = ownedBy(model, recordId);
        return owned.isEmpty() ? null : owned.get(0);
    }

    /**
     * THE stored-row answer to "whose workload is this": the manage-grant subjects of the
     * OWNING product record when the row carries an attribution, else of the instance
     * record itself. The empty set is the operator.
     *
     * AIDEV-NOTE: the stored twin of {@code InstanceQuota.creationOwnerOf}, which asks the
     * same question about a write in flight (from the ambient GeneratedRows attribution)
     * rather than about a record that exists. Two derivations of "who owns this workload"
     * that could disagree is exactly what the QUOTA_BUCKET note warns about, so this one
     * reads GRANTS, never the charged bucket -- a bucket is bookkeeping, and grants added
     * after create move ownership without moving it.
     *
     * @return the manage subjects, or null when grants are unreadable (callers fail closed)
     */
    public static @Nullable Set<String> ownerSubjectsOf(@NonNull Row instance) {
        String ownerModel = instance.get(InstanceModel.GENERATED_FOR_MODEL);
        Integer ownerId = instance.get(InstanceModel.GENERATED_FOR_ID);
        if (ownerModel != null && ownerId != null) {
            Identifier model = Identifier.tryParse(ownerModel);
            if (model != null) {
                return HohenheimAccess.manageSubjectsOf(model, ownerId);
            }
        }
        Integer id = instance.get(InstanceModel.ID);
        if (id == null) {
            return Set.of();
        }
        return HohenheimAccess.manageSubjectsOf(InstanceModel.MODEL_ID, id);
    }

    /**
     * Whether this workload answers to a TENANT rather than to the operator -- the
     * question every posture and placement gate is actually about.
     *
     * AIDEV-NOTE: this is NOT {@code handler.tenantAuthored()}, and the difference is the
     * defect it was written for. tenantAuthored is a property of the KIND (may a tenant
     * write this record at all); a database engine, a site release container and a stack
     * service are all operator-authored kinds that a TENANT owns through the product
     * record above them. Gating the deploy-time posture check on the kind meant those
     * workloads were posture-checked once at placement and never again -- so a host whose
     * posture regressed, or whose shared-kernel acknowledgement was withdrawn, kept
     * redeploying tenant workloads onto itself. The posture doctrine is about the RISK a
     * tenant is exposed to, which the authoring tier does not change.
     *
     * Fails CLOSED: unreadable grants count as tenant-attributed, so an unreadable answer
     * runs the gate rather than skipping it.
     */
    public static boolean isTenantAttributed(@NonNull Row instance) {
        Set<String> owner = ownerSubjectsOf(instance);
        return owner == null || !owner.isEmpty();
    }

    /**
     * Whether deploying this workload runs the host placement gate.
     *
     * AIDEV-NOTE: the gate asks whether the WORKLOAD is tenant-attributed, never whether
     * the KIND is tenant-authored, and the difference was a real hole. A managed database's
     * engine, a site's release container and a stack service are operator-AUTHORED kinds
     * that a TENANT owns through the product record above them ({@link #isTenantAttributed}
     * reads that ownership from grants). They are posture-checked once, at placement, and
     * gating the redeploy on tenantAuthored() meant they were never checked again -- so a
     * host whose posture regressed, whose shared-kernel acknowledgement was withdrawn or
     * whose kernel lane stopped proving itself kept taking tenant workloads back. An
     * operator-OWNED workload still skips it, which is the exemption the flag was for.
     *
     * It lives here rather than on the kind SPI so the grants dependency stays out of it,
     * and it is shared so the deploy lane and the page EXPLAINING that lane cannot disagree
     * about which workloads are even gated.
     */
    public static boolean isPlacementGated(@NonNull InstanceKindHandler handler,
                                           @NonNull Row instance) {
        return handler.tenantAuthored() || isTenantAttributed(instance);
    }
}
