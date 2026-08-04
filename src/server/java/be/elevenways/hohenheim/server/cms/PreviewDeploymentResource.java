package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.hohenheim.server.preview.PreviewDeployments;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldAccess;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Preview deployment inventory: read-only rows (the lifecycle engine owns them) plus
 * the one operator verb -- destroy now, ahead of the expiry sweep. Creation happens
 * through webhooks; there is deliberately no form that could half-author a preview.
 */
public final class PreviewDeploymentResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(PreviewDeploymentModel.SITE_ID)
        .add(PreviewDeploymentModel.REF)
        .add(PreviewDeploymentModel.PR_NUMBER)
        .add(PreviewDeploymentModel.HEAD_SHA)
        .add(PreviewDeploymentModel.HOSTNAME)
        .add(PreviewDeploymentModel.STATUS)
        .add(PreviewDeploymentModel.EXPIRES_AT)
        .add(PreviewDeploymentModel.INSTANCE_ID)
        .add(PreviewDeploymentModel.LAST_ERROR)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(PreviewDeploymentModel.HOSTNAME).filterable().build())
        .column(ColumnSpec.fromField(PreviewDeploymentModel.REF).filterable().build())
        .column(ColumnSpec.fromField(PreviewDeploymentModel.STATUS).filterable().build())
        .column(ColumnSpec.fromField(PreviewDeploymentModel.EXPIRES_AT).sortable().build())
        .column(ColumnSpec.fromField(PreviewDeploymentModel.CREATED_AT).sortable().build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "preview_deployment"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "preview_deployment"); }
    @Override public @NonNull String slug() { return "previews"; }
    @Override public @NonNull Model model() { return Models.get(PreviewDeploymentModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.PROXY_GROUP; }
    @Override public int navOrder() { return 20; }
    @Override public @NonNull Icon icon() { return Icon.of("flask"); }

    @Override public boolean creatable() { return false; }

    @Override
    public @NonNull List<ResourceFieldBinding> fieldBindings() {
        List<ResourceFieldBinding> bindings = new ArrayList<>();
        for (var entry : this.formSpec.entries()) {
            bindings.add(ResourceFieldBinding.of(entry.name(), FieldAccess.alwaysReadonly()));
        }
        return bindings;
    }

    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        List<RowAction<Row>> actions = new ArrayList<>(super.rowActions());
        actions.add(RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "destroy_preview"))
            .label(Microcopy.of("destroy_now").withFilter("scope", "preview_deployment"))
            .icon(Icon.of("trash"))
            .handler((row, ctx) -> {
                try {
                    PreviewDeployments.destroy(
                        row.get(PreviewDeploymentModel.ID), "operator");
                    return CmsActionResult.toast(
                        Microcopy.of("destroyed").withFilter("scope", "preview_deployment")
                            .withArg("hostname",
                                row.get(PreviewDeploymentModel.HOSTNAME)));
                } catch (Exception failed) {
                    return CmsActionResult.errorToast(
                        Microcopy.of("destroy_failed").withFilter("scope", "preview_deployment")
                            .withArg("reason", failed.getMessage() != null
                                ? failed.getMessage() : failed.toString()));
                }
            })
            .build());
        return actions;
    }
}
