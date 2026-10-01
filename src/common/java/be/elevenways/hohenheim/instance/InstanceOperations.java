package be.elevenways.hohenheim.instance;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimRefusalReason;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The instance operations every surface places: start, stop, restart, backup, snapshot, a console command and the
 * in-place app update of one instance.
 *
 * AIDEV-NOTE: no applies and no availability (stage 2 contract 6.10, S1). A stopped instance's stop is idempotent
 * and answers success, and a database that is not ready is the start handler's retriable refusal, so the API and a
 * schedule step answer as they did before. What the admin hides or deadens is the admin placement's presentation.
 *
 * AIDEV-NOTE: every gate refuses with {@link HohenheimRefusalReason#INSTANCE_NOT_PERMITTED}, the instance tier's
 * uniform refusal, never core's forbidden: no surface may become a capability oracle.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class InstanceOperations {

    /** The subject of every instance operation: one instance record. */
    public static final SubjectType<Row> INSTANCE = SubjectType.record(InstanceModel.MODEL_ID);

    public static final Operation<Row, Void, PowerResult> START = Operation.declare(HohenheimIds.id("start_instance"))
        .label(label("start", "power_operation", "Start"))
        .icon(Icon.of("play"))
        .one(INSTANCE)
        .gate(gate(HohenheimCapabilities.POWER))
        .result(PowerResult.class)
        .facts(OperationFact.REACHES_OUTSIDE, OperationFact.IDEMPOTENT)
        .register();

    public static final Operation<Row, Void, PowerResult> STOP = Operation.declare(HohenheimIds.id("stop_instance"))
        .label(label("stop", "power_operation", "Stop"))
        .icon(Icon.of("stop"))
        .one(INSTANCE)
        .gate(gate(HohenheimCapabilities.POWER))
        .result(PowerResult.class)
        .facts(OperationFact.REACHES_OUTSIDE, OperationFact.IDEMPOTENT, OperationFact.DESTRUCTIVE)
        .register();

    public static final Operation<Row, Void, PowerResult> RESTART =
        Operation.declare(HohenheimIds.id("restart_instance"))
            .label(label("restart", "power_operation", "Restart"))
            .icon(Icon.of("rotate-right"))
            .one(INSTANCE)
            .gate(gate(HohenheimCapabilities.POWER))
            .result(PowerResult.class)
            .facts(OperationFact.REACHES_OUTSIDE)
            .register();

    public static final Operation<Row, Void, Integer> BACKUP = Operation.declare(HohenheimIds.id("backup_instance"))
        .label(label("backup", "schedule_action", "Backup"))
        .icon(Icon.of("box-archive"))
        .one(INSTANCE)
        .gate(gate(HohenheimCapabilities.BACKUPS))
        .result(Integer.class)
        .facts(OperationFact.REACHES_OUTSIDE)
        .register();

    /** The snapshot's optional note. */
    public static final StringField NOTE = StringField.builder("note")
        .label(HohenheimFormCopy.label("snapshot_note"))
        .build();

    public static final Operation<Row, SnapshotInput, Integer> SNAPSHOT =
        Operation.declare(HohenheimIds.id("snapshot_instance"))
            .label(label("snapshot", "schedule_action", "Snapshot"))
            .icon(Icon.of("camera"))
            .one(INSTANCE)
            .gate(gate(HohenheimCapabilities.SNAPSHOTS))
            .input(OperationInput.of(FormSpec.builder().add(NOTE).build(), SnapshotInput.class,
                values -> new SnapshotInput(values.get(NOTE))))
            .result(Integer.class)
            .facts(OperationFact.REACHES_OUTSIDE)
            .register();

    /** The snapshot's input. */
    public record SnapshotInput(@Nullable String note) {
    }

    /** The console line to send. */
    public static final StringField COMMAND = StringField.builder("command")
        .label(HohenheimFormCopy.label("console_line"))
        .required()
        .build();

    /**
     * One line to the workload's primary process; a line equal to the template's stop command still counts as an
     * observed stop (InstanceConsoles' funnel).
     */
    public static final Operation<Row, ConsoleCommandInput, String> CONSOLE_COMMAND =
        Operation.declare(HohenheimIds.id("console_command_instance"))
            .label(label("console_command", "schedule_action", "Console command"))
            .icon(Icon.of("terminal"))
            .one(INSTANCE)
            .gate(gate(HohenheimCapabilities.CONSOLE))
            .input(OperationInput.of(FormSpec.builder().add(COMMAND).build(), ConsoleCommandInput.class,
                values -> new ConsoleCommandInput(values.get(COMMAND))))
            .result(String.class)
            .facts(OperationFact.REACHES_OUTSIDE)
            .register();

    /** The console command's input. */
    public record ConsoleCommandInput(@Nullable String command) {
    }

    /** The template's in-place update script, run inside the running workload; it changes what runs: config. */
    public static final Operation<Row, Void, String> APP_UPDATE =
        Operation.declare(HohenheimIds.id("app_update_instance"))
            .label(label("app_update", "schedule_action", "App update"))
            .icon(Icon.of("arrow-up-from-bracket"))
            .one(INSTANCE)
            .gate(gate(HohenheimCapabilities.CONFIG))
            .result(String.class)
            .facts(OperationFact.REACHES_OUTSIDE)
            .register();

    /**
     * What a power operation left behind.
     *
     * @param status    the instance's stored status afterwards
     * @param trigger   the deploy trigger's recorded word, null when nothing was deployed
     * @param unchanged true when the instance already was in the asked state and nothing ran (a stopped instance's
     *                  stop)
     */
    public record PowerResult(@NonNull String status, @Nullable String trigger, boolean unchanged) {

        /** @return the schedule step's recorded outcome: the status, how it was reached, or that it already was */
        @Override
        public @NonNull String toString() {
            if (this.unchanged) {
                return "already " + this.status;
            }
            return this.trigger == null ? this.status : this.status + " (" + this.trigger + ")";
        }
    }

    private InstanceOperations() {
    }

    private static @NonNull OperationGate gate(@NonNull String capability) {
        return OperationGate.open().subjectCapability(capability)
            .refusing(HohenheimRefusalReason.INSTANCE_NOT_PERMITTED);
    }

    private static @NonNull Microcopy label(@NonNull String key, @NonNull String scope, @NonNull String fallback) {
        return Microcopy.of(key).withFilter("scope", scope).withFallback(fallback);
    }
}
