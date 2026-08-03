package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.HostLeases;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The settle-then-refuse discipline shared by every instance operation: ONE fenced
 * status write (the guarded updateAll every runtime outcome rides) and ONE protected-
 * status gate. While a capture or restore holds the status, deploy and stop REFUSE
 * (the Pterodactyl {@code restoring_backup} lesson); destroy deliberately does NOT --
 * cleanup must always be possible (the HostAdmission doctrine), and destroying a
 * mid-restore instance is the operator's explicit abandon-ship.
 */
final class InstanceOperationGuard {

    private InstanceOperationGuard() {}

    /**
     * Refuse while a snapshot capture or restore protects this instance.
     *
     * @throws Violations {@code instance_busy}
     */
    static void requireOperable(@NonNull Row row) {
        String status = row.get(InstanceModel.STATUS);
        if (InstanceModel.STATUS_CAPTURING.equals(status)
                || InstanceModel.STATUS_RESTORING.equals(status)) {
            throw Violations.ofForm(Microcopy.of("instance_busy")
                .withFilter("scope", "violations")
                .withArg("name", String.valueOf((Object) row.get(InstanceModel.NAME)))
                .withArg("status", status));
        }
    }

    /**
     * Refuse deploy while the template's install lifecycle is unfinished: starting a
     * workload whose install step never completed runs it on half-written data.
     * {@code none} and {@code installed} pass; stop and destroy stay ungated.
     *
     * @throws Violations {@code install_incomplete}
     */
    static void requireInstalled(@NonNull Row row) {
        String state = row.get(InstanceModel.INSTALL_STATE);
        if (state == null || InstanceModel.INSTALL_NONE.equals(state)
                || InstanceModel.INSTALL_INSTALLED.equals(state)) {
            return;
        }
        throw Violations.ofForm(Microcopy.of("install_incomplete")
            .withFilter("scope", "violations")
            .withArg("name", String.valueOf((Object) row.get(InstanceModel.NAME)))
            .withArg("state", state));
    }

    /**
     * THE fenced install-state write: same guard as {@link #stamp} ({@code deleted_at
     * IS NULL AND claim_fence <= :myFence}), assigning the install lifecycle columns
     * while leaving the runtime status untouched. Zero rows is the same hard
     * fenced-out failure.
     *
     * @throws Violations {@code instance_fenced_out}
     */
    static void stampInstall(@NonNull HostLeases leases, int instanceId, int serverId, long fence,
                             @NonNull String installState, @Nullable String installError,
                             @NonNull Object instanceName) {
        int matched = Models.get(InstanceModel.class).find()
            .where(InstanceModel.ID.eq(instanceId))
            .where(InstanceModel.DELETED_AT.isNull())
            .where(Criteria.or(
                InstanceModel.CLAIM_FENCE.isNull(),
                InstanceModel.CLAIM_FENCE.lte(fence)))
            .assign(InstanceModel.INSTALL_STATE, installState)
            .assign(InstanceModel.INSTALL_ERROR, installError)
            .assign(InstanceModel.CLAIM_FENCE, fence)
            .updateAll();
        if (matched == 0) {
            leases.fencedOut(serverId);
            throw Violations.ofForm(Microcopy.of("instance_fenced_out")
                .withFilter("scope", "violations")
                .withArg("name", String.valueOf(instanceName))
                .withArg("server", ServerModel.nameOf(serverId)));
        }
    }

    /**
     * THE fenced outcome write: one guarded statement that both records the status and
     * stamps the fence -- {@code WHERE id = ? AND deleted_at IS NULL AND (claim_fence
     * IS NULL OR claim_fence <= :myFence)}. Zero matched rows is a HARD FAILURE, never
     * a shrug: a rival controller with a higher fence owns this record now, so this
     * controller drops its hold and aborts. Cleanup is the winner's job.
     *
     * @throws Violations {@code instance_fenced_out}
     */
    static void stamp(@NonNull HostLeases leases, int instanceId, int serverId, long fence,
                      @NonNull String status, @NonNull Object instanceName) {
        int matched = Models.get(InstanceModel.class).find()
            .where(InstanceModel.ID.eq(instanceId))
            .where(InstanceModel.DELETED_AT.isNull())
            .where(Criteria.or(
                InstanceModel.CLAIM_FENCE.isNull(),
                InstanceModel.CLAIM_FENCE.lte(fence)))
            .assign(InstanceModel.STATUS, status)
            .assign(InstanceModel.CLAIM_FENCE, fence)
            .updateAll();
        if (matched == 0) {
            leases.fencedOut(serverId);
            throw Violations.ofForm(Microcopy.of("instance_fenced_out")
                .withFilter("scope", "violations")
                .withArg("name", String.valueOf(instanceName))
                .withArg("server", ServerModel.nameOf(serverId)));
        }
    }
}
