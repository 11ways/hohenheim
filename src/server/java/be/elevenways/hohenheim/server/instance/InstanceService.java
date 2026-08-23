package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceFileModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.BootSettle;
import be.elevenways.hohenheim.server.application.ApplicationDeploys;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.application.ApplicationUpstreams;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.hohenheim.server.database.DatabaseEnvInjection;
import be.elevenways.hohenheim.server.database.InstanceDatabaseLinks;
import be.elevenways.hohenheim.server.game.GameDomains;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.host.HostLeases;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.FileStagingSupport;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.server.runtime.NativeSnapshotSupport;
import be.elevenways.hohenheim.server.runtime.VolumeSnapshotSupport;
import be.elevenways.hohenheim.server.runtime.WorkloadAttribution;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.server.task.record.RecordSchedules;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Orchestrates instance records through the driver seam: deploy (create + start +
 * record-after port claim), stop, verified destroy + soft delete, and live status.
 * Refusals are thrown as {@link Violations} with named reasons so the admin surface
 * shows the operator what actually happened -- never a bare 500, never a silent
 * success.
 *
 * AIDEV-NOTE: every operation runs under the HOST LEASE (HostLeases) and every write
 * recording a runtime outcome is CONDITIONAL ON THE FENCE (stampGuarded): a stale
 * controller's write matches zero rows, zero rows is a hard failure, and the loser
 * ABORTS without touching the ledger -- parking claims on the fenced-out path would
 * park the WINNER's fresh rows. The daemon still obeys a stale controller (nothing
 * we mint changes that); what the fence guarantees is that a stale controller's
 * operation cannot STICK -- the database refuses the outcome and the winner's next
 * deploy reconciles the daemon through the owner labels (removeIfOwnedBy).
 *
 * AIDEV-NOTE: power accountability lives HERE, in the one funnel, not on the surfaces.
 * Two reasons, both structural. First, the outcome write is {@code stampGuarded}, a
 * set-based updateAll that fires NO model hooks -- so nothing is recorded unless it is
 * recorded explicitly (the same trap {@link InstanceMigrations#recordMigration} and
 * {@link InstanceSnapshots#recordRestore} document; {@code ActivityLog.withAction} only
 * RENAMES hook-written rows and would record literally nothing here). Second, until
 * 2026-08-08 only the automation API recorded power actions, so the SAME operation was
 * audited over /api/v1 and silent from the admin panel and from /manage -- an audit
 * trail with a hole in it, read by operators as complete. Recording in the service
 * means every caller inherits it: panel row action, API, schedule chain, release
 * engine, crash restart. WHICH surface it was stays answerable through the activity
 * row's own {@code origin} column (web/api/system), which is why the API lane no
 * longer carries a second, hand-written row of its own.
 */
public final class InstanceService {

    /** Publications bind loopback (DockerInstanceRuntime.HOST_BIND_ADDRESS's ledger spelling). */
    private static final String BIND_ADDRESS = "127.0.0.1";

    /** The activity action a SETTLED deploy is recorded under. */
    public static final String ACTIVITY_DEPLOY_ACTION = "deployed";

    /** The trigger a deploy records when its caller names none. */
    public static final String DEFAULT_DEPLOY_REASON = "deploy";

    /** The activity action a SETTLED stop is recorded under. */
    public static final String ACTIVITY_STOP_ACTION = "stopped";

    /** The activity detail a verified destroy renames its soft-delete row with. */
    public static final String ACTIVITY_DESTROY_DETAIL = "destroy";

    /**
     * The instance ids THIS controller has a runtime operation in flight for.
     *
     * AIDEV-NOTE: the missing half of the transitional story, and it exists for exactly
     * one reader: {@link InstanceStatusReconciler}. A deploy is not marked by any status
     * -- the record keeps its PREVIOUS status until the fenced stamp at the very end --
     * so between {@code create} and that stamp the daemon legitimately disagrees with a
     * record that says {@code running}, and a sweeper reading only the daemon would
     * "correct" a workload that is being brought up right now.
     *
     * The host LEASE cannot answer this: {@code HostLeases.requireFence} acquires on miss
     * and then holds for the process lifetime, so "we hold host X" means "no RIVAL is
     * working on X", never "we are idle on X". The two guards are complementary and both
     * are needed -- the lease excludes other controllers, this set excludes ourselves.
     */
    private static final Set<Integer> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    /** Whether this controller is inside a deploy, stop or destroy of this record. */
    static boolean hasOperationInFlight(int instanceId) {
        return IN_FLIGHT.contains(instanceId);
    }

    /**
     * Run one runtime operation with the record marked in flight; re-entrant safe (only
     * the OUTERMOST scope clears the mark).
     *
     * AIDEV-NOTE: the upstream invalidation lives HERE, at the one funnel deploy, stop and
     * destroy all pass through, rather than in each kind's lane. Only the release engine
     * used to invalidate, so a site exposing a WORKSPACE or a DOCKER CONTAINER (both kinds
     * declare supportsSiteUpstream, neither bumps any generation) froze its resolved address
     * at route-build time: routes built while the workload was down answered 503 forever,
     * and -- worse -- a loopback publication's host port is EPHEMERAL, so after a restart the
     * site kept forwarding to a number the daemon may already have handed to somebody else's
     * container. One placement covers every kind, including the ones added later.
     *
     * It runs in a finally, and on the INNER scopes of a nested operation too, because a
     * failed or partial operation moves the address exactly as a settled one does -- a
     * refused stop parks the claims, and the next resolution must see that.
     */
    private <T> T inFlight(int instanceId, @NonNull Supplier<T> body) {
        boolean marked = IN_FLIGHT.add(instanceId);
        try {
            return body.get();
        } finally {
            if (marked) {
                IN_FLIGHT.remove(instanceId);
            }
            ApplicationUpstreams.invalidateForInstance(instanceId);
        }
    }

    private final HostLeases leases;

    /** Test seam: runs between the daemon operations and the fenced outcome write. */
    private final Runnable beforeOutcomeWrite;

    public InstanceService() {
        this(HostLeases.production(), () -> {});
    }

    /**
     * Test seam: a rival controller is an InstanceService over a rival {@link HostLeases};
     * the pause hook is the injectable SIGSTOP between daemon work and the outcome write.
     */
    public InstanceService(@NonNull HostLeases leases, @NonNull Runnable beforeOutcomeWrite) {
        this.leases = leases;
        this.beforeOutcomeWrite = beforeOutcomeWrite;
    }

    /**
     * THE deploy verb, and the one place a kind decides what deploying it MEANS.
     *
     * AIDEV-NOTE: three lanes, chosen here and nowhere else. A release-managed record
     * converges a release; a workspace that DECLARES a repository deploys its SOURCE
     * (checkout + build + restart, {@link WorkspaceBuilds}); everything else is the plain
     * workload half. The fold lives on the service rather than on the surfaces because
     * the row action, the automation API, the schedule chain and the crash restart all
     * reach it -- a surface that had to know which verb its kind wanted is how the
     * workspace's git deploy ended up reachable ONLY from a forge webhook.
     *
     * @throws Violations naming the failure; the record is stamped {@code error}
     */
    public @NonNull InstanceStatus deploy(int instanceId) {
        return deploy(instanceId, DEFAULT_DEPLOY_REASON);
    }

    /**
     * {@link #deploy(int)} with the trigger the surface wants recorded ({@code manual},
     * {@code webhook}, a schedule's own word).
     */
    public @NonNull InstanceStatus deploy(int instanceId, @NonNull String reason) {
        // The ONE power gate, on the service every surface funnels through: the CMS row
        // action, the automation API and anything later. A tenant-originated call must
        // hold power; operator and system work (crash restarts, schedule chains, installs)
        // runs outside a request and passes untouched.
        HohenheimAccess.requireOperationCapability(instanceId, HohenheimAccess.POWER);
        // A release-managed record owns no container: deploying it means checking the
        // source out and converging a RELEASE. The branch is here, after the gate and
        // before any driver work, so every existing power surface deploys an application
        // with nothing wired at the call site.
        if (releaseManaged(instanceId)) {
            return ApplicationDeploys.deploy(instanceId, null, reason).status();
        }
        // The workspace's own fold, on exactly the same terms: WorkspaceBuilds stays THE
        // mechanism and brings the workload up through deployWorkload below, so this is a
        // branch and never a second deploy path.
        if (WorkspaceBuilds.deploysSource(liveRow(instanceId))) {
            return new WorkspaceBuilds(this).deploy(instanceId, null, reason).status();
        }
        return deployWorkload(instanceId);
    }

    /**
     * Create (replacing only an own leftover container) and start the instance's
     * workload, then record the observed published port in the ledger (record-after:
     * the OwnerLabels landed at create, so a crash inside the window stays attributable).
     *
     * AIDEV-NOTE: the WORKLOAD half of {@link #deploy}, with none of its kind folds. A
     * source-deploying kind calls THIS to bring its container up and back, which is what
     * keeps {@code deploy -> WorkspaceBuilds.deploy -> restart -> deploy} from being a
     * cycle. It asks the power gate itself so no in-package caller can be a wider door.
     *
     * @throws Violations naming the failure; the record is stamped {@code error}
     */
    @NonNull InstanceStatus deployWorkload(int instanceId) {
        return inFlight(instanceId, () -> deployWorkloadNow(instanceId));
    }

    /** {@link #deployWorkload}'s body; the in-flight mark is the wrapper's job. */
    private @NonNull InstanceStatus deployWorkloadNow(int instanceId) {
        HohenheimAccess.requireOperationCapability(instanceId, HohenheimAccess.POWER);
        Resolved resolved = resolve(instanceId);
        // Settle-then-refuse: a start under a live capture/restore corrupts the very
        // data those operations exist to protect; a start before the template's install
        // step completed runs the workload on half-written data.
        InstanceOperationGuard.requireOperable(resolved.row());
        InstanceOperationGuard.requireInstalled(resolved.row());
        long fence = this.leases.requireFence(resolved.serverId());
        // The predicate (and the reasoning behind it) lives on OwnedInstances.isPlacementGated,
        // so the instance overview can explain this refusal by asking the SAME question.
        if (OwnedInstances.isPlacementGated(resolved.handler(), resolved.row())) {
            HostAdmission.requireInstancePlacement(resolved.serverId(),
                resolved.handler().isolation(),
                resolved.row().get(InstanceModel.QUOTA_BUCKET));
        }
        InstanceConsoles.Watch watch = null;
        try {
            // A previous run's console session (if any) watches a container this
            // deploy is about to replace; end it before the daemon work starts.
            InstanceConsoles.closeSession(instanceId);
            // Pre-allocation honesty: a UDP/public/fixed-port publication claims its host
            // port in the ledger BEFORE the container exists (and before the image pull
            // that sits inside the create window); record-after specs pass unchanged.
            InstanceSpec spec = PortPublications.ensureClaimed(resolved, instanceId);
            // The kind's own host preparation: volume directories materialized with their
            // quota and ownership, the runtime image built if the host does not have it.
            // Before create, because a bind whose source does not exist is silently
            // created root-owned by the daemon.
            resolved.handler().prepareForDeploy(instanceId,
                ServerModel.nameOf(resolved.serverId()), resolved.settings());
            String handle = resolved.runtime().create(spec);
            // Pin honesty, recorded from DAEMON truth right after create: the resolved
            // image identity behind the mutable alias is what the record answers with,
            // and what an absent-workload recreate resolves from.
            pinResolvedImage(resolved, spec, fence);
            // Desired devices reconcile BEFORE start: a recreated workload comes back
            // with its disks and NICs, never silently without them.
            new InstanceDevices(this).reconcile(resolved, instanceId);
            stageConfigFiles(resolved, instanceId);
            // Deploy recreates the container, dropping every non-primary network: the
            // owning tiers re-establish their link networks here, BEFORE start, with
            // their policy enforced -- a workload must never serve without its links.
            // Every registered hook, in declared weight order; each gates itself.
            InstancePreStartHooks.run(resolved, instanceId);
            // The console attaches BETWEEN create and start (docker run's own order),
            // so a readiness line printed in the first instant cannot be missed.
            watch = InstanceConsoles.prepare(resolved, instanceId, this.leases);
            resolved.runtime().start(handle);
            InstanceStatus status = resolved.runtime().status(handle);
            // Assert the DAEMON's binding against the declaration BEFORE any outcome
            // write: a mismatch (wrong bind address, wrong pre-allocated number) stops
            // the workload and refuses the deploy -- never a success report over a bind
            // nothing declared.
            PortPublications.verifyPublished(resolved, spec, status);
            // The template's DECLARED readiness, for the two kinds that can only be
            // answered once the workload is up and its host port is known. console_line
            // is the console hub's and was already armed above.
            InstanceReadiness.await(resolved.row(), status);
            this.beforeOutcomeWrite.run();
            // The fence gate comes BEFORE any ledger write: a stale controller that
            // reached the ledger first would delete the winner's fresh port claim.
            // With a readiness matcher the stamp is STARTING; the matcher's own
            // fenced write flips it to RUNNING when the line is observed.
            stampGuarded(resolved, fence, watch != null
                ? watch.initialStatus() : InstanceModel.STATUS_RUNNING);
            for (var publication : spec.publications()) {
                if (publication.requiresPreallocation()) {
                    // A pre-allocated claim already exists (written before create);
                    // re-recording it here would rewrite the row without its mode and
                    // turn the stable reservation back into an ephemeral one.
                    continue;
                }
                var observed = spec.publications().size() == 1
                    ? (status.publishedPorts().isEmpty()
                        ? null : status.publishedPorts().get(0))
                    : status.publishedFor(publication.containerPort(), publication.protocol());
                if (observed != null) {
                    PortLedger.recordObserved(resolved.serverId(), BIND_ADDRESS,
                        observed.hostPort(), "tcp", InstanceModel.MODEL_ID, instanceId, null);
                }
            }
            if (watch != null) {
                InstanceConsoles.arm(watch, instanceId);
            }
            // The published port is fresh (loopback publications are ephemeral), so any
            // generated SRV rows riding this proxy re-reconcile now.
            GameDomains.afterInstanceDeploy(instanceId);
            recordPower(instanceId, ACTIVITY_DEPLOY_ACTION, resolved);
            return status;
        } catch (IOException e) {
            InstanceConsoles.closeSession(instanceId);
            // Fence first, ledger second: a fenced-out loser must not park the
            // winner's claims. Whatever the previous deploy held is unverifiable
            // for a still-fenced controller: park, never delete.
            stampGuarded(resolved, fence, InstanceModel.STATUS_ERROR);
            PortLedger.releaseOwner(InstanceModel.MODEL_ID, instanceId);
            throw refusal("instance_deploy_failed", resolved.row(), e);
        } catch (Violations refused) {
            // stampGuarded (fenced out) or the console's own named refusal: never
            // leave a console session attached to a deploy this controller lost.
            InstanceConsoles.closeSession(instanceId);
            throw refused;
        }
    }

    /**
     * Stop the workload. A confirmed stop IS an observation that the published port is
     * free (a stopped container binds nothing at the kernel), so the OBSERVED claims are
     * released verified and a restart records a fresh ephemeral port. Pre-allocated
     * claims deliberately SURVIVE a stop: the kernel port is free but the NUMBER stays
     * reserved, which is what keeps the DNS rows pointing at it honest across restarts.
     *
     * @throws Violations naming the failure; the claims are parked, the status untouched
     */
    public void stop(int instanceId) {
        inFlight(instanceId, () -> {
            stopNow(instanceId);
            return null;
        });
    }

    /** {@link #stop}'s body; the in-flight mark is the wrapper's job. */
    private void stopNow(int instanceId) {
        HohenheimAccess.requireOperationCapability(instanceId, HohenheimAccess.POWER);
        if (releaseManaged(instanceId)) {
            ApplicationReleases.stopFor(instanceId);
            return;
        }
        Resolved resolved = resolve(instanceId);
        // An operator stop mid-capture/mid-restore would stamp STOPPED over the
        // protected status and un-protect the operation; destroy stays ungated.
        InstanceOperationGuard.requireOperable(resolved.row());
        long fence = this.leases.requireFence(resolved.serverId());
        try {
            // The console half of a stop: mark the coming exit OBSERVED (crash
            // detection must not fire on an operator stop), then try the template's
            // stop command over stdin. The daemon stop below runs either way -- it is
            // the daemon's idempotent no-op after a successful console stop, and the
            // enforcement (SIGTERM, then SIGKILL after grace) when there was none.
            InstanceConsoles.markStopExpected(instanceId);
            boolean graceful = InstanceConsoles.tryGracefulStop(instanceId, 10);
            if (graceful) {
                Blast.log("INSTANCE: stop of", resolved.spec().handle(),
                    "settled via the console stop command");
            }
            resolved.runtime().stop(resolved.spec().handle(), 10);
            this.beforeOutcomeWrite.run();
            stampGuarded(resolved, fence, InstanceModel.STATUS_STOPPED);
            PortLedger.releaseOwnerObserved(InstanceModel.MODEL_ID, instanceId);
            recordPower(instanceId, ACTIVITY_STOP_ACTION, resolved);
        } catch (IOException e) {
            stampGuarded(resolved, fence, InstanceModel.STATUS_ERROR);
            PortLedger.releaseOwner(InstanceModel.MODEL_ID, instanceId);
            throw refusal("instance_stop_failed", resolved.row(), e);
        }
    }

    /**
     * Verified teardown + soft delete: the container is removed (or observed absent),
     * the port claims are released as observed, and only then is the record trashed
     * (deleted_at; the grant liveWhen predicate keys on it). Named volumes survive --
     * the reconciler reports them as orphans until an operator decides -- and every site
     * that exposed the workload is DISABLED rather than left serving a permanent 503
     * ({@link InstanceExposure}); the site itself, its hostnames and its certificate are
     * never touched.
     *
     * AIDEV-NOTE: the deleted_at write itself rides save(), NOT the guarded updateAll,
     * because the instance-quota release lives in a beforeWrite hook on the deleted_at
     * transition and updateAll is hook-free by contract. The guarded stamp immediately
     * before is the fence proof; the small window between it and the save is
     * covered by the held lease, not by a row guard.
     *
     * @throws Violations when the daemon cannot confirm removal: the record is KEPT
     *         (status {@code error}), the claims are parked, and the operator retries
     */
    public void destroy(int instanceId) {
        inFlight(instanceId, () -> {
            destroyNow(instanceId);
            return null;
        });
    }

    /** {@link #destroy}'s body; the in-flight mark is the wrapper's job. */
    private void destroyNow(int instanceId) {
        // Destroy is its OWN verb, not a power action: stopping is reversible, this is not.
        HohenheimAccess.requireOperationCapability(instanceId, HohenheimAccess.DESTROY);
        if (releaseManaged(instanceId)) {
            ApplicationReleases.destroyFor(instanceId);
            trash(instanceId);
            InstanceExposure.disableForDestroyedInstance(instanceId);
            return;
        }
        Resolved resolved = resolve(instanceId);
        long fence = this.leases.requireFence(resolved.serverId());
        try {
            // Destroy is an intended end: never a crash, and no session survives it.
            InstanceConsoles.markStopExpected(instanceId);
            InstanceConsoles.closeSession(instanceId);
            resolved.runtime().destroy(resolved.spec().handle());
            // Device rows and their daemon-side volumes die WITH the workload,
            // verified -- destroy soft-deletes the record, so nothing else would ever
            // release those reservations or reclaim those volumes.
            new InstanceDevices(this).destroyCleanup(resolved, instanceId);
            // Destroy is the operator's abandon-ship during a migration too (the
            // ungated-cleanup doctrine): an import already landed on the destination
            // must not outlive its record as an orphan -- removed when the daemon
            // still attributes it to this record, and ONLY then.
            destroyAbandonedMigrationCopy(resolved);
        } catch (IOException e) {
            stampGuarded(resolved, fence, InstanceModel.STATUS_ERROR);
            PortLedger.releaseOwner(InstanceModel.MODEL_ID, instanceId);
            throw refusal("instance_destroy_failed", resolved.row(), e);
        }
        this.beforeOutcomeWrite.run();
        stampGuarded(resolved, fence, InstanceModel.STATUS_STOPPED);
        // End of life: pre-allocated reservations die WITH the instance -- unlike stop,
        // which keeps them (the stable number is what DNS points at across restarts).
        PortLedger.releaseOwnerFully(InstanceModel.MODEL_ID, instanceId);
        Row row = Models.get(InstanceModel.class).findById(instanceId);
        if (row == null) {
            return;
        }
        row.set(InstanceModel.DELETED_AT, Instant.now());
        // The deleted_at write is the CONTINUATION of a destroy whose capability gate ran
        // at the funnel; TenantWrites' instance rule would otherwise read it as a tenant
        // authoring a frozen column (see inAuthorizedOperation's contract).
        //
        // This save is the ONE hook-firing write in the whole teardown, so the withAction
        // rename belongs around it rather than around the caller's call: the CMS row
        // action used to own that wrapper, which left every OTHER destroy caller (the
        // release engine, preview expiry, database teardown) recording a bare "update"
        // for an irreversible teardown.
        ActivityLog.withAction(ActivityLog.ACTION_DELETE, ACTIVITY_DESTROY_DETAIL,
            () -> TenantWrites.inAuthorizedOperation(
                () -> Models.get(InstanceModel.class).save(row)));
        // Schedules must die with their record, and destroy SOFT-deletes (remove hooks
        // never fire here), so the cleanup is explicit -- nothing else will do it.
        new RecordSchedules(Db.currentOrDefault())
            .deleteForRecord(InstanceModel.MODEL_ID, instanceId);
        // Game-domain mappings die with either of their instances, the same explicit-
        // cleanup shape as schedules: generated DNS rows and forced-hosts entries come
        // down rather than dangle at a dead backend.
        GameDomains.deleteForInstance(instanceId);
        // Database attachments die with the instance for the same reason, and their link
        // networks come down with them: a soft-deleted record fires no remove hooks, so a
        // surviving row would keep a dead workload named in the database's in-use refusal
        // and keep its link network at the daemon forever.
        InstanceDatabaseLinks.deleteForInstance(instanceId);
        // A site that exposed this workload has no backend any more, and nothing else
        // consults sites.instance_id on a teardown -- so it would keep its hostnames,
        // its DNS records and its certificate while answering 503 forever. It is
        // DISABLED, never deleted: the hostnames, certificates and access rules are the
        // SITE's, and re-pointing it at another instance is one toggle away.
        InstanceExposure.disableForDestroyedInstance(instanceId);
        Blast.log("INSTANCE: destroyed", resolved.spec().handle(),
            "- container removed, volumes kept, record soft-deleted");
    }

    /**
     * Record a settled power operation on the INSTANCE record, naming the daemon handle
     * it settled against; a failed operation is answered by the {@code error} status
     * stamp and its named refusal, not by an activity row claiming it happened.
     */
    private static void recordPower(int instanceId, @NonNull String action,
                                    @NonNull Resolved resolved) {
        ActivityLog.record(Models.get(InstanceModel.class), instanceId, action,
            resolved.spec().handle());
    }

    /** Remove a mid-migration destination copy the daemon attributes to this record. */
    private static void destroyAbandonedMigrationCopy(@NonNull Resolved resolved) {
        Integer targetId = resolved.row().get(InstanceModel.MIGRATE_TARGET_ID);
        if (targetId == null || targetId == resolved.serverId()) {
            return;
        }
        try {
            // The named-refusal funnel, caught below: destroy is the abandon-ship verb
            // and must not be BLOCKED by a destination host that became unaddressable.
            InstanceRuntime target = runtimeFor(resolved.handler(),
                ServerModel.nameOf(targetId));
            if (target instanceof WorkloadAttribution support
                    && support.claimOf(resolved.spec())
                        == WorkloadAttribution.WorkloadClaim.OURS) {
                InstanceMigrations.removeMigrationCopy(target, resolved);
            }
        } catch (IOException | Violations cleanupFailed) {
            Blast.log("INSTANCE: destroy could not remove the mid-migration copy of",
                resolved.spec().handle(), "on server", targetId, ":",
                cleanupFailed.getMessage());
        }
    }

    /**
     * THE release-role transition: a fenced SINGLE-COLUMN write, never a whole-row save.
     *
     * AIDEV-NOTE: deliberately does NOT go through {@link #resolve}. A role flip is
     * control-plane bookkeeping that must not acquire preconditions it does not need --
     * resolve() refuses a blank image and an unknown kind, and the switch half of a
     * gated release cannot afford to fail on a spec question after the candidate is
     * already taking traffic. It reads the host off the row and stamps under the host
     * fence, so a stale controller's flip matches zero rows exactly like every other
     * outcome write. No capability gate: the role is not a request-reachable verb, and
     * the operation that owns it (a site release) gated itself at the site tier.
     *
     * @throws Violations {@code instance_not_found} or {@code instance_fenced_out}
     */
    public void assignRuntimeRole(int instanceId, @NonNull String role) {
        Row row = Models.get(InstanceModel.class).find()
            .where(InstanceModel.ID.eq(instanceId))
            .where(InstanceModel.DELETED_AT.isNull())
            .first();
        if (row == null) {
            throw Violations.ofForm(violationText("instance_not_found")
                .withArg("id", instanceId));
        }
        int serverId = ServerModel.canonicalServerId(row.get(InstanceModel.SERVER_ID));
        long fence = this.leases.requireFence(serverId);
        InstanceOperationGuard.stampRole(this.leases, instanceId, serverId, fence, role,
            String.valueOf((Object) row.get(InstanceModel.NAME)));
    }

    /** Typed live status straight off the daemon; never throws. */
    public @NonNull InstanceStatus liveStatus(int instanceId) {
        if (releaseManaged(instanceId)) {
            // An application's live state IS its serving release's; with nothing serving
            // the honest answer is ABSENT, not an exception from a driver it has none of.
            InstanceStatus serving = ApplicationReleases.liveStatus(instanceId);
            return serving != null ? serving : new InstanceStatus(ContainerState.ABSENT, null);
        }
        Resolved resolved = resolve(instanceId);
        return resolved.runtime().status(resolved.spec().handle());
    }

    /** Whether this record's kind deploys through the release engine instead of a driver. */
    private static boolean releaseManaged(int instanceId) {
        Row row = liveRow(instanceId);
        return row != null && InstanceKinds.isReleaseManaged(row.get(InstanceModel.KIND));
    }

    /** The record as it stands, or null when it is missing or trashed. */
    private static @Nullable Row liveRow(int instanceId) {
        return Models.get(InstanceModel.class).find()
            .where(InstanceModel.ID.eq(instanceId))
            .where(InstanceModel.DELETED_AT.isNull())
            .first();
    }

    /**
     * Soft-delete a record whose runtime consequences are already settled.
     *
     * AIDEV-NOTE: an application has no container, so the destroy verb's fenced
     * stamp-then-trash sequence has nothing to fence against; what makes ITS delete safe is
     * that {@code ApplicationReleases.destroyFor} refused unless every release was verified
     * gone first.
     */
    private void trash(int instanceId) {
        Row row = Models.get(InstanceModel.class).findById(instanceId);
        if (row == null || row.get(InstanceModel.DELETED_AT) != null) {
            return;
        }
        row.set(InstanceModel.DELETED_AT, Instant.now());
        Models.get(InstanceModel.class).save(row);
    }

    // -- the fence discipline -------------------------------------------------

    /**
     * THE fenced outcome write: one guarded statement that both records the status and
     * stamps the fence -- {@code WHERE id = ? AND deleted_at IS NULL AND (claim_fence
     * IS NULL OR claim_fence <= :myFence)}. Zero matched rows is a HARD FAILURE, never
     * a shrug: it means a rival controller with a higher fence owns this record now,
     * so this controller drops its hold and aborts. Cleanup is the winner's job.
     *
     * @throws Violations {@code instance_fenced_out}
     */
    private void stampGuarded(@NonNull Resolved resolved, long fence, @NonNull String status) {
        InstanceOperationGuard.stamp(this.leases, resolved.row().get(InstanceModel.ID),
            resolved.serverId(), fence, status,
            String.valueOf((Object) resolved.row().get(InstanceModel.NAME)));
    }

    /** The lease set this service mutates hosts under (shared with snapshot/backup ops). */
    @NonNull HostLeases leases() {
        return this.leases;
    }

    /**
     * Record the daemon's RESOLVED image identity on the record (fenced write), read
     * right after create so "what is actually running" is answerable from the row.
     * Drivers without an identity-reporting capability leave the column untouched.
     */
    private void pinResolvedImage(@NonNull Resolved resolved, @NonNull InstanceSpec spec,
                                  long fence) throws IOException {
        String observed = null;
        if (resolved.runtime() instanceof NativeSnapshotSupport support) {
            observed = support.imageIdentity(spec).id();
        } else if (resolved.runtime() instanceof VolumeSnapshotSupport support) {
            observed = support.imageIdentity(spec).id();
        }
        if (observed == null || observed.isBlank()
                || observed.equals(resolved.row().get(InstanceModel.IMAGE_FINGERPRINT))) {
            return;
        }
        InstanceOperationGuard.stampFingerprint(this.leases,
            resolved.row().get(InstanceModel.ID), resolved.serverId(), fence, observed,
            String.valueOf((Object) resolved.row().get(InstanceModel.NAME)));
    }

    /**
     * Render and place the instance's config files into the CREATED workload (before
     * start), {@code {{KEY}}} placeholders resolved against its variables. A driver
     * without file staging refuses when files exist -- never a silently missing config.
     */
    private static void stageConfigFiles(@NonNull Resolved resolved, int instanceId)
            throws IOException {
        List<Row> files = Models.get(InstanceFileModel.class).findByInstanceId(instanceId);
        if (files.isEmpty()) {
            return;
        }
        if (!(resolved.runtime() instanceof FileStagingSupport staging)) {
            throw Violations.ofForm(violationText("files_unsupported")
                .withArg("name", String.valueOf((Object) resolved.row().get(InstanceModel.NAME))));
        }
        List<FileStagingSupport.StagedFile> staged = new ArrayList<>();
        for (Row file : files) {
            String content = file.get(InstanceFileModel.CONTENT);
            String mode = file.get(InstanceFileModel.MODE);
            staged.add(new FileStagingSupport.StagedFile(
                file.get(InstanceFileModel.CONTAINER_PATH),
                InstanceVariables.substitute(content == null ? "" : content, resolved.variables()),
                mode == null || mode.isBlank() ? "0644" : mode));
        }
        staging.stageFiles(resolved.spec().handle(), staged, resolved.spec().ownerLabels());
    }

    /**
     * Re-render and push the instance's config files into its PRESENT container (the
     * game-domains materialize-on-change path). ABSENT is a deliberate no-op -- deploy
     * stages unconditionally -- and every other failure is loud.
     *
     * @throws IOException when the container is there but the push fails
     */
    public void restageConfigFiles(int instanceId) throws IOException {
        Resolved resolved = resolve(instanceId);
        ContainerState state = resolved.runtime().status(resolved.spec().handle()).state();
        if (state == ContainerState.ABSENT) {
            return;
        }
        if (state == ContainerState.UNREACHABLE) {
            // The rows are the source of truth and deploy re-stages unconditionally;
            // an unreachable daemon DEFERS convergence, loudly, instead of bricking
            // the control-plane write.
            Blast.log("INSTANCE: daemon unreachable; config files of", instanceId,
                "will be staged at the next deploy");
            return;
        }
        stageConfigFiles(resolved, instanceId);
    }

    // -- resolution -----------------------------------------------------------

    public record Resolved(@NonNull Row row, @NonNull InstanceKindHandler handler,
                           @NonNull InstanceRuntime runtime,
                           @NonNull InstanceSpec spec, int serverId,
                           @NonNull Map<String, String> variables,
                           @NonNull Map<String, Object> settings) {}

    // -- interrupted capture/restore recovery ---------------------------------------

    /** The activity action an interrupted-status settle is recorded under. */
    public static final String ACTIVITY_SETTLE_ACTION = "settled_interrupted";

    /**
     * Boot recovery: settle every instance a killed controller left {@code capturing} or
     * {@code restoring} -- statuses only the in-process outcome paths ever clear, so a
     * controller kill used to leave the record refusing deploy, stop, backup, snapshot
     * and restore FOREVER, with destroy as the only escape.
     *
     * The discriminator is the process-start fence InstanceBackups/InstanceSnapshots
     * already settle by: captures and restores are synchronous and in-process, so a
     * status written before this process existed belongs to a dead controller and one
     * this process wrote is a live operation and untouchable
     * ({@code InstanceOperationGuard.stamp} assigns UPDATED_AT for exactly this read).
     *
     * AIDEV-NOTE: the clock is the SECOND guard; the host lease is the first. The query is
     * scoped only by status, so on a multi-process deployment it also returns rows another
     * controller is working on right now -- and that controller's clock is not ours, so the
     * process-start comparison would happily read its live capture as a corpse. Every row
     * is therefore settled through {@code BootSettle.underBorrowedHostLease}: a host a
     * rival holds is skipped (its work is its own to settle) and a host taken purely to
     * settle is handed straight back, instead of {@code settleInterrupted -> requireFence}
     * seizing it for this process's lifetime and fencing the rightful controller out.
     */
    public static void recoverInterrupted() {
        List<Row> stuck = Models.get(InstanceModel.class).find()
            .where(InstanceModel.DELETED_AT.isNull())
            .where(Criteria.or(
                InstanceModel.STATUS.eq(InstanceModel.STATUS_CAPTURING),
                InstanceModel.STATUS.eq(InstanceModel.STATUS_RESTORING)))
            .all();
        if (stuck.isEmpty()) {
            return;
        }
        InstanceService service = new InstanceService();
        for (Row row : stuck) {
            Instant written = row.get(InstanceModel.UPDATED_AT);
            if (written == null) {
                written = row.get(InstanceModel.CREATED_AT);
            }
            if (BootSettle.writtenByThisProcess(written)) {
                continue;   // written by THIS process: a live operation, not a corpse
            }
            int id = row.get(InstanceModel.ID);
            Integer serverId = row.get(InstanceModel.SERVER_ID);
            try {
                Runnable settle = () -> {
                    if (!service.settleInterrupted(id)) {
                        Blast.log("INSTANCE: could not settle interrupted",
                            row.get(InstanceModel.STATUS), "state of", id,
                            "- the daemon did not answer; retried at the next boot");
                    }
                };
                if (serverId == null) {
                    settle.run();   // hostless row: no lease to borrow, nothing to fence
                } else {
                    BootSettle.underBorrowedHostLease(service.leases(), serverId, settle);
                }
            } catch (RuntimeException error) {
                Blast.log("INSTANCE: settling interrupted state of", id,
                    "failed:", error.getMessage());
            }
        }
    }

    /**
     * Settle one dead-controller {@code capturing}/{@code restoring} record.
     *
     * A capture never changes the payload it reads, so the settle takes DAEMON truth
     * (a killed native-lane capture leaves the workload running, a killed cold-lane one
     * leaves it stopped). An interrupted RESTORE may have half-written the volume, so it
     * settles to {@code error} without asking the daemon -- the workload's presence says
     * nothing about the payload's integrity, and an operator must decide.
     *
     * @return true when the record was settled, false when the daemon did not answer
     */
    boolean settleInterrupted(int instanceId) {
        Resolved resolved = resolve(instanceId);
        Row row = resolved.row();
        String status = row.get(InstanceModel.STATUS);
        boolean capturing = InstanceModel.STATUS_CAPTURING.equals(status);
        if (!capturing && !InstanceModel.STATUS_RESTORING.equals(status)) {
            return true;   // settled by someone else meanwhile
        }
        String settled;
        if (capturing) {
            InstanceStatus live;
            try {
                live = resolved.runtime().status(resolved.spec().handle());
            } catch (RuntimeException unreachable) {
                return false;   // refusing to answer is not evidence; defer
            }
            settled = live.running()
                ? InstanceModel.STATUS_RUNNING : InstanceModel.STATUS_STOPPED;
        } else {
            settled = InstanceModel.STATUS_ERROR;
        }
        long fence = this.leases.requireFence(resolved.serverId());
        InstanceOperationGuard.stamp(this.leases, instanceId, resolved.serverId(), fence,
            settled, row.get(InstanceModel.NAME));
        ActivityLog.record(Models.get(InstanceModel.class), instanceId,
            ACTIVITY_SETTLE_ACTION, status + " -> " + settled
                + " (interrupted by a controller restart)");
        Blast.log("INSTANCE: settled interrupted", status, "state of",
            row.get(InstanceModel.NAME), "->", settled);
        return true;
    }

    /**
     * Destroy the workload AND the data its volumes hold.
     *
     * AIDEV-NOTE: a SEPARATE verb from {@link #destroy}, not a boolean on it, because the
     * two have different consequences and different confirmations. An ordinary destroy is
     * recoverable in the only sense that matters -- the bytes are still on the host, and a
     * re-created instance mounts them again. This one is not, which is why its surface
     * asks the operator to type the instance's name.
     *
     * @return the host paths that were removed
     * @throws Violations naming whatever refused; the volumes are removed only AFTER the
     *         container is verifiably gone, so a failed destroy never takes the data with it
     */
    public @NonNull List<String> destroyWithData(int instanceId) {
        HohenheimAccess.requireOperationCapability(instanceId, HohenheimAccess.DESTROY);
        Row row = Models.get(InstanceModel.class).findById(instanceId);
        if (row == null) {
            throw Violations.ofForm(violationText("instance_not_found")
                .withArg("id", instanceId));
        }
        String serverName = ServerModel.nameOf(
            ServerModel.canonicalServerId(row.get(InstanceModel.SERVER_ID)));
        // AIDEV-NOTE: an ALREADY destroyed record still has its data, and that is the whole
        // reason an ordinary destroy keeps it. "I deleted the workspace last week, now
        // remove its files" has to work, so the container teardown is conditional and the
        // volume removal is not -- an unconditional destroy() here refused with
        // instance_not_found and left the bytes on the host forever.
        if (row.get(InstanceModel.DELETED_AT) == null) {
            destroy(instanceId);
        }
        List<String> removed = InstanceVolumes.destroyAll(instanceId, serverName);
        ActivityLog.record(Models.get(InstanceModel.class), instanceId, "deleted_data",
            String.join(", ", removed));
        return removed;
    }

    /**
     * Stop and deploy again, in one verb.
     *
     * AIDEV-NOTE: THE restart composition, and there is exactly one. It used to live
     * inline in {@code InstancePowerAction.execute} and nowhere else, so the CMS surface
     * that wanted a restart button had to either hand-roll the pair (two independent
     * gate checks, two toasts, and a UI-side window where the workload is down with
     * nothing recording why) or grow a schedule to press it. stop() is idempotent when
     * the workload is already stopped and deploy() is create-plus-start, so the pair IS
     * the restart -- but the pair belongs to the service every surface funnels through.
     * Both halves ask {@link HohenheimAccess#POWER} themselves; this method deliberately
     * adds no gate of its own, so a restart can never be a wider door than a stop.
     */
    public void restart(int instanceId) {
        stop(instanceId);
        deploy(instanceId);
    }

    /**
     * {@link #restart} without the kind folds, for the source-deploying lane that IS one
     * of those folds -- see {@link #deployWorkload}.
     *
     * @return the status the workload came back with
     */
    @NonNull InstanceStatus restartWorkload(int instanceId) {
        stop(instanceId);
        return deployWorkload(instanceId);
    }

    /**
     * Load the record and resolve its kind handler, host, variables and spec (variables
     * merge into the env and substitute inside the command -- see InstanceVariables).
     *
     * @throws Violations for a missing/trashed record, an unknown kind or a blank image
     */
    public Resolved resolve(int instanceId) {
        Row row = Models.get(InstanceModel.class).find()
            .where(InstanceModel.ID.eq(instanceId))
            .where(InstanceModel.DELETED_AT.isNull())
            .first();
        if (row == null) {
            throw Violations.ofForm(violationText("instance_not_found")
                .withArg("id", instanceId));
        }
        InstanceKindHandler handler = InstanceKinds.getHandler(row.get(InstanceModel.KIND));
        if (handler == null) {
            throw Violations.ofField("kind", row.get(InstanceModel.KIND),
                violationText("instance_kind_unknown")
                    .withArg("kind", String.valueOf((Object) row.get(InstanceModel.KIND))));
        }
        Map<String, Object> settings = row.get(InstanceModel.SETTINGS) instanceof Map<?, ?> map
            ? castSettings(map) : Map.of();
        InstanceVariables instanceVariables = new InstanceVariables();
        Map<String, String> declared = instanceVariables.valuesFor(instanceId);
        // An attached managed database's connection family is DERIVED here, at resolve
        // time, and stored nowhere -- the property DatabaseEnvInjection exists to keep, and
        // the reason a rotated credential needs no rewrite of any settings map. It sits
        // UNDER everything the operator authored (see applyToSettings) and rides
        // Resolved.variables(), so it substitutes into command, cloud_init and staged
        // config files exactly like a declared variable does.
        Map<String, String> derived = DatabaseEnvInjection.envForInstance(instanceId, null);
        Map<String, String> variables = InstanceVariables.layered(derived, declared);
        settings = instanceVariables.applyToSettings(settings, declared, derived);
        InstanceSpec spec = handler.specFor(instanceId, settings);
        if (spec.image().isBlank() && !handler.allowsBlankImage(settings)) {
            throw Violations.ofField("settings.image", "", violationText("instance_image_required"));
        }
        // The record's pinned resolved image identity rides the spec: a driver that
        // resolves by fingerprint recreates an ABSENT workload from the pin, never by
        // re-resolving the mutable alias (cleared on image change by the write hook).
        String pinned = row.get(InstanceModel.IMAGE_FINGERPRINT);
        if (pinned != null && !pinned.isBlank()) {
            spec = spec.withImageFingerprint(pinned);
        }
        // THE canonical host key -- null folds to the local daemon, any other spelling
        // was already normalized onto servers.id by the model's beforeValidate hook.
        int serverId = ServerModel.canonicalServerId(row.get(InstanceModel.SERVER_ID));
        String serverName = ServerModel.nameOf(serverId);
        // The kind's declared runtime must MATCH the host's declared runtime before any
        // client is built: a docker kind resolved against an incus host (or vice versa)
        // is a named refusal, never a client aimed at the wrong daemon.
        Row server = Models.get(ServerModel.class).findById(serverId);
        String hostRuntime = server != null ? ServerModel.runtimeOf(server)
            : ServerModel.RUNTIME_DOCKER;
        InstanceKinds.requireRuntimeMatch(serverName, hostRuntime, handler.supportedRuntimes());
        return new Resolved(row, handler, runtimeFor(handler, serverName), spec, serverId,
            variables, settings);
    }

    /**
     * Build the kind's driver for a host, turning an UNADDRESSABLE host into a NAMED
     * refusal.
     *
     * AIDEV-NOTE: found 2026-08-07 while lowering the database tier. Client construction
     * can throw on its own -- {@code HostKeys.sshArgv} refuses an unpinned host or one
     * with no client identity with a HostTrustException, which is an IllegalStateException
     * -- and that escaped {@link #resolve} raw, so deploy/stop/destroy of an instance on
     * such a host produced a bare 500 instead of telling the operator to scan and confirm
     * the host key. This class's whole contract is "refusals are named Violations, never a
     * bare 500", so the gap was a contradiction of its own docblock, not a missing nicety.
     *
     * AIDEV-NOTE: package-visible since 2026-08-12. The migration lane builds
     * DESTINATION runtimes too (survey and submit), and bypassing this funnel let an
     * unpinned SSH host's HostTrustException escape destinationsFor raw -- ONE
     * unpinnable host in the estate 500ed the whole migrate page.
     *
     * @throws Violations {@code instance_host_unreachable}, naming the host and the reason
     */
    static @NonNull InstanceRuntime runtimeFor(@NonNull InstanceKindHandler handler,
                                               @NonNull String serverName) {
        try {
            return handler.runtimeFor(serverName);
        } catch (Violations alreadyNamed) {
            throw alreadyNamed;
        } catch (RuntimeException unaddressable) {
            throw Violations.ofForm(violationText("instance_host_unreachable")
                .withArg("name", serverName)
                .withArg("reason", unaddressable.getMessage() != null
                    ? unaddressable.getMessage() : unaddressable.toString()));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castSettings(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static Violations refusal(String key, Row row, IOException cause) {
        return Violations.ofForm(violationText(key)
            .withArg("name", String.valueOf((Object) row.get(InstanceModel.NAME)))
            .withArg("reason", cause.getMessage() != null ? cause.getMessage() : cause.toString()));
    }

    private static Microcopy violationText(String key) {
        return Microcopy.of(key).withFilter("scope", "violations");
    }
}
