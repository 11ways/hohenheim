package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.render.table.EnumBadgeState;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
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
 * {@link HohenheimSlugs#PROTECTED_PATHS} child list) and hidden from the sidebar. The path's canonical spelling,
 * completeness and one row per
 * (site, path) are the model's write hook ({@link ProtectedPathInvariant}), so both twins write plain rows. There is
 * no quick-add bar: the row is a path PLUS a list pick, and a bar carrying only the path would produce rows the
 * invariant must refuse.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class ProtectedPathParts {

    /** The virtual column saying whether the path is really guarded. */
    static final String PROTECTION_COLUMN = "protection";

    private static final SubjectType<Row> SUBJECT = SubjectType.record(ProtectedPathModel.MODEL_ID);

    private ProtectedPathParts() {
    }

    /** @return the admin protected-path resource */
    public static @NonNull PanelResource<Row> admin() {
        return entry(HohenheimIds.id("protected_path"))
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions())
            .build();
    }

    /** @return the /manage twin: the protected paths of the sites the caller manages */
    public static @NonNull PanelResource<Row> manage() {
        return ManageTwin.reached(entry(ManageTwin.id("protected_path")), TenantScopes.PROTECTED_PATHS,
                ResourceTabs.<Row>none().withContributions())
            .build();
    }

    /** The identity, list, form, reads, writes, parent and authority both twins share. */
    private static PanelResource.@NonNull Builder<Row> entry(@NonNull Identifier id) {
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(ProtectedPathModel.PATH).filterable().copyable().build())
            .column(ColumnSpec.fromField(ProtectedPathModel.ACCESS_LIST_ID)
                .label(FieldLabels.labelForRelation(ProtectedPathModel.ACCESS_LIST_ID))
                .relation(RelationPick.of(ProtectedPathModel.ACCESS_LIST_ID, AccessListModel.MODEL_ID).build())
                .build())
            .column(ColumnSpec.virtual(PROTECTION_COLUMN, HohenheimMicrocopy.PROTECTED_PATH.of("protection"))
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
        return PanelResource.builder(id, HohenheimSlugs.PROTECTED_PATHS, SUBJECT)
            .label(HohenheimMicrocopy.PROTECTED_PATH.of("plural"))
            .recordLabel(HohenheimMicrocopy.PROTECTED_PATH.of("singular"))
            .icon(Icon.of("lock"))
            .navGroup(HohenheimPanel.NETWORK_GROUP)
            .navOrder(32)
            .showInNav(false)
            .standsUnder(HohenheimSlugs.SITES)
            .parent(ResourceParent.of(HohenheimSlugs.SITES, ProtectedPathModel.SITE_ID)
            .tab(HohenheimSlugs.PROTECTED_PATHS))
            .reads(ResourceReads.rows())
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL).facets().ruleFilters()
                .search(ProtectedPathModel.PATH)
                .computed(Objects.requireNonNull(table.column(PROTECTION_COLUMN)),
                    (path, request) -> protectionBadge(path))
                .build())
            .form(ResourceForm.<Row>of(form).build())
            .writes(ResourceMutations.rows().create().update().delete().build())
            .authority(SiteParts.childAuthority(ProtectedPathModel.SITE_ID));
    }

    /**
     * "Protected" or "Open to everyone": the gate's own reading of the path's list, so a path whose list lets every
     * visitor through never reads as protection.
     */
    static @NonNull EnumBadgeState protectionBadge(@NonNull Row path) {
        return ProtectedPathInvariant.isOpen(path)
            ? new EnumBadgeState("open", HohenheimMicrocopy.PROTECTED_PATH.of("open_to_everyone"), null, "lock-open",
                BadgeVariant.DESTRUCTIVE,
                null, true)
            : new EnumBadgeState("protected", HohenheimMicrocopy.PROTECTED_PATH.of("protected"), null, "lock",
                BadgeVariant.SUCCESS, null, true);
    }
}
