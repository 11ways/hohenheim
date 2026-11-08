package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.SiteRedirectModel;
import be.elevenways.protoblast.common.registry.Identifier;
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
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The site redirects' shared parts, and the admin redirect resource and its /manage twin built from them.
 *
 * AIDEV-NOTE: a redirect is a child of its site, reached through the site's Redirects tab (the
 * {@link HohenheimSlugs#REDIRECTS} child list) and hidden from the sidebar, like a protected path
 * ({@link ProtectedPathParts}). The canonical path, a safe target, no loop and one row per (site, path) are the
 * model's write hook ({@link SiteRedirectInvariant}), so both twins write plain rows.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class SiteRedirectParts {

    private static final SubjectType<Row> SUBJECT = SubjectType.record(SiteRedirectModel.REF);

    private SiteRedirectParts() {
    }

    /** @return the admin redirect resource */
    public static @NonNull PanelResource<Row> admin() {
        return entry(HohenheimIds.id("site_redirect"))
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions())
            .build();
    }

    /** @return the /manage twin: the redirects of the sites the caller manages */
    public static @NonNull PanelResource<Row> manage() {
        return ManageTwin.reached(entry(ManageTwin.id("site_redirect")), TenantScopes.REDIRECTS,
                ResourceTabs.<Row>none().withContributions())
            .build();
    }

    /** The identity, list, form, reads, writes, parent and authority both twins share. */
    private static PanelResource.@NonNull Builder<Row> entry(@NonNull Identifier id) {
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(SiteRedirectModel.FROM_PATH).filterable().copyable().build())
            .column(ColumnSpec.fromField(SiteRedirectModel.MATCH).build())
            .column(ColumnSpec.fromField(SiteRedirectModel.TARGET).copyable().build())
            .column(ColumnSpec.fromField(SiteRedirectModel.HTTP_STATUS).build())
            .column(ColumnSpec.fromField(SiteRedirectModel.SITE_ID)
                .label(FieldLabels.labelForRelation(SiteRedirectModel.SITE_ID))
                .relation(RelationPick.of(SiteRedirectModel.SITE_ID, SiteModel.MODEL_ID).build())
                .build())
            .build();
        FormSpec form = FormSpec.builder()
            .add(RelationPick.of(SiteRedirectModel.SITE_ID, SiteModel.MODEL_ID).build())
            .add(SiteRedirectModel.FROM_PATH)
            .add(SiteRedirectModel.MATCH)
            .add(SiteRedirectModel.TARGET)
            .add(SiteRedirectModel.HTTP_STATUS)
            .build();
        return PanelResource.builder(id, HohenheimSlugs.REDIRECTS, SUBJECT)
            .label(HohenheimMicrocopy.SITE_REDIRECT.of("plural"))
            .recordLabel(HohenheimMicrocopy.SITE_REDIRECT.of("singular"))
            .icon(Icon.of("arrow-right-arrow-left"))
            .navGroup(HohenheimPanel.NETWORK_GROUP)
            .navOrder(33)
            .showInNav(false)
            .parent(ResourceParent.of(HohenheimSlugs.SITES, SiteRedirectModel.SITE_ID)
                .tab(HohenheimSlugs.REDIRECTS))
            .reads(ResourceReads.rows())
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL).facets().ruleFilters()
                .search(SiteRedirectModel.FROM_PATH)
                .build())
            .form(ResourceForm.<Row>of(form).build())
            .writes(ResourceMutations.rows().create().update().delete().build())
            .authority(SiteParts.childAuthority(SiteRedirectModel.SITE_ID));
    }
}
