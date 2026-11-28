package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimVerbs;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.backup.BackupStorages;
import be.elevenways.hohenheim.server.backup.HohenheimStorages;
import be.elevenways.protoblast.common.annotation.BlastAutoLoad;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.dry.BlastDrySerializers;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.cms.common.CmsMicrocopy;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.resource.CellMapper;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.RecordOverview;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.cms.server.page.SettingsPage;
import be.elevenways.zenit.common.edit.FieldFormEntryDefaults;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.Nested;
import be.elevenways.zenit.common.edit.JsonValue;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.OperationVerb;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Secret;
import be.elevenways.zenit.common.storage.StorageKindInfo;
import be.elevenways.zenit.common.storage.StorageKindRegistry;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Proposal;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.Availability;
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.server.storage.StorageAdapter;
import be.elevenways.zenit.server.storage.StorageCheck;
import be.elevenways.zenit.server.storage.StorageFact;
import be.elevenways.zenit.server.storage.StorageKinds;
import be.elevenways.zenit.server.storage.StorageOwner;
import be.elevenways.zenit.server.storage.StorageReadings;
import be.elevenways.zenit.server.storage.StorageRoom;
import be.elevenways.zenit.server.storage.StorageTrial;
import be.elevenways.zenit.server.storage.Storages;
import be.elevenways.zenit.server.storage.DeclaredStorage;
import be.elevenways.zenit.server.storage.LocalFolderStorageKind;
import be.elevenways.hohenheim.HohenheimCounts;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.server.files.SiteFiles;
import be.elevenways.zenit.widget.common.builtin.ItemListWidget;
import be.elevenways.zenit.widget.common.data.UsageData;
import be.elevenways.zenit.widget.common.data.WidgetItem;
import be.elevenways.zenit.widget.common.WidgetInstance;
import be.elevenways.zenit.widget.common.WidgetTree;
import be.elevenways.zenit.widget.common.builtin.CardWidget;
import be.elevenways.zenit.widget.common.builtin.FactListWidget;
import be.elevenways.zenit.widget.common.data.WidgetFact;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The storages' parts (board FO128): the Storages page an administrator reads every storage on and its /manage twin
 * a customer adds, tests, edits and deletes their own on, a storage's page (where it is, its room, what uses it), and
 * a live test, the storage's own check against its real destination, never a form-level syntax check.
 *
 * AIDEV-NOTE: a save runs that same check first (the kind's trial) and a storage that fails it, or whose server
 * presents a key the settings do not pin yet, is not saved: the refusal says why, and for a key it names the
 * fingerprint to compare and pin. A name a mount of this server's settings holds is refused, so a record never
 * shadows one ({@link BackupStorages#of}). The list's failure line and room come from the storage layer's last
 * readings ({@link StorageReadings}), never a store dialled per render: a backup, a Test or a save reads them.
 *
 * AIDEV-NOTE: "Everyone may use it" (a shared storage) and the per-storage "may reach a private address" switch of the
 * board have no column (backup_targets holds id, name, kind, settings, timestamps) and no grant expresses "everyone"
 * (CapabilityScopes: sharing is deliberately a column, never a capability), so neither is drawn: an operator's storage
 * follows the boot's private-network opt-in, a tenant's never reaches a private address, and nothing is shared.
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

    /** The list's columns no field backs. */
    static final String FAILURE_COLUMN = "failure";
    static final String WHERE_COLUMN = "where";
    static final String WHERE_DETAIL_COLUMN = "where_detail";
    static final String ADDED_BY_COLUMN = "added_by";
    static final String USED_BY_COLUMN = "used_by";
    static final String ROOM_COLUMN = "room_left";
    static final String KIND_COLUMN = "kind_words";

    /** The administrator's form: every kind the registry holds. */
    private static final FormSpec ADMIN_FORM = FormSpec.builder()
        .createTitle(HohenheimMicrocopy.BACKUP_TARGET.of("add_storage"))
        .add(BackupTargetModel.NAME)
        .add(Select.of(BackupTargetModel.KIND).options(FieldFormEntryDefaults.enumOptionSource(BackupTargetModel.KIND))
            .presentation(Select.Presentation.SEGMENTS).build())
        .add(Nested.of(BackupTargetModel.SETTINGS).schemaFrom(BackupTargetModel.KIND.getName()).unheaded().build())
        .build();

    /** A customer's form: only the kinds a tenant may declare. */
    private static final FormSpec MANAGE_FORM = FormSpec.builder()
        .createTitle(HohenheimMicrocopy.BACKUP_TARGET.of("add_storage"))
        .add(BackupTargetModel.NAME)
        .add(Select.of(BackupTargetModel.KIND).options(OptionSource.supplied(BackupTargetParts::tenantKinds))
            .presentation(Select.Presentation.SEGMENTS).build())
        .add(Nested.of(BackupTargetModel.SETTINGS).schemaFrom(BackupTargetModel.KIND.getName()).unheaded().build())
        .build();

    /** The add and edit form's Test on the administrator's page: the save's own check, saving nothing. */
    public static final Operation<Void, OperationInput.Values, String> TRY = tryOn("try_backup_target", ADMIN_FORM);

    /** The same Test on a customer's page, over their form. */
    public static final Operation<Void, OperationInput.Values, String> TRY_MANAGED =
        tryOn("try_managed_backup_target", MANAGE_FORM);

    /** Runs the storage's check against its real destination, and reads its room. */
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

    /** Removes a storage its manager holds; refused while anything uses it (BackupTargetModel's removal hook). */
    public static final Operation<Row, Void, Integer> DELETE =
        Operation.declare(HohenheimIds.id("delete_backup_target"))
            .happened(OperationSentences.of("delete_backup_target"))
            .label(CmsMicrocopy.of("delete"))
            .description(HohenheimMicrocopy.HOHENHEIM.of("delete_backup_target_description"))
            .icon(Icon.TRASH)
            .one(SUBJECT)
            .verb(OperationVerb.DELETE)
            .gate(OperationGate.open().subjectCapability(HohenheimCapabilities.MANAGE))
            .result(Integer.class)
            .facts(OperationFact.DESTRUCTIVE)
            .command(CmsCommands.TRANSACTIONAL)
            .register();

    /**
     * One health check's outcome.
     *
     * @param name    the storage's name
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
            StorageAdapter storage;
            try {
                storage = BackupStorages.of(target);
                check = StorageReadings.check(storage);
            } catch (RuntimeException unusable) {
                // Violations too: an SSH-era destination host that is unconfirmed or quarantined is exactly what
                // this test exists to surface, and an escaping refusal would render as a generic failure instead.
                return new TestOutcome(name, HohenheimViolations.reasonOf(unusable));
            }
            if (!check.isUsable()) {
                return new TestOutcome(name, check.diagnostic());
            }
            try {
                // The room the storage's page and the list show next; a store that passed its check but cannot
                // tell its room now keeps the last room it told.
                StorageReadings.room(storage);
            } catch (RuntimeException unread) {
                Blast.log("STORAGE: the room of", name, "could not be read:", unread.getMessage());
            }
            return new TestOutcome(name, null);
        });
        OperationHandlers.attach(DELETE)
            // Offered dead with what uses it: one grouped read for the whole page of storages.
            .availability(Availability.batch((storages, access) -> inUse(storages)))
            .handle(call -> {
                Models.get(BackupTargetModel.class).delete(call.subject());
                StorageReadings.forget(String.valueOf((Object) call.subject().get(BackupTargetModel.NAME)));
                return 1;
            });
        for (Operation<Void, OperationInput.Values, String> trying : List.of(TRY, TRY_MANAGED)) {
            OperationHandlers.attach(trying).handle(call -> {
                OperationInput.Values values = Objects.requireNonNull(call.input(), "a Test takes the form");
                Row row = Models.get(BackupTargetModel.class).createEmptyRow();
                row.set(BackupTargetModel.NAME, values.get(BackupTargetModel.NAME));
                row.set(BackupTargetModel.KIND, values.get(BackupTargetModel.KIND));
                row.set(BackupTargetModel.SETTINGS, revealed(RawValues.map(values.get(SETTINGS))));
                DeclaredStorage declaration = HohenheimStorages.declarationOf(row,
                    HohenheimStorages.ownerOfCreator(call.access()));
                refuseUnfit(declaration, StorageKinds.trial(declaration));
                AccessContext access = Objects.requireNonNull(call.access(), "a Test has its caller");
                List<StorageFact> listed = new ArrayList<>();
                for (StorageFact fact : StorageKinds.facts(declaration)) {
                    if (fact.listed() != null) {
                        listed.add(fact);
                    }
                }
                String detail = place(listed, access, false);
                return detail != null ? detail : Objects.requireNonNullElse(place(listed, access, true),
                    declaration.name());
            });
        }
    }

    private BackupTargetParts() {
    }

    /** @return the administrator's Storages page: every storage people declared, who added it and what uses it */
    public static @NonNull PanelResource<Row> admin() {
        return base(HohenheimIds.id("backup_target"), ADMIN_FORM, TRY, true)
            // Listed inside the settings page's Backups section (board Settings), beside the settings that name one
            // of these storages, never a sidebar row of its own.
            .showInNav(false)
            .standsUnder(SettingsPage.DEFAULT_SLUG)
            .writes(ResourceMutations.rows().create().update().delete(DELETE)
                .beforeSave(save -> judgeBeforeSave(save.row(), save.isCreate()
                    ? HohenheimStorages.ownerOfCreator(save.access()) : HohenheimStorages.ownerOf(save.row())))
                .build())
            .tabs(ResourceTabs.<Row>of(List.of(overview())).withHistory().withContributions())
            .build();
    }

    /**
     * @return the /manage twin: the storages the customer added, through a form offering only the kinds a tenant may
     *         declare; the creator's manage grant IS the ownership, as for a tenant's git connection
     */
    public static @NonNull PanelResource<Row> manage() {
        return ManageTwin.reached(base(ManageTwin.id("backup_target"), MANAGE_FORM, TRY_MANAGED, false),
                TenantScopes.MANAGED_STORAGES,
                ResourceTabs.<Row>of(List.of(overview())).withContributions())
            .standsUnder(HohenheimSlugs.APPS)
            .writes(ResourceMutations.rows().create().update().delete(DELETE)
                .beforeSave(save -> judgeBeforeSave(save.row(), save.isCreate()
                    ? HohenheimStorages.ownerOfCreator(save.access()) : HohenheimStorages.ownerOf(save.row())))
                .afterSave(save -> {
                    if (save.isCreate()) {
                        HohenheimAccess.grantCreatorManage(BackupTargetModel.MODEL_ID,
                            Integer.parseInt(String.valueOf(save.key())), save.access());
                    }
                })
                .build())
            .build();
    }

    /**
     * The parts both twins share: identity, the list as drawn and its page read, the form and the test.
     *
     * @param settingsCard whether the list shows the storages the server's settings declare above the records
     */
    private static PanelResource.@NonNull Builder<Row> base(@NonNull Identifier id, @NonNull FormSpec form,
                                                           @NonNull Operation<Void, OperationInput.Values,
                                                               String> trying,
                                                           boolean settingsCard) {
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(BackupTargetModel.NAME).subtext(FAILURE_COLUMN).build())
            .column(ColumnSpec.virtual(FAILURE_COLUMN, HohenheimMicrocopy.BACKUP_TARGET.of("column_failure"))
                .hidden().build())
            // The kind in its own words, plain text as the board draws it: never a coloured pill.
            .column(ColumnSpec.virtual(KIND_COLUMN, HohenheimMicrocopy.HOHENHEIM_FIELD.of("kind")).build())
            .column(ColumnSpec.virtual(WHERE_COLUMN, HohenheimMicrocopy.BACKUP_TARGET.of("column_where"))
                .subtext(WHERE_DETAIL_COLUMN).build())
            .column(ColumnSpec.virtual(WHERE_DETAIL_COLUMN, HohenheimMicrocopy.BACKUP_TARGET.of("column_where"))
                .hidden().build())
            .column(ColumnSpec.virtual(ADDED_BY_COLUMN, HohenheimMicrocopy.BACKUP_TARGET.of("column_added_by"))
                .build())
            .column(ColumnSpec.virtual(USED_BY_COLUMN, HohenheimMicrocopy.BACKUP_TARGET.of("column_used_by"))
                .build())
            .column(ColumnSpec.virtual(ROOM_COLUMN, HohenheimMicrocopy.BACKUP_TARGET.of("column_room_left"))
                .build())
            .build();
        ResourceList.Builder<Row> list = ResourceList.rows(table).chrome(ListChrome.MINIMAL)
            .emptyDescription(HohenheimMicrocopy.BACKUP_TARGET.of("empty"));
        if (settingsCard) {
            list.widgets(scope -> inSettings(scope.accessContext()));
        }
        // The one-record read of each computed column; a listed page answers from the page batch.
        for (String column : List.of(FAILURE_COLUMN, KIND_COLUMN, WHERE_COLUMN, WHERE_DETAIL_COLUMN, ADDED_BY_COLUMN,
                USED_BY_COLUMN, ROOM_COLUMN)) {
            ColumnSpec spec = Objects.requireNonNull(table.column(column), column);
            list.computed(spec, (storage, request) -> cells(List.of(storage), request.access()).cell(storage, spec));
        }
        return PanelResource.builder(id, HohenheimSlugs.BACKUP_TARGETS, SUBJECT)
            .label(HohenheimMicrocopy.BACKUP_TARGET.of("plural"))
            .recordLabel(HohenheimMicrocopy.BACKUP_TARGET.of("singular"))
            .description(CmsSupport.navHint(HohenheimMicrocopy.BACKUP_TARGET))
            .icon(Icon.of("box-archive"))
            .reads(ResourceReads.rows().pageBatch(BackupTargetParts::cells))
            .list(list.build())
            .form(ResourceForm.<Row>of(form).landingTab(RecordOverview.SLUG).savesAtFootOnly().build())
            .actions(List.of(PanelAction.<Row, TestOutcome>places(TEST, ActionPlacement.ROW,
                    (request, outcome) -> testWords(Objects.requireNonNull(outcome, "a test answers")))
                .label(HohenheimMicrocopy.BACKUP_TARGET.of("test_connection"))
                .icon(Icon.of("plug-circle-check"))
                .build(),
                PanelAction.<Row, String>places(trying, ActionPlacement.FORM,
                    (request, where) -> CmsActionResult.successToast(HohenheimMicrocopy.BACKUP_TARGET.of("try_ok")
                        .withArg("where", Objects.requireNonNull(where, "a Test says where"))
                        .withArg("at", Now.instant()))
                        .withTitle(HohenheimMicrocopy.BACKUP_TARGET.of("try_ok_title")))
                .label(HohenheimMicrocopy.BACKUP_TARGET.of("test_connection"))
                .build()));
    }

    /** @return the Test of a storage's form as it stands, over {@code form}: the save's check, writing nothing */
    private static @NonNull Operation<Void, OperationInput.Values, String> tryOn(@NonNull String name,
                                                                                  @NonNull FormSpec form) {
        return Operation.declare(HohenheimIds.id(name))
            .label(HohenheimMicrocopy.BACKUP_TARGET.of("test_connection"))
            .description(HohenheimMicrocopy.HOHENHEIM.of("test_backup_target_description"))
            .noSubject()
            .gate(OperationGate.open())
            .input(OperationInput.derived(form))
            .facts(OperationFact.READ_ONLY, OperationFact.REACHES_OUTSIDE)
            .result(String.class)
            .register();
    }

    /** @return the settings with each secret the form holds as its value, as a kind reads them */
    private static @NonNull Map<String, Object> revealed(@NonNull Map<String, Object> settings) {
        Map<String, Object> revealed = new LinkedHashMap<>();
        settings.forEach((key, value) -> revealed.put(key, value instanceof Secret secret ? secret.reveal() : value));
        return revealed;
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
        DeclaredStorage declaration = HohenheimStorages.declarationOf(row, owner);
        StorageTrial trial = StorageKinds.trial(declaration);
        if (row.get(BackupTargetModel.ID) != null) {
            // A saved storage's check is what its list line says next, a failed save's included.
            StorageReadings.recordCheck(name, trial.check());
        }
        refuseUnfit(declaration, trial);
    }

    /**
     * Refuses a storage its kind's trial asks a confirmation of, proposing the value its server presented for the
     * settings entry the kind names (a form offers the button saving it), or finds unusable.
     *
     * @throws Violations the proposal, or the problem in the trial's words
     */
    private static void refuseUnfit(@NonNull DeclaredStorage declaration, @NonNull StorageTrial trial) {
        StorageTrial.Question question = trial.question();
        if (question != null) {
            String entry = question.asked().entry();
            StorageKindInfo kind = StorageKindRegistry.REGISTRY.get(declaration.kind());
            Field<?, ?> field = kind == null ? null : kind.getSchema().getField(entry);
            throw Violations.proposing(entry, new Proposal(SETTINGS + "." + entry, question.presented(),
                question.asked().question(), question.asked().help(),
                field == null ? null : FieldLabels.labelFor(field), question.asked().confirm()));
        }
        if (!trial.check().isUsable()) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("backup_target_unusable")
                .withArg("name", declaration.name())
                .withArg("reason", Objects.requireNonNull(trial.check().problem()).copy())
                .withArg("diagnostic", trial.check().diagnostic()));
        }
    }

    /** @return the kinds a tenant may declare, in the registry's own words and order */
    private static @NonNull List<FieldOption<String>> tenantKinds() {
        List<FieldOption<String>> kinds = new ArrayList<>();
        for (FieldOption<String> option : FieldFormEntryDefaults.enumOptionSource(BackupTargetModel.KIND)
                .resolve(null)) {
            StorageKindInfo kind = option.value() == null ? null
                : StorageKindRegistry.REGISTRY.get(Identifier.tryParse(option.value()));
            if (kind != null && kind.tenantsMayDeclare()) {
                kinds.add(option);
            }
        }
        return kinds;
    }

    /** @return each storage's refusal of a delete while anything uses it, in the storages' order */
    private static @NonNull List<@Nullable Microcopy> inUse(@NonNull List<? extends Row> storages) {
        Map<Integer, StorageUsage.Counts> usage = StorageUsage.of(List.copyOf(storages));
        List<Microcopy> reasons = new ArrayList<>(storages.size());
        for (Row storage : storages) {
            StorageUsage.Counts counts = usage.getOrDefault(storage.get(BackupTargetModel.ID),
                StorageUsage.Counts.NONE);
            reasons.add(counts.inUse() ? HohenheimMicrocopy.BACKUP_TARGET.of("in_use")
                .withArg("used", Objects.requireNonNull(counts.words(), "a used storage names its users")) : null);
        }
        return reasons;
    }

    /**
     * The list's computed cells for one page of storages, in a fixed number of reads: one for their owners' grants,
     * one for those owners' names, and {@link StorageUsage}'s; the failure line and the room are the storage layer's
     * last readings, read from memory.
     */
    static @NonNull CellMapper<Row> cells(@NonNull List<Row> storages, @NonNull AccessContext access) {
        List<Object> ids = new ArrayList<>(storages.size());
        for (Row storage : storages) {
            ids.add(storage.get(BackupTargetModel.ID));
        }
        Map<String, Set<String>> owners = HohenheimAccess.manageSubjectsOfAll(BackupTargetModel.MODEL_ID, ids);
        List<String> subjects = new ArrayList<>();
        if (owners != null) {
            owners.values().forEach(subjects::addAll);
        }
        Map<String, String> labels = subjects.isEmpty() ? Map.of() : HohenheimAccess.subjectLabels(subjects);
        Set<String> mine = HohenheimAccess.creationOwnerSubjects(access);
        Map<Integer, StorageUsage.Counts> usage = StorageUsage.of(storages);
        Map<Object, List<StorageFact>> where = new HashMap<>();
        return (storage, column) -> {
            String name = String.valueOf((Object) storage.get(BackupTargetModel.NAME));
            Object id = storage.get(BackupTargetModel.ID);
            return switch (column.name()) {
                case FAILURE_COLUMN -> CellMapper.Computed.orEmpty(failure(name, access));
                case KIND_COLUMN -> {
                    StorageKindInfo kind = StorageKindRegistry.REGISTRY.get(HohenheimStorages.kindOf(storage));
                    yield CellMapper.Computed.orEmpty(kind == null ? null : access.text(kind.getLabel()));
                }
                case WHERE_COLUMN -> CellMapper.Computed.orEmpty(place(where.computeIfAbsent(id,
                    ignored -> listedFacts(storage)), access, true));
                case WHERE_DETAIL_COLUMN -> CellMapper.Computed.orEmpty(place(where.computeIfAbsent(id,
                    ignored -> listedFacts(storage)), access, false));
                case ADDED_BY_COLUMN -> CellMapper.Computed.orEmpty(addedBy(owners == null ? null
                    : owners.get(String.valueOf(id)), labels, mine, access));
                case USED_BY_COLUMN -> {
                    Microcopy words = usage.getOrDefault(id, StorageUsage.Counts.NONE).words();
                    yield CellMapper.Computed.orEmpty(words == null ? null : access.text(words));
                }
                case ROOM_COLUMN -> CellMapper.Computed.orEmpty(room(name, access));
                default -> null;
            };
        };
    }

    /** @return "Sign-in refused since 27-11-2026 03:00" while the storage's checks fail, else null */
    private static @Nullable String failure(@NonNull String name, @NonNull AccessContext access) {
        StorageReadings.Reading reading = StorageReadings.of(name);
        StorageCheck check = reading.check();
        if (check == null || check.isUsable() || reading.failingSince() == null) {
            return null;
        }
        return access.text(HohenheimMicrocopy.BACKUP_TARGET.of("failing_since")
            .withArg("reason", Objects.requireNonNull(check.problem()).copy())
            .withArg("since", reading.failingSince()));
    }

    /** @return the room the storage last told, "318 GB of 500 GB", else null */
    private static @Nullable String room(@NonNull String name, @NonNull AccessContext access) {
        StorageRoom room = StorageReadings.of(name).room();
        return room == null ? null : access.text(HohenheimMicrocopy.BACKUP_TARGET.of("room_of")
            .withArg("free", room.freeBytes()).withArg("total", room.totalBytes()));
    }

    /**
     * @param subjects the storage's manage-grant subjects, empty for the operator's, null when unreadable
     * @return "You" for the viewer's own, the owner's name, "An administrator" for the operator's
     */
    private static @Nullable String addedBy(@Nullable Set<String> subjects, @NonNull Map<String, String> labels,
                                            @NonNull Set<String> mine, @NonNull AccessContext access) {
        if (subjects == null) {
            return null;
        }
        if (subjects.isEmpty()) {
            return access.text(HohenheimMicrocopy.BACKUP_TARGET.of("added_by_administrator"));
        }
        for (String subject : subjects) {
            if (mine.contains(subject)) {
                return access.text(HohenheimMicrocopy.BACKUP_TARGET.of("added_by_you"));
            }
        }
        List<String> names = new ArrayList<>();
        for (String subject : subjects) {
            names.add(labels.getOrDefault(subject, subject));
        }
        return String.join(", ", names);
    }

    /** @return the facts the storage's kind names in a list, none for a storage whose settings cannot be read */
    private static @NonNull List<StorageFact> listedFacts(@NonNull Row storage) {
        List<StorageFact> listed = new ArrayList<>();
        for (StorageFact fact : facts(storage)) {
            if (fact.listed() != null) {
                listed.add(fact);
            }
        }
        return listed;
    }

    /** @return every fact the storage's kind shows of where it is, none when its settings cannot be read */
    private static @NonNull List<StorageFact> facts(@NonNull Row storage) {
        try {
            // Facts read only the kind and its settings; the owner decides reach, which no fact shows.
            return StorageKinds.facts(HohenheimStorages.declarationOf(storage, StorageOwner.OPERATOR));
        } catch (RuntimeException unreadable) {
            return List.of();
        }
    }

    /**
     * @param first the place itself (the first listed fact, in its kind's words) when true, else the rest as one line
     *              under it ("bucket tadoma-releases, folder site")
     */
    static @Nullable String place(@NonNull List<StorageFact> listed, @NonNull AccessContext access, boolean first) {
        if (listed.isEmpty()) {
            return null;
        }
        if (first) {
            return access.text(Objects.requireNonNull(listed.getFirst().listed()));
        }
        List<String> rest = new ArrayList<>();
        for (StorageFact fact : listed.subList(1, listed.size())) {
            rest.add(access.text(Objects.requireNonNull(fact.listed())));
        }
        return rest.isEmpty() ? null : String.join(", ", rest);
    }

    /**
     * The storages the server's settings declare (board FO128 screen 1, "From the server's settings"): this server's
     * own site files first, then each storage line of its settings, read-only, each with where it is in its kind's
     * words, the sites keeping their files there and the room it last told. A module's own home (media, content) is
     * no storage a person chooses and is not here ({@link Storages#inSettings}).
     *
     * AIDEV-NOTE: drawn as a card above the records, not as rows of the records' table: a list's rows are records of
     * its model, and these are no row of backup_targets. One list over both needs a list source uniting records with
     * declared read-only rows (zenit-cms typed stores); until the Storages page reads one, the card says the same
     * facts per storage as a row does.
     */
    static @NonNull WidgetTree inSettings(@NonNull AccessContext access) {
        List<DeclaredStorage> declared = new ArrayList<>();
        Map<String, Microcopy> named = new HashMap<>();
        try {
            SiteFiles.storageOf(null);
            declared.add(DeclaredStorage.inSettings(SiteFiles.STORAGE, LocalFolderStorageKind.ID,
                Map.of(LocalFolderStorageKind.FOLDER.getName(),
                    Storages.localDirectory(HohenheimSettings.Files.SITE_FILES_DIRECTORY).toString())));
            named.put(SiteFiles.STORAGE, HohenheimMicrocopy.BACKUP_TARGET.of("site_files_own"));
        } catch (RuntimeException unmounted) {
            Blast.log("STORAGE: this server's site files are not mounted:", unmounted.getMessage());
        }
        declared.addAll(Storages.inSettings());
        Map<String, Long> sites = StorageUsage.sitesByStorage();
        List<WidgetItem> items = new ArrayList<>();
        for (DeclaredStorage storage : declared) {
            List<String> line = new ArrayList<>();
            StorageKindInfo kind = StorageKindRegistry.REGISTRY.get(storage.kind());
            if (kind != null) {
                line.add(access.text(kind.getLabel()));
            }
            List<StorageFact> listed = new ArrayList<>();
            try {
                for (StorageFact fact : StorageKinds.facts(storage)) {
                    if (fact.listed() != null) {
                        listed.add(fact);
                    }
                }
            } catch (RuntimeException unreadable) {
                Blast.log("STORAGE: the facts of", storage.name(), "cannot be read:", unreadable.getMessage());
            }
            String where = place(listed, access, true);
            String detail = place(listed, access, false);
            for (String part : new String[] {where, detail}) {
                if (part != null) {
                    line.add(part);
                }
            }
            long used = sites.getOrDefault(storage.name(), 0L);
            if (used > 0) {
                line.add(access.text(HohenheimCounts.of("sites", used)));
            }
            Microcopy name = named.get(storage.name());
            WidgetItem item = new WidgetItem(name == null ? storage.name() : access.text(name), null, List.of())
                .withFacts(line);
            String room = room(storage.name(), access);
            items.add(room == null ? item : item.withAside(room));
        }
        WidgetInstance card = new WidgetInstance(CardWidget.ID,
            Map.of("title", HohenheimMicrocopy.BACKUP_TARGET.of("in_settings_title"),
                "lead", HohenheimMicrocopy.BACKUP_TARGET.of("in_settings_lead")),
            new WidgetTree(List.of(ItemListWidget.of(items, null))));
        return new WidgetTree(List.of(card));
    }

    /** A storage's page: where it is, the room it last told and what uses it. */
    private static @NonNull RecordOverview<Row> overview() {
        return RecordOverview.<Row>fields(RecordOverview.SLUG, HohenheimMicrocopy.BACKUP_TARGET.of("overview"))
            .withoutFields()
            .widgets(BackupTargetParts::widgets);
    }

    /**
     * @return each secret the storage's kind holds and the storage stores, saying it is set and never shown: a
     *         secret is replaced through the form's own stored-secret entry, never read back here
     */
    private static @NonNull List<WidgetFact> secrets(@NonNull Row storage, @NonNull AccessContext access) {
        // Read whole: a page's record may come projected or masked, and only whether a secret is stored is shown.
        Row whole = Models.get(BackupTargetModel.class).findById(storage.get(BackupTargetModel.ID));
        List<WidgetFact> secrets = new ArrayList<>();
        StorageKindInfo kind = whole == null ? null : StorageKindRegistry.REGISTRY.get(HohenheimStorages.kindOf(whole));
        if (kind == null) {
            return secrets;
        }
        Map<String, Object> settings = RawValues.map(whole.get(BackupTargetModel.SETTINGS));
        for (Field<?, ?> field : kind.getSchema().getFields().values()) {
            Object value = settings.get(field.getName());
            if (field.isSecret() && value != null && !String.valueOf(value).isEmpty()) {
                secrets.add(WidgetFact.of(access.text(FieldLabels.labelFor(field)),
                    access.text(HohenheimMicrocopy.BACKUP_TARGET.of("secret_stored"))));
            }
        }
        return secrets;
    }

    private static @NonNull WidgetTree widgets(@NonNull Row storage, @NonNull AccessContext access) {
        String panelSlug = CmsSupport.panelSlug(access.conduit());
        String name = String.valueOf((Object) storage.get(BackupTargetModel.NAME));
        List<WidgetInstance> main = new ArrayList<>();
        List<WidgetFact> where = new ArrayList<>();
        for (StorageFact fact : facts(storage)) {
            where.add(WidgetFact.of(access.text(fact.label()), fact.value()));
        }
        main.add(CardWidget.ofFacts(HohenheimMicrocopy.BACKUP_TARGET.of("card_where"), where));
        // One card per secret the storage holds, named by it as the board draws "Password": set, never shown.
        for (WidgetFact secret : secrets(storage, access)) {
            main.add(CardWidget.ofFacts(Microcopy.literal(secret.label()), List.of(WidgetFact.of(
                access.text(HohenheimMicrocopy.BACKUP_TARGET.of("secret_stored")),
                access.text(HohenheimMicrocopy.BACKUP_TARGET.of("secret_never_shown"))))));
        }
        StorageUsage.Counts counts = StorageUsage.of(storage);
        List<WidgetFact> used = StorageUsage.facts(storage, panelSlug, access);
        main.add(counts.inUse()
            ? CardWidget.ofFacts(HohenheimMicrocopy.BACKUP_TARGET.of("card_used_by"),
                HohenheimMicrocopy.BACKUP_TARGET.of("in_use_lead"), used)
            : CardWidget.ofFacts(HohenheimMicrocopy.BACKUP_TARGET.of("card_used_by"), used));

        List<WidgetInstance> side = new ArrayList<>();
        StorageReadings.Reading reading = StorageReadings.of(name);
        List<WidgetFact> room = new ArrayList<>();
        Microcopy roomLead = null;
        if (reading.room() != null && reading.roomReadAt() != null) {
            roomLead = HohenheimMicrocopy.BACKUP_TARGET.of("room_read_lead").withArg("at", reading.roomReadAt());
            room.add(WidgetFact.of(access.text(HohenheimMicrocopy.BACKUP_TARGET.of("room_left")),
                access.text(HohenheimMicrocopy.BACKUP_TARGET.of("room_left_value")
                    .withArg("free", reading.room().freeBytes()))));
            room.add(WidgetFact.of(access.text(HohenheimMicrocopy.BACKUP_TARGET.of("room_used")),
                access.text(HohenheimMicrocopy.BACKUP_TARGET.of("room_used_value")
                    .withArg("used", reading.room().totalBytes() - reading.room().freeBytes())
                    .withArg("total", reading.room().totalBytes()))));
        } else {
            room.add(WidgetFact.of(access.text(HohenheimMicrocopy.BACKUP_TARGET.of("room_left")),
                access.text(HohenheimMicrocopy.BACKUP_TARGET.of("room_unknown_detail"))));
        }
        String failing = failure(name, access);
        if (failing != null) {
            room.add(WidgetFact.of(access.text(HohenheimMicrocopy.BACKUP_TARGET.of("last_check")), failing));
        }
        Microcopy roomTitle = HohenheimMicrocopy.BACKUP_TARGET.of("card_room");
        if (roomLead != null && reading.room().totalBytes() > 0) {
            // The board's usage bar over the facts: the framework gauge, so the reading's own sizes say it.
            long usedBytes = reading.room().totalBytes() - reading.room().freeBytes();
            WidgetInstance gauge = AppOverview.gauge(HohenheimMicrocopy.BACKUP_TARGET.of("room_used"),
                UsageData.bytes(usedBytes, reading.room().totalBytes(), reading.roomReadAt().toString()));
            side.add(CardWidget.of(roomTitle, roomLead, new WidgetTree(List.of(gauge, FactListWidget.of(room)))));
        } else {
            side.add(roomLead == null ? CardWidget.ofFacts(roomTitle, room)
                : CardWidget.ofFacts(roomTitle, roomLead, room));
        }
        return AppOverview.compose(List.of(), main, side);
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
