package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimRefusalReason;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.instance.InstanceOperations.PowerResult;
import be.elevenways.hohenheim.instance.InstanceOperations.SnapshotInput;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.database.InstanceDatabaseLinks;
import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.CmsPlacementSurface;
import be.elevenways.zenit.common.operation.PlacementSurface;
import be.elevenways.zenit.common.operation.ZenitPlacementSurface;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.server.operation.OperationCall;
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.server.task.record.SchedulePlacements;
import be.elevenways.zenit.server.task.record.StepFailure;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * The handlers of the instance operations and their schedule placements, attached once per JVM.
 *
 * AIDEV-NOTE: every handler funnels through the service a surface used before ({@link InstanceService},
 * {@link InstanceBackups}, {@link InstanceSnapshots}), which keeps its own gates, its operation lock and its activity
 * rows; a handler writes no activity row and takes no lock of its own. A restart is {@link InstanceService#restart},
 * ONE lock hold across both halves, from every surface.
 *
 * AIDEV-NOTE: attached in a static initializer, so a JVM that boots twice (test hosts) attaches nothing twice;
 * {@link #init()} only forces the class to load before boot verifies every operation has its handler.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class InstanceOperationHandlers {

    /**
     * The deploy trigger per surface: the trigger decides {@link DeployStartPolicy}, so a surface missing here fails
     * closed rather than guessing. The admin's placed instance actions ({@code InstanceActions}) are a person's
     * click: MANUAL.
     */
    private static final Map<Identifier, DeployTrigger> TRIGGERS = Map.of(
        ZenitPlacementSurface.SCHEDULE_STEP.id(), DeployTrigger.SCHEDULE,
        ZenitPlacementSurface.HTTP_API.id(), DeployTrigger.API,
        CmsPlacementSurface.ADMIN_ACTION.id(), DeployTrigger.MANUAL);

    /** The note a scheduled snapshot carries when its step stores none. */
    static final String SCHEDULED_NOTE = "scheduled";

    static {
        OperationHandlers.attach(InstanceOperations.START).applies(InstanceOperationHandlers::authored)
            .handle(InstanceOperationHandlers::start);
        OperationHandlers.attach(InstanceOperations.STOP).applies(InstanceOperationHandlers::authored)
            .handle(InstanceOperationHandlers::stop);
        OperationHandlers.attach(InstanceOperations.RESTART).applies(InstanceOperationHandlers::authored)
            .handle(InstanceOperationHandlers::restart);
        OperationHandlers.attach(InstanceOperations.BACKUP).applies(InstanceOperationHandlers::authored)
            .handle(InstanceOperationHandlers::backup);
        OperationHandlers.attach(InstanceOperations.SNAPSHOT).applies(InstanceOperationHandlers::authored)
            .handle(InstanceOperationHandlers::snapshot);
        SchedulePlacements.place(InstanceOperations.START);
        SchedulePlacements.place(InstanceOperations.STOP);
        SchedulePlacements.place(InstanceOperations.RESTART);
        SchedulePlacements.place(InstanceOperations.BACKUP).onFailure(InstanceOperationHandlers::alertBackupFailed);
        SchedulePlacements.place(InstanceOperations.SNAPSHOT);
    }

    private InstanceOperationHandlers() {
    }

    /** Loads the class, attaching the handlers and placements; idempotent. */
    public static void init() {
        // The static initializer did the work.
    }

    /**
     * @return the trigger a deploy asked from {@code surface} runs under
     * @throws IllegalStateException for a surface that names no trigger
     */
    public static @NonNull DeployTrigger triggerOf(@NonNull PlacementSurface surface) {
        DeployTrigger trigger = TRIGGERS.get(surface.id());
        if (trigger == null) {
            throw new IllegalStateException("No deploy trigger is declared for the surface " + surface.id()
                + "; it decides whether a stopped workload may start, so it is never guessed");
        }
        return trigger;
    }

    /**
     * Whether an operation applies to this instance at all: a generated instance (a product tier's lowered runtime) is
     * managed only through its owning record's surface, on every surface (the API never lists it either).
     */
    private static boolean authored(@NonNull Row instance) {
        return instance.get(InstanceModel.GENERATED_BY) == null;
    }

    /**
     * @throws DomainRefusal {@link HohenheimRefusalReason#DATABASE_NOT_READY}, retriable, while an attached database
     *                       is not active
     */
    private static @NonNull PowerResult start(@NonNull OperationCall<Row, Void> call) {
        int instanceId = instanceId(call);
        Microcopy notReady = InstanceDatabaseLinks.notReadyReason(instanceId);
        if (notReady != null) {
            throw new DomainRefusal(HohenheimRefusalReason.DATABASE_NOT_READY,
                "instance " + instanceId + " uses a database that is not active", notReady);
        }
        DeployTrigger trigger = triggerOf(call.surface());
        new InstanceService().deploy(instanceId, trigger);
        return new PowerResult(statusOf(instanceId), trigger.word(), false);
    }

    /** A stopped instance's stop runs nothing and answers success: stop is idempotent on every surface. */
    private static @NonNull PowerResult stop(@NonNull OperationCall<Row, Void> call) {
        int instanceId = instanceId(call);
        if (InstanceModel.STATUS_STOPPED.equals(statusOf(instanceId))) {
            return new PowerResult(InstanceModel.STATUS_STOPPED, null, true);
        }
        new InstanceService().stop(instanceId);
        return new PowerResult(statusOf(instanceId), null, false);
    }

    private static @NonNull PowerResult restart(@NonNull OperationCall<Row, Void> call) {
        int instanceId = instanceId(call);
        DeployTrigger trigger = triggerOf(call.surface());
        new InstanceService().restart(instanceId, trigger);
        return new PowerResult(statusOf(instanceId), trigger.word(), false);
    }

    private static @NonNull Integer backup(@NonNull OperationCall<Row, Void> call) {
        return new InstanceBackups().backupNow(instanceId(call));
    }

    private static @NonNull Integer snapshot(@NonNull OperationCall<Row, SnapshotInput> call) {
        SnapshotInput input = call.input();
        String note = input == null || input.note() == null || input.note().isBlank() ? null : input.note();
        if (note == null && call.surface() == ZenitPlacementSurface.SCHEDULE_STEP) {
            note = SCHEDULED_NOTE;
        }
        return new InstanceSnapshots().create(instanceId(call), note);
    }

    /**
     * The scheduled backup's operator alert; the failure stands either way. An outcome nobody knows (a step reaped
     * after a crash) alerts with its own words.
     */
    private static void alertBackupFailed(@NonNull StepFailure failure) {
        String key = failure.subjectKeys().isEmpty() ? null : failure.subjectKeys().get(0);
        Row instance = null;
        try {
            instance = key == null ? null : Models.get(InstanceModel.class).findById(Integer.parseInt(key));
        } catch (NumberFormatException notAnId) {
            // The name below falls back to the key.
        }
        String name = instance != null ? instance.get(InstanceModel.NAME) : "#" + key;
        Microcopy subject = Microcopy.of("instance_backup_failed_subject").withFilter("scope", "alert")
            .withArg("name", name);
        Microcopy body = failure.outcomeUnknown()
            ? Microcopy.of("instance_backup_unknown_body").withFilter("scope", "alert").withArg("name", name)
            : Microcopy.of("instance_backup_failed_body").withFilter("scope", "alert").withArg("name", name)
                .withArg("reason", reasonOf(failure.cause()));
        Alerts.trySend(NotificationEvents.BACKUP_FAILED, subject, body);
    }

    /** @return what failed, in words a person reads: a refusal's shown words, else the failure's message */
    private static @NonNull Object reasonOf(@Nullable RuntimeException cause) {
        if (cause instanceof DomainRefusal refusal) {
            return refusal.shown();
        }
        if (cause == null) {
            return "";
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }

    private static int instanceId(@NonNull OperationCall<Row, ?> call) {
        Integer id = call.subject().get(InstanceModel.ID);
        return id;
    }

    private static @NonNull String statusOf(int instanceId) {
        Row row = Models.get(InstanceModel.class).findById(instanceId);
        return row == null ? "" : String.valueOf((Object) row.get(InstanceModel.STATUS));
    }
}
