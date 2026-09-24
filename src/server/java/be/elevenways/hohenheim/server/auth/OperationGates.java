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
     * system work (background tasks, schedule chains re-authorized per step, seeds) passes
     * untouched. It sits on the SERVICE, not on a resource or a handler, for the reason
     * {@link TenantWrites} spells out: the HTML row action, the automation API and any
     * future caller all reach the service, and a second copy per surface is how the API
     * ends up a wider door than the UI.
     *
     * AIDEV-NOTE: the refusal is the SAME text for every capability and never says which
     * one is missing -- naming it would turn a refusal into a capability oracle. It is
     * also the same refusal a caller gets for an instance they cannot see at all, which
     * is what the API's uniform 404 is built on.
     *
     * AIDEV-NOTE (default-allow, deferred inversion -- 2026-08-10): the opening
     * {@code !isTenantOriginated()} ALLOWS whenever no tenant identity is in flight, and
     * "no conduit" conflates a boot task, a sweeper, a WebSocket handler and a LEAKED
     * JobRunner continuation into one verdict -- only some of which are provably safe. A
     * dedicated recon established, and this was confirmed, that this is STRUCTURAL, NOT LIVE:
     * no off-thread path reaches this gate today (the file-manager caller set is fully
     * synchronous; the two request-continuations that DO reach a gate -- the template-install
     * runner and SiteReleases.scheduleDrain -> InstanceService.stop -- gain no authority
     * because the entry point already authorized the same target). The durable fix is to
     * demand a POSITIVE system/operator marker ({@code TenantWrites.asSystem(...)}) rather
     * than infer one from an empty ThreadLocal, plus narrowing {@code GeneratedRows} from a
     * whole-thread off-switch to "attribution plus the writes it wraps". That inversion is
     * deferred DELIBERATELY: fail-closed-by-default requires enumerating and wrapping EVERY
     * system entry point (boot stages, TaskService sweepers, seeds, the ACME publisher, CLI
     * tools, the WebSocket authenticators, the migration/lease runners) -- miss one and
     * legitimate system work refuses itself, which is worse than a gap with no live exploit.
     * It warrants its own wave with a full enumeration; do not close it with a blind marker.
     *
     * AIDEV-NOTE (re-assessed 2026-09-23, still deferred, with the measured scope): as of
     * this date the "no conduit = system" reading is relied on by 20 ScheduledTask
     * implementations, 6 dedicated JobRunner pools and roughly 50 async spawn sites
     * (fireAndForget / submit / raw threads) in src/server, plus the boot stages, seeds, CLI
     * commands and the WebSocket handlers (InstanceConsoles, InstanceShell) that answer from
     * a Principal with no conduit. Failing closed needs ONE of two things first, and neither
     * exists: (a) a positive system marker wrapped around every one of those entry points
     * (miss one and legitimate operator/system work refuses itself), or (b) request-identity
     * PROPAGATION through protoblast's JobRunner, so a continuation spawned by a tenant
     * request carries that tenant instead of reading as system -- a framework feature, not a
     * hohenheim edit. Until (b) lands, a tenant-originated background step is only as safe as
     * the synchronous gate its entry point ran on the SAME target, which is what the two
     * known request-continuations above do.
     *
     * @throws Violations {@code instance_not_permitted}
     */
    static void requireOperationCapability(int instanceId, @NonNull String capability) {
        if (!TenantWrites.isTenantOriginated()) {
            return;
        }
        AccessContext ctx = TenantWrites.acting();
        // ctx.isAnonymous() aligns this with requireDatabaseCapability. The walk already
        // returns false for an anonymous principal before any lookup, so this is an explicit
        // fail-closed spelling for readability, not a behaviour change.
        if (ctx == null || ctx.isAnonymous()
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
        if (ctx.isAnonymous()) {
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
     * {@link #requireOperationCapability} contract) passes untouched, including its
     * documented default-allow debt.
     *
     * @throws Violations {@code instance_not_permitted}
     */
    static void requireOperatorOperation() {
        if (!TenantWrites.isTenantOriginated()) {
            return;
        }
        AccessContext ctx = TenantWrites.acting();
        if (ctx == null || ctx.isAnonymous() || !HohenheimAccess.isAdmin(ctx)) {
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
        if (ctx == null || ctx.isAnonymous()
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
