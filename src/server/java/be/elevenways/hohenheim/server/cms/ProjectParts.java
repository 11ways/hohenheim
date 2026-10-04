package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
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
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
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
    public static final String SLUG = "projects";
    private static final SubjectType<Row> SUBJECT = SubjectType.record(ProjectModel.MODEL_ID);
    public static final Operation<Row, Void, Integer> DELETE = Operation.declare(HohenheimIds.id("delete_project"))
        .label(Microcopy.of("delete").withFilter("scope", "cms"))
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
        return entry("project", 10)
            .form(ResourceForm.<Row>of(formSpec())
                .quickCreate(QuickCreateSpec.of(ProjectModel.NAME.getName(), ProjectModel.DESCRIPTION.getName()))
                .inlineEditable(ProjectModel.NAME, ProjectModel.DESCRIPTION).build())
            .writes(ResourceMutations.rows().create().update().delete(DELETE).build())
            .deleteConfirmation(DeleteConfirmation.of(ConfirmationSpec.builder()
                .title(Microcopy.of("confirm_title").withFilter("scope", "cms"))
                .body(Microcopy.of("delete_confirm").withFilter("scope", "project"))
                .confirmLabel(Microcopy.of("delete").withFilter("scope", "cms"))
                .cancelLabel(Microcopy.of("cancel").withFilter("scope", "cms"))
                .style(ActionStyle.DESTRUCTIVE).build()))
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions())
            .relatedPages(RelatedPage.toPeer("environments"))
            .build();
    }

    public static @NonNull PanelResource<Row> manage() {
        return entry("manage_project", 40).scope(TenantScopes.PROJECTS)
            .form(ResourceForm.<Row>of(formSpec()).build())
            .hasInScopeRecords(access -> !Projects.visibleTo(access).isEmpty())
            .build();
    }

    private static PanelResource.@NonNull Builder<Row> entry(String id, int order) {
        return PanelResource.builder(HohenheimIds.id(id), SLUG, SUBJECT)
            .label(Microcopy.of("plural").withFilter("scope", "project"))
            .recordLabel(Microcopy.of("singular").withFilter("scope", "project"))
            .description(CmsSupport.navHint("project"))
            .navGroup(HohenheimPanel.DEPLOY_GROUP).navOrder(order).icon(Icon.of("folder-tree"))
            .list(ResourceList.rows(tableSpec()).chrome(ListChrome.MINIMAL)
                .search(ProjectModel.NAME, ProjectModel.DESCRIPTION).build())
            .reads(ResourceReads.rows().mapCells(ProjectParts::cell));
    }

    static @NonNull FormSpec formSpec() {
        return FormSpec.builder().add(ProjectModel.NAME).add(ProjectModel.DESCRIPTION).build();
    }

    static @NonNull TableSpec<Row> tableSpec() {
        return TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(ProjectModel.NAME).filterable().subtext("description").build())
            .column(ColumnSpec.fromField(ProjectModel.DESCRIPTION).hidden().build())
            .column(ColumnSpec.virtual("members", Microcopy.of("members").withFilter("scope", "project")).build())
            .column(ColumnSpec.virtual("owned_sites", Microcopy.of("owned_sites").withFilter("scope", "project")).build())
            .column(ColumnSpec.virtual("owned_instances",
                Microcopy.of("owned_instances").withFilter("scope", "project")).build())
            .column(ColumnSpec.virtual("instance_quota",
                Microcopy.of("instance_quota").withFilter("scope", "project")).build()).build();
    }

    static @Nullable Object cell(@NonNull Row row, @NonNull ColumnSpec column) {
        Integer group = row.get(ProjectModel.GROUP_ID);
        return switch (column.name()) {
            case "members" -> group == null ? 0 : Projects.directMembersOf(row).size();
            case "owned_sites" -> group == null ? 0 : ownedCount(group, SiteModel.MODEL_ID);
            case "owned_instances" -> group == null ? 0 : ownedCount(group, InstanceModel.MODEL_ID);
            case "instance_quota" -> quota(row, group);
            default -> null;
        };
    }

    private static String quota(Row row, Integer group) {
        if (group == null) return "-";
        String packed = HohenheimAccess.packSubjects(Projects.ownerSubjectsOf(row));
        Integer limit = InstanceQuota.limitFor(packed);
        return InstanceQuota.usedBy(packed) + " / " + (limit == null ? "-" : String.valueOf(limit));
    }

    private static long ownedCount(int group, Identifier model) {
        return RecordGrants.listForSubject(GrantSubjectType.GROUP, group).stream()
            .filter(grant -> HohenheimAccess.MANAGE.equals(grant.get(RecordGrantModel.CAPABILITY))
                && Boolean.TRUE.equals(grant.get(RecordGrantModel.VALUE))
                && model.toString().equals(grant.get(RecordGrantModel.MODEL))).count();
    }
}
