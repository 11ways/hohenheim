package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldAccess;
import be.elevenways.zenit.common.edit.FormSection;
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
 * Release history: the generated resource IS the page (the BuildOperationResource
 * shape), because a release operation offers nothing beyond its own columns. The
 * record is written by the release engine only, so every entry is read-only -- an
 * editable step log or image pin would be a second authority over what served when.
 */
public final class ReleaseOperationResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(ReleaseOperationModel.KIND)
        .add(ReleaseOperationModel.FOR_MODEL)
        .add(ReleaseOperationModel.FOR_ID)
        .add(ReleaseOperationModel.STATUS)
        .add(ReleaseOperationModel.IMAGE_ID)
        .add(ReleaseOperationModel.CANDIDATE_INSTANCE_ID)
        .add(ReleaseOperationModel.RETIRED_INSTANCE_ID)
        .add(ReleaseOperationModel.FAILURE_REASON)
        .add(ReleaseOperationModel.DURATION_MS)
        .add(ReleaseOperationModel.STEP_LOG)
        // What went live and whether it worked reads first; which record it was for and
        // which instances it swapped are the plumbing behind that answer.
        .section(FormSection.advanced(
            ReleaseOperationModel.FOR_MODEL.getName(),
            ReleaseOperationModel.FOR_ID.getName(),
            ReleaseOperationModel.CANDIDATE_INSTANCE_ID.getName(),
            ReleaseOperationModel.RETIRED_INSTANCE_ID.getName(),
            ReleaseOperationModel.DURATION_MS.getName()))
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(ReleaseOperationModel.ID).build())
        .column(ColumnSpec.fromField(ReleaseOperationModel.STATUS).filterable().build())
        .column(ColumnSpec.fromField(ReleaseOperationModel.KIND).filterable().build())
        .column(ColumnSpec.fromField(ReleaseOperationModel.FOR_MODEL).filterable().build())
        .column(ColumnSpec.fromField(ReleaseOperationModel.FOR_ID).build())
        .column(ColumnSpec.fromField(ReleaseOperationModel.IMAGE_ID).copyable().build())
        .column(ColumnSpec.fromField(ReleaseOperationModel.DURATION_MS).build())
        .column(ColumnSpec.fromField(ReleaseOperationModel.STARTED_AT).sortable().build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "release_operation"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "release_operation"); }
    @Override public @Nullable Microcopy recordLabel() { return Microcopy.of("singular").withFilter("scope", "release_operation"); }
    @Override public @NonNull String slug() { return "releases"; }
    @Override public @NonNull Model model() { return Models.get(ReleaseOperationModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull ListChrome listChrome() { return CmsSupport.WIDE_LIST; }

    /** A release is traced back from the image it shipped or the reason it did not. */
    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(ReleaseOperationModel.IMAGE_ID, ReleaseOperationModel.FAILURE_REASON);
    }

    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.DEPLOY_GROUP; }
    @Override public int navOrder() { return 18; }

    /**
     * Demoted out of the sidebar, so this sentence reaches a reader through the panel
     * index and the related-pages menu of the list that names it.
     */
    @Override public @Nullable Microcopy description() { return CmsSupport.navHint("release_operation"); }

    @Override public boolean showInNav() { return false; }
    @Override public @NonNull Icon icon() { return Icon.of("rocket"); }

    @Override public boolean creatable() { return false; }

    /** Every field read-only (the BuildOperationResource shape): the engine owns these rows. */
    @Override
    public @NonNull List<ResourceFieldBinding> fieldBindings() {
        List<ResourceFieldBinding> bindings = new ArrayList<>();
        for (var entry : this.formSpec.entries()) {
            bindings.add(ResourceFieldBinding.of(entry.name(), FieldAccess.alwaysReadonly()));
        }
        return bindings;
    }
}
