package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.project.Projects;
import be.elevenways.protoblast.common.i18n.Microcopy;
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
import org.checkerframework.checker.nullness.qual.Nullable;

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
        .label(HohenheimFormCopy.label("project")).build();
    private static final StringField MEMBER = StringField.builder("member")
        .label(Microcopy.of("member").withFilter("scope", "project")).build();
    private static final StringField KIND = StringField.builder("kind")
        .label(Microcopy.of("member_kind").withFilter("scope", "project")).build();
    private static final StorePages<Membership> PAGES = new StorePages<>() {
        @Override public @NonNull List<Field<?, ?>> fields() { return List.of(PROJECT, MEMBER, KIND); }
        @Override public @NonNull RecordPage<Membership> page(TableView.@NonNull Applied<Membership> applied,
                                                               @NonNull AccessContext access) {
            return inMemory(applied, memberships(access), ProjectMembershipParts::cell, access);
        }
    };

    /** The entry's slug, a member of the /manage Team cluster. */
    public static final String SLUG = "project-members";

    private ProjectMembershipParts() {}

    public static @NonNull PanelResource<Membership> manage() {
        return PanelResource.builder(HohenheimIds.id("manage_project_member"), SLUG,
                SubjectType.of(HohenheimIds.id("project_membership"), Membership.class, Membership::key))
            .label(Microcopy.of("members").withFilter("scope", "project"))
            .recordLabel(Microcopy.of("singular").withFilter("scope", "project_member"))
            .description(CmsSupport.navHint("project_member")).navOrder(41).icon(Icon.of("users"))
            .hasInScopeRecords(access -> !Projects.visibleTo(access).isEmpty())
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
                .values(row -> Map.of("project", row.projectName(), "member", row.member(), "kind", row.subjectType()))
                .cells(ProjectMembershipParts::cell).build().title(Membership::member))
            .build();
    }

    private static @Nullable Object cell(Membership row, ColumnSpec column) {
        return switch (column.name()) {
            case "project" -> row.projectName();
            case "member" -> row.member();
            case "kind" -> row.subjectType();
            default -> null;
        };
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
