package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldAccess;
import be.elevenways.zenit.common.edit.FormEntry;
import be.elevenways.zenit.common.edit.FormSection;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * The operation HISTORIES an orchestrator writes, from shared parts: the build history and the release history, each
 * nav-hidden, never created here, and every entry read-only.
 *
 * AIDEV-NOTE: the rows are written by their engine only (the build orchestrator, the release engine), so every field
 * is read-only through its FieldAccess binding -- an editable image pin or step log would be a second authority over
 * what ran and what served when. The generated resource IS the page: a history offers nothing beyond its columns.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class OperationHistoryParts {

    /** The build history's slug, which the instance list names as a related page. */
    public static final String BUILDS = "builds";

    /** The release history's slug, which the instance list names as a related page. */
    public static final String RELEASES = "releases";

    private OperationHistoryParts() {
    }

    /** @return the build history, with a read-only detail form carrying the captured log */
    public static @NonNull PanelResource<Row> builds() {
        FormSpec form = FormSpec.builder()
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
            // A build read-out answers "what ran and how did it end" first; the sandbox ceilings it ran under and
            // what it measured are the second question.
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
        TableSpec<Row> table = TableSpec.<Row>builder()
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
        // A build is traced back from a commit, an image, a tag, or the reason it failed.
        return history("build_operation", BUILDS, SubjectType.record(BuildOperationModel.MODEL_ID), form, table,
            List.of(BuildOperationModel.SOURCE_REF, BuildOperationModel.IMAGE_ID, BuildOperationModel.TAG,
                BuildOperationModel.FAILURE_REASON))
            .navOrder(17)
            .icon(Icon.of("hammer"))
            .build();
    }

    /** @return the release history: what went live, whether it worked, and the instances it swapped */
    public static @NonNull PanelResource<Row> releases() {
        FormSpec form = FormSpec.builder()
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
            // What went live and whether it worked reads first; which record it was for and which instances it
            // swapped are the plumbing behind that answer.
            .section(FormSection.advanced(
                ReleaseOperationModel.FOR_MODEL.getName(),
                ReleaseOperationModel.FOR_ID.getName(),
                ReleaseOperationModel.CANDIDATE_INSTANCE_ID.getName(),
                ReleaseOperationModel.RETIRED_INSTANCE_ID.getName(),
                ReleaseOperationModel.DURATION_MS.getName()))
            .build();
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(ReleaseOperationModel.ID).build())
            .column(ColumnSpec.fromField(ReleaseOperationModel.STATUS).filterable().build())
            .column(ColumnSpec.fromField(ReleaseOperationModel.KIND).filterable().build())
            .column(ColumnSpec.fromField(ReleaseOperationModel.FOR_MODEL).filterable().build())
            .column(ColumnSpec.fromField(ReleaseOperationModel.FOR_ID).build())
            .column(ColumnSpec.fromField(ReleaseOperationModel.IMAGE_ID).copyable().build())
            .column(ColumnSpec.fromField(ReleaseOperationModel.DURATION_MS).build())
            .column(ColumnSpec.fromField(ReleaseOperationModel.STARTED_AT).sortable().build())
            .build();
        // A release is traced back from the image it shipped or the reason it did not.
        return history("release_operation", RELEASES, SubjectType.record(ReleaseOperationModel.MODEL_ID), form,
            table, List.of(ReleaseOperationModel.IMAGE_ID, ReleaseOperationModel.FAILURE_REASON))
            .navOrder(18)
            .icon(Icon.of("rocket"))
            .build();
    }

    /**
     * The shape both histories share: nav-hidden in the deploy group, the wide list, every form entry read-only, no
     * create.
     *
     * AIDEV-NOTE: the update and delete stay offered, as on the legacy history: the update over an all-read-only form
     * writes nothing, and the delete is the operator's cleanup of a history row.
     *
     * @param scope the microcopy scope, which is also the identifier path
     */
    private static PanelResource.@NonNull Builder<Row> history(@NonNull String scope, @NonNull String slug,
                                                               @NonNull SubjectType<Row> subject,
                                                               @NonNull FormSpec form, @NonNull TableSpec<Row> table,
                                                               @NonNull List<Field<?, ?>> search) {
        List<ResourceFieldBinding> readOnly = new ArrayList<>();
        for (FormEntry entry : form.entries()) {
            readOnly.add(ResourceFieldBinding.of(entry.name(), FieldAccess.alwaysReadonly()));
        }
        return PanelResource.builder(HohenheimIds.id(scope), slug, subject)
            .label(Microcopy.of("plural").withFilter("scope", scope))
            .recordLabel(Microcopy.of("singular").withFilter("scope", scope))
            // Demoted out of the sidebar, so this sentence reaches a reader through the panel index and the
            // related-pages menu of the list that names it.
            .description(CmsSupport.navHint(scope))
            .navGroup(HohenheimPanel.DEPLOY_GROUP)
            .showInNav(false)
            .reads(ResourceReads.rows())
            .list(ResourceList.rows(table).chrome(CmsSupport.WIDE_LIST).facets().ruleFilters()
                .search(search.toArray(Field<?, ?>[]::new)).build())
            .form(ResourceForm.<Row>of(form).bindings(readOnly).build())
            .writes(ResourceMutations.rows().update().delete().build())
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions());
    }
}
