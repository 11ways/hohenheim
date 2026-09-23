package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FormSection;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * Release history: the generated resource IS the page (the BuildOperationResource
 * shape), because a release operation offers nothing beyond its own columns. The
 * record is written by the release engine only, so every entry is read-only -- an
 * editable step log or image pin would be a second authority over what served when.
 */
public final class ReleaseOperationResource extends OperationHistoryResource {

    public ReleaseOperationResource() {
        super("release_operation");
    }

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

    @Override public @NonNull String slug() { return "releases"; }
    @Override public @NonNull Model model() { return Models.get(ReleaseOperationModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }

    /** A release is traced back from the image it shipped or the reason it did not. */
    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(ReleaseOperationModel.IMAGE_ID, ReleaseOperationModel.FAILURE_REASON);
    }

    @Override public int navOrder() { return 18; }

    @Override public @NonNull Icon icon() { return Icon.of("rocket"); }
}
