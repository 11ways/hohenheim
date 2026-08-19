package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
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
 * Build history: the generated resource IS the page (list, sort, filter, paginate, and
 * a read-only detail form carrying the captured log), because a build operation offers
 * nothing beyond its own columns. The record is written by the build orchestrator only,
 * so every entry here is read-only -- an editable build outcome would be a second
 * authority over what a release is pinned to.
 */
public final class BuildOperationResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(BuildOperationModel.BUILDER_KIND)
        .add(BuildOperationModel.FOR_MODEL)
        .add(BuildOperationModel.FOR_ID)
        .add(BuildOperationModel.STATUS)
        .add(BuildOperationModel.SOURCE_REF)
        .add(BuildOperationModel.IMAGE_ID)
        .add(BuildOperationModel.TAG)
        .add(BuildOperationModel.EXIT_CODE)
        .add(BuildOperationModel.FAILURE_REASON)
        .add(BuildOperationModel.CPU_LIMIT)
        .add(BuildOperationModel.MEMORY_LIMIT_MB)
        .add(BuildOperationModel.DISK_LIMIT_MB)
        .add(BuildOperationModel.PIDS_LIMIT)
        .add(BuildOperationModel.TIMEOUT_SECONDS)
        .add(BuildOperationModel.PEAK_DISK_BYTES)
        .add(BuildOperationModel.ARTIFACT_BYTES)
        .add(BuildOperationModel.DURATION_MS)
        .add(BuildOperationModel.LOG)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(BuildOperationModel.ID).build())
        .column(ColumnSpec.fromField(BuildOperationModel.STATUS).filterable().build())
        .column(ColumnSpec.fromField(BuildOperationModel.BUILDER_KIND).filterable().build())
        .column(ColumnSpec.fromField(BuildOperationModel.FOR_MODEL).filterable().build())
        .column(ColumnSpec.fromField(BuildOperationModel.FOR_ID).build())
        .column(ColumnSpec.fromField(BuildOperationModel.SOURCE_REF).build())
        .column(ColumnSpec.fromField(BuildOperationModel.IMAGE_ID).build())
        .column(ColumnSpec.fromField(BuildOperationModel.DURATION_MS).build())
        .column(ColumnSpec.fromField(BuildOperationModel.STARTED_AT).sortable().build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "build_operation"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "build_operation"); }
    @Override public @NonNull String slug() { return "builds"; }
    @Override public @NonNull Model model() { return Models.get(BuildOperationModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.DEPLOY_GROUP; }
    @Override public int navOrder() { return 17; }

    @Override public boolean showInNav() { return false; }
    @Override public @NonNull Icon icon() { return Icon.of("hammer"); }

    @Override public boolean creatable() { return false; }

    /**
     * Every field is read-only through the FieldAccess binding (the ActivityResource
     * shape): the orchestrator owns these rows, and an editable {@code image_id} would be
     * a second authority over what a release runs.
     */
    @Override
    public @NonNull List<ResourceFieldBinding> fieldBindings() {
        List<ResourceFieldBinding> bindings = new ArrayList<>();
        for (var entry : this.formSpec.entries()) {
            bindings.add(ResourceFieldBinding.of(entry.name(), FieldAccess.alwaysReadonly()));
        }
        return bindings;
    }
}
