package be.elevenways.hohenheim.server.auth;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import static be.elevenways.hohenheim.server.auth.HohenheimAccess.DESTROY;

/**
 * The service-side operation gates of the instance and managed-database tiers, with their
 * uniform refusals; reached through {@link HohenheimAccess}.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
final class OperationGates {

    private OperationGates() {
    }

    /**
     * THE operation-funnel gate for a capability-sensitive instance act (power, snapshot,
     * backup): a TENANT-ORIGINATED call must hold the capability, while operator and
     * DECLARED system work (background tasks, schedule chains re-authorized per step, seeds)
     * passes untouched. It sits on the SERVICE, not on a resource or a handler, for the reason
     * {@link TenantWrites} spells out: the HTML row action, the automation API and any
     * future caller all reach the service, and a second copy per surface is how the API
     * ends up a wider door than the UI.
     *
     * AIDEV-NOTE: the refusal is the SAME text for every capability and never says which
     * one is missing -- naming it would turn a refusal into a capability oracle. It is
     * also the same refusal a caller gets for an instance they cannot see at all, which
     * is what the API's uniform 404 is built on.
     *
     * AIDEV-NOTE: the gate FAILS CLOSED. Only a DECLARED system identity passes untouched
     * (boot, tasks, record schedules, offline commands and whatever they schedule carry
     * zenit's {@code ExecutionIdentity} through every JobRunner hop); work with NO identity
     * reads as tenant-originated with a null {@link TenantWrites#acting()} and is refused. A
     * request's continuation runs as that request's caller, so it is judged exactly as the
     * request would be. This replaced the 2026-08-10 default-allow that read "no conduit"
     * as system work.
     *
     * @throws Violations {@code instance_not_permitted}
     */
    static void requireOperationCapability(int instanceId, @NonNull String capability) {
        if (!TenantWrites.isTenantOriginated()) {
            return;
        }
        AccessContext ctx = TenantWrites.acting();
        // !ctx.isAccount() aligns this with requireDatabaseCapability. The walk already
        // returns false for an anonymous principal before any lookup, so this is an explicit
        // fail-closed spelling for readability, not a behaviour change.
        if (ctx == null || !ctx.isAccount()
                || !HohenheimAccess.hasInstanceCapability(ctx, instanceId, capability)) {
            throw Violations.ofForm(instanceNotPermitted());
        }
    }

    /**
     * THE destroy decision, in the shape a RENDER asks it: why this instance's delete is
     * offered yet certain to be refused, or null when it can run.
     *
     * AIDEV-NOTE: this and {@link #requireDestroyPermitted} are the two faces of ONE
     * decision -- same capability, same text -- because the panel used to answer the
     * delete affordance with {@code deletableBy} while the only real gate sat inside
     * {@code InstanceService.destroy}: a view-only delegate was shown a live Delete that
     * could only 422. The FACT is asked over the walk each lane demands (see the note on
     * {@link HohenheimAccess#hasInstanceCapability(AccessContext, int, String)}), which is
     * the one thing the two spellings differ on.
     */
    static @Nullable Microcopy destroyUnavailableReason(@NonNull AccessContext ctx, int instanceId) {
        return destroyRefusal(ctx, instanceId, true);
    }

    /**
     * THE destroy gate, in the shape a WRITE asks it: the same refusal
     * {@link #destroyUnavailableReason} renders the dead Delete with, thrown.
     *
     * @throws Violations {@code instance_not_permitted}
     */
    static void requireDestroyPermitted(int instanceId) {
        if (!TenantWrites.isTenantOriginated()) {
            return;
        }
        AccessContext ctx = TenantWrites.acting();
        if (ctx == null) {
            throw Violations.ofForm(instanceNotPermitted());
        }
        Microcopy refusal = destroyRefusal(ctx, instanceId, false);
        if (refusal != null) {
            throw Violations.ofForm(refusal);
        }
    }

    /**
     * @param memoized the render lane's request memo ({@link CapabilityScopes#reachesRecord});
     *                 false is the fresh walk every write gate keeps
     */
    private static @Nullable Microcopy destroyRefusal(@NonNull AccessContext ctx, int instanceId,
                                                      boolean memoized) {
        if (!ctx.isAccount()) {
            return instanceNotPermitted();
        }
        boolean holds = memoized
            ? CapabilityScopes.reachesRecord(ctx, InstanceModel.MODEL_ID, instanceId, DESTROY)
            : HohenheimAccess.hasInstanceCapability(ctx, instanceId, DESTROY);
        return holds ? null : instanceNotPermitted();
    }

    /**
     * The instance tier's uniform refusal, which deliberately never names the capability
     * that is missing -- rendered as a dead affordance's reason exactly as it is answered
     * to a POST, so neither surface is a capability oracle the other is not.
     */
    private static @NonNull Microcopy instanceNotPermitted() {
        return Microcopy.of("instance_not_permitted").withFilter("scope", "violations");
    }

    /**
     * THE operator gate of an instance-tier act no delegation reaches (install-media
     * attach, template capture): a tenant-originated caller must hold the ADMIN
     * permission, and the refusal is the tier's uniform one -- naming "operators only"
     * would tell a delegate the act exists specifically above them. System work (the
     * {@link #requireOperationCapability} contract) passes untouched; work with no
     * identity is refused.
     *
     * @throws Violations {@code instance_not_permitted}
     */
    static void requireOperatorOperation() {
        if (!TenantWrites.isTenantOriginated()) {
            return;
        }
        AccessContext ctx = TenantWrites.acting();
        if (ctx == null || !ctx.isAccount() || !HohenheimAccess.isAdmin(ctx)) {
            throw Violations.ofForm(instanceNotPermitted());
        }
    }

    /**
     * THE operation-funnel gate for a capability-sensitive managed-database act (backup,
     * destroy). It sits on the SERVICE for the reason {@link #requireOperationCapability}
     * spells out one tier over: the row action, the download endpoint and any later caller
     * all reach the service, and a second copy per surface is how one of them ends up a
     * wider door than the others. Operator and system work (the nightly backup task, the
     * reconciler, seeds) passes untouched.
     *
     * AIDEV-NOTE: the refusal never names the missing capability and is the SAME text a
     * caller gets for a database they cannot see at all -- the instance tier's uniform
     * refusal, for the same reason: a refusal that distinguishes the two is an oracle.
     *
     * @throws Violations {@code database_not_permitted}
     */
    static void requireDatabaseCapability(int databaseId, @NonNull String capability) {
        if (!TenantWrites.isTenantOriginated()) {
            return;
        }
        AccessContext ctx = TenantWrites.acting();
        if (ctx == null || !ctx.isAccount()
                || !HohenheimAccess.hasDatabaseCapability(ctx, databaseId, capability)) {
            throw databaseRefusal();
        }
    }

    /** THE uniform managed-database refusal; visibility, absence and denial are one answer. */
    static @NonNull Violations databaseRefusal() {
        return Violations.ofForm(Microcopy.of("database_not_permitted")
            .withFilter("scope", "violations"));
    }
}
