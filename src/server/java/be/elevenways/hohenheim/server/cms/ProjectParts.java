package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.server.quota.OwnerBudget;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.project.Projects;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.RecordGrantModel;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.CmsMicrocopy;
import be.elevenways.zenit.cms.common.resource.DeleteConfirmation;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.QuickCreateSpec;
import be.elevenways.zenit.cms.common.resource.RelatedPage;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.operation.OperationHandlers;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Objects;

/**
 * Project owner-tier parts. User names/descriptions stay verbatim; generated labels and refusals are localized.
 *
 * AIDEV-NOTE: ProjectGuards owns the group's lifecycle for every write lane. The tenant mirror is deliberately
 * read-only: a membership write requires the nondelegable grant-administration boundary, not project membership.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class ProjectParts {
    private static final SubjectType<Row> SUBJECT = SubjectType.record(ProjectModel.MODEL_ID);

    /** The list's computed count columns. */
    private static final String MEMBERS_COLUMN = "members";
    private static final String OWNED_SITES_COLUMN = "owned_sites";
    private static final String OWNED_INSTANCES_COLUMN = "owned_instances";
    private static final String INSTANCE_QUOTA_COLUMN = "instance_quota";

    public static final Operation<Row, Void, Integer> DELETE = Operation.declare(HohenheimIds.id("delete_project"))
        .happened(OperationSentences.of("delete_project"))
        .label(CmsMicrocopy.of("delete"))
        .one(SUBJECT).gate(OperationGate.permission(HohenheimPanel.ACCESS))
        .result(Integer.class).facts(OperationFact.DESTRUCTIVE).command(CmsCommands.TRANSACTIONAL).register();

    static {
        OperationHandlers.attach(DELETE).handle(call -> {
            Models.get(ProjectModel.class).delete(call.subject());
            return 1;
        });
    }

    private ProjectParts() {}

    public static @NonNull PanelResource<Row> admin() {
        return entry(HohenheimIds.id("project"), 10)
            // Reached from the Apps list's toolbar (HohenheimPanel's sidebar note); its pages mark Apps in the sidebar.
            .showInNav(false)
            .standsUnder(HohenheimSlugs.APPS)
            .form(ResourceForm.<Row>of(formSpec())
                .quickCreate(QuickCreateSpec.of(ProjectModel.NAME.getName(), ProjectModel.DESCRIPTION.getName()))
                .inlineEditable(ProjectModel.NAME, ProjectModel.DESCRIPTION).build())
            .writes(ResourceMutations.rows().create().update().delete(DELETE).build())
            .deleteConfirmation(DeleteConfirmation.of(
                DeleteConfirmation.body(HohenheimMicrocopy.PROJECT.of("delete_confirm"))))
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions())
            .relatedPages(RelatedPage.toPeer(HohenheimSlugs.ENVIRONMENTS))
            .build();
    }

    public static @NonNull PanelResource<Row> manage() {
        // A Team cluster member, shown while the tenant is in a project.
        return ManageTwin.listed(entry(ManageTwin.id("project"), 40), TenantScopes.PROJECTS, ResourceTabs.none(),
                access -> !Projects.visibleTo(access).isEmpty())
            .form(ResourceForm.<Row>of(formSpec()).build())
            .build();
    }

    private static PanelResource.@NonNull Builder<Row> entry(@NonNull Identifier id, int order) {
        return PanelResource.builder(id, HohenheimSlugs.PROJECTS, SUBJECT)
            .label(HohenheimMicrocopy.PROJECT.of("plural"))
            .recordLabel(HohenheimMicrocopy.PROJECT.of("singular"))
            .description(CmsSupport.navHint(HohenheimMicrocopy.PROJECT))
            .navGroup(HohenheimPanel.DEPLOY_GROUP).navOrder(order).icon(Icon.of("folder-tree"))
            .list(list())
            .reads(ResourceReads.rows());
    }

    /** The list: each count column reads its project's group, and a project without one counts nothing. */
    private static @NonNull ResourceList<Row> list() {
        TableSpec<Row> table = tableSpec();
        return ResourceList.rows(table).chrome(ListChrome.MINIMAL)
            .search(ProjectModel.NAME, ProjectModel.DESCRIPTION)
            .computed(Objects.requireNonNull(table.column(MEMBERS_COLUMN)),
                (row, request) -> groupOf(row) == null ? 0 : Projects.directMembersOf(row).size())
            .computed(Objects.requireNonNull(table.column(OWNED_SITES_COLUMN)),
                (row, request) -> ownedCount(groupOf(row), SiteModel.MODEL_ID))
            .computed(Objects.requireNonNull(table.column(OWNED_INSTANCES_COLUMN)),
                (row, request) -> ownedCount(groupOf(row), InstanceModel.MODEL_ID))
            .computed(Objects.requireNonNull(table.column(INSTANCE_QUOTA_COLUMN)),
                (row, request) -> quota(row, groupOf(row)))
            .build();
    }

    static @NonNull FormSpec formSpec() {
        return FormSpec.builder().add(ProjectModel.NAME).add(ProjectModel.DESCRIPTION).build();
    }

    static @NonNull TableSpec<Row> tableSpec() {
        return TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(ProjectModel.NAME).filterable().subtext("description").build())
            .column(ColumnSpec.fromField(ProjectModel.DESCRIPTION).hidden().build())
            .column(ColumnSpec.virtual(MEMBERS_COLUMN, HohenheimMicrocopy.PROJECT.of("members")).build())
            .column(ColumnSpec.virtual(OWNED_SITES_COLUMN, HohenheimMicrocopy.PROJECT.of("owned_sites")).build())
            .column(ColumnSpec.virtual(OWNED_INSTANCES_COLUMN,
                HohenheimMicrocopy.PROJECT.of("owned_instances")).build())
            .column(ColumnSpec.virtual(INSTANCE_QUOTA_COLUMN,
                HohenheimMicrocopy.PROJECT.of("instance_quota")).build()).build();
    }

    private static @Nullable Integer groupOf(@NonNull Row project) {
        return project.get(ProjectModel.GROUP_ID);
    }

    private static String quota(Row row, @Nullable Integer group) {
        if (group == null) return "-";
        String packed = HohenheimAccess.packSubjects(Projects.ownerSubjectsOf(row));
        Integer limit = OwnerBudget.INSTANCES.limitFor(packed);
        return OwnerBudget.INSTANCES.usedBy(packed) + " / " + (limit == null ? "-" : String.valueOf(limit));
    }

    private static long ownedCount(@Nullable Integer group, Identifier model) {
        if (group == null) return 0;
        return RecordGrants.listForSubject(GrantSubjectType.GROUP, group).stream()
            .filter(grant -> HohenheimCapabilities.MANAGE.equals(grant.get(RecordGrantModel.CAPABILITY))
                && Boolean.TRUE.equals(grant.get(RecordGrantModel.VALUE))
                && model.toString().equals(grant.get(RecordGrantModel.MODEL))).count();
    }
}
