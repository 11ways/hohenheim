package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceFileModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.docker.SiteDatabaseNetworks;
import be.elevenways.hohenheim.server.game.GameDomains;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.host.HostLeases;
import be.elevenways.hohenheim.server.stack.StackInstances;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.FileStagingSupport;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.server.runtime.NativeSnapshotSupport;
import be.elevenways.hohenheim.server.runtime.VolumeSnapshotSupport;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.task.record.RecordSchedules;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 */
public final class InstanceService {

    /** Publications bind loopback (DockerInstanceRuntime.HOST_BIND_ADDRESS's ledger spelling). */
    private static final String BIND_ADDRESS = "127.0.0.1";

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
     * Create (replacing only an own leftover container) and start the instance's
     * workload, then record the observed published port in the ledger (record-after:
     * the OwnerLabels landed at create, so a crash inside the window stays attributable).
     *
     * @throws Violations naming the failure; the record is stamped {@code error}
     */
    public @NonNull InstanceStatus deploy(int instanceId) {
        // The ONE power gate, on the service every surface funnels through: the CMS row
        // action, the automation API and anything later. A tenant-originated call must
        // hold manage; operator and system work (crash restarts, schedule chains, installs)
        // runs outside a request and passes untouched.
        HohenheimAccess.requireOperationCapability(instanceId, HohenheimAccess.MANAGE);
        Resolved resolved = resolve(instanceId);
        // Settle-then-refuse: a start under a live capture/restore corrupts the very
        // data those operations exist to protect; a start before the template's install
        // step completed runs the workload on half-written data.
        InstanceOperationGuard.requireOperable(resolved.row());
        InstanceOperationGuard.requireInstalled(resolved.row());
        long fence = this.leases.requireFence(resolved.serverId());
        if (resolved.handler().tenantAuthored()) {
            HostAdmission.requireInstancePlacement(resolved.serverId());
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
            // game-domain link networks re-attach here, BEFORE start, with their policy
            // enforced -- a backend must never serve a window without its links.
            GameDomains.attachLinksBeforeStart(resolved, instanceId);
            // Same seam for a Docker site's database links: the release container joins
            // each attached database's link network before it starts, so the health gate
            // probes the candidate WITH its database reachable.
            SiteDatabaseNetworks.attachLinksBeforeStart(resolved, instanceId);
            // And the third owner of the same seam: a stack service joins its stack's
            // shared link network under its compose service name, so a sibling that
            // dials "postgres" resolves it -- and only inside that stack.
            StackInstances.attachLinksBeforeStart(resolved, instanceId);
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
        HohenheimAccess.requireOperationCapability(instanceId, HohenheimAccess.MANAGE);
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
     * the reconciler reports them as orphans until an operator decides.
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
        HohenheimAccess.requireOperationCapability(instanceId, HohenheimAccess.MANAGE);
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
        Models.get(InstanceModel.class).save(row);
        // Schedules must die with their record, and destroy SOFT-deletes (remove hooks
        // never fire here), so the cleanup is explicit -- nothing else will do it.
        Datasource scheduleStore = Db.current() != null ? Db.current() : Datasources.getDefault();
        if (scheduleStore != null) {
            new RecordSchedules(scheduleStore).deleteForRecord(InstanceModel.MODEL_ID, instanceId);
        }
        // Game-domain mappings die with either of their instances, the same explicit-
        // cleanup shape as schedules: generated DNS rows and forced-hosts entries come
        // down rather than dangle at a dead backend.
        GameDomains.deleteForInstance(instanceId);
        Blast.log("INSTANCE: destroyed", resolved.spec().handle(),
            "- container removed, volumes kept, record soft-deleted");
    }

    /** Remove a mid-migration destination copy the daemon attributes to this record. */
    private static void destroyAbandonedMigrationCopy(@NonNull Resolved resolved) {
        Integer targetId = resolved.row().get(InstanceModel.MIGRATE_TARGET_ID);
        if (targetId == null || targetId == resolved.serverId()) {
            return;
        }
        try {
            InstanceRuntime target = resolved.handler()
                .runtimeFor(ServerModel.nameOf(targetId));
            if (target instanceof NativeSnapshotSupport support
                    && support.claimOf(resolved.spec())
                        == NativeSnapshotSupport.WorkloadClaim.OURS) {
                target.destroy(resolved.spec().handle());
            }
        } catch (IOException cleanupFailed) {
            Blast.log("INSTANCE: destroy could not remove the mid-migration copy of",
                resolved.spec().handle(), "on server", targetId, ":",
                cleanupFailed.getMessage());
        }
    }

    /** Typed live status straight off the daemon; never throws. */
    public @NonNull InstanceStatus liveStatus(int instanceId) {
        Resolved resolved = resolve(instanceId);
        return resolved.runtime().status(resolved.spec().handle());
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
                           @NonNull Map<String, String> variables) {}

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
        Map<String, String> variables = instanceVariables.valuesFor(instanceId);
        settings = instanceVariables.applyToSettings(settings, variables);
        InstanceSpec spec = handler.specFor(instanceId, settings);
        if (spec.image().isBlank()) {
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
        if (!hostRuntime.equals(handler.requiredRuntime())) {
            throw Violations.ofForm(violationText("host_runtime_mismatch")
                .withArg("name", serverName)
                .withArg("runtime", hostRuntime)
                .withArg("required", handler.requiredRuntime()));
        }
        return new Resolved(row, handler, runtimeFor(handler, serverName), spec, serverId,
            variables);
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
     * @throws Violations {@code instance_host_unreachable}, naming the host and the reason
     */
    private static @NonNull InstanceRuntime runtimeFor(@NonNull InstanceKindHandler handler,
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
