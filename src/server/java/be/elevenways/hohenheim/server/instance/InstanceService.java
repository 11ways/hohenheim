package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceFileModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.host.HostLeases;
import be.elevenways.hohenheim.server.runtime.FileStagingSupport;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
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
        Resolved resolved = resolve(instanceId);
        // Settle-then-refuse: a start under a live capture/restore corrupts the very
        // data those operations exist to protect; a start before the template's install
        // step completed runs the workload on half-written data.
        InstanceOperationGuard.requireOperable(resolved.row());
        InstanceOperationGuard.requireInstalled(resolved.row());
        long fence = this.leases.requireFence(resolved.serverId());
        HostAdmission.requireInstancePlacement(resolved.serverId());
        InstanceConsoles.Watch watch = null;
        try {
            // A previous run's console session (if any) watches a container this
            // deploy is about to replace; end it before the daemon work starts.
            InstanceConsoles.closeSession(instanceId);
            String handle = resolved.runtime().create(resolved.spec());
            stageConfigFiles(resolved, instanceId);
            // The console attaches BETWEEN create and start (docker run's own order),
            // so a readiness line printed in the first instant cannot be missed.
            watch = InstanceConsoles.prepare(resolved, instanceId, this.leases);
            resolved.runtime().start(handle);
            InstanceStatus status = resolved.runtime().status(handle);
            this.beforeOutcomeWrite.run();
            // The fence gate comes BEFORE any ledger write: a stale controller that
            // reached the ledger first would delete the winner's fresh port claim.
            // With a readiness matcher the stamp is STARTING; the matcher's own
            // fenced write flips it to RUNNING when the line is observed.
            stampGuarded(resolved, fence, watch != null
                ? watch.initialStatus() : InstanceModel.STATUS_RUNNING);
            if (status.publishedPort() != null) {
                PortLedger.recordObserved(resolved.serverId(), BIND_ADDRESS,
                    status.publishedPort(), "tcp", InstanceModel.MODEL_ID, instanceId, null);
            }
            if (watch != null) {
                InstanceConsoles.arm(watch, instanceId);
            }
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
     * free (a stopped container binds nothing at the kernel), so the claims are released
     * verified; a restart publishes -- and records -- a fresh ephemeral port.
     *
     * @throws Violations naming the failure; the claims are parked, the status untouched
     */
    public void stop(int instanceId) {
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
        Resolved resolved = resolve(instanceId);
        long fence = this.leases.requireFence(resolved.serverId());
        try {
            // Destroy is an intended end: never a crash, and no session survives it.
            InstanceConsoles.markStopExpected(instanceId);
            InstanceConsoles.closeSession(instanceId);
            resolved.runtime().destroy(resolved.spec().handle());
        } catch (IOException e) {
            stampGuarded(resolved, fence, InstanceModel.STATUS_ERROR);
            PortLedger.releaseOwner(InstanceModel.MODEL_ID, instanceId);
            throw refusal("instance_destroy_failed", resolved.row(), e);
        }
        this.beforeOutcomeWrite.run();
        stampGuarded(resolved, fence, InstanceModel.STATUS_STOPPED);
        PortLedger.releaseOwnerObserved(InstanceModel.MODEL_ID, instanceId);
        Row row = Models.get(InstanceModel.class).findById(instanceId);
        if (row == null) {
            return;
        }
        row.set(InstanceModel.DELETED_AT, Instant.now());
        Models.get(InstanceModel.class).save(row);
        Blast.log("INSTANCE: destroyed", resolved.spec().handle(),
            "- container removed, volumes kept, record soft-deleted");
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
        staging.stageFiles(resolved.spec().handle(), staged);
    }

    // -- resolution -----------------------------------------------------------

    record Resolved(@NonNull Row row, @NonNull InstanceRuntime runtime,
                    @NonNull InstanceSpec spec, int serverId,
                    @NonNull Map<String, String> variables) {}

    /**
     * Load the record and resolve its kind handler, host, variables and spec (variables
     * merge into the env and substitute inside the command -- see InstanceVariables).
     *
     * @throws Violations for a missing/trashed record, an unknown kind or a blank image
     */
    Resolved resolve(int instanceId) {
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
        if (spec.image().isEmpty()) {
            throw Violations.ofField("settings.image", "", violationText("instance_image_required"));
        }
        // THE canonical host key -- null folds to the local daemon, any other spelling
        // was already normalized onto servers.id by the model's beforeValidate hook.
        int serverId = ServerModel.canonicalServerId(row.get(InstanceModel.SERVER_ID));
        String serverName = ServerModel.nameOf(serverId);
        return new Resolved(row, handler.runtimeFor(serverName), spec, serverId, variables);
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
