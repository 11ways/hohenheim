package be.elevenways.hohenheim.instance;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimRefusalReason;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.zenit.cms.common.CmsMicrocopy;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.orm.lease.LeaseKeys;
import be.elevenways.zenit.common.orm.command.CommandExecution;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.orm.field.IntegerField;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The instance operations every surface places: start, stop, restart, backup, snapshot, a console command and the
 * in-place app update of one instance; and the two sessions its tabs open, a shell and a framebuffer.
 *
 * AIDEV-NOTE: no applies and no availability. A stopped instance's stop is idempotent
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
    public static final LeaseKeys KEYS = LeaseKeys.declare(HohenheimIds.id("instance"), "hohenheim_instance_");
    private static final OperationCommand COMMAND_FACET = OperationCommand.perSubject(KEYS)
        .execution(CommandExecution.OUTSIDE_TRANSACTION);
    /**
     * Start and stop replay a completed answer; an interrupted one may run again, since powering an instance to the
     * state it is already in changes nothing at the provider.
     *
     * AIDEV-NOTE: keyed per verb and instance, never on the instance lease: a start asked while a restart holds the
     * instance must reach the service's in-progress refusal at once, not wait for that lease and time out.
     */
    private static final OperationCommand POWER_FACET = OperationCommand.perVerbAndSubject()
        .execution(CommandExecution.OUTSIDE_TRANSACTION_RETRY_SAFE);

    /** The subject of every instance operation: one instance record. */
    public static final SubjectType<Row> INSTANCE = SubjectType.record(InstanceModel.MODEL_ID);

    public static final Operation<Row, Void, PowerResult> START = Operation.declare(HohenheimIds.id("start_instance"))
        .happened(OperationSentences.of("start_instance"))
        .label(HohenheimMicrocopy.POWER_OPERATION.of("start").withFallback("Start"))
        .icon(Icon.of("play"))
        .one(INSTANCE)
        .gate(gate(HohenheimCapabilities.POWER))
        .result(PowerResult.class)
        .facts(OperationFact.REACHES_OUTSIDE, OperationFact.IDEMPOTENT)
        .command(POWER_FACET)
        .register();

    public static final Operation<Row, Void, PowerResult> STOP = Operation.declare(HohenheimIds.id("stop_instance"))
        .happened(OperationSentences.of("stop_instance"))
        .label(HohenheimMicrocopy.POWER_OPERATION.of("stop").withFallback("Stop"))
        .icon(Icon.of("stop"))
        .one(INSTANCE)
        .gate(gate(HohenheimCapabilities.POWER))
        .result(PowerResult.class)
        .facts(OperationFact.REACHES_OUTSIDE, OperationFact.IDEMPOTENT, OperationFact.DESTRUCTIVE)
        .command(POWER_FACET)
        .register();

    public static final Operation<Row, Void, PowerResult> RESTART =
        Operation.declare(HohenheimIds.id("restart_instance"))
            .happened(OperationSentences.of("restart_instance"))
            .label(HohenheimMicrocopy.POWER_OPERATION.of("restart").withFallback("Restart"))
            .icon(Icon.of("rotate-right"))
            .one(INSTANCE)
            .gate(gate(HohenheimCapabilities.POWER))
            .result(PowerResult.class)
            .command(COMMAND_FACET)
            .facts(OperationFact.REACHES_OUTSIDE)
            .register();

    public static final Operation<Row, Void, Integer> BACKUP = Operation.declare(HohenheimIds.id("backup_instance"))
        .happened(OperationSentences.of("backup_instance"))
        .label(HohenheimMicrocopy.SCHEDULE_ACTION.of("backup").withFallback("Backup"))
        .icon(Icon.of("box-archive"))
        .one(INSTANCE)
        .gate(gate(HohenheimCapabilities.BACKUPS))
        .result(Integer.class)
        .command(COMMAND_FACET)
        .facts(OperationFact.REACHES_OUTSIDE)
        .register();

    /** The snapshot's optional note. */
    public static final StringField NOTE = StringField.builder("note")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("snapshot_note"))
        .build();

    public static final Operation<Row, SnapshotInput, Integer> SNAPSHOT =
        Operation.declare(HohenheimIds.id("snapshot_instance"))
            .happened(OperationSentences.of("snapshot_instance"))
            .label(HohenheimMicrocopy.SCHEDULE_ACTION.of("snapshot").withFallback("Snapshot"))
            .icon(Icon.of("camera"))
            .one(INSTANCE)
            .gate(gate(HohenheimCapabilities.SNAPSHOTS))
            .input(OperationInput.of(FormSpec.builder().add(NOTE).build(), SnapshotInput.class,
                values -> new SnapshotInput(values.get(NOTE))))
            .result(Integer.class)
            .command(COMMAND_FACET)
            .facts(OperationFact.REACHES_OUTSIDE)
            .register();

    /** The snapshot's input. */
    public record SnapshotInput(@Nullable String note) {
    }

    /** The console line to send. */
    public static final StringField COMMAND = StringField.builder("command")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("console_line"))
        .required()
        .build();

    /**
     * One line to the workload's primary process; a line equal to the template's stop command still counts as an
     * observed stop (InstanceConsoles' funnel).
     */
    public static final Operation<Row, ConsoleCommandInput, String> CONSOLE_COMMAND =
        Operation.declare(HohenheimIds.id("console_command_instance"))
            .happened(OperationSentences.of("console_command_instance"))
            .label(HohenheimMicrocopy.SCHEDULE_ACTION.of("console_command").withFallback("Console command"))
            .icon(Icon.of("terminal"))
            .one(INSTANCE)
            .gate(gate(HohenheimCapabilities.CONSOLE))
            .input(OperationInput.of(FormSpec.builder().add(COMMAND).build(), ConsoleCommandInput.class,
                values -> new ConsoleCommandInput(values.get(COMMAND))))
            .result(String.class)
            .command(COMMAND_FACET)
            .facts(OperationFact.REACHES_OUTSIDE)
            .register();

    /** The console command's input. */
    public record ConsoleCommandInput(@Nullable String command) {
    }

    /** The program the exec tab runs. */
    public static final StringField EXEC_COMMAND = StringField.builder("command")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("exec_command"))
        .required()
        .build();

    /**
     * Run ONE arbitrary program inside the workload and read its exit code and output: what the exec tab offers.
     *
     * AIDEV-NOTE: not the console command under another name: the console reaches the workload's own primary process,
     * this starts a new program, and the two answer to different capabilities. InstanceExec asks EXEC once more on its
     * funnel, the lane a future API reaches too.
     */
    public static final Operation<Row, ExecInput, ExecRun> EXEC =
        Operation.declare(HohenheimIds.id("exec_instance"))
            .happened(OperationSentences.of("exec_instance"))
            .label(HohenheimMicrocopy.INSTANCE_EXEC.of("run").withFallback("Run"))
            .icon(Icon.of("code"))
            .one(INSTANCE)
            .gate(gate(HohenheimCapabilities.EXEC))
            .input(OperationInput.of(FormSpec.builder().add(EXEC_COMMAND).build(), ExecInput.class,
                values -> new ExecInput(values.get(EXEC_COMMAND))))
            .result(ExecRun.class)
            .command(COMMAND_FACET)
            .facts(OperationFact.REACHES_OUTSIDE)
            .register();

    /** Roll a release-managed instance back to its retained release; POWER on the record. */
    public static final Operation<Row, Void, Void> ROLLBACK =
        Operation.declare(HohenheimIds.id("rollback_instance"))
            .happened(OperationSentences.of("rollback_instance"))
            .label(HohenheimMicrocopy.INSTANCE.of("rollback").withFallback("Roll back"))
            .icon(Icon.of("clock-rotate-left"))
            .one(INSTANCE)
            .gate(gate(HohenheimCapabilities.POWER))
            .command(COMMAND_FACET)
            .facts(OperationFact.REACHES_OUTSIDE)
            .register();

    /**
     * Run (or resume, or retry) the template's install step; OPERATOR-ONLY through its authorizer, applicable while the
     * install is neither absent nor done.
     */
    public static final Operation<Row, Void, Void> INSTALL =
        Operation.declare(HohenheimIds.id("install_instance"))
            .happened(OperationSentences.of("install_instance"))
            .label(HohenheimMicrocopy.INSTANCE.of("install").withFallback("Install"))
            .icon(Icon.of("wand-magic-sparkles"))
            .one(INSTANCE)
            .gate(OperationGate.open())
            .command(COMMAND_FACET)
            .facts(OperationFact.REACHES_OUTSIDE)
            .register();

    /**
     * Reinstall per the template's EXPLICIT data policy; OPERATOR-ONLY, applicable once installed or failed. The policy
     * is enforced in InstanceInstalls; the placement's dialog is the accident guard.
     */
    public static final Operation<Row, Void, Void> REINSTALL =
        Operation.declare(HohenheimIds.id("reinstall_instance"))
            .happened(OperationSentences.of("reinstall_instance"))
            .label(HohenheimMicrocopy.INSTANCE.of("reinstall").withFallback("Reinstall"))
            .icon(Icon.of("rotate"))
            .one(INSTANCE)
            .gate(OperationGate.open())
            .command(COMMAND_FACET)
            .facts(OperationFact.REACHES_OUTSIDE)
            .register();

    /**
     * Publish this STOPPED instance's state as a prepared (unapproved) template; OPERATOR-ONLY, because capture mints
     * catalog authority. The result is the minted template.
     */
    public static final Operation<Row, Void, Integer> CAPTURE_TEMPLATE =
        Operation.declare(HohenheimIds.id("capture_template"))
            .happened(OperationSentences.of("capture_template"))
            .label(HohenheimMicrocopy.INSTANCE.of("capture_template").withFallback("Capture as template"))
            .icon(Icon.of("box-archive"))
            .one(INSTANCE)
            .gate(OperationGate.open())
            .result(Integer.class)
            .command(COMMAND_FACET)
            .facts(OperationFact.REACHES_OUTSIDE)
            .register();

    /**
     * The one irreversible verb: destroy the workload AND the volumes it owns; OPERATOR-ONLY. A separate verb beside
     * delete, which keeps the data by design.
     */
    public static final Operation<Row, Void, Void> DESTROY_WITH_DATA =
        Operation.declare(HohenheimIds.id("destroy_instance_data"))
            .happened(OperationSentences.of("destroy_instance_data"))
            .label(HohenheimMicrocopy.INSTANCE.of("delete_with_data").withFallback("Delete with data"))
            .icon(Icon.of("trash-can"))
            .one(INSTANCE)
            .gate(OperationGate.open())
            .command(COMMAND_FACET)
            .facts(OperationFact.REACHES_OUTSIDE)
            .register();

    /**
     * The verified destroy, the instance entry's delete: container removed (or observed absent) and port claims
     * released before the record is soft-deleted; volumes survive by design, the reconciler surfaces them as orphans.
     *
     * AIDEV-NOTE: offered DEAD, never hidden, to a viewer without {@code destroy} on the record: its availability is
     * the teardown funnel's own refusal, so the button and the POST answer with one decision and one text.
     */
    public static final Operation<Row, Void, Integer> DELETE = Operation.declare(HohenheimIds.id("delete_instance"))
        .happened(OperationSentences.of("delete_instance"))
        .label(CmsMicrocopy.of("delete"))
        .icon(Icon.TRASH)
        .one(INSTANCE)
        .gate(OperationGate.open())
        .result(Integer.class)
        .facts(OperationFact.REACHES_OUTSIDE, OperationFact.DESTRUCTIVE)
        .command(COMMAND_FACET)
        .register();

    /** The host a migration moves the workload to. */
    public static final IntegerField TARGET_SERVER = IntegerField.builder("targetServerId")
        .label(HohenheimMicrocopy.INSTANCE_MIGRATE.of("host"))
        .required()
        .build();

    /**
     * The cold move of one instance to another host: what the migrate tab places, once per destination row.
     *
     * AIDEV-NOTE: OPERATOR-ONLY, twice over: its server authorizer refuses anyone else, and InstanceMigrations
     * refuses every tenant-originated call by name. Placement is an operator authority, the same decision
     * InstancePlacement records for creates. The result is the destination, so the toast can name it.
     */
    public static final Operation<Row, MigrateInput, Integer> MIGRATE =
        Operation.declare(HohenheimIds.id("migrate_instance"))
            .happened(OperationSentences.of("migrate_instance"))
            .label(HohenheimMicrocopy.INSTANCE_MIGRATE.of("migrate").withFallback("Migrate"))
            .icon(Icon.of("truck-fast"))
            .one(INSTANCE)
            .gate(OperationGate.open())
            .input(OperationInput.of(FormSpec.builder().add(TARGET_SERVER).build(), MigrateInput.class,
                values -> new MigrateInput(values.get(TARGET_SERVER))))
            .result(Integer.class)
            .command(COMMAND_FACET)
            .facts(OperationFact.REACHES_OUTSIDE)
            .register();

    /** The migration's input: the destination host. */
    public record MigrateInput(@Nullable Integer targetServerId) {
    }

    /** The exec tab's input. */
    public record ExecInput(@Nullable String command) {
    }

    /**
     * One exec run's outcome: page CONTENT, never a notification.
     *
     * @param exitCode the program's exit code
     * @param output   its captured output
     */
    @HawkeyeClass
    public record ExecRun(int exitCode, @NonNull String output) {
    }

    /**
     * Open an interactive shell inside one instance: what the shell tab offers and the shell socket admits.
     *
     * AIDEV-NOTE: a SESSION, not an invocation. Its effect is the socket the shell tab opens, so no surface invokes it;
     * the socket asks {@code OperationPipeline.offer} and admits exactly whom the operation is offered to, which
     * carries the gate, the generated-instance applicability and the authorization of every other instance operation.
     */
    public static final Operation<Row, Void, Void> OPEN_SHELL = Operation.declare(HohenheimIds.id("open_shell"))
        .happened(OperationSentences.of("open_shell"))
        .label(HohenheimMicrocopy.INSTANCE.of("shell").withFallback("Shell"))
        .icon(Icon.of("terminal"))
        .one(INSTANCE)
        .gate(gate(HohenheimCapabilities.SHELL))
        .facts(OperationFact.REACHES_OUTSIDE)
        .register();

    /** Watch and drive a virtual machine's screen: the framebuffer tab and socket, the {@link #OPEN_SHELL} shape. */
    public static final Operation<Row, Void, Void> OPEN_FRAMEBUFFER =
        Operation.declare(HohenheimIds.id("open_framebuffer"))
            .happened(OperationSentences.of("open_framebuffer"))
            .label(HohenheimMicrocopy.INSTANCE.of("framebuffer").withFallback("Framebuffer"))
            .icon(Icon.of("display"))
            .one(INSTANCE)
            .gate(gate(HohenheimCapabilities.CONSOLE))
            .facts(OperationFact.REACHES_OUTSIDE)
            .register();

    /** The template's in-place update script, run inside the running workload; it changes what runs: config. */
    public static final Operation<Row, Void, String> APP_UPDATE =
        Operation.declare(HohenheimIds.id("app_update_instance"))
            .happened(OperationSentences.of("app_update_instance"))
            .label(HohenheimMicrocopy.SCHEDULE_ACTION.of("app_update").withFallback("App update"))
            .icon(Icon.of("arrow-up-from-bracket"))
            .one(INSTANCE)
            .gate(gate(HohenheimCapabilities.CONFIG))
            .result(String.class)
            .command(COMMAND_FACET)
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
    @HawkeyeClass
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
}
