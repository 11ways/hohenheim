package be.elevenways.hohenheim.instance;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.operation.OperationInvocation;
import be.elevenways.zenit.common.orm.lease.LeaseKeys;
import be.elevenways.zenit.common.orm.command.CommandExecution;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.common.task.record.RecordScheduleStepModel;
import be.elevenways.zenit.common.ui.Icon;

/**
 * The operations on an instance's schedules and their chain steps: running a schedule off-cron and the two entries'
 * canonical deletes.
 *
 * AIDEV-NOTE: every one demands CONFIG on the schedule's instance, asked by its server authorizer (the offer) and
 * again, freshly, by its handler (the write gate). Run-now's id is the legacy row action's own,
 * {@code hohenheim:run_schedule}; execution itself is authorized against the schedule's stored run_as, never the
 * invoker. The deletes are domain deletes: removing a schedule takes its chain and run history, and removing a step
 * hands the chain to its editor.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class InstanceScheduleOperations {
    private static final LeaseKeys KEYS = LeaseKeys.declare(HohenheimIds.id("schedule_command"));
    private static final OperationCommand COMMAND = OperationCommand.serializedBy(KEYS, OperationInvocation::operationId);

    /** One record schedule. */
    public static final SubjectType<Row> SCHEDULE = SubjectType.record(RecordScheduleModel.MODEL_ID);

    /** One chain step of a record schedule. */
    public static final SubjectType<Row> STEP = SubjectType.record(RecordScheduleStepModel.MODEL_ID);

    /** Fires the chain now; offered on an enabled schedule. The result is the run's status. */
    public static final Operation<Row, Void, String> RUN_SCHEDULE =
        Operation.declare(HohenheimIds.id("run_schedule"))
            .label(Microcopy.of("run_now").withFilter("scope", "instance_schedule"))
            .icon(Icon.of("play"))
            .one(SCHEDULE)
            .gate(OperationGate.open())
            .result(String.class)
            .command(COMMAND.execution(CommandExecution.OUTSIDE_TRANSACTION))
            .register();

    /** Removes the schedule with its chain and run history. */
    public static final Operation<Row, Void, Integer> DELETE_SCHEDULE =
        Operation.declare(HohenheimIds.id("delete_schedule"))
            .label(Microcopy.of("delete").withFilter("scope", "cms"))
            .icon(Icon.TRASH)
            .one(SCHEDULE)
            .gate(OperationGate.open())
            .result(Integer.class)
            .command(COMMAND)
            .register();

    /** Removes one chain step; the chain now runs under the authority of whoever removed it. */
    public static final Operation<Row, Void, Integer> DELETE_STEP =
        Operation.declare(HohenheimIds.id("delete_schedule_step"))
            .label(Microcopy.of("delete").withFilter("scope", "cms"))
            .icon(Icon.TRASH)
            .one(STEP)
            .gate(OperationGate.open())
            .result(Integer.class)
            .command(COMMAND)
            .register();

    private InstanceScheduleOperations() {
    }
}
