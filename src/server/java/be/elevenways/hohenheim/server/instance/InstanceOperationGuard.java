package be.elevenways.hohenheim.server.instance;

import be.elevenways.zenit.common.text.Texts;
import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.database.InstanceDatabaseLinks;
import be.elevenways.hohenheim.server.host.HostLeases;
import be.elevenways.hohenheim.server.security.IsolationUnenforceable;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.Set;


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
     * AIDEV-NOTE: read-then-act, NOT mutual exclusion -- the row was loaded at resolve()
     * and the CAPTURING stamp lands later (after a network health check on the backup
     * lane), so two operations that START close together can both pass this gate. That is
     * accepted, by decision: the gate exists to refuse OBVIOUSLY rival operator actions,
     * data-integrity does not ride on it (backup/snapshot payload names are row-id-unique
     * and every status write is fenced), and making entry atomic is a
     * compare-and-swap-entry redesign shared by all three lanes, not a patch here.
     *
     * @throws Violations {@code instance_busy}
     */
    static void requireOperable(@NonNull Row row) {
        String status = row.get(InstanceModel.STATUS);
        if (!InstanceModel.isOperable(row)) {
            throw Violations.ofForm(HohenheimViolations.instanceRefusalText("instance_busy", row, null)
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
        throw Violations.ofForm(HohenheimViolations.instanceRefusalText("install_incomplete", row, null)
            .withArg("state", state));
    }

    /**
     * Refuse deploy while an attached managed database is not {@code active}: injection
     * fail-softs, so the workload would boot with its credential family silently missing
     * and look healthy. Stop and destroy stay ungated.
     *
     * AIDEV-NOTE: the reason comes from {@code InstanceDatabaseLinks.notReadyReason},
     * which is also what the Deploy row action renders itself dead with -- one resolver,
     * so the dead button is never the gate and the POST refuses with the same sentence.
     *
     * @throws Violations {@code database_not_ready}
     */
    static void requireDatabasesReady(int instanceId) {
        Microcopy reason = InstanceDatabaseLinks.notReadyReason(instanceId);
        if (reason != null) {
            throw Violations.ofForm(reason);
        }
    }

    /**
     * THE fenced install-state write: same guard as {@link #stamp} (the record the
     * operation's claim still owns, live, on this host), assigning the install lifecycle columns
     * while leaving the runtime status untouched. Zero rows is the same hard
     * fenced-out failure.
     *
     * @throws Violations {@code instance_fenced_out}
     */
    static void stampInstall(@NonNull HostLeases leases, int instanceId, int serverId,
                             @NonNull String installState, @Nullable String installError,
                             @NonNull Object instanceName) {
        int matched = fenced(leases, instanceId, serverId)
            .assign(InstanceModel.INSTALL_STATE, installState)
            .assign(InstanceModel.INSTALL_ERROR, installError)
            .updateAll();
        requireMatched(matched, serverId, instanceName);
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
    static void stampRole(@NonNull HostLeases leases, int instanceId, int serverId,
                          @NonNull String role, @NonNull Object instanceName) {
        int matched = fenced(leases, instanceId, serverId)
            .assign(InstanceModel.RUNTIME_ROLE, role)
            .updateAll();
        requireMatched(matched, serverId, instanceName);
    }

    /**
     * The fenced image-identity write ({@link #stamp}'s guard, assigning only the
     * pinned fingerprint). Zero rows is the same hard fenced-out failure.
     *
     * @throws Violations {@code instance_fenced_out}
     */
    static void stampFingerprint(@NonNull HostLeases leases, int instanceId, int serverId,
                                 @NonNull String fingerprint,
                                 @NonNull Object instanceName) {
        int matched = fenced(leases, instanceId, serverId)
            .assign(InstanceModel.IMAGE_FINGERPRINT, fingerprint)
            .updateAll();
        requireMatched(matched, serverId, instanceName);
    }

    /**
     * THE fenced outcome write: one guarded statement that records the status on the
     * record the operation's claim still owns -- {@code WHERE id = ? AND deleted_at IS NULL
     * AND claim_fence = :claimFence} plus the host scope (InstanceOperationLock.owned). Zero
     * matched rows is a HARD FAILURE, never a shrug: a rival operation took the record over
     * with a later fence, so this one aborts. Cleanup is the winner's job.
     *
     * AIDEV-NOTE: the {@code deleted_at IS NULL} half of every guard in this class is
     * InstanceModel.SOFT_DELETE's: an updateAll is scoped by the find hooks, so a trashed
     * record matches none of these statements without any of them spelling the filter.
     *
     * @throws Violations {@code instance_fenced_out}
     * @throws IllegalArgumentException for {@code error}, which names its cause through {@link #stampError}
     */
    static void stamp(@NonNull HostLeases leases, int instanceId, int serverId,
                      @NonNull String status, @NonNull Object instanceName) {
        requireCause(status, null);
        write(leases, instanceId, serverId, status, instanceName);
    }

    /**
     * {@link #stamp} of {@code error}, recording what caused it on the record's activity, where the dashboard's
     * crash item reads its words ({@code InstanceErrorCauses}).
     *
     * AIDEV-NOTE: the ONE way a workload reaches ERROR with a cause, and {@link #stamp} refuses ERROR without one:
     * the status alone told a failed start, a failed restore and a crash apart for nobody, so the dashboard claimed a
     * crash for every one of them (D10b).
     *
     * @param cause  what happened, a verb whose {@link HohenheimActivityAction#errorCause()} is a cause
     * @param detail the row's detail as the cause's fact declares it (an exit code, a failure's message), or null
     * @throws Violations {@code instance_fenced_out}
     */
    static void stampError(@NonNull HostLeases leases, int instanceId, int serverId, @NonNull Object instanceName,
                           @NonNull HohenheimActivityAction cause, @Nullable String detail) {
        requireCause(InstanceModel.STATUS_ERROR, cause);
        write(leases, instanceId, serverId, InstanceModel.STATUS_ERROR, instanceName);
        recordCause(instanceId, cause, detail);
    }

    /**
     * A crash restart the start gates refused leaves the record ERROR with that refusal as its cause, instead of a log
     * line nobody reads: the workload stays down, and its app read "Not running" (or still "Running") with no reason.
     * Only while the record still holds a status the restart was attempted from; a start that got further stamped its
     * own outcome. The observed port claims go as on every crash settle: the daemon said the workload is gone.
     *
     * AIDEV-NOTE: called under the record's claim (the console watch's exit policy, the reconciler's queued restart).
     *
     * @param from the statuses the restart was attempted from
     * @return whether the refusal was recorded
     */
    static boolean stampRestartRefused(@NonNull HostLeases leases, int instanceId, int serverId,
                                       @NonNull Object instanceName, @NonNull Set<String> from,
                                       @NonNull RuntimeException refused) {
        Row row = Models.get(InstanceModel.class).findById(instanceId);
        if (row == null || !from.contains(row.get(InstanceModel.STATUS))) {
            return false;
        }
        leases.requireFence(serverId);
        stampError(leases, instanceId, serverId, instanceName, HohenheimActivityAction.WORKLOAD_RESTART_REFUSED,
            refused.getMessage());
        PortLedger.releaseOwnerObserved(InstanceModel.MODEL_ID, instanceId);
        return true;
    }

    /**
     * @return the cause verb a failed start records: its host's switched-off per-workload firewall rules when that is
     *         what refused it (the dashboard then folds the workload under its host), else the failure's own message
     */
    static @NonNull HohenheimActivityAction startFailureOf(@NonNull Throwable failure) {
        return IsolationUnenforceable.in(failure) ? HohenheimActivityAction.WORKLOAD_ISOLATION_REFUSED
            : HohenheimActivityAction.WORKLOAD_START_FAILED;
    }

    /** The fenced status write behind {@link #stamp} and {@link #stampError}. */
    private static void write(@NonNull HostLeases leases, int instanceId, int serverId,
                              @NonNull String status, @NonNull Object instanceName) {
        // AIDEV-NOTE: UPDATED_AT is assigned HERE because this is a set-based updateAll
        // that fires no write hooks -- without it a CAPTURING/RESTORING stamp leaves the
        // row's timestamp at whatever save() last wrote, and the boot settle
        // (InstanceService.recoverInterrupted) could not tell a status this process just
        // stamped from one a dead controller left behind. The process-start fence rides
        // this column.
        // AIDEV-NOTE: an operation also CLEARS the observed kill (see
        // InstanceModel.WORKLOAD_KILLED_AT): a deploy, stop or restart replaces the container
        // that carried it, and a kill that survives the operation is re-observed next sweep.
        int matched = fenced(leases, instanceId, serverId)
            .assign(InstanceModel.STATUS, status)
            .assign(InstanceModel.UPDATED_AT, Now.instant())
            .assign(InstanceModel.WORKLOAD_KILLED_AT, null)
            .updateAll();
        requireMatched(matched, serverId, instanceName);
    }

    /**
     * THE reconciler's write: {@link #stamp}'s guard, assigning the status the DAEMON was
     * observed in together with the moment it answered.
     *
     * AIDEV-NOTE: separate from {@link #stamp} because the two write different claims.
     * {@code stamp} records what an OPERATION did (including {@code error}, which no
     * daemon ever reported), so folding {@code status_observed_at} into it would stamp
     * "confirmed against the host" onto statuses nothing confirmed. It also deliberately
     * leaves {@code updated_at} ALONE when the status is unchanged, so a confirmation
     * cannot look like a state transition to the boot settle's process-start clock.
     *
     * @param changed          whether the status actually moved (an unchanged one still
     *                         records the confirmation, and only that)
     * @param workloadKilledAt the {@code workload_killed_at} value this observation settles
     *                         to (the caller passes the stored value when it learned nothing)
     * @param cause            what made a changed status {@code error} (a crash nobody watched), recorded as
     *                         {@link #stampError} records it; null for any other status
     * @throws Violations {@code instance_fenced_out}
     */
    static void stampObserved(@NonNull HostLeases leases, int instanceId, int serverId,
                              @NonNull String status, boolean changed,
                              @Nullable Instant workloadKilledAt, @NonNull Object instanceName,
                              @Nullable HohenheimActivityAction cause) {
        requireCause(changed ? status : "", cause);
        var statement = fenced(leases, instanceId, serverId)
            .assign(InstanceModel.STATUS, status)
            .assign(InstanceModel.STATUS_OBSERVED_AT, Now.instant())
            .assign(InstanceModel.WORKLOAD_KILLED_AT, workloadKilledAt);
        if (changed) {
            statement = statement.assign(InstanceModel.UPDATED_AT, Now.instant());
        }
        requireMatched(statement.updateAll(), serverId, instanceName);
        if (cause != null) {
            recordCause(instanceId, cause, null);
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
                               int targetServerId, long reservedMb,
                               @NonNull Object instanceName) {
        int matched = fenced(leases, instanceId, serverId)
            .assign(InstanceModel.STATUS, InstanceModel.STATUS_MIGRATING)
            .assign(InstanceModel.MIGRATE_TARGET_ID, targetServerId)
            .assign(InstanceModel.MIGRATE_RESERVED_MB, (int) reservedMb)
            .updateAll();
        requireMatched(matched, serverId, instanceName);
    }

    /**
     * Close a migration window WITHOUT moving the record: clears the destination
     * pointer and stamps {@code status} through the operation's claim (the rollback
     * half of a settle).
     *
     * The DESTINATION's capacity booking (taken when the window opened) is handed back
     * here, because the record is staying where it is -- see
     * {@link InstanceCapacity#openMigrationWindow} for why the release rides the settle
     * rather than the failure.
     *
     * @param reservedTargetServerId the host the window booked, or null when none was
     * @param cause                  what made the status {@code error}, recorded as {@link #stampError} records
     *                               it; null for any other status
     * @throws Violations {@code instance_fenced_out}
     */
    static void clearMigration(@NonNull HostLeases leases, int instanceId, int serverId,
                               @Nullable Integer reservedTargetServerId,
                               @NonNull String status,
                               @NonNull Object instanceName,
                               @Nullable HohenheimActivityAction cause) {
        requireCause(status, cause);
        // The STORED window amount, never a recompute: releasing anything else against
        // the destination is the over-release that clamps its bucket to zero.
        long booked = reservedTargetServerId == null ? 0 : InstanceCapacity.windowReservedOf(
            Models.get(InstanceModel.class).findById(instanceId));
        int matched = fenced(leases, instanceId, serverId)
            .assign(InstanceModel.STATUS, status)
            .assign(InstanceModel.MIGRATE_TARGET_ID, (Object) null)
            .assign(InstanceModel.MIGRATE_RESERVED_MB, (Object) null)
            .updateAll();
        requireMatched(matched, serverId, instanceName);
        if (reservedTargetServerId != null) {
            InstanceCapacity.release(reservedTargetServerId, booked);
        }
        if (cause != null) {
            recordCause(instanceId, cause, null);
        }
    }

    /**
     * THE ownership handoff of a cold migration: one guarded statement that repoints
     * the record at the destination host and closes the migration window. Guarded on the
     * operation's claim and the SOURCE host, so a stale holder cannot hand off a record a
     * rival already owns; the claim, and so the fence, is the record's and moves with it.
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
                        int targetServerId,
                        @NonNull String status, @NonNull Object instanceName) {
        requireCause(status, null);
        Row stored = Models.get(InstanceModel.class).findById(instanceId);
        long booked = InstanceCapacity.sourceBookedOf(stored);
        long reserved = InstanceCapacity.windowReservedOf(stored);
        int matched = fenced(leases, instanceId, sourceServerId)
            .assign(InstanceModel.SERVER_ID, targetServerId)
            .assign(InstanceModel.MIGRATE_TARGET_ID, (Object) null)
            .assign(InstanceModel.MIGRATE_RESERVED_MB, (Object) null)
            .assign(InstanceModel.CAPACITY_MB, (int) reserved)
            .assign(InstanceModel.STATUS, status)
            .updateAll();
        requireMatched(matched, sourceServerId, instanceName);
        InstanceCapacity.release(sourceServerId, booked);
    }

    /**
     * Refuses an {@code error} status without a cause, and a cause beside any other status: every ERROR a workload
     * holds says what caused it.
     *
     * @throws IllegalArgumentException for either mismatch
     */
    private static void requireCause(@NonNull String status, @Nullable HohenheimActivityAction cause) {
        boolean error = InstanceModel.STATUS_ERROR.equals(status);
        if (error && (cause == null || !cause.errorCause().isCause())) {
            throw new IllegalArgumentException("An error status names its cause (InstanceOperationGuard.stampError)");
        }
        if (!error && cause != null) {
            throw new IllegalArgumentException("Only an error status records a cause, not " + status);
        }
    }

    /** Records the cause on the record's activity, its detail the first line of what the cause declares. */
    private static void recordCause(int instanceId, @NonNull HohenheimActivityAction cause, @Nullable String detail) {
        // The row carries the detail its cause's fact declares, and nothing where it declares none.
        String kept = switch (cause.errorCause()) {
            case EXIT_CODE, MESSAGE -> detail;
            case NONE, PLAIN, HOST_ISOLATION -> null;
        };
        String line = kept == null ? null : kept.strip().lines().findFirst().orElse(null);
        ActivityLog.record(Models.get(InstanceModel.class), instanceId, cause,
            Texts.blankAsNull(line));
    }

    /**
     * THE fenced write every stamp makes: this record, through the claim this thread's operation holds on it, still on
     * this host.
     */
    private static @NonNull QueryBuilder<Row> fenced(@NonNull HostLeases leases, int instanceId, int serverId) {
        return InstanceOperationLock.of(leases).owned(instanceId).where(hostScope(serverId));
    }

    /**
     * A fenced write that matched no row lost the record: a rival operation took it over (this holder stalled past
     * its claim's TTL) or it moved off this host. That is the hard fenced-out failure; cleanup is the winner's job.
     *
     * @throws Violations {@code instance_fenced_out}
     */
    private static void requireMatched(int matched, int serverId, @NonNull Object instanceName) {
        if (matched == 0) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("instance_fenced_out")
                .withArg("name", String.valueOf(instanceName))
                .withArg("server", ServerModel.nameOf(serverId)));
        }
    }

    /**
     * The record-must-still-be-on-this-host half of every guard. NULL {@code server_id} is a legal spelling of the
     * local daemon, so the local host matches both spellings; any other host matches only its own id.
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
