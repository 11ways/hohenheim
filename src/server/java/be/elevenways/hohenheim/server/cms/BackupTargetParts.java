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
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.JsonValue;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationVerb;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.storage.StorageKindInfo;
import be.elevenways.zenit.common.storage.StorageKindRegistry;
import be.elevenways.zenit.common.ui.Icon;
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
import be.elevenways.zenit.widget.common.WidgetInstance;
import be.elevenways.zenit.widget.common.WidgetTree;
import be.elevenways.zenit.widget.common.builtin.CardWidget;
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
    }

    private BackupTargetParts() {
    }

    /** @return the administrator's Storages page: every storage people declared, who added it and what uses it */
    public static @NonNull PanelResource<Row> admin() {
        FormSpec form = FormSpec.builder()
            .add(BackupTargetModel.NAME)
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(BackupTargetModel.KIND))
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(BackupTargetModel.SETTINGS))
            .build();
        return base(HohenheimIds.id("backup_target"), form)
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
        FormSpec form = FormSpec.builder()
            .add(BackupTargetModel.NAME)
            .add(Select.of(BackupTargetModel.KIND).options(OptionSource.supplied(BackupTargetParts::tenantKinds))
                .build())
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(BackupTargetModel.SETTINGS))
            .build();
        return ManageTwin.reached(base(ManageTwin.id("backup_target"), form), TenantScopes.MANAGED_STORAGES,
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

    /** The parts both twins share: identity, the list as drawn and its page read, the form and the test. */
    private static PanelResource.@NonNull Builder<Row> base(@NonNull Identifier id, @NonNull FormSpec form) {
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(BackupTargetModel.NAME).filterable().subtext(FAILURE_COLUMN).build())
            .column(ColumnSpec.virtual(FAILURE_COLUMN, HohenheimMicrocopy.BACKUP_TARGET.of("column_failure"))
                .hidden().build())
            .column(ColumnSpec.fromField(BackupTargetModel.KIND).filterable().build())
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
            .emptyDescription(HohenheimMicrocopy.BACKUP_TARGET.of("empty"))
            .search(BackupTargetModel.NAME);
        // The one-record read of each computed column; a listed page answers from the page batch.
        for (String column : List.of(FAILURE_COLUMN, WHERE_COLUMN, WHERE_DETAIL_COLUMN, ADDED_BY_COLUMN,
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
            .form(ResourceForm.<Row>of(form).landingTab(RecordOverview.SLUG).build())
            .actions(List.of(PanelAction.<Row, TestOutcome>places(TEST, ActionPlacement.ROW,
                    (request, outcome) -> testWords(Objects.requireNonNull(outcome, "a test answers")))
                .label(HohenheimMicrocopy.BACKUP_TARGET.of("test_connection"))
                .icon(Icon.of("plug-circle-check"))
                .build()));
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
        if (row.get(BackupTargetModel.ID) != null) {
            // A saved storage's check is what its list line says next, a failed save's included.
            StorageReadings.recordCheck(name, trial.check());
        }
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

    /** @return the facts the storage's kind marks listed, none for a storage whose settings cannot be read */
    private static @NonNull List<StorageFact> listedFacts(@NonNull Row storage) {
        List<StorageFact> listed = new ArrayList<>();
        for (StorageFact fact : facts(storage)) {
            if (fact.listed()) {
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
     * @param first the place itself (the first listed fact's value) when true, else the rest as one line under it
     *              ("bucket tadoma-releases, folder site")
     */
    private static @Nullable String place(@NonNull List<StorageFact> listed, @NonNull AccessContext access,
                                          boolean first) {
        if (listed.isEmpty()) {
            return null;
        }
        if (first) {
            return listed.getFirst().value();
        }
        List<String> rest = new ArrayList<>();
        for (StorageFact fact : listed.subList(1, listed.size())) {
            rest.add(access.text(HohenheimMicrocopy.BACKUP_TARGET.of("where_fact")
                .withArg("label", fact.label()).withArg("value", fact.value())));
        }
        return rest.isEmpty() ? null : String.join(", ", rest);
    }

    /** A storage's page: where it is, the room it last told and what uses it. */
    private static @NonNull RecordOverview<Row> overview() {
        return RecordOverview.<Row>fields(RecordOverview.SLUG, HohenheimMicrocopy.BACKUP_TARGET.of("overview"))
            .withoutFields()
            .widgets(BackupTargetParts::widgets);
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
        StorageUsage.Counts counts = StorageUsage.of(storage);
        List<WidgetFact> used = StorageUsage.facts(storage, panelSlug, access);
        main.add(counts.inUse()
            ? CardWidget.ofFacts(HohenheimMicrocopy.BACKUP_TARGET.of("card_used_by"),
                HohenheimMicrocopy.BACKUP_TARGET.of("in_use_lead"), used)
            : CardWidget.ofFacts(HohenheimMicrocopy.BACKUP_TARGET.of("card_used_by"), used));

        List<WidgetInstance> side = new ArrayList<>();
        StorageReadings.Reading reading = StorageReadings.of(name);
        List<WidgetFact> room = new ArrayList<>();
        if (reading.room() != null && reading.roomReadAt() != null) {
            room.add(WidgetFact.of(access.text(HohenheimMicrocopy.BACKUP_TARGET.of("room_left")),
                access.text(HohenheimMicrocopy.BACKUP_TARGET.of("room_left_value")
                    .withArg("free", reading.room().freeBytes()))));
            room.add(WidgetFact.of(access.text(HohenheimMicrocopy.BACKUP_TARGET.of("room_used")),
                access.text(HohenheimMicrocopy.BACKUP_TARGET.of("room_used_value")
                    .withArg("used", reading.room().totalBytes() - reading.room().freeBytes())
                    .withArg("total", reading.room().totalBytes()))));
            room.add(WidgetFact.instant(access.text(HohenheimMicrocopy.BACKUP_TARGET.of("room_read_at")),
                reading.roomReadAt().toString()));
        } else {
            room.add(WidgetFact.of(access.text(HohenheimMicrocopy.BACKUP_TARGET.of("room_unknown")),
                access.text(HohenheimMicrocopy.BACKUP_TARGET.of("room_unknown_detail"))));
        }
        String failing = failure(name, access);
        if (failing != null) {
            room.add(WidgetFact.of(access.text(HohenheimMicrocopy.BACKUP_TARGET.of("last_check")), failing));
        }
        side.add(CardWidget.ofFacts(HohenheimMicrocopy.BACKUP_TARGET.of("card_room"), room));
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
