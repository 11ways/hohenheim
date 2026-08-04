package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.instance.InstanceBackups;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Backup rows: created by the instance's backup action or the nightly task,
 * restored TO A NEW INSTANCE and deleted here. Restore-to-new is creative, not
 * destructive (the source keeps running), so it confirms without the typed phrase.
 */
public class InstanceBackupResource extends RowResource {

    private final InstanceBackups backups = new InstanceBackups();

    private final FormSpec formSpec = FormSpec.builder()
        .add(RelationPick.of(InstanceBackupModel.INSTANCE_ID, InstanceModel.MODEL_ID).build())
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(InstanceBackupModel.INSTANCE_ID)
            .relation(RelationPick.of(InstanceBackupModel.INSTANCE_ID,
                InstanceModel.MODEL_ID).build()).build())
        .column(ColumnSpec.fromField(InstanceBackupModel.TARGET_ID)
            .relation(RelationPick.of(InstanceBackupModel.TARGET_ID,
                BackupTargetModel.MODEL_ID).build()).build())
        .column(ColumnSpec.fromField(InstanceBackupModel.STATUS).filterable().build())
        .column(ColumnSpec.fromField(InstanceBackupModel.SIZE_BYTES).build())
        .column(ColumnSpec.fromField(InstanceBackupModel.CREATED_AT).build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_backup"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "instance_backup"); }
    @Override public @NonNull String slug() { return "instance-backups"; }
    @Override public @NonNull Model model() { return Models.get(InstanceBackupModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.INFRA_GROUP; }
    @Override public int navOrder() { return 17; }
    @Override public @NonNull Icon icon() { return Icon.of("box-archive"); }

    /** Backups are born from the instance action or the nightly task, never a form. */
    @Override
    public boolean creatable() { return false; }

    /** A backup is immutable evidence; readers view, actions act. */
    @Override
    public boolean updatable() { return false; }

    /** Delete removes the artifact FROM THE TARGET with the row (refuses when it cannot). */
    @Override
    public void deleteRow(@NonNull Row existing, @NonNull AccessContext accessContext) {
        Integer id = existing.get(InstanceBackupModel.ID);
        ActivityLog.withAction(ActivityLog.ACTION_DELETE, "delete_backup",
            () -> this.backups.delete(id));
    }

    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        List<RowAction<Row>> actions = new ArrayList<>(super.rowActions());
        actions.add(RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "restore_backup"))
            .label(Microcopy.of("restore_new").withFilter("scope", "instance_backup"))
            .icon(Icon.of("clone"))
            .visibleFor((row, ctx) -> InstanceBackupModel.STATUS_COMPLETE
                .equals(row.get(InstanceBackupModel.STATUS)))
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("restore_new").withFilter("scope", "instance_backup"))
                .body(Microcopy.of("restore_new_confirm").withFilter("scope", "instance_backup"))
                .confirmLabel(Microcopy.of("restore_new").withFilter("scope", "instance_backup"))
                .build())
            .handler((row, ctx) -> {
                AtomicInteger newId = new AtomicInteger();
                ActivityLog.withAction(ActivityLog.ACTION_CREATE, "restore_backup",
                    () -> newId.set(this.backups.restoreToNew(
                        row.get(InstanceBackupModel.ID), null, null)));
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("restored_new").withFilter("scope", "instance_backup")
                        .withArg("id", newId.get()));
            })
            .build());
        return actions;
    }
}
