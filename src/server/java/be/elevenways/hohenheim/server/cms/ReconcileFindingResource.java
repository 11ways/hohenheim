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
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

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
        // The chip carries the resource name: reconciling means pasting it into a docker
        // or incus command on the host it was found on.
        .column(ColumnSpec.fromField(ReconcileFindingModel.RESOURCE_NAME).filterable()
            .subtext("detail").copyable().build())
        .column(ColumnSpec.fromField(ReconcileFindingModel.DETAIL).hidden().build())
        .column(ColumnSpec.fromField(ReconcileFindingModel.BUCKET).filterable().build())
        .filter(FilterSpec.forField(ReconcileFindingModel.SERVER_NAME, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(ReconcileFindingModel.SERVER_NAME)).build())
        .filter(FilterSpec.forField(ReconcileFindingModel.BUCKET, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(ReconcileFindingModel.BUCKET)).build())
        .filter(FilterSpec.forField(ReconcileFindingModel.KIND, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(ReconcileFindingModel.KIND)).build())
        .build();

    /**
     * The name this surface carries everywhere, as ONE declaration.
     *
     * AIDEV-NOTE: public because the dashboard's foreign-resources item NAMES this page in
     * its sentence. It used to spell "Reconcile findings" into the microcopy, a name no
     * surface in the product ever carried, so the row sent operators looking for a page
     * that does not exist. A Microcopy argument resolves in the reader's locale
     * (MessageArgs.render), so passing this constant keeps one translatable home.
     */
    public static final Microcopy LABEL = Microcopy.of("plural").withFilter("scope", "reconcile_finding");

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "reconcile_finding"); }
    @Override public @NonNull Microcopy label() { return LABEL; }
    @Override public @Nullable Microcopy recordLabel() { return Microcopy.of("singular").withFilter("scope", "reconcile_finding"); }
    @Override public @NonNull String slug() { return "reconcile-findings"; }
    @Override public @NonNull Model model() { return Models.get(ReconcileFindingModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    /**
     * MINIMAL plus the query tier, because links arrive here PRE-FILTERED.
     *
     * AIDEV-NOTE: {@code advancedFilter} gates the query box's RENDERING only -- a
     * {@code ?q=} URL applies either way (ListQueryState reads it regardless). With the
     * box hidden, the dashboard's narrowed link landed on a silently filtered list: no
     * visible expression, no way to widen it, and a row count that matched nothing on
     * screen. Offering the box is what makes the narrowing legible and reversible.
     */
    @Override public @NonNull ListChrome listChrome() { return ListChrome.MINIMAL.withAdvancedFilter(true); }

    /** A finding is hunted for by the host it was seen on, the thing it names, or the words explaining it. */
    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(ReconcileFindingModel.SERVER_NAME, ReconcileFindingModel.RESOURCE_NAME, ReconcileFindingModel.DETAIL);
    }

    @Override public @NonNull NavGroup navGroup() { return NavGroup.SYSTEM; }
    @Override public int navOrder() { return 25; }

    /**
     * Demoted out of the sidebar, so this sentence reaches a reader through the panel
     * index and the related-pages menu of the list that names it.
     */
    @Override public @Nullable Microcopy description() { return CmsSupport.navHint("reconcile_finding"); }

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
