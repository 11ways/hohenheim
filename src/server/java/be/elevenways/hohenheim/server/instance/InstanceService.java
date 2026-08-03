package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortLedger;
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
import java.util.Map;

/**
 * Orchestrates instance records through the driver seam: deploy (create + start +
 * record-after port claim), stop, verified destroy + soft delete, and live status.
 * Refusals are thrown as {@link Violations} with named reasons so the admin surface
 * shows the operator what actually happened -- never a bare 500, never a silent
 * success.
 */
public final class InstanceService {

    /** Publications bind loopback (DockerInstanceRuntime.HOST_BIND_ADDRESS's ledger spelling). */
    private static final String BIND_ADDRESS = "127.0.0.1";

    /**
     * Create (replacing only an own leftover container) and start the instance's
     * workload, then record the observed published port in the ledger (record-after:
     * the OwnerLabels landed at create, so a crash inside the window stays attributable).
     *
     * @throws Violations naming the failure; the record is stamped {@code error}
     */
    public @NonNull InstanceStatus deploy(int instanceId) {
        Resolved resolved = resolve(instanceId);
        try {
            String handle = resolved.runtime().create(resolved.spec());
            resolved.runtime().start(handle);
            InstanceStatus status = resolved.runtime().status(handle);
            if (status.publishedPort() != null) {
                PortLedger.recordObserved(resolved.serverId(), BIND_ADDRESS,
                    status.publishedPort(), "tcp", InstanceModel.MODEL_ID, instanceId, null);
            }
            stamp(resolved.row(), InstanceModel.STATUS_RUNNING);
            return status;
        } catch (IOException e) {
            // Whatever the previous deploy held is now unverifiable: park, never delete.
            PortLedger.releaseOwner(InstanceModel.MODEL_ID, instanceId);
            stamp(resolved.row(), InstanceModel.STATUS_ERROR);
            throw refusal("instance_deploy_failed", resolved.row(), e);
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
        try {
            resolved.runtime().stop(resolved.spec().handle(), 10);
            PortLedger.releaseOwnerObserved(InstanceModel.MODEL_ID, instanceId);
            stamp(resolved.row(), InstanceModel.STATUS_STOPPED);
        } catch (IOException e) {
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
     * @throws Violations when the daemon cannot confirm removal: the record is KEPT
     *         (status {@code error}), the claims are parked, and the operator retries
     */
    public void destroy(int instanceId) {
        Resolved resolved = resolve(instanceId);
        try {
            resolved.runtime().destroy(resolved.spec().handle());
        } catch (IOException e) {
            PortLedger.releaseOwner(InstanceModel.MODEL_ID, instanceId);
            stamp(resolved.row(), InstanceModel.STATUS_ERROR);
            throw refusal("instance_destroy_failed", resolved.row(), e);
        }
        PortLedger.releaseOwnerObserved(InstanceModel.MODEL_ID, instanceId);
        Row row = resolved.row();
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
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

    // -- resolution -----------------------------------------------------------

    record Resolved(@NonNull Row row, @NonNull InstanceRuntime runtime,
                    @NonNull InstanceSpec spec, int serverId) {}

    /**
     * Load the record and resolve its kind handler, host and spec.
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
        InstanceSpec spec = handler.specFor(instanceId, settings);
        if (spec.image().isEmpty()) {
            throw Violations.ofField("settings.image", "", violationText("instance_image_required"));
        }
        // THE canonical host key -- null folds to the local daemon, any other spelling
        // was already normalized onto servers.id by the model's beforeValidate hook.
        int serverId = ServerModel.canonicalServerId(row.get(InstanceModel.SERVER_ID));
        String serverName = ServerModel.nameOf(serverId);
        return new Resolved(row, handler.runtimeFor(serverName), spec, serverId);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castSettings(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static void stamp(Row row, String status) {
        row.set(InstanceModel.STATUS, status);
        Models.get(InstanceModel.class).save(row);
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
