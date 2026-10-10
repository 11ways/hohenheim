package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.project.Projects;
import be.elevenways.protoblast.common.typed.CoreTypes;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
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
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only direct membership roster over the visible projects, with no second membership store.
 * User and project names are verbatim; column headings are localized.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class ProjectMembershipParts {
    public record Membership(@NonNull String key, int projectId, @NonNull String projectName,
                             @NonNull String subjectType, int subjectId, @NonNull String member) {}

    private static final StringField PROJECT = StringField.builder("project")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("project")).build();
    private static final StringField MEMBER = StringField.builder("member")
        .label(HohenheimMicrocopy.PROJECT.of("member")).build();
    private static final StringField KIND = StringField.builder("kind")
        .label(HohenheimMicrocopy.PROJECT.of("member_kind")).build();
    private static final StorePages<Membership> PAGES = new StorePages<>() {
        @Override public @NonNull List<Field<?, ?>> fields() { return List.of(PROJECT, MEMBER, KIND); }
        @Override public @NonNull RecordPage<Membership> page(TableView.@NonNull Applied<Membership> applied,
                                                               @NonNull AccessContext access) {
            return inMemory(applied, memberships(access), (row, column) -> values(row).get(column.name()), access);
        }
    };

    private ProjectMembershipParts() {}

    public static @NonNull PanelResource<Membership> manage() {
        // A Team cluster member with no admin twin, shown while the tenant is in a project.
        return ManageTwin.listed(PanelResource.builder(ManageTwin.id("project_member"), HohenheimSlugs.PROJECT_MEMBERS,
                    SubjectType.of(HohenheimIds.id("project_membership"), Membership.class, Membership::key)),
                access -> !Projects.visibleTo(access).isEmpty())
            .label(HohenheimMicrocopy.PROJECT.of("members"))
            .recordLabel(HohenheimMicrocopy.PROJECT_MEMBER.of("singular"))
            .description(CmsSupport.navHint(HohenheimMicrocopy.PROJECT_MEMBER)).navOrder(41).icon(Icon.of("users"))
            .form(ResourceForm.<Membership>of(FormSpec.builder().add(PROJECT).add(MEMBER).add(KIND).build()).build())
            .list(ResourceList.store(TableSpec.<Membership>builder()
                    .column(ColumnSpec.fromField(PROJECT).filterable().build())
                    .column(ColumnSpec.fromField(MEMBER).filterable().build())
                    .column(ColumnSpec.fromField(KIND).build())
                    .filter(FilterSpec.leaf(PROJECT, CoreTypes.CONTAINS).build())
                    .filter(FilterSpec.leaf(MEMBER, CoreTypes.CONTAINS).build())
                    .defaultSort(SortSpec.asc("project")).build(), PAGES)
                .chrome(ListChrome.MINIMAL).build())
            .reads(ResourceReads.<Membership>typed(Membership::key)
                .load((key, access) -> memberships(access).stream().filter(row -> row.key().equals(key))
                    .findFirst().orElse(null))
                .values(ProjectMembershipParts::values).build().title(Membership::member))
            .build();
    }

    /** @return the membership's value of each declared field */
    private static @NonNull Map<String, Object> values(@NonNull Membership row) {
        return Map.of(PROJECT.getName(), row.projectName(), MEMBER.getName(), row.member(),
            KIND.getName(), row.subjectType());
    }

    private static List<Membership> memberships(AccessContext access) {
        List<Membership> rows = new ArrayList<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (Row project : Projects.visibleTo(access)) {
            Integer id = project.get(ProjectModel.ID);
            if (id == null) continue;
            String name = String.valueOf((Object) project.get(ProjectModel.NAME));
            // AIDEV-NOTE: nested groups stay as direct members; expanding them would disclose unrelated rosters.
            for (Projects.Member member : Projects.directMembersOf(project)) {
                String subject = member.subjectType() + ":" + member.subjectId();
                rows.add(new Membership(id + ":" + subject, id, name, member.subjectType(), member.subjectId(),
                    labels.computeIfAbsent(subject, HohenheimAccess::subjectLabel)));
            }
        }
        return rows;
    }
}
