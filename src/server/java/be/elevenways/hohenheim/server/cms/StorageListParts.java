package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimCounts;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.files.SiteFiles;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.CmsMicrocopy;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.resource.CellMapper;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.RecordOverview;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.StorePages;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.cms.common.schema.TableView;
import be.elevenways.zenit.cms.server.page.SettingsPage;
import be.elevenways.zenit.common.data.RecordPage;
import be.elevenways.zenit.common.live.LiveFeed;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.storage.StorageKindInfo;
import be.elevenways.zenit.common.storage.StorageKindRegistry;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.storage.DeclaredStorage;
import be.elevenways.zenit.server.storage.LocalFolderStorageKind;
import be.elevenways.zenit.server.storage.StorageFact;
import be.elevenways.zenit.server.storage.StorageKinds;
import be.elevenways.zenit.server.storage.Storages;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The administrator's one Storages list (board FO128 screen 1): the storages the server's settings declare, read-only
 * rows saying so, then every storage people added, records of {@link BackupTargetParts#admin}.
 *
 * AIDEV-NOTE: a typed store over both, loaded whole per request and paged in memory ({@link StorePages#inMemory}) as
 * the Apps list is: storages are counted in tens. A record's row opens its page and draws the storage entry's own Test
 * ({@link PanelAction#fromEntry}, so its gate and invoke stay that entry's) and an Edit link; a settings row opens
 * nothing and offers neither, since the settings file is where it changes. The record cells are the storage entry's
 * own page batch ({@link BackupTargetParts#cells}), so both lists read the same words in the same fixed reads.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class StorageListParts {

    private static final StringField NAME = StringField.builder("name")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("name")).build();

    /** The columns a row fills beside its name, each worded as the storage entry's own list words it. */
    private static final List<String> CELLS = List.of(BackupTargetParts.FAILURE_COLUMN,
        BackupTargetParts.KIND_COLUMN, BackupTargetParts.WHERE_COLUMN, BackupTargetParts.WHERE_DETAIL_COLUMN,
        BackupTargetParts.ADDED_BY_COLUMN, BackupTargetParts.USED_BY_COLUMN, BackupTargetParts.ROOM_COLUMN);

    /** The list hears every storage record and the sites choosing one, so a change there reaches it at once. */
    private static final LiveFeed FEED = LiveFeed.of(AccessContext::isAccount, BackupTargetModel.MODEL_ID,
        SiteModel.MODEL_ID);

    private static final TableSpec<Listed> TABLE = table();

    /** One row of the list: its key, its name and the words of each of its columns. */
    sealed interface Listed permits Stored, Declared {

        @NonNull String key();

        @NonNull Map<String, Object> values();
    }

    /** A storage a person added: a record of the storage entry. */
    record Stored(@NonNull String key, @NonNull Map<String, Object> values, @NonNull Object id) implements Listed {
    }

    /** A storage the server's settings declare: read-only here. */
    record Declared(@NonNull String key, @NonNull Map<String, Object> values) implements Listed {
    }

    private StorageListParts() {
    }

    /** @return the administrator's Storages list, inside the settings page's Backups section */
    static @NonNull PanelResource<Listed> admin() {
        StorePages<Listed> pages = new StorePages<>() {
            @Override
            public @NonNull List<Field<?, ?>> fields() {
                return List.of(NAME);
            }

            @Override
            public @NonNull List<String> searchColumns() {
                return List.of(NAME.getName());
            }

            @Override
            public @NonNull RecordPage<Listed> page(TableView.@NonNull Applied<Listed> applied,
                                                    @NonNull AccessContext access) {
                return this.inMemory(applied, rows(access), (row, column) -> row.values().get(column.name()), access);
            }
        };
        ResourceList.Builder<Listed> list = ResourceList.store(TABLE, pages, FEED).chrome(ListChrome.MINIMAL)
            .emptyDescription(HohenheimMicrocopy.BACKUP_TARGET.of("empty"))
            .rowLink((row, request) -> switch (row) {
                case Stored stored -> CmsRoutes.subpage(HohenheimSlugs.ADMIN, HohenheimSlugs.BACKUP_TARGETS,
                    stored.id(), RecordOverview.SLUG);
                case Declared declared -> null;
            });
        return PanelResource.builder(HohenheimIds.id("storage"), HohenheimSlugs.STORAGES,
                SubjectType.of(HohenheimIds.id("storage"), Listed.class, Listed::key))
            .label(HohenheimMicrocopy.BACKUP_TARGET.of("plural"))
            .recordLabel(HohenheimMicrocopy.BACKUP_TARGET.of("singular"))
            .description(CmsSupport.navHint(HohenheimMicrocopy.BACKUP_TARGET))
            .icon(Icon.of("box-archive"))
            // Listed inside the settings page's Backups section (board Settings), never a sidebar row of its own; a
            // storage's record trails "Storages / record", never through the records' own list.
            .showInNav(false)
            .standsUnder(SettingsPage.DEFAULT_SLUG)
            .showsRowsOf(HohenheimSlugs.BACKUP_TARGETS)
            .list(list.build())
            .actions(List.of(addAction(), testAction(), editAction()))
            // A row is no page of this entry: a record's opens the storage entry's page, a settings row none.
            .reads(ResourceReads.<Listed>typed(Listed::key)
                .load((key, access) -> null)
                .values(Listed::values)
                .build()
                .title(row -> Microcopy.literalOrNull(String.valueOf(row.values().get(NAME.getName())))))
            .build();
    }

    /** The table as the board draws it: name with its failure under it, kind, where, added by, used by, room left. */
    private static @NonNull TableSpec<Listed> table() {
        return TableSpec.<Listed>builder()
            .column(ColumnSpec.fromField(NAME).subtext(BackupTargetParts.FAILURE_COLUMN).build())
            .column(ColumnSpec.virtual(BackupTargetParts.FAILURE_COLUMN,
                HohenheimMicrocopy.BACKUP_TARGET.of("column_failure")).hidden().build())
            .column(ColumnSpec.virtual(BackupTargetParts.KIND_COLUMN, HohenheimMicrocopy.HOHENHEIM_FIELD.of("kind"))
                .build())
            .column(ColumnSpec.virtual(BackupTargetParts.WHERE_COLUMN,
                HohenheimMicrocopy.BACKUP_TARGET.of("column_where")).subtext(BackupTargetParts.WHERE_DETAIL_COLUMN)
                .build())
            .column(ColumnSpec.virtual(BackupTargetParts.WHERE_DETAIL_COLUMN,
                HohenheimMicrocopy.BACKUP_TARGET.of("column_where")).hidden().build())
            .column(ColumnSpec.virtual(BackupTargetParts.ADDED_BY_COLUMN,
                HohenheimMicrocopy.BACKUP_TARGET.of("column_added_by")).build())
            .column(ColumnSpec.virtual(BackupTargetParts.USED_BY_COLUMN,
                HohenheimMicrocopy.BACKUP_TARGET.of("column_used_by")).build())
            .column(ColumnSpec.virtual(BackupTargetParts.ROOM_COLUMN,
                HohenheimMicrocopy.BACKUP_TARGET.of("column_room_left")).build())
            // Test and Edit side by side in a record's row, as drawn; never behind its menu.
            .inlineRowActions(2)
            .build();
    }

    /** The list's primary verb: the storage entry's add form. */
    private static @NonNull PanelAction<Listed> addAction() {
        return PanelAction.<Listed>link(HohenheimIds.id("storage_add"), ActionPlacement.HEADER)
            .label(HohenheimMicrocopy.BACKUP_TARGET.of("add_storage"))
            .icon(Icon.of("plus"))
            .style(ActionStyle.PRIMARY)
            .inlineInHeader(true)
            .route((row, request) -> CmsRoutes.create(HohenheimSlugs.ADMIN, HohenheimSlugs.BACKUP_TARGETS))
            .build();
    }

    /** A record's Test: the storage entry's own row action, drawn for the record the row names. */
    private static @NonNull PanelAction<Listed> testAction() {
        return PanelAction.<Listed>fromEntry(HohenheimIds.id("storage_test"), ActionPlacement.ROW)
            .label(HohenheimMicrocopy.BACKUP_TARGET.of("test_connection"))
            .icon(Icon.of("plug-circle-check"))
            .drawing(HohenheimIds.id("admin"), HohenheimSlugs.BACKUP_TARGETS, BackupTargetParts.TEST.id(),
                row -> row instanceof Stored stored ? String.valueOf(stored.id()) : null)
            .build();
    }

    /** A record's Edit beside its Test, as the board draws it: the record's own form. */
    private static @NonNull PanelAction<Listed> editAction() {
        return PanelAction.<Listed>link(HohenheimIds.id("storage_edit"), ActionPlacement.ROW)
            .label(CmsMicrocopy.of("edit"))
            .icon(Icon.of("pen"))
            .inlineInRow(true)
            .shownWhen((row, access) -> row instanceof Stored)
            .route((row, request) -> CmsRoutes.detail(HohenheimSlugs.ADMIN, HohenheimSlugs.BACKUP_TARGETS,
                ((Stored) row).id()))
            .build();
    }

    /**
     * @return the settings' storages first (to an administrator only: they are the server's own), then the records
     *         the viewer may list, each with its words read for this viewer
     */
    static @NonNull List<Listed> rows(@NonNull AccessContext access) {
        Panel panel = PanelRegistry.getBySlug(HohenheimSlugs.ADMIN);
        if (panel == null) {
            return List.of();
        }
        List<Listed> rows = new ArrayList<>();
        if (HohenheimAccess.isAdmin(access)) {
            Map<String, Long> sites = StorageUsage.sitesByStorage();
            for (Map.Entry<DeclaredStorage, @Nullable Microcopy> declared : declared().entrySet()) {
                rows.add(declaredRow(declared.getKey(), declared.getValue(), sites, access));
            }
        }
        List<Row> records = AppDirectory.listed(panel, HohenheimSlugs.BACKUP_TARGETS, access);
        CellMapper<Row> cells = BackupTargetParts.cells(records, access);
        for (Row record : records) {
            Map<String, Object> values = new HashMap<>();
            values.put(NAME.getName(), String.valueOf((Object) record.get(BackupTargetModel.NAME)));
            for (String column : CELLS) {
                values.put(column, cells.cell(record, Objects.requireNonNull(TABLE.column(column), column)));
            }
            Object id = Objects.requireNonNull(record.get(BackupTargetModel.ID), "a listed storage has its id");
            rows.add(new Stored("record-" + id, values, id));
        }
        return rows;
    }

    /**
     * @return the storages the server's settings declare, each with the words naming it where its name is no word a
     *         person reads (this server's own site files); a module's own home (media, content) is no storage a
     *         person chooses and is not here ({@link Storages#inSettings})
     */
    private static @NonNull Map<DeclaredStorage, @Nullable Microcopy> declared() {
        Map<DeclaredStorage, @Nullable Microcopy> declared = new LinkedHashMap<>();
        try {
            SiteFiles.storageOf(null);
            declared.put(DeclaredStorage.inSettings(SiteFiles.STORAGE, LocalFolderStorageKind.ID,
                Map.of(LocalFolderStorageKind.FOLDER.getName(),
                    Storages.localDirectory(HohenheimSettings.Files.SITE_FILES_DIRECTORY).toString())),
                HohenheimMicrocopy.BACKUP_TARGET.of("site_files_own"));
        } catch (RuntimeException unmounted) {
            Blast.log("STORAGE: this server's site files are not mounted:", unmounted.getMessage());
        }
        for (DeclaredStorage storage : Storages.inSettings()) {
            declared.put(storage, null);
        }
        return declared;
    }

    /** @return a settings storage's row: where it is in its kind's words, the sites keeping files there, its room */
    private static @NonNull Declared declaredRow(@NonNull DeclaredStorage storage, @Nullable Microcopy shownName,
                                                 @NonNull Map<String, Long> sites, @NonNull AccessContext access) {
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
        StorageKindInfo kind = StorageKindRegistry.REGISTRY.get(storage.kind());
        long used = sites.getOrDefault(storage.name(), 0L);
        Map<String, Object> values = new HashMap<>();
        values.put(NAME.getName(), shownName == null ? storage.name() : access.text(shownName));
        values.put(BackupTargetParts.FAILURE_COLUMN,
            CellMapper.Computed.orEmpty(BackupTargetParts.failure(storage.name(), access)));
        values.put(BackupTargetParts.KIND_COLUMN,
            CellMapper.Computed.orEmpty(kind == null ? null : access.text(kind.getLabel())));
        values.put(BackupTargetParts.WHERE_COLUMN,
            CellMapper.Computed.orEmpty(BackupTargetParts.place(listed, access, true)));
        values.put(BackupTargetParts.WHERE_DETAIL_COLUMN,
            CellMapper.Computed.orEmpty(BackupTargetParts.place(listed, access, false)));
        values.put(BackupTargetParts.ADDED_BY_COLUMN,
            access.text(HohenheimMicrocopy.BACKUP_TARGET.of("in_settings_title")));
        values.put(BackupTargetParts.USED_BY_COLUMN,
            CellMapper.Computed.orEmpty(used > 0 ? access.text(HohenheimCounts.of("sites", used)) : null));
        values.put(BackupTargetParts.ROOM_COLUMN,
            CellMapper.Computed.orEmpty(BackupTargetParts.room(storage.name(), access)));
        return new Declared("settings-" + storage.name(), values);
    }
}
