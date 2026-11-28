package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.annotation.BlastAutoLoad;
import be.elevenways.zenit.cms.server.page.SettingsPage;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.HohenheimVerbs;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.server.backup.BackupStorages;
import be.elevenways.hohenheim.server.backup.HohenheimStorages;
import be.elevenways.protoblast.common.dry.BlastDrySerializers;
import be.elevenways.zenit.cms.common.CmsMicrocopy;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.JsonValue;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationVerb;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.server.storage.StorageCheck;
import be.elevenways.zenit.server.storage.StorageKinds;
import be.elevenways.zenit.server.storage.StorageOwner;
import be.elevenways.zenit.server.storage.StorageTrial;
import be.elevenways.zenit.server.storage.Storages;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * The storages' parts (board FO128): full CRUD over the kind and settings the storage layer declares, and a live
 * test, the storage's own check against its real destination, never a form-level syntax check.
 *
 * AIDEV-NOTE: a save runs that same check first (the kind's trial) and a storage that fails it, or whose server
 * presents a key the settings do not pin yet, is not saved: the refusal says why, and for a key it names the
 * fingerprint to compare and pin. A name a mount of this server's settings holds is refused, so a record never
 * shadows one ({@link BackupStorages#of}).
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
@BlastAutoLoad
public final class BackupTargetParts {

    /** Read by the autoload index, which loads the class and so runs the DRY registration below at boot. */
    public static final Object LOADED = new Object();

    private static final SubjectType<Row> SUBJECT = SubjectType.record(BackupTargetModel.REF);

    /** The argument carrying a storage's settings, in its kind's shape. */
    public static final String SETTINGS = "settings";

    /** Runs the target's health check against its real destination. */
    public static final Operation<Row, Void, TestOutcome> TEST =
        Operation.declare(HohenheimIds.id("test_backup_target"))
            .happened(OperationSentences.of("test_backup_target"))
            .label(HohenheimMicrocopy.BACKUP_TARGET.of("test_connection"))
            .description(HohenheimMicrocopy.HOHENHEIM.of("test_backup_target_description"))
            .icon(Icon.of("plug-circle-check"))
            .one(SUBJECT)
            .verb(HohenheimVerbs.TEST)
            .gate(OperationGate.open())
            .result(TestOutcome.class)
            .facts(OperationFact.REACHES_OUTSIDE)
            .command(CmsCommands.EXTERNAL)
            .register();

    /** Removes a target; refused while a setting still names it (BackupTargetModel's removal hook). */
    public static final Operation<Row, Void, Integer> DELETE =
        Operation.declare(HohenheimIds.id("delete_backup_target"))
            .happened(OperationSentences.of("delete_backup_target"))
            .label(CmsMicrocopy.of("delete"))
            .description(HohenheimMicrocopy.HOHENHEIM.of("delete_backup_target_description"))
            .icon(Icon.TRASH)
            .one(SUBJECT)
            .verb(OperationVerb.DELETE)
            .gate(HohenheimSources.OPERATOR)
            .result(Integer.class)
            .facts(OperationFact.DESTRUCTIVE)
            .command(CmsCommands.TRANSACTIONAL)
            .register();

    /**
     * One health check's outcome.
     *
     * @param name    the target's name
     * @param failure the check's own reason, null when it passed
     */
    public record TestOutcome(@Nullable String name, @Nullable String failure) {
    }

    /**
     * A storage's create and change through this resource's form: its name, its kind by its word and its kind's
     * settings as one map, so a kind a module adds is written with no change here.
     */
    public static final PanelRecordWrites WRITES = PanelRecordWrites.of("backup_target", BackupTargetModel.MODEL_ID,
            SUBJECT, BackupTargetModel.ID, HohenheimSlugs.BACKUP_TARGETS)
        .settings(List.of(BackupTargetModel.NAME))
        // A caller names the kind by its plain word (storage_s3, storage_sftp); the stored key carries the namespace.
        .byWord(BackupTargetModel.KIND)
        // One object in the kind's own shape, judged by that kind when the record is written: an open object here,
        // since its shape follows the kind the same call names.
        .setting(JsonValue.of(SETTINGS).schema(Map.of("type", "object"))
            .help(HohenheimMicrocopy.HELP.of("backup_target_settings")).build(), BackupTargetModel.SETTINGS)
        .build();

    static {
        // The test is a command operation: its outcome is stored as the receipt, so it must serialize, or the
        // invoke fails after the check ran and the operator reads a generic "action failed".
        BlastDrySerializers.addCustomRegistration(registry -> {
            registry.registerSerializer(TestOutcome.class, (outcome, context) -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("name", outcome.name());
                map.put("failure", outcome.failure());
                return map;
            });
            registry.registerReviver(TestOutcome.class,
                (data, context) -> new TestOutcome(data.getString("name"), data.getString("failure")));
        });
        OperationHandlers.attach(TEST).handle(call -> {
            Row target = call.subject();
            String name = target.get(BackupTargetModel.NAME);
            StorageCheck check;
            try {
                check = BackupStorages.of(target).check();
            } catch (RuntimeException unusable) {
                // Violations too: an SSH-era destination host that is unconfirmed or quarantined is exactly what
                // this test exists to surface, and an escaping refusal would render as a generic failure instead.
                return new TestOutcome(name, HohenheimViolations.reasonOf(unusable));
            }
            if (!check.isUsable()) {
                return new TestOutcome(name, check.diagnostic());
            }
            return new TestOutcome(name, null);
        });
        OperationHandlers.attach(DELETE).handle(call -> {
            Models.get(BackupTargetModel.class).delete(call.subject());
            return 1;
        });
    }

    private BackupTargetParts() {
    }

    /** @return the admin backup target resource */
    public static @NonNull PanelResource<Row> admin() {
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(BackupTargetModel.NAME).filterable().copyable().build())
            .column(ColumnSpec.fromField(BackupTargetModel.KIND).filterable().build())
            .column(ColumnSpec.fromField(BackupTargetModel.CREATED_AT).build())
            .build();
        FormSpec form = FormSpec.builder()
            .add(BackupTargetModel.NAME)
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(BackupTargetModel.KIND))
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(BackupTargetModel.SETTINGS))
            .build();
        return PanelResource.builder(HohenheimIds.id("backup_target"), HohenheimSlugs.BACKUP_TARGETS, SUBJECT)
            .label(HohenheimMicrocopy.BACKUP_TARGET.of("plural"))
            .recordLabel(HohenheimMicrocopy.BACKUP_TARGET.of("singular"))
            // Listed inside the settings page's Backups section (board Settings), beside the control-plane backup
            // setting that names one of these targets, never a sidebar row of its own.
            .description(CmsSupport.navHint(HohenheimMicrocopy.BACKUP_TARGET))
            .icon(Icon.of("box-archive"))
            .showInNav(false)
            .standsUnder(SettingsPage.DEFAULT_SLUG)
            .reads(ResourceReads.rows())
            // The name is the only text a target carries; the credentials live in a secret settings blob.
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL).facets().ruleFilters()
                .search(BackupTargetModel.NAME).build())
            .form(ResourceForm.<Row>of(form).build())
            .writes(ResourceMutations.rows().create().update().delete(DELETE)
                .beforeSave(save -> judgeBeforeSave(save.row(), save.isCreate()
                    ? HohenheimStorages.ownerOfCreator(save.access()) : HohenheimStorages.ownerOf(save.row())))
                .build())
            .actions(List.of(PanelAction.<Row, TestOutcome>places(TEST, ActionPlacement.ROW,
                    (request, outcome) -> testWords(Objects.requireNonNull(outcome, "a test answers")))
                .label(HohenheimMicrocopy.BACKUP_TARGET.of("test_connection"))
                .icon(Icon.of("plug-circle-check"))
                .build()))
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions())
            .build();
    }

    /**
     * Refuses saving a storage whose name a settings mount holds, or that its kind's trial finds unusable or asks a
     * confirmation of: the check a Test runs, before anything is written.
     *
     * @throws Violations naming the name, the problem or the fingerprint to pin
     */
    public static void judgeBeforeSave(@NonNull Row row, @NonNull StorageOwner owner) {
        String name = String.valueOf((Object) row.get(BackupTargetModel.NAME));
        if (Storages.has(name)) {
            throw Violations.ofField(BackupTargetModel.NAME.getName(), name,
                HohenheimMicrocopy.VIOLATIONS.of("backup_target_name_taken").withArg("name", name));
        }
        StorageTrial trial = StorageKinds.trial(HohenheimStorages.declarationOf(row, owner));
        if (trial.question() != null) {
            throw Violations.ofField(BackupTargetModel.SETTINGS.getName(), null,
                HohenheimMicrocopy.VIOLATIONS.of("backup_target_confirm")
                    .withArg("question", trial.question().asked().question())
                    .withArg("presented", trial.question().presented()));
        }
        if (!trial.check().isUsable()) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("backup_target_unusable")
                .withArg("name", name)
                .withArg("reason", Objects.requireNonNull(trial.check().problem()).copy())
                .withArg("diagnostic", trial.check().diagnostic()));
        }
    }

    private static @NonNull CmsActionResult testWords(@NonNull TestOutcome outcome) {
        if (outcome.failure() != null) {
            return CmsActionResult.errorToast(HohenheimMicrocopy.BACKUP_TARGET.of("test_failed")
                .withArg("reason", outcome.failure()));
        }
        return CmsActionResult.toast(HohenheimMicrocopy.BACKUP_TARGET.of("test_ok")
            .withArg("name", outcome.name()));
    }
}
