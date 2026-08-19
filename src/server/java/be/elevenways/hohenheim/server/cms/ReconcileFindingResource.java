package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.ReconcileFindingModel;
import be.elevenways.hohenheim.server.docker.DockerReconciler;
import be.elevenways.hohenheim.server.docker.OrphanActions;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * The reconciler's stored findings as an operator surface: read-only rows, with the
 * EXPLICIT remove-orphan authority ({@link OrphanActions}) as a confirmed row action.
 * Adoption/quarantine stops being a side effect of classification here and becomes a
 * decision an operator takes. The activity record is written by {@link OrphanActions}
 * itself, on the host record -- this surface must not be the only place the removal is
 * accountable, because it is not the only possible caller.
 */
public final class ReconcileFindingResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(ReconcileFindingModel.SERVER_NAME)
        .add(ReconcileFindingModel.KIND)
        .add(ReconcileFindingModel.RESOURCE_NAME)
        .add(ReconcileFindingModel.BUCKET)
        .add(ReconcileFindingModel.EVIDENCE)
        .add(ReconcileFindingModel.DETAIL)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(ReconcileFindingModel.SERVER_NAME).filterable().build())
        .column(ColumnSpec.fromField(ReconcileFindingModel.KIND).filterable().build())
        .column(ColumnSpec.fromField(ReconcileFindingModel.RESOURCE_NAME).filterable().build())
        .column(ColumnSpec.fromField(ReconcileFindingModel.BUCKET).filterable().build())
        .column(ColumnSpec.fromField(ReconcileFindingModel.DETAIL).build())
        .filter(FilterSpec.forField(ReconcileFindingModel.SERVER_NAME, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(ReconcileFindingModel.SERVER_NAME)).build())
        .filter(FilterSpec.forField(ReconcileFindingModel.BUCKET, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(ReconcileFindingModel.BUCKET)).build())
        .filter(FilterSpec.forField(ReconcileFindingModel.KIND, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(ReconcileFindingModel.KIND)).build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "reconcile_finding"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "reconcile_finding"); }
    @Override public @NonNull String slug() { return "reconcile-findings"; }
    @Override public @NonNull Model model() { return Models.get(ReconcileFindingModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.INFRA_GROUP; }
    @Override public int navOrder() { return 25; }

    @Override public boolean showInNav() { return false; }
    @Override public @NonNull Icon icon() { return Icon.of("magnifying-glass"); }
    @Override public boolean creatable() { return false; }
    @Override public boolean updatable() { return false; }
    @Override public boolean deletable() { return false; }

    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        List<RowAction<Row>> actions = new ArrayList<>(super.rowActions());
        actions.add(this.removeOrphanAction());
        return actions;
    }

    /** Volumes never offer this: they are the one unrecoverable resource. */
    private @NonNull RowAction<Row> removeOrphanAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "remove_orphan"))
            .label(Microcopy.of("remove_orphan").withFilter("scope", "reconcile_finding"))
            .icon(Icon.of("trash"))
            .style(ActionStyle.DESTRUCTIVE)
            .visibleFor((row, ctx) ->
                ReconcileFindingModel.BUCKET_ORPHANED.equals(row.get(ReconcileFindingModel.BUCKET))
                    && !DockerReconciler.KIND_VOLUME.equals(row.get(ReconcileFindingModel.KIND)))
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("remove_orphan").withFilter("scope", "reconcile_finding"))
                .body(Microcopy.of("remove_orphan_confirm").withFilter("scope", "reconcile_finding"))
                .confirmLabel(Microcopy.of("remove_orphan").withFilter("scope", "reconcile_finding"))
                .style(ActionStyle.DESTRUCTIVE)
                .build())
            .handler((row, ctx) -> {
                OrphanActions.removeOrphan(row);
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("orphan_removed").withFilter("scope", "reconcile_finding")
                        .withArg("name", row.get(ReconcileFindingModel.RESOURCE_NAME)));
            })
            .build();
    }
}
