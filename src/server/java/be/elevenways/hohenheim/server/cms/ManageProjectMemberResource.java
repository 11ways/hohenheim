package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.server.project.Projects;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.auth.model.PermissionGroupModel;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.cms.common.resource.Resource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.SortSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.cms.common.schema.TableView;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WHO is in the tenant's projects: one row per DIRECT membership grant, over exactly
 * the projects {@link Projects#visibleTo} enumerates.
 *
 * A store-backed {@code Resource<T>} rather than a RowResource, because a membership is
 * not a row of any model -- it IS the {@code group.<slug>} grant, and the whole point of
 * the project tier is that authority has no second source of truth. Listing/sorting/
 * filtering/paging still come free through {@code applyTableView}; nothing is
 * hand-rolled. Read-only for the reason spelled out on {@link ManageProjectResource}:
 * every membership WRITE is governed by {@code GrantAdministration}, which no tenant can
 * satisfy, so an editor here could only ever refuse.
 *
 * AIDEV-NOTE: nested GROUP members are listed as themselves and never expanded. A group's
 * own members reach the project through the resolver's membership walk, so expanding them
 * here would print a membership list that no single grant backs -- and it would leak the
 * roster of an auth group the tenant has no authority over.
 */
public final class ManageProjectMemberResource extends Resource<ManageProjectMemberResource.Membership> {

    /** One direct membership, flattened for display; the grant remains the truth. */
    public record Membership(@NonNull String key, int projectId, @NonNull String projectName,
                             @NonNull String subjectType, int subjectId, @NonNull String member) {
    }

    private static final Schema SCHEMA = new Schema();

    private static final StringField PROJECT = SCHEMA.addField(StringField.builder("project")
        .label(Microcopy.of("singular").withFilter("scope", "project")).build());
    private static final StringField MEMBER = SCHEMA.addField(StringField.builder("member")
        .label(Microcopy.of("member").withFilter("scope", "project")).build());
    private static final StringField KIND = SCHEMA.addField(StringField.builder("kind")
        .label(Microcopy.of("member_kind").withFilter("scope", "project")).build());

    private final FormSpec formSpec = FormSpec.builder().add(PROJECT).add(MEMBER).add(KIND).build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "manage_project_member"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("members").withFilter("scope", "project"); }
    @Override public @NonNull String slug() { return "project-members"; }
    @Override public @NonNull Schema schema() { return SCHEMA; }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public int navOrder() { return 41; }
    @Override public @NonNull Icon icon() { return Icon.of("users"); }

    @Override public boolean creatable() { return false; }
    @Override public boolean updatable() { return false; }
    @Override public boolean deletable() { return false; }

    @Override
    public @NonNull TableSpec<Membership> tableSpec() {
        return TableSpec.<Membership>builder()
            .column(ColumnSpec.fromField(PROJECT).filterable().build())
            .column(ColumnSpec.fromField(MEMBER).filterable().build())
            .column(ColumnSpec.fromField(KIND).build())
            .filter(FilterSpec.forField(PROJECT, FilterSpec.Kind.TEXT).build())
            .filter(FilterSpec.forField(MEMBER, FilterSpec.Kind.TEXT).build())
            .defaultSort(SortSpec.asc("project"))
            .build();
    }

    @Override
    public @NonNull List<Membership> listRows(TableView.@NonNull Applied<Membership> applied,
                                              @NonNull AccessContext accessContext) {
        return this.applyTableView(applied, memberships(accessContext));
    }

    @Override
    public long countRows(TableView.Applied<Membership> applied,
                          @NonNull AccessContext accessContext) {
        return memberships(accessContext).size();
    }

    /** NAV-ONLY; the listing itself is scoped by the same enumeration. */
    @Override
    public boolean hasInScopeRecords(@NonNull AccessContext accessContext) {
        return !Projects.visibleTo(accessContext).isEmpty();
    }

    /**
     * The memberships of every VISIBLE project, and of no other -- a member of project A
     * learns nothing about who is in project B.
     */
    private static @NonNull List<Membership> memberships(@NonNull AccessContext ctx) {
        List<Membership> rows = new ArrayList<>();
        Map<Integer, String> userNames = new LinkedHashMap<>();
        Map<Integer, String> groupNames = new LinkedHashMap<>();
        for (Row project : Projects.visibleTo(ctx)) {
            Integer projectId = project.get(ProjectModel.ID);
            String projectName = String.valueOf((Object) project.get(ProjectModel.NAME));
            if (projectId == null) {
                continue;
            }
            for (Projects.Member member : Projects.directMembersOf(project)) {
                String name = "group".equals(member.subjectType())
                    ? groupNames.computeIfAbsent(member.subjectId(),
                        ManageProjectMemberResource::groupName)
                    : userNames.computeIfAbsent(member.subjectId(),
                        ManageProjectMemberResource::userName);
                rows.add(new Membership(projectId + ":" + member.subjectType() + ":" + member.subjectId(),
                    projectId, projectName, member.subjectType(), member.subjectId(), name));
            }
        }
        return rows;
    }

    private static @NonNull String userName(int userId) {
        Row user = AuthModels.users().findById(userId);
        if (user == null) {
            return "#" + userId;
        }
        String name = user.get(UserModel.DISPLAY_NAME);
        return name == null || name.isBlank()
            ? String.valueOf((Object) user.get(UserModel.EMAIL)) : name;
    }

    private static @NonNull String groupName(int groupId) {
        Row group = AuthModels.permissionGroups().findById(groupId);
        if (group == null) {
            return "#" + groupId;
        }
        String title = group.get(PermissionGroupModel.TITLE);
        return title == null || title.isBlank()
            ? String.valueOf((Object) group.get(PermissionGroupModel.SLUG)) : title;
    }

    @Override public @NonNull String rowKey(@NonNull Membership row) { return row.key(); }

    @Override
    public @Nullable Membership loadRow(@NonNull Object primaryKey, @NonNull AccessContext ctx) {
        String key = primaryKey.toString();
        for (Membership membership : memberships(ctx)) {
            if (membership.key().equals(key)) {
                return membership;
            }
        }
        return null;
    }

    @Override
    public @NonNull Map<String, Object> valuesFromRow(@NonNull Membership row) {
        return Map.of("project", row.projectName(), "member", row.member(), "kind", row.subjectType());
    }

    @Override
    public @Nullable Object cellValue(@NonNull Membership row, @NonNull ColumnSpec column) {
        return switch (column.name()) {
            case "project" -> row.projectName();
            case "member" -> row.member();
            case "kind" -> row.subjectType();
            default -> null;
        };
    }

    // Read-only: creatable/updatable/deletable are all false, so the framework never
    // routes here. Throwing is the honest body -- a silent no-op would report success.
    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced, @NonNull AccessContext ctx) {
        throw new UnsupportedOperationException("project membership is edited through the grant editors");
    }

    @Override
    public void updateRow(@NonNull Membership existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext ctx) {
        throw new UnsupportedOperationException("project membership is edited through the grant editors");
    }

    @Override
    public void deleteRow(@NonNull Membership existing, @NonNull AccessContext ctx) {
        throw new UnsupportedOperationException("project membership is edited through the grant editors");
    }
}
