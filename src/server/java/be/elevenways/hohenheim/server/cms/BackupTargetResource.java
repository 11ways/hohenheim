package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.server.backup.BackupTargetKinds;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Admin surface for backup targets: full CRUD plus a live connection test (the
 * target seam's health check against the real destination, never a form-level
 * syntax check).
 */
public final class BackupTargetResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(BackupTargetModel.NAME)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(BackupTargetModel.KIND))
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(BackupTargetModel.SETTINGS))
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(BackupTargetModel.NAME).filterable().copyable().build())
        .column(ColumnSpec.fromField(BackupTargetModel.KIND).filterable().build())
        .column(ColumnSpec.fromField(BackupTargetModel.CREATED_AT).build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "backup_target"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "backup_target"); }
    @Override public @Nullable Microcopy recordLabel() { return Microcopy.of("singular").withFilter("scope", "backup_target"); }
    @Override public @NonNull String slug() { return "backup-targets"; }
    @Override public @NonNull Model model() { return Models.get(BackupTargetModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull ListChrome listChrome() { return ListChrome.MINIMAL; }

    /** The name is the only text a target carries; the credentials live in a secret settings blob. */
    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(BackupTargetModel.NAME);
    }

    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.DEPLOY_GROUP; }
    @Override public int navOrder() { return 18; }

    /**
     * Demoted out of the sidebar, so this sentence reaches a reader through the panel
     * index and the related-pages menu of the list that names it.
     */
    @Override public @Nullable Microcopy description() { return CmsSupport.navHint("backup_target"); }

    @Override public boolean showInNav() { return false; }
    @Override public @NonNull Icon icon() { return Icon.of("box-archive"); }

    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        List<RowAction<Row>> actions = new ArrayList<>(super.rowActions());
        actions.add(RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "test_backup_target"))
            .label(Microcopy.of("test_connection").withFilter("scope", "backup_target"))
            .icon(Icon.of("plug-circle-check"))
            .handler((row, ctx) -> {
                try {
                    BackupTargetKinds.targetOf(row).healthCheck();
                } catch (IOException | Violations unhealthy) {
                    // Violations too: a destination host that is unconfirmed or
                    // quarantined is exactly what this button exists to surface, and an
                    // escaping refusal would render as a generic failure instead.
                    return CmsActionResult.errorToast(
                        Microcopy.of("test_failed").withFilter("scope", "backup_target")
                            .withArg("reason", unhealthy.getMessage() != null
                                ? unhealthy.getMessage() : unhealthy.toString()));
                }
                return CmsActionResult.toast(
                    Microcopy.of("test_ok").withFilter("scope", "backup_target")
                        .withArg("name", row.get(BackupTargetModel.NAME)));
            })
            .build());
        return actions;
    }
}
