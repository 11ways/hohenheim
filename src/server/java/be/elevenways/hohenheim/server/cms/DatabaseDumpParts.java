package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.model.DatabaseDumpModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.database.DatabaseBackups;
import be.elevenways.hohenheim.server.database.DatabaseRestores;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.resource.ChildList;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.RecordOverview;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.OperationSteps;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.command.CommandExecution;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.operation.OperationHandlers;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The stored dumps of the managed databases on the operator's panel: a database's Backups tab lists them, and each
 * complete one restores into a NEW database or in place, both background runs.
 *
 * AIDEV-NOTE: both restores are OPERATOR-ONLY, twice: their authorizer refuses anyone else and
 * {@code DatabaseService.restoreFromFile} refuses a tenant-originated call underneath (an uploaded or stored dump is
 * SQL run as the engine superuser). The in-place restore first stores a fresh dump of the database (the tenant-restore
 * rule: in place always takes a backup first, and its confirmation says so). A dump outlives its database
 * (ResourceParent.survivingArchive), so the dump of a deleted database still restores into a new one.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class DatabaseDumpParts {

    /** The subject of every dump operation: one ledger row. */
    public static final SubjectType<Row> DUMP = SubjectType.record(DatabaseDumpModel.REF);

    /** The database a restore into a new one creates, attached to its run. */
    private static final SubjectType<Row> DATABASE = SubjectType.record(DatabaseModel.REF);

    private static final OperationCommand COMMAND = OperationCommand.perSubject()
        .execution(CommandExecution.OUTSIDE_TRANSACTION);

    /** The new database's name, the restore-to-new's one input. */
    public static final StringField NEW_NAME = StringField.builder(DatabaseModel.NAME.getName())
        .required()
        .label(HohenheimMicrocopy.DATABASE_DUMP.of("dump_new_name"))
        .help(HohenheimMicrocopy.DATABASE_DUMP.of("dump_new_name_help"))
        .build();

    /** @param name the new database's name */
    public record RestoreInput(@Nullable String name) {
    }

    /** Restores the dump into a new dedicated database of its kind and image; answers the new database's id. */
    public static final Operation<Row, RestoreInput, Integer> RESTORE_TO_NEW =
        Operation.declare(HohenheimIds.id("restore_database_dump"))
            .happened(OperationSentences.of("restore_database_dump"))
            .label(HohenheimMicrocopy.DATABASE_DUMP.of("dump_restore_new"))
            .icon(Icon.of("clone"))
            .one(DUMP)
            .gate(OperationGate.permission(HohenheimSources.ADMIN_ACCESS))
            .input(OperationInput.of(FormSpec.builder().add(NEW_NAME).build(), RestoreInput.class,
                values -> new RestoreInput(values.get(NEW_NAME.getName()) instanceof String name ? name : null)))
            .result(Integer.class)
            .command(COMMAND)
            .background(OperationSteps.of(
                OperationSteps.step("restore", HohenheimMicrocopy.DATABASE_DUMP.of("dump_restore_new_step"))))
            .register();

    /** Stores a fresh dump of the dump's database, then restores the dump into it; answers the fresh dump's id. */
    public static final Operation<Row, Void, Integer> RESTORE_IN_PLACE =
        Operation.declare(HohenheimIds.id("restore_database_dump_in_place"))
            .happened(OperationSentences.of("restore_database_dump_in_place"))
            .label(HohenheimMicrocopy.DATABASE_DUMP.of("dump_restore_in_place"))
            .icon(Icon.of("clock-rotate-left"))
            .one(DUMP)
            .gate(OperationGate.permission(HohenheimSources.ADMIN_ACCESS))
            .facts(OperationFact.DESTRUCTIVE)
            .result(Integer.class)
            .command(COMMAND)
            .background(OperationSteps.of(
                OperationSteps.step("restore", HohenheimMicrocopy.DATABASE_DUMP.of("dump_restore_in_place_step"))))
            .register();

    /** Removes a settled dump's artifact from its target with the row; refused while the target cannot be reached. */
    public static final Operation<Row, Void, Integer> DELETE =
        Operation.declare(HohenheimIds.id("delete_database_dump"))
            .happened(OperationSentences.of("delete_database_dump"))
            .label(HohenheimMicrocopy.DATABASE_DUMP.of("dump_delete"))
            .icon(Icon.TRASH)
            .one(DUMP)
            .gate(OperationGate.permission(HohenheimSources.ADMIN_ACCESS))
            .facts(OperationFact.DESTRUCTIVE)
            .result(Integer.class)
            .command(COMMAND)
            .register();

    static {
        OperationHandlers.attach(RESTORE_TO_NEW)
            .applies(DatabaseDumpModel::isRestorable)
            .authorize(HohenheimAccess.operatorOnly("restoring a stored dump is an operator act"))
            .handle(call -> {
                String name = Objects.requireNonNull(call.input(), "the restore names its database").name();
                Row created = DatabaseRestores.restoreToNew(new DatabaseService(),
                    call.subject().get(DatabaseDumpModel.ID), Objects.requireNonNullElse(name, ""));
                call.attachSubject(DATABASE, created);
                call.reportStepDone(HohenheimMicrocopy.DATABASE_DUMP.of("dump_restored_new")
                    .withArg("name", created.get(DatabaseModel.NAME)));
                return created.get(DatabaseModel.ID);
            });
        OperationHandlers.attach(RESTORE_IN_PLACE)
            .applies(dump -> DatabaseDumpModel.isRestorable(dump)
                && dump.get(DatabaseDumpModel.DATABASE_ID) != null)
            .authorize(HohenheimAccess.operatorOnly("restoring a stored dump is an operator act"))
            .handle(call -> {
                Row before = DatabaseRestores.restoreInPlace(new DatabaseService(),
                    call.subject().get(DatabaseDumpModel.ID));
                call.reportStepDone(HohenheimMicrocopy.DATABASE_DUMP.of("dump_restored_in_place")
                    .withArg("name", call.subject().get(DatabaseDumpModel.DATABASE_NAME))
                    .withArg("before", taken(before)));
                return before.get(DatabaseDumpModel.ID);
            });
        // A row still uploading is its upload's to settle: deleting it would orphan the artifact the upload commits.
        OperationHandlers.attach(DELETE)
            .applies(DatabaseDumpModel::isSettled)
            .authorize(HohenheimAccess.operatorOnly("deleting a stored dump is an operator act"))
            .handle(call -> {
                DatabaseBackups.delete(call.subject());
                return 1;
            });
    }

    private DatabaseDumpParts() {
    }

    /** The dump ledger's list shape, beside the instance backups' (BackupLedgerList). */
    private static final BackupLedgerList LEDGER_LIST = new BackupLedgerList(DUMP, HohenheimSlugs.DATABASE_DUMPS,
        HohenheimMicrocopy.DATABASE_DUMP.of("dump_plural"), HohenheimMicrocopy.DATABASE_DUMP.of("dump_singular"), 51,
        DatabaseDumpParts::title, DELETE, DatabaseDumpModel.CREATED_AT, DatabaseDumpModel.STATUS,
        DatabaseDumpModel.SIZE_BYTES, DatabaseDumpModel.TARGET_ID, DatabaseDumpModel.REMOTE_KEY);

    /** @return the operator's dumps: each database's, its Backups tab's rows, restorable from their row */
    public static @NonNull PanelResource<Row> admin() {
        return LEDGER_LIST.resource(HohenheimIds.id("database_dump"),
                ResourceParent.of(HohenheimSlugs.DATABASES, DatabaseDumpModel.DATABASE_ID)
                    .tab(HohenheimSlugs.Tab.BACKUPS).survivingArchive(),
                List.of(ColumnSpec.fromField(DatabaseDumpModel.DATABASE_NAME).filterable().build()), true,
                List.of(ColumnSpec.fromField(DatabaseDumpModel.ENGINE).hidden().build()),
                list -> list.emptyDescription(HohenheimMicrocopy.DATABASE_DUMP.of("dump_empty")))
            .actions(List.of(restoreToNew(), restoreInPlace()))
            .tabs(ResourceTabs.<Row>of(List.of(RecordOverview.<Row>fields())).withContributions())
            .build();
    }

    /** A database's Backups tab: its dumps, newest first, with Back up now offered where it has none. */
    public static @NonNull ChildList<Row> tab() {
        return ChildList.<Row>of(admin())
            .label(HohenheimMicrocopy.DATABASE_DUMP.of("dump_tab"))
            .icon(Icon.of("box-archive"))
            .lead(HohenheimMicrocopy.DATABASE_DUMP.of("dump_lead"))
            .hide(HohenheimSlugs.DATABASE_DUMPS, DatabaseDumpModel.DATABASE_NAME.getName())
            .parentActions(HohenheimSlugs.DATABASE_DUMPS, DatabaseParts.BACK_UP_NOW.id());
    }

    /** A dump is named by when it was taken, read on each reader's own clock. */
    static @NonNull Microcopy title(@NonNull Row dump) {
        return taken(dump);
    }

    private static @NonNull Microcopy taken(@NonNull Row dump) {
        Instant at = dump.get(DatabaseDumpModel.CREATED_AT);
        return at != null ? HohenheimMicrocopy.DATABASE_DUMP.of("dump_title_taken").withArg("at", at)
            : Microcopy.literal("#" + dump.get(DatabaseDumpModel.ID));
    }

    /** Restore into a new database: creative, so confirmed without a typed phrase; the run opens the new database. */
    private static @NonNull PanelAction<Row> restoreToNew() {
        Microcopy verb = HohenheimMicrocopy.DATABASE_DUMP.of("dump_restore_new");
        return PanelAction.<Row, Integer>placesRun(RESTORE_TO_NEW, ActionPlacement.ROW)
            .label(verb)
            .icon(Icon.of("clone"))
            .inlineInRow(false)
            .confirmation(ConfirmationSpec.verb(verb, HohenheimMicrocopy.DATABASE_DUMP.of("dump_restore_new_confirm"),
                ActionStyle.DEFAULT))
            .build();
    }

    /** Restore in place: destructive, and its confirmation names the fresh backup taken first. */
    private static @NonNull PanelAction<Row> restoreInPlace() {
        Microcopy verb = HohenheimMicrocopy.DATABASE_DUMP.of("dump_restore_in_place");
        ConfirmationSpec confirm = ConfirmationSpec.verb(verb,
            HohenheimMicrocopy.DATABASE_DUMP.of("dump_restore_in_place_confirm_generic"), ActionStyle.DESTRUCTIVE);
        return PanelAction.<Row, Integer>placesRun(RESTORE_IN_PLACE, ActionPlacement.ROW)
            .label(verb)
            .icon(Icon.of("clock-rotate-left"))
            .style(ActionStyle.DESTRUCTIVE)
            .inlineInRow(false)
            .confirmation(confirm)
            .dynamicConfirmation(dump -> confirm.withBody(HohenheimMicrocopy.DATABASE_DUMP.of(
                    "dump_restore_in_place_confirm")
                .withArg("name", dump.get(DatabaseDumpModel.DATABASE_NAME))
                .withArg("at", taken(dump))))
            .build();
    }
}
