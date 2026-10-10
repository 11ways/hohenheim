package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.instance.InstanceScheduleOperations;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.activity.ZenitActivityAction;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.common.task.record.RecordScheduleRunModel;
import be.elevenways.zenit.common.task.record.RecordScheduleStepModel;
import be.elevenways.zenit.server.operation.OperationCall;
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.server.task.record.RecordSchedules;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Objects;

/**
 * The handlers of the instance schedule operations, attached once per JVM; {@link #init()} only forces the class to
 * load before boot verifies every operation has its handler.
 *
 * AIDEV-NOTE: each authorizer asks CONFIG on the schedule's instance off the request memo (the offer, once per
 * rendered row), and each handler asks it again through the FRESH walk before it acts (the write gate). Run-now's
 * execution is still authorized against the stored run_as, never the invoker.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class InstanceScheduleOperationHandlers {

    static {
        OperationHandlers.attach(InstanceScheduleOperations.RUN_SCHEDULE)
            .applies(schedule -> Boolean.TRUE.equals(schedule.get(RecordScheduleModel.ENABLED)))
            .authorize((schedule, input, access) -> configRefusal(InstanceScheduleParts.writableBy(schedule, access)))
            .handle(InstanceScheduleOperationHandlers::runNow);
        OperationHandlers.attach(InstanceScheduleOperations.DELETE_SCHEDULE)
            .authorize((schedule, input, access) -> configRefusal(InstanceScheduleParts.writableBy(schedule, access)))
            .handle(InstanceScheduleOperationHandlers::deleteSchedule);
        OperationHandlers.attach(InstanceScheduleOperations.DELETE_STEP)
            .authorize((step, input, access) -> configRefusal(InstanceScheduleStepParts.writableBy(step, access)))
            .handle(InstanceScheduleOperationHandlers::deleteStep);
    }

    private InstanceScheduleOperationHandlers() {
    }

    /** Loads the class, attaching the handlers; idempotent. */
    public static void init() {
        // The static initializer did the work.
    }

    private static @Nullable DomainRefusal configRefusal(boolean writable) {
        return writable ? null : new DomainRefusal(ZenitRefusalReason.FORBIDDEN, "shaping a schedule demands CONFIG");
    }

    private static @NonNull String runNow(@NonNull OperationCall<Row, Void> call) {
        Row schedule = call.subject();
        InstanceScheduleParts.requireManage(access(call),
            InstanceScheduleParts.parseInstanceId(schedule.get(RecordScheduleModel.RECORD_ID)));
        Row run = InstanceScheduleParts.recordSchedules().runNow(schedule.get(RecordScheduleModel.ID));
        return run != null ? run.get(RecordScheduleRunModel.STATUS) : "busy";
    }

    /** Removes the chain and its run history with the schedule. */
    private static @NonNull Integer deleteSchedule(@NonNull OperationCall<Row, Void> call) {
        Row schedule = call.subject();
        InstanceScheduleParts.requireManage(access(call),
            InstanceScheduleParts.parseInstanceId(schedule.get(RecordScheduleModel.RECORD_ID)));
        Integer id = schedule.get(RecordScheduleModel.ID);
        ActivityLog.withAction(ZenitActivityAction.DELETE, "delete_schedule",
            () -> InstanceScheduleParts.recordSchedules().deleteSchedule(id));
        return 1;
    }

    /** Removing a step shapes the chain too: the same gate, and the chain now runs as its remover. */
    private static @NonNull Integer deleteStep(@NonNull OperationCall<Row, Void> call) {
        Row step = call.subject();
        Integer scheduleId = step.get(RecordScheduleStepModel.SCHEDULE_ID);
        Row schedule = Models.get(RecordScheduleModel.class).findById(scheduleId);
        if (schedule != null) {
            InstanceScheduleParts.requireManage(access(call),
                InstanceScheduleParts.parseInstanceId(schedule.get(RecordScheduleModel.RECORD_ID)));
        }
        boolean deleted = Models.get(RecordScheduleStepModel.class).delete(step);
        if (schedule != null) {
            RecordSchedules.chainEditedBy(scheduleId, access(call));
        }
        return deleted ? 1 : 0;
    }

    private static @NonNull AccessContext access(@NonNull OperationCall<Row, Void> call) {
        return Objects.requireNonNull(call.access(), "a schedule operation has its caller");
    }
}
