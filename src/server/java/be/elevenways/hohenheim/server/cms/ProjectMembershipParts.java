package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.zenit.auth.model.GrantModel;
import be.elevenways.zenit.common.live.LiveFeed;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.project.Projects;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.typed.CoreTypes;
import be.elevenways.zenit.auth.cms.SubjectTag;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.server.cms.AuthAdminParts;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.resource.ChildList;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.StorePages;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.SortSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.cms.common.schema.TableView;
import be.elevenways.zenit.cms.server.panel.PartsReads;
import be.elevenways.zenit.common.data.RecordPage;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only direct membership roster over the visible projects, with no second membership store: the Team roster on
 * /manage and the Members tab of a project's page on both panels.
 * User and project names are verbatim; column headings and the member kind are localized.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class ProjectMembershipParts {
    public record Membership(@NonNull String key, int projectId, @NonNull String projectName,
                             @NonNull String subjectType, @NonNull UUID subjectId, @NonNull String member) {}

    private static final StringField PROJECT = StringField.builder("project")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("project")).build();
    private static final StringField MEMBER = StringField.builder("member")
        .label(HohenheimMicrocopy.PROJECT.of("member")).build();
    private static final EnumField KIND = kindField();
    private static final StorePages<Membership> PAGES = new StorePages<>() {
        @Override public @NonNull List<Field<?, ?>> fields() { return List.of(PROJECT, MEMBER, KIND); }
        @Override public @NonNull RecordPage<Membership> page(TableView.@NonNull Applied<Membership> applied,
                                                               @NonNull AccessContext access) {
            return inMemory(applied, memberships(access), ProjectMembershipParts::cell, access);
        }
    };

    /** The Members tab of a project's admin page, pointing at the People page where someone is given its role. */
    static @NonNull ChildList<Row> adminMembers() {
        return ChildList.<Row>of(admin())
            .icon(Icon.of("users"))
            .lead(HohenheimMicrocopy.PROJECT.of("members_lead"))
            .hide(HohenheimSlugs.PROJECT_MEMBERS, PROJECT.getName())
            .headerLink(HohenheimSlugs.PROJECT_MEMBERS,
                PanelAction.<Row>link(HohenheimIds.id("project_members_add"), ActionPlacement.HEADER)
                    .label(HohenheimMicrocopy.PROJECT.of("add_member"))
                    .icon(Icon.of("user-plus"))
                    .route((project, request) -> CmsRoutes.list(request.panelSlug(), AuthAdminParts.USERS_SLUG))
                    .build());
    }

    /** The Members tab of a project's /manage page: no header link to the People page a tenant cannot reach. */
    static @NonNull ChildList<Row> manageMembers() {
        return ChildList.<Row>of(manage())
            .icon(Icon.of("users"))
            .hide(HohenheimSlugs.PROJECT_MEMBERS, PROJECT.getName());
    }

    private ProjectMembershipParts() {}

    /** @return the operator's roster, listed on each project's page and never in the sidebar */
    public static @NonNull PanelResource<Membership> admin() {
        return entry(HohenheimIds.id("project_member"))
            .showInNav(false)
            .standsUnder(HohenheimSlugs.PROJECTS)
            .build();
    }

    public static @NonNull PanelResource<Membership> manage() {
        // A Team cluster member, shown while the tenant is in a project.
        return ManageTwin.listed(entry(ManageTwin.id("project_member")).navOrder(41),
                access -> !Projects.visibleTo(access).isEmpty())
            .build();
    }

    /** The feed the member lists hear: a project or a membership grant written. */
    private static final LiveFeed MEMBERS_FEED =
        LiveFeed.of(AccessContext::isAccount, ProjectModel.MODEL_ID, GrantModel.MODEL_ID);

    /** The identity, parent, list and reads both twins share. */
    private static PanelResource.@NonNull Builder<Membership> entry(@NonNull Identifier id) {
        return PanelResource.builder(id, HohenheimSlugs.PROJECT_MEMBERS,
                SubjectType.of(HohenheimIds.id("project_membership"), Membership.class, Membership::key))
            .label(HohenheimMicrocopy.PROJECT.of("members"))
            .recordLabel(HohenheimMicrocopy.PROJECT_MEMBER.of("singular"))
            .description(CmsSupport.navHint(HohenheimMicrocopy.PROJECT_MEMBER)).icon(Icon.of("users"))
            .parent(ResourceParent.<Membership>of(HohenheimSlugs.PROJECTS, Membership::projectId)
                .tab(HohenheimSlugs.Tab.MEMBERS))
            .form(ResourceForm.<Membership>of(FormSpec.builder().add(PROJECT).add(MEMBER).add(KIND).build()).build())
            // The member leads: it is the row's title, which opens the member's own page (memberPage).
            .list(ResourceList.store(TableSpec.<Membership>builder()
                    .column(ColumnSpec.fromField(MEMBER).filterable().build())
                    .column(ColumnSpec.fromField(PROJECT).filterable().build())
                    .column(ColumnSpec.fromField(KIND).build())
                    .filter(FilterSpec.leaf(PROJECT, CoreTypes.CONTAINS).build())
                    .filter(FilterSpec.leaf(MEMBER, CoreTypes.CONTAINS).build())
                    .defaultSort(SortSpec.asc("project")).build(), PAGES,
                    (projectKey, applied, access) -> PAGES.inMemory(applied, memberships(access).stream()
                        .filter(row -> String.valueOf(row.projectId()).equals(String.valueOf(projectKey)))
                        .toList(), ProjectMembershipParts::cell, access), MEMBERS_FEED)
                .chrome(ListChrome.MINIMAL).rowLink(ProjectMembershipParts::memberPage).build())
            .reads(ResourceReads.<Membership>typed(Membership::key)
                .load((key, access) -> memberships(access).stream().filter(row -> row.key().equals(key))
                    .findFirst().orElse(null))
                .values(ProjectMembershipParts::values).build()
                .title(membership -> Microcopy.literalOrNull(membership.member())));
    }

    /** @return the membership's value of each declared field */
    private static @NonNull Map<String, Object> values(@NonNull Membership row) {
        return Map.of(PROJECT.getName(), row.projectName(), MEMBER.getName(), row.member(),
            KIND.getName(), row.subjectType());
    }

    private static @Nullable Object cell(@NonNull Membership row, @NonNull ColumnSpec column) {
        return values(row).get(column.name());
    }

    /**
     * The member's own page (a person's, a group's) for a viewer this panel lets open it.
     *
     * AIDEV-NOTE: a viewer who may not open it keeps the framework's fallback, the membership's own page: a row title
     * never loses its link (PanelRowLinks).
     */
    private static @Nullable RouteTarget memberPage(@NonNull Membership row, @NonNull PanelRequest request) {
        GrantSubjectType type = GrantSubjectType.fromStored(row.subjectType());
        return type == null ? null
            : PartsReads.openRoute(request.panel(), type.modelId(), row.subjectId(), request.access());
    }

    /** The member kind in words: each grant subject type's own label, keyed by the type's stored key. */
    private static @NonNull EnumField kindField() {
        EnumField.Builder kind = EnumField.builder("kind").label(HohenheimMicrocopy.PROJECT.of("member_kind"));
        for (GrantSubjectType type : GrantSubjectType.values()) {
            kind.value(type.key(), type.label());
        }
        return kind.build();
    }

    /**
     * Every direct membership of the projects the reader sees: a fixed number of reads (the projects, their groups,
     * their grants, the members' labels), whatever the number of projects and members.
     */
    static List<Membership> memberships(AccessContext access) {
        List<Row> projects = new ArrayList<>();
        for (Row project : Projects.visibleTo(access)) {
            if (project.get(ProjectModel.ID) != null) projects.add(project);
        }
        // AIDEV-NOTE: nested groups stay as direct members; expanding them would disclose unrelated rosters.
        List<List<SubjectTag>> members = Projects.directMembersOf(projects);
        List<String> subjects = new ArrayList<>();
        for (List<SubjectTag> projectMembers : members) {
            for (SubjectTag member : projectMembers) subjects.add(member.token());
        }
        Map<String, String> labels = HohenheimAccess.subjectLabels(subjects);
        List<Membership> rows = new ArrayList<>();
        for (int i = 0; i < projects.size(); i++) {
            Row project = projects.get(i);
            Integer id = project.get(ProjectModel.ID);
            String name = String.valueOf((Object) project.get(ProjectModel.NAME));
            for (SubjectTag member : members.get(i)) {
                String subject = member.token();
                rows.add(new Membership(id + ":" + subject, id, name, member.type().key(), member.id(),
                    labels.get(subject)));
            }
        }
        return rows;
    }
}
