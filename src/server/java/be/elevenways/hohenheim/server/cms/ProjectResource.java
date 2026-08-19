package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceQuota;
import be.elevenways.hohenheim.server.project.Projects;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.RecordGrantModel;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.HeaderAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Projects: the hierarchical owner tier's admin surface. The generated floor plus
 * derived columns -- members, owned sites/instances (grant-derived, never a FK) and
 * the project's instance-quota usage/cap (the InstanceQuotaModel row keyed by the
 * project's packed subject stays the ONE cap authority; this list only reads it).
 * Membership itself is edited through the zenit-auth grant surfaces (a project IS a
 * permission group) -- a second member editor here would be two UIs over one record.
 */
public class ProjectResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(ProjectModel.NAME)
        .add(ProjectModel.DESCRIPTION)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(ProjectModel.NAME).filterable().build())
        .column(ColumnSpec.virtual("members",
            Microcopy.of("members").withFilter("scope", "project")).build())
        .column(ColumnSpec.virtual("owned_sites",
            Microcopy.of("owned_sites").withFilter("scope", "project")).build())
        .column(ColumnSpec.virtual("owned_instances",
            Microcopy.of("owned_instances").withFilter("scope", "project")).build())
        .column(ColumnSpec.virtual("instance_quota",
            Microcopy.of("instance_quota").withFilter("scope", "project")).build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "project"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "project"); }
    @Override public @NonNull String slug() { return "projects"; }
    @Override public @NonNull Model model() { return Models.get(ProjectModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.DEPLOY_GROUP; }
    @Override public int navOrder() { return 10; }

    @Override
    public @Nullable Microcopy description() {
        return Microcopy.of("nav_hint").withFilter("scope", "project");
    }
    @Override public @NonNull Icon icon() { return Icon.of("folder-tree"); }

    @Override
    public @Nullable Object cellValue(@NonNull Row row, @NonNull ColumnSpec column) {
        Integer groupId = row.get(ProjectModel.GROUP_ID);
        switch (column.name()) {
            case "members":
                return groupId == null ? 0 : Projects.directMembersOf(row).size();
            case "owned_sites":
                return groupId == null ? 0 : ownedCount(groupId, SiteModel.MODEL_ID);
            case "owned_instances":
                return groupId == null ? 0 : ownedCount(groupId, InstanceModel.MODEL_ID);
            case "instance_quota": {
                if (groupId == null) {
                    return "-";
                }
                String packed = HohenheimAccess.packSubjects(Projects.ownerSubjectsOf(row));
                Integer limit = InstanceQuota.limitFor(packed);
                long used = InstanceQuota.usedBy(packed);
                return used + " / " + (limit == null ? "-" : String.valueOf(limit));
            }
            default:
                return super.cellValue(row, column);
        }
    }

    /** The delete guard refuses non-empty projects; say so up front. */
    @Override
    public @NonNull ConfirmationSpec deleteConfirmation() {
        return deleteConfirmation(Microcopy.of("delete_confirm").withFilter("scope", "project"));
    }

    private static long ownedCount(int groupId, @NonNull Identifier model) {
        long count = 0;
        for (Row grant : RecordGrants.listForSubject(GrantSubjectType.GROUP, groupId)) {
            if (HohenheimAccess.MANAGE.equals(grant.get(RecordGrantModel.CAPABILITY))
                    && Boolean.TRUE.equals(grant.get(RecordGrantModel.VALUE))
                    && model.toString().equals(grant.get(RecordGrantModel.MODEL))) {
                count++;
            }
        }
        return count;
    }

    /**
     * The environments of these projects, demoted out of the sidebar.
     */
    @Override
    public @NonNull List<HeaderAction> headerActions() {
        List<HeaderAction> actions = new ArrayList<>(super.headerActions());
        actions.addAll(List.of(
            CmsSupport.relatedList("environments_link", "environments", "environment", Icon.of("layer-group"))));
        return actions;
    }

}
