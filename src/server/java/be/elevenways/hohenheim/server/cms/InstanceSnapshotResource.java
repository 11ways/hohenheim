package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceSnapshotModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceSnapshots;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Snapshot rows: created via the instance's own snapshot action, restored and
 * deleted here. Restore is the data-destructive act of this surface, so it demands
 * the typed phrase (the purge_stack_volumes precedent) -- current volume contents
 * are REPLACED with the captured state.
 */
public class InstanceSnapshotResource extends RowResource {

    private final InstanceSnapshots snapshots = new InstanceSnapshots();

    private final FormSpec formSpec = FormSpec.builder()
        .add(InstanceSnapshotModel.NOTE)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(InstanceSnapshotModel.INSTANCE_ID)
            .relation(RelationPick.of(InstanceSnapshotModel.INSTANCE_ID,
                InstanceModel.MODEL_ID).build()).build())
        .column(ColumnSpec.fromField(InstanceSnapshotModel.STATUS).filterable()
            .subtext("total_bytes").build())
        .column(ColumnSpec.fromField(InstanceSnapshotModel.NOTE).build())
        .column(ColumnSpec.fromField(InstanceSnapshotModel.TOTAL_BYTES).hidden().build())
        .column(ColumnSpec.fromField(InstanceSnapshotModel.CREATED_AT).build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_snapshot"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "instance_snapshot"); }
    @Override public @Nullable Microcopy recordLabel() { return Microcopy.of("singular").withFilter("scope", "instance_snapshot"); }
    @Override public @NonNull String slug() { return "instance-snapshots"; }
    @Override public @NonNull Model model() { return Models.get(InstanceSnapshotModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull ListChrome listChrome() { return ListChrome.MINIMAL; }

    /** The note is the only thing an operator wrote here themselves. */
    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(InstanceSnapshotModel.NOTE);
    }

    /**
     * The note, and only the note.
     *
     * AIDEV-NOTE: every OTHER column of a snapshot is captured state -- the status the
     * capture reached, the byte count it wrote, the instance it was taken from. Editing
     * one in place would be editing the record OF an event that already happened, so the
     * form does not offer them either (NOTE is its sole entry).
     */
    @Override
    public @NonNull List<Field<?, ?>> inlineEditableFields() {
        return List.of(InstanceSnapshotModel.NOTE);
    }

    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.DEPLOY_GROUP; }
    @Override public int navOrder() { return 16; }

    @Override public boolean showInNav() { return false; }
    @Override public @NonNull Icon icon() { return Icon.of("camera"); }

    /** Snapshots are born from the instance action, never from a form. */
    @Override
    public boolean creatable() { return false; }

    /**
     * Writing a snapshot row demands {@code snapshots} on the instance it captured --
     * the same capability {@link ManageInstanceSnapshotResource} scopes its list by.
     *
     * AIDEV-NOTE: declared HERE, on the base, and not left to the delegated peer. Until
     * this wave the mirror was safe only by COINCIDENCE (no base {@code writableBy}, plus
     * a read scope that already excluded foreign snapshots), and a one-click pencil makes
     * "the list only shows yours" stop being the whole answer: the affordance, the cell
     * endpoint and the commit all consult THIS predicate.
     */
    @Override
    public boolean writableBy(@NonNull Row record, @NonNull AccessContext accessContext) {
        // reachesRecord, never hasInstanceCapability: this runs once per RENDERED ROW.
        return HohenheimAccess.reachesRecord(accessContext, InstanceModel.MODEL_ID,
            record.get(InstanceSnapshotModel.INSTANCE_ID), HohenheimAccess.SNAPSHOTS);
    }

    /** Delete removes the payload files WITH the row. */
    @Override
    public void deleteRow(@NonNull Row existing, @NonNull AccessContext accessContext) {
        Integer id = existing.get(InstanceSnapshotModel.ID);
        ActivityLog.withAction(ActivityLog.ACTION_DELETE, "delete_snapshot",
            () -> this.snapshots.delete(id));
    }

    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        List<RowAction<Row>> actions = new ArrayList<>(super.rowActions());
        actions.add(RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "restore_snapshot"))
            .label(Microcopy.of("restore").withFilter("scope", "instance_snapshot"))
            .icon(Icon.of("clock-rotate-left"))
            .style(ActionStyle.DESTRUCTIVE)
            .visibleFor((row, ctx) -> InstanceSnapshotModel.STATUS_COMPLETE
                .equals(row.get(InstanceSnapshotModel.STATUS)))
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("restore").withFilter("scope", "instance_snapshot"))
                .body(Microcopy.of("restore_confirm_generic").withFilter("scope", "instance_snapshot"))
                .confirmLabel(Microcopy.of("restore").withFilter("scope", "instance_snapshot"))
                .style(ActionStyle.DESTRUCTIVE)
                .build())
            .dynamicConfirmation(row -> ConfirmationSpec.builder()
                .title(Microcopy.of("restore").withFilter("scope", "instance_snapshot"))
                .body(Microcopy.of("restore_confirm").withFilter("scope", "instance_snapshot")
                    .withArg("name", instanceNameOf(row)))
                .confirmLabel(Microcopy.of("restore").withFilter("scope", "instance_snapshot"))
                .style(ActionStyle.DESTRUCTIVE)
                .requireTypedConfirmation(instanceNameOf(row))
                .build())
            .handler((row, ctx) -> {
                // No withAction wrapper: InstanceSnapshots.restore records itself on
                // the instance record. Wrapping it recorded nothing at all -- its whole
                // state write is an updateAll, which fires no hooks for withAction to
                // rename.
                this.snapshots.restore(row.get(InstanceSnapshotModel.ID));
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("restored").withFilter("scope", "instance_snapshot")
                        .withArg("name", instanceNameOf(row)));
            })
            .build());
        return actions;
    }

    private static @NonNull String instanceNameOf(@NonNull Row snapshot) {
        Row instance = Models.get(InstanceModel.class)
            .findById(snapshot.get(InstanceSnapshotModel.INSTANCE_ID));
        return instance != null
            ? String.valueOf((Object) instance.get(InstanceModel.NAME))
            : String.valueOf((Object) snapshot.get(InstanceSnapshotModel.INSTANCE_ID));
    }
}
