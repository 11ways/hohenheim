package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.ReconcileFindingModel;
import be.elevenways.hohenheim.server.docker.DockerReconciler;
import be.elevenways.hohenheim.server.docker.OrphanActions;
import be.elevenways.protoblast.common.i18n.Locale;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.typed.CoreTypes;
import be.elevenways.protoblast.common.typed.rule.Condition;
import be.elevenways.protoblast.common.typed.rule.Operand;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.ListScope;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.data.RecordSourceRegistry;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.widget.common.WidgetInstance;
import be.elevenways.zenit.widget.common.WidgetTree;
import be.elevenways.zenit.widget.common.builtin.StatWidget;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        // MULTI-valued, so the facet rail presents both as counted checkbox panels: a triage
        // pass reads "orphaned or colliding, containers and volumes" at a glance.
        .filter(FilterSpec.forField(ReconcileFindingModel.BUCKET, FilterSpec.Kind.MULTI_SELECT)
            .label(FieldLabels.labelFor(ReconcileFindingModel.BUCKET)).build())
        .filter(FilterSpec.forField(ReconcileFindingModel.KIND, FilterSpec.Kind.MULTI_SELECT)
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
    @Override public @NonNull ListChrome listChrome() {
        return ListChrome.MINIMAL.withAdvancedFilter(true).withFacetRail(true);
    }

    /** Findings are pasted into host commands and tickets: the filtered list leaves as CSV. */
    @Override public boolean exportable() { return true; }

    /**
     * What the current filters still hold that needs a decision: the orphans an operator may
     * remove and the collisions the reconciler warns about. Both tiles count through the
     * findings' own record source under the list's narrowing, so they follow every rail tick.
     *
     * AIDEV-NOTE: the narrowing is the FILTERS, never the plain search box (see zenit-cms
     * ListScope), so the tiles are worded "under these filters", not "in this view": a typed
     * search narrows the rows and leaves the tiles counting the filtered set.
     */
    @Override
    public @NonNull WidgetTree listWidgets(@NonNull ListScope scope) {
        return new WidgetTree(List.of(
            bucketTile(scope, HohenheimWidgetCopy.localized("orphaned_filtered", "reconcile_finding"),
                ReconcileFindingModel.BUCKET_ORPHANED, "trash"),
            bucketTile(scope, HohenheimWidgetCopy.localized("colliding_filtered", "reconcile_finding"),
                ReconcileFindingModel.BUCKET_FOREIGN_COLLIDING, "triangle-exclamation")));
    }

    /**
     * The tiles count through the source the default CMS glue registered over the findings, the
     * model's own (a default source's id IS its model's), named by the token that source spells.
     */
    private static @NonNull WidgetInstance bucketTile(@NonNull ListScope scope, @NonNull Map<Locale, String> label,
                                                      @NonNull String bucket, @NonNull String icon) {
        return new WidgetInstance(StatWidget.ID, Map.of(
            "label", label,
            "source", RecordSourceRegistry.INSTANCE.requireById(ReconcileFindingModel.MODEL_ID).idToken(),
            "rules", Condition.all(scope.narrowing(), Condition.test(ReconcileFindingModel.BUCKET.getName(),
                CoreTypes.EQUALS, Operand.of(bucket))),
            "icon", icon));
    }

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
