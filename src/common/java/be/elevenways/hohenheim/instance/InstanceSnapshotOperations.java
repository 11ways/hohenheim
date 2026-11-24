package be.elevenways.hohenheim.instance;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimVerbs;
import be.elevenways.hohenheim.model.InstanceSnapshotModel;
import be.elevenways.zenit.cms.common.CmsMicrocopy;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.orm.command.CommandExecution;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.OperationVerb;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Snapshot payload operations. Notes and instance names stay verbatim; operation words are localized.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class InstanceSnapshotOperations {
    private static final OperationCommand COMMAND = OperationCommand.perSubject()
        .execution(CommandExecution.OUTSIDE_TRANSACTION);
    public static final SubjectType<Row> SNAPSHOT = SubjectType.record(InstanceSnapshotModel.REF);
    public static final Operation<Row, Void, Void> RESTORE = Operation.declare(HohenheimIds.id("restore_snapshot"))
        .happened(OperationSentences.of("restore_snapshot"))
        .label(HohenheimMicrocopy.INSTANCE_SNAPSHOT.of("restore"))
        .description(HohenheimMicrocopy.HOHENHEIM.of("restore_snapshot_description"))
        .icon(Icon.of("clock-rotate-left")).one(SNAPSHOT).verb(HohenheimVerbs.RESTORE_IN_PLACE)
        .gate(OperationGate.open())
        .facts(OperationFact.DESTRUCTIVE, OperationFact.REACHES_OUTSIDE).command(COMMAND).register();
    public static final Operation<Row, Void, Integer> DELETE = Operation.declare(HohenheimIds.id("delete_snapshot"))
        .happened(OperationSentences.of("delete_snapshot"))
        .label(CmsMicrocopy.of("delete")).icon(Icon.TRASH)
        .description(HohenheimMicrocopy.HOHENHEIM.of("delete_snapshot_description"))
        .one(SNAPSHOT).verb(OperationVerb.DELETE).gate(OperationGate.open()).result(Integer.class)
        .facts(OperationFact.DESTRUCTIVE, OperationFact.REACHES_OUTSIDE).command(COMMAND).register();

    /** @param note the words kept with the snapshot, null or blank for none */
    public record NoteInput(@Nullable String note) {}

    /** Changes the words kept with a snapshot; answers the snapshot's id. */
    public static final Operation<Row, NoteInput, Integer> CHANGE_NOTE =
            Operation.declare(HohenheimIds.id("change_snapshot_note"))
        .happened(OperationSentences.of("change_snapshot_note"))
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("note"))
        .description(HohenheimMicrocopy.HOHENHEIM.of("change_snapshot_note_description"))
        .icon(Icon.of("pen")).one(SNAPSHOT).gate(OperationGate.open())
        .input(OperationInput.of(FormSpec.builder().add(InstanceSnapshotModel.NOTE).build(), NoteInput.class,
            values -> new NoteInput(values.get(InstanceSnapshotModel.NOTE))))
        .result(Integer.class).patchable().facts(OperationFact.IDEMPOTENT).command(OperationCommand.perSubject())
        .register();
    private InstanceSnapshotOperations() {}
}
