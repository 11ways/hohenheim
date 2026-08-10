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
     * Refuse while a snapshot capture, restore or migration protects this instance.
     *
     * @throws Violations {@code instance_busy}
     */
    static void requireOperable(@NonNull Row row) {
        String status = row.get(InstanceModel.STATUS);
        if (InstanceModel.STATUS_CAPTURING.equals(status)
                || InstanceModel.STATUS_RESTORING.equals(status)
                || InstanceModel.STATUS_MIGRATING.equals(status)) {
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
            .where(hostScope(serverId))
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
     * THE fenced runtime-role write ({@link #stamp}'s guard, assigning only the release
     * role). Zero rows is the same hard fenced-out failure.
     *
     * AIDEV-NOTE: this exists because the release engine used to flip a role with
     * {@code Models.save(row)}, and {@code Models.save} writes EVERY column present on
     * the Row -- a row loaded from the database carries all of them, so the flip
     * rewrote status, claim_fence and image_fingerprint back to whatever they were at
     * load time. It silently undid the volume-name heal (the rollback target kept
     * pointing at the old volume) and, worse, could rewind claim_fence, which is the
     * one column the whole two-controller discipline depends on. A role transition is
     * ONE column; write ONE column.
     *
     * @throws Violations {@code instance_fenced_out}
     */
    static void stampRole(@NonNull HostLeases leases, int instanceId, int serverId, long fence,
                          @NonNull String role, @NonNull Object instanceName) {
        int matched = Models.get(InstanceModel.class).find()
            .where(InstanceModel.ID.eq(instanceId))
            .where(InstanceModel.DELETED_AT.isNull())
            .where(hostScope(serverId))
            .where(Criteria.or(
                InstanceModel.CLAIM_FENCE.isNull(),
                InstanceModel.CLAIM_FENCE.lte(fence)))
            .assign(InstanceModel.RUNTIME_ROLE, role)
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
     * The fenced image-identity write ({@link #stamp}'s guard, assigning only the
     * pinned fingerprint). Zero rows is the same hard fenced-out failure.
     *
     * @throws Violations {@code instance_fenced_out}
     */
    static void stampFingerprint(@NonNull HostLeases leases, int instanceId, int serverId,
                                 long fence, @NonNull String fingerprint,
                                 @NonNull Object instanceName) {
        int matched = Models.get(InstanceModel.class).find()
            .where(InstanceModel.ID.eq(instanceId))
            .where(InstanceModel.DELETED_AT.isNull())
            .where(hostScope(serverId))
            .where(Criteria.or(
                InstanceModel.CLAIM_FENCE.isNull(),
                InstanceModel.CLAIM_FENCE.lte(fence)))
            .assign(InstanceModel.IMAGE_FINGERPRINT, fingerprint)
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
            .where(hostScope(serverId))
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

    /**
     * Open a migration window: the same fenced guard as {@link #stamp}, additionally
     * recording the destination host AND the amount the window reserved on it -- the
     * settle releases that stored amount verbatim, never a recompute (see
     * {@code InstanceModel.MIGRATE_RESERVED_MB}). From here until the handoff (or a
     * settle), the record's own host remains the data authority.
     *
     * @param reservedMb what {@code InstanceCapacity.openMigrationWindow} booked
     * @throws Violations {@code instance_fenced_out}
     */
    static void stampMigrating(@NonNull HostLeases leases, int instanceId, int serverId,
                               long fence, int targetServerId, long reservedMb,
                               @NonNull Object instanceName) {
        int matched = Models.get(InstanceModel.class).find()
            .where(InstanceModel.ID.eq(instanceId))
            .where(InstanceModel.DELETED_AT.isNull())
            .where(hostScope(serverId))
            .where(Criteria.or(
                InstanceModel.CLAIM_FENCE.isNull(),
                InstanceModel.CLAIM_FENCE.lte(fence)))
            .assign(InstanceModel.STATUS, InstanceModel.STATUS_MIGRATING)
            .assign(InstanceModel.MIGRATE_TARGET_ID, targetServerId)
            .assign(InstanceModel.MIGRATE_RESERVED_MB, (int) reservedMb)
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
     * Close a migration window WITHOUT moving the record: clears the destination
     * pointer and stamps {@code status} under the source host's fence (the rollback
     * half of a settle).
     *
     * The DESTINATION's capacity booking (taken when the window opened) is handed back
     * here, because the record is staying where it is -- see
     * {@link InstanceCapacity#openMigrationWindow} for why the release rides the settle
     * rather than the failure.
     *
     * @param reservedTargetServerId the host the window booked, or null when none was
     * @throws Violations {@code instance_fenced_out}
     */
    static void clearMigration(@NonNull HostLeases leases, int instanceId, int serverId,
                               long fence, @Nullable Integer reservedTargetServerId,
                               @NonNull String status,
                               @NonNull Object instanceName) {
        // The STORED window amount, never a recompute: releasing anything else against
        // the destination is the over-release that clamps its bucket to zero.
        long booked = reservedTargetServerId == null ? 0 : InstanceCapacity.windowReservedOf(
            Models.get(InstanceModel.class).findById(instanceId));
        int matched = Models.get(InstanceModel.class).find()
            .where(InstanceModel.ID.eq(instanceId))
            .where(InstanceModel.DELETED_AT.isNull())
            .where(hostScope(serverId))
            .where(Criteria.or(
                InstanceModel.CLAIM_FENCE.isNull(),
                InstanceModel.CLAIM_FENCE.lte(fence)))
            .assign(InstanceModel.STATUS, status)
            .assign(InstanceModel.MIGRATE_TARGET_ID, (Object) null)
            .assign(InstanceModel.MIGRATE_RESERVED_MB, (Object) null)
            .assign(InstanceModel.CLAIM_FENCE, fence)
            .updateAll();
        if (matched == 0) {
            leases.fencedOut(serverId);
            throw Violations.ofForm(Microcopy.of("instance_fenced_out")
                .withFilter("scope", "violations")
                .withArg("name", String.valueOf(instanceName))
                .withArg("server", ServerModel.nameOf(serverId)));
        }
        if (reservedTargetServerId != null) {
            InstanceCapacity.release(reservedTargetServerId, booked);
        }
    }

    /**
     * THE ownership handoff of a cold migration: one guarded statement that repoints
     * the record at the destination host, closes the migration window and re-bases
     * the fence into the destination's lease domain. Guarded on the SOURCE domain
     * (host + fence) so a stale source controller cannot hand off a record a rival
     * already owns; from the moment it matches, every further write must come from
     * the destination host's lease.
     *
     * The SOURCE's capacity booking is handed back once the statement has MATCHED, never
     * before: a handoff that loses the fence changed nothing, so it must move no charge
     * either -- the rival that owns the record answers for its ledger. The destination was
     * already booked when the window opened ({@link InstanceCapacity#openMigrationWindow}),
     * which is where the ordering argument lives. What comes back is what the source
     * actually holds -- the row's stamp, 0 for a hostless row -- and the statement
     * re-stamps CAPACITY_MB to the WINDOW's reserved amount, because from the match on
     * that is what the destination bucket carries for this row; a later release of any
     * other number is the bucket-zeroing over-release.
     *
     * AIDEV-NOTE: this is a set-based updateAll and fires NO write hooks, so
     * InstanceCapacity's rebook hook never sees a migration (and now REFUSES the
     * footprint edits that used to slip through it mid-window). Anything else this
     * statement should keep in step (an owner-side ledger, a derived counter) must
     * likewise be spelled out HERE -- reaching for save() to get hooks would trade away
     * the fence, which is the only thing stopping a stale controller from handing off a
     * record a rival already owns.
     *
     * @throws Violations {@code instance_fenced_out}
     */
    static void handoff(@NonNull HostLeases leases, int instanceId, int sourceServerId,
                        long sourceFence, int targetServerId, long targetFence,
                        @NonNull String status, @NonNull Object instanceName) {
        Row stored = Models.get(InstanceModel.class).findById(instanceId);
        long booked = InstanceCapacity.sourceBookedOf(stored);
        long reserved = InstanceCapacity.windowReservedOf(stored);
        int matched = Models.get(InstanceModel.class).find()
            .where(InstanceModel.ID.eq(instanceId))
            .where(InstanceModel.DELETED_AT.isNull())
            .where(hostScope(sourceServerId))
            .where(Criteria.or(
                InstanceModel.CLAIM_FENCE.isNull(),
                InstanceModel.CLAIM_FENCE.lte(sourceFence)))
            .assign(InstanceModel.SERVER_ID, targetServerId)
            .assign(InstanceModel.MIGRATE_TARGET_ID, (Object) null)
            .assign(InstanceModel.MIGRATE_RESERVED_MB, (Object) null)
            .assign(InstanceModel.CAPACITY_MB, (int) reserved)
            .assign(InstanceModel.STATUS, status)
            .assign(InstanceModel.CLAIM_FENCE, targetFence)
            .updateAll();
        if (matched == 0) {
            leases.fencedOut(sourceServerId);
            throw Violations.ofForm(Microcopy.of("instance_fenced_out")
                .withFilter("scope", "violations")
                .withArg("name", String.valueOf(instanceName))
                .withArg("server", ServerModel.nameOf(sourceServerId)));
        }
        InstanceCapacity.release(sourceServerId, booked);
    }

    /**
     * The record-must-still-be-on-this-host half of every guard. NULL {@code
     * server_id} is a legal spelling of the local daemon, so the local host matches
     * both spellings; any other host matches only its own id. This is what makes a
     * post-handoff write from the OLD host's lease domain match zero rows even
     * though fences from different domains are numerically incomparable.
     */
    private static @NonNull Criteria hostScope(int serverId) {
        if (serverId == ServerModel.localServerId()) {
            return Criteria.or(
                InstanceModel.SERVER_ID.isNull(),
                InstanceModel.SERVER_ID.eq(serverId));
        }
        return InstanceModel.SERVER_ID.eq(serverId);
    }
}
