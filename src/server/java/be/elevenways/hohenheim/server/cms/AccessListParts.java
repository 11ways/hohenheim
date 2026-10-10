package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.typed.CoreTypes;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.resource.DeleteConfirmation;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
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
import java.util.Objects;
import java.util.function.UnaryOperator;

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

    /** The list's "lets in" column: its rules in a few words. */
    private static final String LETS_IN_COLUMN = "lets_in";

    /** The list's "protects" column: how many sites and paths it gates. */
    private static final String PROTECTS_COLUMN = "protects";

    private AccessListParts() {
    }

    /** @return the admin access-list resource: every list, and the SHARED switch */
    public static @NonNull PanelResource<Row> admin() {
        // AIDEV-NOTE: an explicit spec. The rules themselves live in their own table, so "which list holds
        // 10.0.0.5" is answered by the rule search, not by a column here.
        // The list reads an access list by what it lets in and where it is used; how its root group combines
        // rules and when it was made stay in the picker.
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(AccessListModel.NAME).filterable().build())
            .column(ColumnSpec.virtual(LETS_IN_COLUMN, HohenheimMicrocopy.ACCESS_LIST.of("lets_in_column")).build())
            .column(ColumnSpec.virtual(PROTECTS_COLUMN, HohenheimMicrocopy.ACCESS_LIST.of("protects_column")).build())
            .column(ColumnSpec.fromField(AccessListModel.SHARED).filterable().build())
            .column(ColumnSpec.fromField(AccessListModel.SATISFY).filterable().hidden().build())
            .column(ColumnSpec.fromField(AccessListModel.CREATED_AT).hidden().build())
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
        return entry(HohenheimIds.id("access_list"), table, form, list -> list
                .computed(Objects.requireNonNull(table.column(LETS_IN_COLUMN)),
                    (row, request) -> AccessRuleSummaries.letsInOf(row.get(AccessListModel.ID)))
                .computed(Objects.requireNonNull(table.column(PROTECTS_COLUMN)),
                    (row, request) -> protectsCount(row.get(AccessListModel.ID))))
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
        // A Domains cluster member. Admins see every list; everyone else only the ones the walk confirms manage on, so
        // an unowned id reads as MISSING (zenit-cms 404s an out-of-scope load). The Rules tab plus the CONTRIBUTED tabs
        // (the generic access matrix, so an owner can delegate its list from /manage).
        //
        // AIDEV-NOTE: shown while the tenant holds a list or manages a site, the only place a list it makes can guard
        // (a site's list, a protected path). Before the probe, a tenant of instances alone read an empty
        // "Access lists" tab in the Domains cluster, and a list it made there could guard nothing.
        return ManageTwin.listed(entry(ManageTwin.id("access_list"), table, form, list -> list),
                TenantScopes.MANAGED_ACCESS_LISTS,
                ResourceTabs.<Row>of(List.of(new AccessListRulesPage())).withContributions(),
                access -> HohenheimAccess.managesAnySite(access)
                    || HohenheimAccess.reachesAny(access, AccessListModel.MODEL_ID, HohenheimCapabilities.MANAGE))
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
            .build();
    }

    /** The identity, nav placement, reads, list chrome, form part and delete dialog both twins share. */
    private static PanelResource.@NonNull Builder<Row> entry(@NonNull Identifier id, @NonNull TableSpec<Row> table,
                                                             @NonNull FormSpec form,
                                                             @NonNull UnaryOperator<ResourceList.Builder<Row>> cells) {
        return PanelResource.builder(id, HohenheimSlugs.ACCESS_LISTS, SUBJECT)
            .label(HohenheimMicrocopy.ACCESS_LIST.of("plural"))
            .recordLabel(HohenheimMicrocopy.ACCESS_LIST.of("singular"))
            .description(HohenheimMicrocopy.ACCESS_LIST.of("nav_hint"))
            .icon(Icon.of("shield-halved"))
            .navGroup(HohenheimPanel.NETWORK_GROUP)
            .navOrder(30)
            .reads(ResourceReads.rows())
            .list(cells.apply(ResourceList.rows(table).chrome(ListChrome.MINIMAL).facets().ruleFilters()
                .search(AccessListModel.NAME)).build())
            // AIDEV-NOTE: no quick-add bar: a list is made through "New access list", which opens
            // its form, and lists for one path are made by Protect a path. The name is the one inline cell: SATISFY
            // is the AND/OR of the request-time gate, so a cell edit would change on the next request whether the
            // root group's rules must ALL pass.
            .form(ResourceForm.<Row>of(form)
                .inlineEditable(AccessListModel.NAME)
                .build())
            .deleteConfirmation(DeleteConfirmation.<Row>of(deleteBody(null))
                .forRow((list, request) -> deleteBody(list)));
    }

    /** How many places a list gates, in words; "nothing yet" for a list no site or path names. */
    private static @NonNull Microcopy protectsCount(@Nullable Integer listId) {
        int uses = DeleteImpact.usesOfAccessList(listId).size();
        return uses == 0 ? HohenheimMicrocopy.ACCESS_LIST.of("protects_nothing")
            : HohenheimMicrocopy.ACCESS_LIST.of("protects_count").withArg("count", uses);
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
            return DeleteConfirmation.body(HohenheimMicrocopy.ACCESS_LIST.of("delete_confirm"));
        }
        Integer id = list.get(AccessListModel.ID);
        String gated = DeleteImpact.join(DeleteImpact.gatedByAccessList(id));
        Microcopy body = HohenheimMicrocopy.ACCESS_LIST
            .of(gated.isEmpty() ? "delete_confirm_named" : "delete_confirm_gating")
            .withArg("name", String.valueOf((Object) list.get(AccessListModel.NAME)))
            .withArg("rules", DeleteImpact.rulesOfAccessList(id));
        if (!gated.isEmpty()) {
            body = body.withArg("gated", gated);
        }
        return DeleteConfirmation.body(body);
    }
}
