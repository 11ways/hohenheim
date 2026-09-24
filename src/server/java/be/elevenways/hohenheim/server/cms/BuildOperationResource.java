package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.BuildOperationModel;
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
 * Build history: the generated resource IS the page (list, sort, filter, paginate, and
 * a read-only detail form carrying the captured log), because a build operation offers
 * nothing beyond its own columns. The record is written by the build orchestrator only,
 * so every entry here is read-only -- an editable build outcome would be a second
 * authority over what a release is pinned to.
 */
public final class BuildOperationResource extends OperationHistoryResource {

    /** This resource's slug, which the instance list names as a related page. */
    public static final String SLUG = "builds";

    public BuildOperationResource() {
        super("build_operation");
    }

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
        // A build read-out answers "what ran and how did it end" first; the sandbox
        // ceilings it ran under and what it measured are the second question.
        .section(FormSection.advanced(
            BuildOperationModel.CPU_LIMIT.getName(),
            BuildOperationModel.MEMORY_LIMIT_MB.getName(),
            BuildOperationModel.DISK_LIMIT_MB.getName(),
            BuildOperationModel.PIDS_LIMIT.getName(),
            BuildOperationModel.TIMEOUT_SECONDS.getName(),
            BuildOperationModel.PEAK_DISK_BYTES.getName(),
            BuildOperationModel.ARTIFACT_BYTES.getName(),
            BuildOperationModel.DURATION_MS.getName()))
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(BuildOperationModel.ID).build())
        .column(ColumnSpec.fromField(BuildOperationModel.STATUS).filterable().build())
        .column(ColumnSpec.fromField(BuildOperationModel.BUILDER_KIND).filterable().build())
        .column(ColumnSpec.fromField(BuildOperationModel.FOR_MODEL).filterable().build())
        .column(ColumnSpec.fromField(BuildOperationModel.FOR_ID).build())
        .column(ColumnSpec.fromField(BuildOperationModel.SOURCE_REF).copyable().build())
        .column(ColumnSpec.fromField(BuildOperationModel.IMAGE_ID).copyable().build())
        .column(ColumnSpec.fromField(BuildOperationModel.DURATION_MS).build())
        .column(ColumnSpec.fromField(BuildOperationModel.STARTED_AT).sortable().build())
        .build();

    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Model model() { return Models.get(BuildOperationModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }

    /** A build is traced back from a commit, an image, a tag, or the reason it failed. */
    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(BuildOperationModel.SOURCE_REF, BuildOperationModel.IMAGE_ID, BuildOperationModel.TAG, BuildOperationModel.FAILURE_REASON);
    }

    @Override public int navOrder() { return 17; }

    @Override public @NonNull Icon icon() { return Icon.of("hammer"); }
}
