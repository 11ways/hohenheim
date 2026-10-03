package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.model.ReconcileFindingModel;
import be.elevenways.hohenheim.server.docker.DockerReconciler;
import be.elevenways.hohenheim.server.docker.OrphanActions;
import be.elevenways.protoblast.common.i18n.Locale;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.typed.CoreTypes;
import be.elevenways.protoblast.common.typed.rule.Condition;
import be.elevenways.protoblast.common.typed.rule.Operand;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.ListScope;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.orm.command.CommandExecution;
import be.elevenways.zenit.common.orm.lease.LeaseKeys;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.widget.common.WidgetInstance;
import be.elevenways.zenit.widget.common.WidgetTree;
import be.elevenways.zenit.widget.common.builtin.StatWidget;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;
import java.util.Map;

/**
 * Stored host findings and the explicit remove-orphan operation. Resource names and evidence remain verbatim.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class ReconcileFindingParts {
    public static final String SLUG = "reconcile-findings";
    public static final Microcopy LABEL = Microcopy.of("plural").withFilter("scope", "reconcile_finding");
    public static final Operation<Row, Void, Void> REMOVE = Operation.declare(HohenheimIds.id("remove_orphan"))
        .label(Microcopy.of("remove_orphan").withFilter("scope", "reconcile_finding"))
        .one(SubjectType.record(ReconcileFindingModel.MODEL_ID)).gate(OperationGate.permission(HohenheimPanel.ACCESS))
        .facts(OperationFact.DESTRUCTIVE, OperationFact.REACHES_OUTSIDE)
        .command(OperationCommand.perSubject(LeaseKeys.declare(ReconcileFindingModel.MODEL_ID))
            .execution(CommandExecution.OUTSIDE_TRANSACTION)).register();
    static {
        OperationHandlers.attach(REMOVE).applies(row -> ReconcileFindingModel.BUCKET_ORPHANED
            .equals(row.get(ReconcileFindingModel.BUCKET))
                && !DockerReconciler.KIND_VOLUME.equals(row.get(ReconcileFindingModel.KIND)))
            .handle(call -> { OrphanActions.removeOrphan(call.subject()); return null; });
    }
    private ReconcileFindingParts() {}
    public static @NonNull PanelResource<Row> admin() {
        return PanelResource.builder(HohenheimIds.id("reconcile_finding"), SLUG,
                SubjectType.record(ReconcileFindingModel.MODEL_ID))
            .label(LABEL).recordLabel(Microcopy.of("singular").withFilter("scope", "reconcile_finding"))
            .description(CmsSupport.navHint("reconcile_finding")).navGroup(NavGroup.SYSTEM)
            .navOrder(25).showInNav(false).icon(Icon.of("magnifying-glass"))
            .form(ResourceForm.<Row>of(formSpec()).build()).reads(ResourceReads.rows())
            .list(ResourceList.rows(tableSpec()).chrome(ListChrome.MINIMAL.withAdvancedFilter(true).withFacetRail(true))
                .facets().ruleFilters().exportable(true).widgets(ReconcileFindingParts::widgets)
                .search(ReconcileFindingModel.SERVER_NAME, ReconcileFindingModel.RESOURCE_NAME, ReconcileFindingModel.DETAIL).build())
            .actions(List.of(PanelAction.<Row, Void>places(REMOVE, ActionPlacement.ROW,
                    (request, result) -> CmsActionResult.refreshWithToast(Microcopy.of("orphan_removed")
                        .withFilter("scope", "reconcile_finding")
                        .withArg("name", request.subject().get(ReconcileFindingModel.RESOURCE_NAME))))
                .icon(Icon.of("trash")).style(ActionStyle.DESTRUCTIVE)
                .confirmation(ConfirmationSpec.builder()
                    .title(Microcopy.of("remove_orphan").withFilter("scope", "reconcile_finding"))
                    .body(Microcopy.of("remove_orphan_confirm").withFilter("scope", "reconcile_finding"))
                    .confirmLabel(Microcopy.of("remove_orphan").withFilter("scope", "reconcile_finding"))
                    .style(ActionStyle.DESTRUCTIVE).build()).build())).build();
    }
    static FormSpec formSpec() {
        return FormSpec.builder().add(ReconcileFindingModel.SERVER_NAME).add(ReconcileFindingModel.KIND)
            .add(ReconcileFindingModel.RESOURCE_NAME).add(ReconcileFindingModel.BUCKET)
            .add(ReconcileFindingModel.EVIDENCE).add(ReconcileFindingModel.DETAIL).build();
    }
    static TableSpec<Row> tableSpec() {
        return TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(ReconcileFindingModel.SERVER_NAME).filterable().build())
            .column(ColumnSpec.fromField(ReconcileFindingModel.KIND).filterable().build())
            .column(ColumnSpec.fromField(ReconcileFindingModel.RESOURCE_NAME).filterable().subtext("detail").copyable().build())
            .column(ColumnSpec.fromField(ReconcileFindingModel.DETAIL).hidden().build())
            .column(ColumnSpec.fromField(ReconcileFindingModel.BUCKET).filterable().build())
            .filter(FilterSpec.forField(ReconcileFindingModel.SERVER_NAME, FilterSpec.Kind.TEXT)
                .label(FieldLabels.labelFor(ReconcileFindingModel.SERVER_NAME)).build())
            .filter(FilterSpec.forField(ReconcileFindingModel.BUCKET, FilterSpec.Kind.MULTI_SELECT)
                .label(FieldLabels.labelFor(ReconcileFindingModel.BUCKET)).build())
            .filter(FilterSpec.forField(ReconcileFindingModel.KIND, FilterSpec.Kind.MULTI_SELECT)
                .label(FieldLabels.labelFor(ReconcileFindingModel.KIND)).build()).build();
    }
    static WidgetTree widgets(ListScope scope) {
        return new WidgetTree(List.of(tile(scope, HohenheimWidgetCopy.localized("orphaned_filtered", "reconcile_finding"),
                ReconcileFindingModel.BUCKET_ORPHANED, "trash"),
            tile(scope, HohenheimWidgetCopy.localized("colliding_filtered", "reconcile_finding"),
                ReconcileFindingModel.BUCKET_FOREIGN_COLLIDING, "triangle-exclamation")));
    }
    private static WidgetInstance tile(ListScope scope, Map<Locale, String> label, String bucket, String icon) {
        return new WidgetInstance(StatWidget.ID, Map.of("label", label, "source", scope.sourceToken(),
            "rules", Condition.all(scope.narrowing(), Condition.test(ReconcileFindingModel.BUCKET.getName(),
                CoreTypes.EQUALS, Operand.of(bucket))), "icon", icon));
    }
}
