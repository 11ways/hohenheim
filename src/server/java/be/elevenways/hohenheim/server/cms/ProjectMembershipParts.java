package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.ProjectModel;
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
import be.elevenways.zenit.common.data.RecordPage;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    static final ChildList<Row> ADMIN_MEMBERS = ChildList.<Row>of(HohenheimSlugs.PROJECT_MEMBERS)
        .icon(Icon.of("users"))
        .lead(HohenheimMicrocopy.PROJECT.of("members_lead"))
        .hide(HohenheimSlugs.PROJECT_MEMBERS, PROJECT.getName())
        .headerLink(HohenheimSlugs.PROJECT_MEMBERS,
            PanelAction.<Row>link(HohenheimIds.id("project_members_add"), ActionPlacement.HEADER)
                .label(HohenheimMicrocopy.PROJECT.of("add_member"))
                .icon(Icon.of("user-plus"))
                .route((project, request) -> CmsRoutes.list(request.panelSlug(), AuthAdminParts.USERS_SLUG))
                .build());

    /** The Members tab of a project's /manage page; a ChildList resolves once per panel, so each panel has its own. */
    static final ChildList<Row> MANAGE_MEMBERS = ChildList.<Row>of(HohenheimSlugs.PROJECT_MEMBERS)
        .icon(Icon.of("users"))
        .hide(HohenheimSlugs.PROJECT_MEMBERS, PROJECT.getName());

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
            .list(ResourceList.store(TableSpec.<Membership>builder()
                    .column(ColumnSpec.fromField(PROJECT).filterable().build())
                    .column(ColumnSpec.fromField(MEMBER).filterable().build())
                    .column(ColumnSpec.fromField(KIND).build())
                    .filter(FilterSpec.leaf(PROJECT, CoreTypes.CONTAINS).build())
                    .filter(FilterSpec.leaf(MEMBER, CoreTypes.CONTAINS).build())
                    .defaultSort(SortSpec.asc("project")).build(), PAGES,
                    (projectKey, applied, access) -> PAGES.inMemory(applied, memberships(access).stream()
                        .filter(row -> String.valueOf(row.projectId()).equals(String.valueOf(projectKey)))
                        .toList(), ProjectMembershipParts::cell, access))
                .chrome(ListChrome.MINIMAL).build())
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

    /** The member kind in words: each grant subject type's own label, keyed by the type's stored key. */
    private static @NonNull EnumField kindField() {
        EnumField.Builder kind = EnumField.builder("kind").label(HohenheimMicrocopy.PROJECT.of("member_kind"));
        for (GrantSubjectType type : GrantSubjectType.values()) {
            kind.value(type.key(), type.label());
        }
        return kind.build();
    }

    private static List<Membership> memberships(AccessContext access) {
        List<Membership> rows = new ArrayList<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (Row project : Projects.visibleTo(access)) {
            Integer id = project.get(ProjectModel.ID);
            if (id == null) continue;
            String name = String.valueOf((Object) project.get(ProjectModel.NAME));
            // AIDEV-NOTE: nested groups stay as direct members; expanding them would disclose unrelated rosters.
            for (SubjectTag member : Projects.directMembersOf(project)) {
                String subject = member.token();
                rows.add(new Membership(id + ":" + subject, id, name, member.type().key(), member.id(),
                    labels.computeIfAbsent(subject, HohenheimAccess::subjectLabel)));
            }
        }
        return rows;
    }
}
