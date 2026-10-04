package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.typed.CoreTypes;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.resource.DeleteConfirmation;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.QuickCreateSpec;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * The access lists' shared parts, and the admin access-list resource and its /manage twin built from them.
 *
 * AIDEV-NOTE: a list's rule tree is edited on its Rules tab, which stays the rendered {@link AccessListRulesPage}
 * until the rules move onto parts (arbitrary nesting has no form shape). The /manage twin is a NARROWING: SHARED is
 * absent from its form and list (publishing a policy installation-wide is the operator's declaration, and
 * TenantWrites freezes the column on every writer), and its rows are the lists the caller manages, never the shared
 * ones a tenant may only ATTACH.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class AccessListParts {

    private static final SubjectType<Row> SUBJECT = SubjectType.record(AccessListModel.MODEL_ID);

    private AccessListParts() {
    }

    /** @return the admin access-list resource: every list, and the SHARED switch */
    public static @NonNull PanelResource<Row> admin() {
        // AIDEV-NOTE: an explicit spec. The rules themselves live in their own table, so "which list holds
        // 10.0.0.5" is answered by the rule search, not by a column here.
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(AccessListModel.NAME).filterable().build())
            .column(ColumnSpec.fromField(AccessListModel.SATISFY).filterable().build())
            .column(ColumnSpec.fromField(AccessListModel.SHARED).filterable().build())
            .column(ColumnSpec.fromField(AccessListModel.CREATED_AT).build())
            .filter(FilterSpec.leaf(AccessListModel.NAME, CoreTypes.CONTAINS)
                .label(FieldLabels.labelFor(AccessListModel.NAME)).build())
            .filter(FilterSpec.leaf(AccessListModel.SATISFY, CoreTypes.EQUALS)
                .label(FieldLabels.labelFor(AccessListModel.SATISFY)).build())
            .build();
        FormSpec form = FormSpec.builder()
            .add(AccessListModel.NAME)
            // The select derives from the SATISFY EnumField, the vocabulary's one declaring home. This IS the root
            // group's mode; everything else lives in the Rules tab.
            .add(AccessListModel.SATISFY)
            // The operator's switch alone: the /manage twin narrows it away, and TenantWrites freezes it on every
            // writer (the GitProviderParts shape).
            .add(AccessListModel.SHARED)
            .build();
        return entry("access_list", table, form)
            .writes(ResourceMutations.rows().create().update().delete().build())
            .tabs(ResourceTabs.<Row>of(List.of(new AccessListRulesPage())).withHistory().withContributions())
            .build();
    }

    /** @return the /manage twin: the lists the caller manages, through the delegated form */
    public static @NonNull PanelResource<Row> manage() {
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(AccessListModel.NAME).filterable().build())
            .column(ColumnSpec.fromField(AccessListModel.SATISFY).filterable().build())
            .build();
        FormSpec form = FormSpec.builder()
            .add(AccessListModel.NAME)
            .add(AccessListModel.SATISFY)
            .build();
        return entry("manage_access_list", table, form)
            // Admins see every list; everyone else only the ones the walk confirms manage on, so an unowned id reads
            // as MISSING (zenit-cms 404s an out-of-scope load).
            .scope(TenantScopes.MANAGED_ACCESS_LISTS)
            .writes(ResourceMutations.rows().create().update().delete()
                // The grant IS the ownership, the GitProviderParts.manage() shape: an operator create plants nothing
                // (an empty subject set IS operator ownership), and THE planting loop also drops the scope memo the
                // grant made stale, so the create's own scope check sees the new row.
                .afterSave(save -> {
                    if (save.isCreate()) {
                        HohenheimAccess.grantCreatorManage(AccessListModel.MODEL_ID,
                            Integer.parseInt(String.valueOf(save.key())), save.access());
                    }
                })
                .build())
            // The Rules tab plus the CONTRIBUTED tabs (the generic access matrix, so an owner can delegate its list
            // from /manage); the admin activity and revision history stays off the delegated surface.
            .tabs(ResourceTabs.<Row>of(List.of(new AccessListRulesPage())).withContributions())
            .build();
    }

    /** The identity, nav placement, reads, list chrome, form part and delete dialog both twins share. */
    private static PanelResource.@NonNull Builder<Row> entry(@NonNull String id, @NonNull TableSpec<Row> table,
                                                             @NonNull FormSpec form) {
        return PanelResource.builder(HohenheimIds.id(id), HohenheimSlugs.ACCESS_LISTS, SUBJECT)
            .label(Microcopy.of("plural").withFilter("scope", "access_list"))
            .recordLabel(Microcopy.of("singular").withFilter("scope", "access_list"))
            .description(Microcopy.of("nav_hint").withFilter("scope", "access_list"))
            .icon(Icon.of("shield-halved"))
            .navGroup(HohenheimPanel.NETWORK_GROUP)
            .navOrder(30)
            .reads(ResourceReads.rows())
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL).facets().ruleFilters()
                .search(AccessListModel.NAME).build())
            // AIDEV-NOTE: the quick-add bar is a NAME only: a list created empty is INERT, not a lockout
            // (AccessListGate allows when a list carries no rules and no credential). The name is the one inline
            // cell: SATISFY is the AND/OR of the request-time gate, so a cell edit would change on the next request
            // whether the root group's rules must ALL pass.
            .form(ResourceForm.<Row>of(form)
                .quickCreate(QuickCreateSpec.of(AccessListModel.NAME.getName()))
                .inlineEditable(AccessListModel.NAME)
                .build())
            .deleteConfirmation(DeleteConfirmation.<Row>of(deleteBody(null))
                .forRow((list, request) -> deleteBody(list)));
    }

    /**
     * Names the list, how many rules go with it, and everything that stops being gated.
     *
     * AIDEV-NOTE: the second consequence is the dangerous direction: a site whose access list is gone compiles to a
     * null rule tree, and a null tree ALLOWS, so the delete silently opens the site. The two bodies are a deliberate
     * pair (gated yes/no): microcopy args echo verbatim, so an empty list would render a dangling colon. The
     * record-less fallback can only speak about the type.
     *
     * @param list the list, null for the record-less fallback
     */
    private static @NonNull ConfirmationSpec deleteBody(@Nullable Row list) {
        if (list == null) {
            return DeleteConfirmation.body(Microcopy.of("delete_confirm").withFilter("scope", "access_list"));
        }
        Integer id = list.get(AccessListModel.ID);
        String gated = DeleteImpact.join(DeleteImpact.gatedByAccessList(id));
        Microcopy body = Microcopy
            .of(gated.isEmpty() ? "delete_confirm_named" : "delete_confirm_gating")
            .withFilter("scope", "access_list")
            .withArg("name", String.valueOf((Object) list.get(AccessListModel.NAME)))
            .withArg("rules", DeleteImpact.rulesOfAccessList(id));
        if (!gated.isEmpty()) {
            body = body.withArg("gated", gated);
        }
        return DeleteConfirmation.body(body);
    }
}
