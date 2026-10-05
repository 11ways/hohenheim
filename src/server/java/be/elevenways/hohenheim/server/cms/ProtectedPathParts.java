package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.render.table.EnumBadgeState;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceAuthority;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.cms.server.render.table.TableStateTranslator;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.ui.BadgeVariant;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Objects;

/**
 * The protected paths' shared parts, and the admin protected-path resource and its /manage twin built from them.
 *
 * AIDEV-NOTE: a protected path is a child of its site, reached through the site's Protected paths tab (the
 * {@link #SLUG} child list) and hidden from the sidebar. The path's canonical spelling, completeness and one row per
 * (site, path) are the model's write hook ({@link ProtectedPathInvariant}), so both twins write plain rows. There is
 * no quick-add bar: the row is a path PLUS a list pick, and a bar carrying only the path would produce rows the
 * invariant must refuse.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class ProtectedPathParts {

    /** The entry slug both twins share, which the site's tab and the parent links name. */
    public static final String SLUG = "protected-paths";

    /** The virtual column saying whether the path is really guarded. */
    static final String PROTECTION_COLUMN = "protection";

    private static final SubjectType<Row> SUBJECT = SubjectType.record(ProtectedPathModel.MODEL_ID);

    private ProtectedPathParts() {
    }

    /** @return the admin protected-path resource */
    public static @NonNull PanelResource<Row> admin() {
        return entry("protected_path")
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions())
            .build();
    }

    /** @return the /manage twin: the protected paths of the sites the caller manages */
    public static @NonNull PanelResource<Row> manage() {
        return entry("manage_protected_path")
            .scope(TenantScopes.PROTECTED_PATHS)
            // NAV-ONLY (zero granted sites hide the empty list); the route itself stays scoped.
            .hasInScopeRecords(ManagePanel::hasManageScope)
            // The contributed tabs only: the admin activity and revision history stays off the delegated surface.
            .tabs(ResourceTabs.<Row>none().withContributions())
            .build();
    }

    /** The identity, list, form, reads, writes, parent and authority both twins share. */
    private static PanelResource.@NonNull Builder<Row> entry(@NonNull String id) {
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(ProtectedPathModel.PATH).filterable().copyable().build())
            .column(ColumnSpec.fromField(ProtectedPathModel.ACCESS_LIST_ID)
                .label(FieldLabels.labelForRelation(ProtectedPathModel.ACCESS_LIST_ID))
                .relation(RelationPick.of(ProtectedPathModel.ACCESS_LIST_ID, AccessListModel.MODEL_ID).build())
                .build())
            .column(ColumnSpec.virtual(PROTECTION_COLUMN, pathText("protection"))
                .renderer(TableStateTranslator.ENUM_BADGE_RENDERER).build())
            .column(ColumnSpec.fromField(ProtectedPathModel.SITE_ID)
                .label(FieldLabels.labelForRelation(ProtectedPathModel.SITE_ID))
                .relation(RelationPick.of(ProtectedPathModel.SITE_ID, SiteModel.MODEL_ID).build())
                .build())
            .build();
        FormSpec form = FormSpec.builder()
            .add(RelationPick.of(ProtectedPathModel.SITE_ID, SiteModel.MODEL_ID).build())
            .add(ProtectedPathModel.PATH)
            // A list created from inside this pick starts empty, and the invariant refuses pointing a path at a list
            // that lets everyone through, naming it: the operator adds a rule to it and saves again. That beats the
            // dead end of a pick saying "No results found" with no way forward.
            .add(RelationPick.of(ProtectedPathModel.ACCESS_LIST_ID, AccessListModel.MODEL_ID).build())
            .build();
        return PanelResource.builder(HohenheimIds.id(id), SLUG, SUBJECT)
            .label(Microcopy.of("plural").withFilter("scope", "protected_path"))
            .recordLabel(Microcopy.of("singular").withFilter("scope", "protected_path"))
            .icon(Icon.of("lock"))
            .navGroup(HohenheimPanel.NETWORK_GROUP)
            .navOrder(32)
            .showInNav(false)
            .parent(ResourceParent.of(HohenheimSlugs.SITES, ProtectedPathModel.SITE_ID).tab(SLUG))
            .reads(ResourceReads.rows())
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL).facets().ruleFilters()
                .search(ProtectedPathModel.PATH)
                .computed(Objects.requireNonNull(table.column(PROTECTION_COLUMN)),
                    (path, request) -> protectionBadge(path))
                .build())
            .form(ResourceForm.<Row>of(form).build())
            .writes(ResourceMutations.rows().create().update().delete().build())
            .authority(authority());
    }

    /**
     * "Protected" or "Open to everyone": the gate's own reading of the path's list, so a path whose list lets every
     * visitor through never reads as protection.
     */
    static @NonNull EnumBadgeState protectionBadge(@NonNull Row path) {
        return ProtectedPathInvariant.isOpen(path)
            ? new EnumBadgeState("open", pathText("open_to_everyone"), null, "lock-open", BadgeVariant.DESTRUCTIVE,
                null, true)
            : new EnumBadgeState("protected", pathText("protected"), null, "lock", BadgeVariant.SUCCESS, null, true);
    }

    private static @NonNull Microcopy pathText(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "protected_path");
    }

    /**
     * Writing a protected path demands {@code manage} on the site it guards, as a domain row does; a create under a
     * site (its tab's add link, the create form, the submit) asks the same of that site.
     */
    private static @NonNull ResourceAuthority<Row> authority() {
        return ResourceAuthority.<Row>builder()
            .write(null, (path, access) -> HohenheimAccess.reachesRecord(access, SiteModel.MODEL_ID,
                path.get(ProtectedPathModel.SITE_ID), HohenheimAccess.MANAGE))
            .createUnder((site, access) -> site instanceof Integer id
                && HohenheimAccess.reachesRecord(access, SiteModel.MODEL_ID, id, HohenheimAccess.MANAGE))
            .build();
    }
}
