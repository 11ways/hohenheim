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
import be.elevenways.zenit.auth.cms.SubjectTag;
import be.elevenways.zenit.cms.common.resource.CellMapper;
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
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.server.operation.OperationHandlers;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

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
    private static final SubjectType<Row> SUBJECT = SubjectType.record(ProjectModel.REF);

    /** The list's computed count columns. */
    static final String MEMBERS_COLUMN = "members";
    static final String OWNED_SITES_COLUMN = "owned_sites";
    static final String OWNED_INSTANCES_COLUMN = "owned_instances";
    static final String INSTANCE_QUOTA_COLUMN = "instance_quota";

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
            .tabs(ResourceTabs.<Row>of(List.of(EnvironmentParts.projectEnvironments(),
                    ProjectMembershipParts.adminMembers()))
                .withHistory().withContributions())
            .relatedPages(RelatedPage.toPeer(HohenheimSlugs.ENVIRONMENTS))
            .build();
    }

    public static @NonNull PanelResource<Row> manage() {
        // A Team cluster member, shown while the tenant is in a project.
        return ManageTwin.listed(entry(ManageTwin.id("project"), 40), TenantScopes.PROJECTS,
                ResourceTabs.<Row>of(List.of(ProjectMembershipParts.manageMembers())),
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
            .reads(ResourceReads.rows().pageBatch(ProjectParts::countsPage));
    }

    /**
     * The list: its count columns are read for the whole page at once ({@link #countsPage}); a one-record read asks
     * the same batch for its one project.
     */
    private static @NonNull ResourceList<Row> list() {
        TableSpec<Row> table = tableSpec();
        ResourceList.Builder<Row> list = ResourceList.rows(table).chrome(ListChrome.MINIMAL)
            .search(ProjectModel.NAME, ProjectModel.DESCRIPTION);
        for (String name : List.of(MEMBERS_COLUMN, OWNED_SITES_COLUMN, OWNED_INSTANCES_COLUMN,
                INSTANCE_QUOTA_COLUMN)) {
            ColumnSpec column = Objects.requireNonNull(table.column(name));
            list.computed(column, (project, request) -> countsPage(List.of(project), request.access())
                .cell(project, column));
        }
        return list.build();
    }

    /**
     * The page's count columns: each project's direct members, the sites and instances its group manages and its
     * owner bucket's spend against its cap, read set-wise for every listed project; a project without a group counts
     * nothing.
     */
    static @NonNull CellMapper<Row> countsPage(@NonNull List<Row> projects, @NonNull AccessContext access) {
        List<Row> grouped = new ArrayList<>();
        Set<UUID> groups = new LinkedHashSet<>();
        Map<UUID, String> buckets = new LinkedHashMap<>();
        for (Row project : projects) {
            UUID group = groupOf(project);
            if (group == null) continue;
            grouped.add(project);
            groups.add(group);
            buckets.put(group, OwnerBudget.INSTANCES.bucketOf(
                HohenheimAccess.packSubjects(Projects.ownerSubjectsOf(project))));
        }
        Map<UUID, Integer> members = new HashMap<>();
        List<List<SubjectTag>> direct = Projects.directMembersOf(grouped);
        for (int at = 0; at < grouped.size(); at++) members.put(groupOf(grouped.get(at)), direct.get(at).size());
        Map<UUID, List<Row>> held = RecordGrants.liveAllowsHeldBy(GrantSubjectType.GROUP, groups);
        Map<String, @Nullable Integer> limits = OwnerBudget.INSTANCES.limitsIn(buckets.values());
        Map<String, Long> used = OwnerBudget.INSTANCES.usedIn(buckets.values());
        return (project, column) -> {
            UUID group = groupOf(project);
            return switch (column.name()) {
                case MEMBERS_COLUMN -> group == null ? 0 : members.getOrDefault(group, 0);
                case OWNED_SITES_COLUMN -> ownedCount(held.get(group), SiteModel.MODEL_ID);
                case OWNED_INSTANCES_COLUMN -> ownedCount(held.get(group), InstanceModel.MODEL_ID);
                case INSTANCE_QUOTA_COLUMN -> group == null ? "-" : quota(limits.get(buckets.get(group)),
                    used.getOrDefault(buckets.get(group), 0L));
                default -> null;
            };
        };
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

    private static @Nullable UUID groupOf(@NonNull Row project) {
        return project.get(ProjectModel.GROUP_ID);
    }

    /** @return the instances an owner bucket holds, worded against its limit or as having none */
    private static @NonNull Object quota(@Nullable Integer limit, long used) {
        return limit == null ? HohenheimMicrocopy.PROJECT.of("quota_no_limit").withArg("used", used)
            : HohenheimMicrocopy.PROJECT.of("quota_used_of").withArg("used", used).withArg("limit", limit);
    }

    /** @return how many records of the model the group's live grants let it manage */
    private static long ownedCount(@Nullable List<Row> grants, Identifier model) {
        if (grants == null) return 0;
        return grants.stream()
            .filter(grant -> HohenheimCapabilities.MANAGE.equals(grant.get(RecordGrantModel.CAPABILITY))
                && model.toString().equals(grant.get(RecordGrantModel.MODEL))).count();
    }
}
