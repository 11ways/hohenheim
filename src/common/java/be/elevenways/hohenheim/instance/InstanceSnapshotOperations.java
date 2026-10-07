package be.elevenways.hohenheim.instance;

import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.model.InstanceSnapshotModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.orm.command.CommandExecution;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.ui.Icon;

/**
 * Snapshot payload operations. Notes and instance names stay verbatim; operation words are localized.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class InstanceSnapshotOperations {
    private static final OperationCommand COMMAND = OperationCommand.perSubject()
        .execution(CommandExecution.OUTSIDE_TRANSACTION);
    public static final SubjectType<Row> SNAPSHOT = SubjectType.record(InstanceSnapshotModel.MODEL_ID);
    public static final Operation<Row, Void, Void> RESTORE = Operation.declare(HohenheimIds.id("restore_snapshot"))
        .happened(OperationSentences.of("restore_snapshot"))
        .label(Microcopy.of("restore").withFilter("scope", "instance_snapshot"))
        .icon(Icon.of("clock-rotate-left")).one(SNAPSHOT).gate(OperationGate.open())
        .facts(OperationFact.DESTRUCTIVE, OperationFact.REACHES_OUTSIDE).command(COMMAND).register();
    public static final Operation<Row, Void, Integer> DELETE = Operation.declare(HohenheimIds.id("delete_snapshot"))
        .happened(OperationSentences.of("delete_snapshot"))
        .label(Microcopy.of("delete").withFilter("scope", "cms")).icon(Icon.TRASH)
        .one(SNAPSHOT).gate(OperationGate.open()).result(Integer.class)
        .facts(OperationFact.DESTRUCTIVE, OperationFact.REACHES_OUTSIDE).command(COMMAND).register();
    private InstanceSnapshotOperations() {}
}
