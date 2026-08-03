package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.instance.InstanceService;
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
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FieldLabels;
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
import java.util.Map;

/**
 * The instance tier's admin surface (C7: admin panel only; the grant-scoped /manage
 * projection is its own later commit). Create persists the record; deploy, stop and
 * the verified destroy are row actions through {@link InstanceService}.
 */
public final class InstanceResource extends RowResource {

    private final InstanceService instances = new InstanceService();

    private final FormSpec formSpec = FormSpec.builder()
        .add(InstanceModel.NAME)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(InstanceModel.KIND))
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(InstanceModel.SETTINGS))
        .add(RelationPick.of(InstanceModel.SERVER_ID, ServerModel.MODEL_ID).build())
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(InstanceModel.NAME).filterable().build())
        .column(ColumnSpec.fromField(InstanceModel.KIND).filterable().build())
        .column(ColumnSpec.fromField(InstanceModel.SERVER_ID)
            .relation(RelationPick.of(InstanceModel.SERVER_ID, ServerModel.MODEL_ID).build()).build())
        .column(ColumnSpec.fromField(InstanceModel.STATUS).filterable().build())
        .column(ColumnSpec.fromField(InstanceModel.CREATED_AT).filterable().build())
        .filter(FilterSpec.forField(InstanceModel.NAME, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(InstanceModel.NAME)).build())
        .filter(FilterSpec.forField(InstanceModel.KIND, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(InstanceModel.KIND)).build())
        .filter(FilterSpec.forField(InstanceModel.STATUS, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(InstanceModel.STATUS)).build())
        .build();

    /** The server pick defaults to the local daemon (ensuring its row exists for the picker). */
    @Override
    public @NonNull Map<String, Object> createValues(@NonNull Conduit conduit) {
        Map<String, Object> values = CmsSupport.mutable(formSpec().defaultValues());
        values.put("server_id", ServerModel.localServerId());
        return Map.copyOf(values);
    }

    /** Soft-deleted instances are invisible everywhere. */
    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return ctx -> AccessDecision.allow(QueryPredicate.of(InstanceModel.DELETED_AT.isNull()));
    }

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "instance"); }
    @Override public @NonNull String slug() { return "instances"; }
    @Override public @NonNull Model model() { return Models.get(InstanceModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.INFRA_GROUP; }
    @Override public int navOrder() { return 15; }
    @Override public @NonNull Icon icon() { return Icon.of("cube"); }

    /**
     * Delete IS the verified destroy: container removed (or observed absent) and port
     * claims released before the record is soft-deleted; an unreachable daemon is a
     * NAMED refusal ({@link InstanceService#destroy}) that keeps the record. Volumes
     * survive by design -- the reconciler surfaces them as orphans for an explicit
     * operator decision.
     */
    @Override
    public void deleteRow(@NonNull Row existing, @NonNull AccessContext accessContext) {
        Integer id = existing.get(InstanceModel.ID);
        ActivityLog.withAction(ActivityLog.ACTION_DELETE, "destroy",
            () -> this.instances.destroy(id));
    }

    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        List<RowAction<Row>> actions = new ArrayList<>(super.rowActions());
        actions.add(this.deployAction());
        actions.add(this.stopAction());
        return actions;
    }

    private @NonNull RowAction<Row> deployAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "deploy_instance"))
            .label(Microcopy.of("deploy").withFilter("scope", "instance"))
            .icon(Icon.of("play"))
            .handler((row, ctx) -> {
                this.instances.deploy(row.get(InstanceModel.ID));
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("deployed").withFilter("scope", "instance")
                        .withArg("name", row.get(InstanceModel.NAME)));
            })
            .build();
    }

    private @NonNull RowAction<Row> stopAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "stop_instance"))
            .label(Microcopy.of("stop").withFilter("scope", "instance"))
            .icon(Icon.of("stop"))
            .style(ActionStyle.DESTRUCTIVE)
            .visibleFor((row, ctx) ->
                InstanceModel.STATUS_RUNNING.equals(row.get(InstanceModel.STATUS)))
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("stop").withFilter("scope", "instance"))
                .body(Microcopy.of("stop_confirm").withFilter("scope", "instance"))
                .confirmLabel(Microcopy.of("stop").withFilter("scope", "instance"))
                .style(ActionStyle.DESTRUCTIVE)
                .build())
            .handler((row, ctx) -> {
                this.instances.stop(row.get(InstanceModel.ID));
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("stopped_toast").withFilter("scope", "instance")
                        .withArg("name", row.get(InstanceModel.NAME)));
            })
            .build();
    }
}
