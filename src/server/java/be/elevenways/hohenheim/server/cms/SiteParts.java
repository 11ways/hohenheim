package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.CertCoverage;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.tls.CertificateCoverage;
import be.elevenways.hohenheim.server.upstream.UpstreamKindHandler;
import be.elevenways.hohenheim.server.upstream.UpstreamKindHandlers;
import be.elevenways.hohenheim.site.DomainCertCell;
import be.elevenways.hohenheim.site.SiteHostnamesCell;
import be.elevenways.hohenheim.site.SiteOperations;
import be.elevenways.hohenheim.site.SiteTlsCell;
import be.elevenways.hohenheim.site.SiteUpstreamCell;
import be.elevenways.hohenheim.upstream.UpstreamKinds;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.typed.CoreTypes;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.resource.ChildList;
import be.elevenways.zenit.cms.common.resource.DeleteConfirmation;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.RelatedPage;
import be.elevenways.zenit.cms.common.resource.ResourceArchive;
import be.elevenways.zenit.cms.common.resource.ResourceAuthority;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.resource.RecordLead;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceHealth;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.SortSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.FieldAccess;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.BadgeColor;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The proxied sites' shared parts, and the admin site resource and its /manage twin built from them (stage 4 contract
 * 10): type-discriminated settings, relation picks to auth providers and access lists, the Domains child list, the
 * placed site operations, and the write verbs as domain operations ({@link SiteWrites}).
 *
 * AIDEV-NOTE: the /manage twin is a NARROWING, never a gate of its own: its rows are the sites the caller manages
 * ({@link TenantScopes#SITES}), its form edits three metadata columns, it creates and deletes nothing and keeps no
 * trash or history; the operations' authorizers and the model's write hooks judge every writer alike.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class SiteParts {

    /** Virtual column names (renderer cells). */
    static final String HOSTNAMES_COLUMN = "hostnames";
    static final String UPSTREAM_COLUMN = "upstream";
    static final String TLS_COLUMN = "tls";

    /** The Addresses tab's slug, which the domain entries name as their parent tab. */
    public static final String DOMAINS_TAB = DomainParts.SLUG;

    /** The extra certificate coverage column of the Addresses tab's section. */
    static final String CERTIFICATE_COLUMN = "certificate";

    /**
     * The Addresses tab, in the overview card's word: the framework's child list over the panel's domain entry (its
     * /manage twin there), narrowed to the site, without the site column every row would repeat, plus the certificate
     * each hostname is covered by; a domain row's own action requests one.
     *
     * AIDEV-NOTE: the rows, their edit and remove, the add link with its parent preset, the scope and a trashed site's
     * read-only state are the child list's own; what a hostless site's empty tab tells the reader is the domain list's
     * own empty description (DomainParts).
     */
    public static final ChildList<Row> DOMAINS = ChildList.<Row>sections(DOMAINS_TAB,
            AppOverview.copy("addresses"), DomainParts.SLUG)
        .hide(DomainParts.SLUG, SiteDomainModel.SITE_ID.getName())
        .column(DomainParts.SLUG, SubjectType.record(SiteDomainModel.MODEL_ID),
            ColumnSpec.virtual(CERTIFICATE_COLUMN, Microcopy.of("certificate").withFilter("scope", "site_domains"))
                .renderer(HohenheimTemplateIds.CELL_DOMAIN_CERTIFICATE).build(),
            SiteParts::certificateCell);

    /**
     * The Protection tab, in the overview card's word: the framework's child list over the panel's protected-path entry
     * (its /manage twin there), under the site. A TLS passthrough site terminates nothing here, so it has no paths to
     * protect and no tab.
     */
    public static final ChildList<Row> PROTECTED_PATHS = ChildList.<Row>of(ProtectedPathParts.SLUG)
        .label(AppOverview.copy("protection"))
        .visibleWhen((site, access) -> !tlsPassthrough(site));

    private SiteParts() {
    }

    /** @return the operator's site resource: every site, the full form, the trash and the history */
    public static @NonNull PanelResource<Row> admin() {
        return entry("site")
            // Reached through the Apps list, whose toolbar links this list (HohenheimPanel's sidebar note).
            .showInNav(false)
            .health(AppHealth.sites(false))
            .list(adminList())
            .form(ResourceForm.<Row>of(SiteWrites.ADMIN_FORM)
                .landingTab(AppOverview.SLUG)
                .tabLabel(AppOverview.copy("configuration"))
                .lead(SiteParts::lead)
                // The instance pick shows only for the instance kind (SiteWrites.ADMIN_FORM's showWhen); switching a
                // site away from that kind clears its link instead of leaving it unreachable.
                .bindings(List.of())
                // The create stages both without a form entry; a revision restore must still own them.
                .restorableOutsideForm(Set.of(SiteModel.SLUG.getName(), SiteModel.STATUS.getName()))
                .createDefaults(SiteParts::createDefaults)
                .createdRecordUrl(SiteParts::createdRecordUrl)
                .build())
            .writes(ResourceMutations.rows()
                .create(SiteWrites.CREATE)
                .update(SiteWrites.UPDATE, SiteOperationHandlers::revisionOf)
                .delete(SiteWrites.DELETE)
                .build())
            .deleteConfirmation(DeleteConfirmation.<Row>of(deleteBody(null))
                .forRow((site, request) -> deleteBody(site)))
            // The Trash restores and purges through core's archive operations (installation administration).
            .archive(ResourceArchive.of(SiteWrites.RESTORE, SiteWrites.RESTORE_MANY, SiteWrites.PURGE,
                SiteWrites.PURGE_MANY, ResourceAuthority.<Row>builder().delete(HohenheimPanel.ACCESS, null).build()))
            .actions(SiteActions.operator())
            .tabs(ResourceTabs.<Row>of(List.of(AppOverview.siteTab(), DOMAINS, PROTECTED_PATHS,
                    new SiteDevSessionsPage()))
                .withHistory().historyInStrip().withContributions())
            .relatedPages(
                // The hostname catalog itself: nav-hidden, so without this entry the only way to the cross-site
                // domain list was a hand-typed URL.
                RelatedPage.toPeer(DomainParts.SLUG),
                RelatedPage.toPeer("auth-providers"),
                RelatedPage.toPeer("previews"))
            .build();
    }

    /**
     * @return the /manage twin: the sites the caller manages, their three metadata columns, the switches; no create,
     *         delete, clone, rollback, trash or history
     */
    public static @NonNull PanelResource<Row> manage() {
        return entry("manage_site")
            .health(AppHealth.sites(true))
            .scope(TenantScopes.SITES)
            // NAV-ONLY (zero granted sites hide the empty list); the route itself stays scoped.
            .hasInScopeRecords(ManagePanel::hasManageScope)
            .list(ResourceList.rows(TableSpec.<Row>builder()
                    .column(ColumnSpec.fromField(SiteModel.NAME).build())
                    .column(ColumnSpec.fromField(SiteModel.ENABLED).build())
                    .build())
                .chrome(ListChrome.MINIMAL)
                .facets().ruleFilters()
                .search(SiteModel.NAME, SiteModel.SLUG, SiteModel.DESCRIPTION)
                .build())
            .form(ResourceForm.<Row>of(SiteWrites.MANAGE_FORM)
                .landingTab(AppOverview.SLUG)
                .tabLabel(AppOverview.copy("configuration"))
                .lead(SiteParts::lead)
                .bindings(List.of(
                    ResourceFieldBinding.of(SiteModel.NAME.getName(), FieldAccess.ALWAYS_EDITABLE),
                    ResourceFieldBinding.of(SiteModel.ENABLED.getName(), FieldAccess.ALWAYS_EDITABLE),
                    ResourceFieldBinding.of(SiteModel.DESCRIPTION.getName(), FieldAccess.ALWAYS_EDITABLE)))
                .build())
            .writes(ResourceMutations.rows()
                .update(SiteWrites.MANAGE_UPDATE, SiteOperationHandlers::revisionOf)
                .build())
            .actions(SiteActions.delegated())
            // The operator tabs a delegate needs plus the CONTRIBUTED ones (the generic access matrix, so a manage
            // holder can delegate from /manage); never the admin history.
            .tabs(ResourceTabs.<Row>of(List.of(AppOverview.siteTab(), DOMAINS, PROTECTED_PATHS)).withContributions())
            .build();
    }

    /** The identity, nav placement and plain row reads both twins share. */
    private static PanelResource.@NonNull Builder<Row> entry(@NonNull String id) {
        return PanelResource.builder(HohenheimIds.id(id), HohenheimSlugs.SITES, SiteOperations.SITE)
            .label(Microcopy.of("plural").withFilter("scope", "site"))
            .recordLabel(Microcopy.of("singular").withFilter("scope", "site"))
            .description(Microcopy.of("nav_hint").withFilter("scope", "site"))
            .icon(Icon.of("globe"))
            .navGroup(HohenheimPanel.DEPLOY_GROUP)
            .navOrder(20)
            .reads(ResourceReads.rows());
    }

    /**
     * The one list an operator lives in: hundreds of sites, a real rule vocabulary, and saved views are how they are
     * reached.
     *
     * AIDEV-NOTE: STATUS is deliberately absent: SiteModel.STATUS declares exactly ONE member, so its column rendered
     * the same pill on every row. The hostname/upstream/TLS cells each cost one small query per rendered row
     * (page-capped); the upstream kind filter stays DECLARED without a visible column, so it renders labeled in the
     * filter strip instead of duplicating the badge the upstream cell already draws.
     */
    private static @NonNull ResourceList<Row> adminList() {
        TableSpec<Row> table = TableSpec.<Row>builder()
            // The slug names this site in every generated path, container name and log line, so it reads under the
            // name instead of costing a column of its own.
            .column(ColumnSpec.fromField(SiteModel.NAME).filterable().subtext("slug").build())
            .column(ResourceHealth.column())
            .column(ColumnSpec.fromField(SiteModel.SLUG).hidden().build())
            .column(ColumnSpec.virtual(HOSTNAMES_COLUMN, Microcopy.of("hostnames").withFilter("scope", "site"))
                .renderer(HohenheimTemplateIds.CELL_SITE_HOSTNAMES).build())
            .column(ColumnSpec.virtual(UPSTREAM_COLUMN, Microcopy.of("upstream").withFilter("scope", "site"))
                .renderer(HohenheimTemplateIds.CELL_SITE_UPSTREAM).build())
            .column(ColumnSpec.virtual(TLS_COLUMN, Microcopy.of("tls").withFilter("scope", "site"))
                .renderer(HohenheimTemplateIds.CELL_SITE_TLS).build())
            // Enabled reads as ROW STATE (a disabled site renders muted, the strip keeps the tri-state filter)
            // instead of costing a column; the picker still offers it.
            .column(ColumnSpec.fromField(SiteModel.ENABLED).filterable().hidden().build())
            // Off the default view: most sites gate nothing, so a column of blanks would push the actions column
            // into horizontal scroll at laptop widths. The column picker (and the advanced filter) still offer it.
            .column(ColumnSpec.fromField(SiteModel.ACCESS_LIST_ID)
                .relation(RelationPick.of(SiteModel.ACCESS_LIST_ID, AccessListModel.MODEL_ID).build())
                .hidden().build())
            .column(ColumnSpec.fromField(SiteModel.UPSTREAM_KIND).filterable().hidden().build())
            .column(ColumnSpec.fromField(SiteModel.CREATED_AT).filterable().hidden().build())
            .filter(FilterSpec.leaf(SiteModel.NAME, CoreTypes.CONTAINS)
                .label(FieldLabels.labelFor(SiteModel.NAME)).build())
            .filter(FilterSpec.leaf(SiteModel.UPSTREAM_KIND, CoreTypes.EQUALS)
                .label(FieldLabels.labelFor(SiteModel.UPSTREAM_KIND)).build())
            .filter(FilterSpec.leaf(SiteModel.ENABLED, CoreTypes.IS_TRUE, CoreTypes.IS_FALSE)
                .label(FieldLabels.labelFor(SiteModel.ENABLED)).build())
            .filter(FilterSpec.leaf(SiteModel.CREATED_AT, CoreTypes.BETWEEN, CoreTypes.GTE, CoreTypes.LTE)
                .label(FieldLabels.labelFor(SiteModel.CREATED_AT)).build())
            .filter(FilterSpec.globalLeaf(ResourceList.ARCHIVED_FILTER,
                Microcopy.of("trashed").withFilter("scope", "cms").withFilter("target", "filter"),
                ResourceList.ARCHIVED_FILTER, CoreTypes.IS_TRUE, CoreTypes.IS_FALSE).build())
            .defaultSort(SortSpec.desc(SiteModel.CREATED_AT.getName()))
            .rowClasses(row -> Boolean.TRUE.equals(row.get(SiteModel.ENABLED)) ? "" : "hh-site-disabled")
            .build();
        return ResourceList.rows(table)
            .chrome(ListChrome.DEFAULT)
            // The legacy row resource's filter tiers: facet counts, and the query and advanced builder.
            .facets().ruleFilters()
            // An operator looks a site up by what they call it, by what the paths call it, or by their note on it.
            .search(SiteModel.NAME, SiteModel.SLUG, SiteModel.DESCRIPTION)
            // A deleted site lands in the Trash, from where it is restored (the model's save, so every site write
            // hook runs and the quota re-books the slot) or deleted for good.
            .trash()
            .computed(Objects.requireNonNull(table.column(HOSTNAMES_COLUMN)), (row, request) -> hostnamesCellOf(row))
            .computed(Objects.requireNonNull(table.column(UPSTREAM_COLUMN)), (row, request) -> upstreamCellOf(row))
            .computed(Objects.requireNonNull(table.column(TLS_COLUMN)), (row, request) -> tlsCellOf(row))
            .build();
    }

    /**
     * Prefill from the instance page's Expose action ({@code ?upstream_kind=hohenheim:instance&instance_id=N});
     * render-time only, the submit still runs full coercion and the upstream-instance narrowing. A malformed prefill
     * reads as absent: the bare form renders.
     */
    /** The line under a site's heading: what it serves; none on the create form. */
    private static @Nullable RecordLead lead(@NonNull Row site, @NonNull AccessContext access) {
        return site.get(SiteModel.ID) == null ? null
            : new RecordLead(AppOverview.siteLead(site, access.conduit()), null);
    }

    private static @NonNull Map<String, Object> createDefaults(@NonNull PanelRequest request) {
        Map<String, Object> values = new LinkedHashMap<>(SiteWrites.ADMIN_FORM.defaultValues());
        Conduit conduit = request.conduit();
        String kind = CmsSupport.prefill(conduit, HohenheimParams.UPSTREAM_KIND_PREFILL);
        if (kind != null && UpstreamKinds.REGISTRY.get(Identifier.tryParse(kind)) != null) {
            values.put(SiteModel.UPSTREAM_KIND.getName(), kind);
        }
        Integer instanceId = CmsSupport.prefill(conduit, HohenheimParams.INSTANCE_ID_PREFILL);
        if (instanceId != null) {
            values.put(SiteModel.INSTANCE_ID.getName(), instanceId);
        }
        return Map.copyOf(values);
    }

    /**
     * Where a surface links a site: its front door, the overview, never the bare record URL (for an operator who may
     * edit, that URL is the edit form; see {@link InstanceParts#recordRoute}).
     */
    static @NonNull RouteTarget recordRoute(@NonNull String panel, @NonNull Object siteId) {
        return CmsRoutes.subpage(panel, HohenheimSlugs.SITES, siteId, AppOverview.SLUG);
    }

    /**
     * A brand-new site lands on its Domains tab: a filled first hostname still leads to where the second one, the
     * certificate and the TLS switches live, and a blank one means the site has nothing to answer on yet.
     */
    private static @NonNull RouteTarget createdRecordUrl(@NonNull Row site, @NonNull PanelRequest request) {
        return CmsRoutes.subpage(request.panelSlug(), HohenheimSlugs.SITES, site.get(SiteModel.ID), DOMAINS_TAB);
    }

    /**
     * The delete dialog NAMING the hostnames that stop answering, the whole consequence of a site delete; the
     * record-less fallback can only say that much.
     *
     * AIDEV-NOTE: two bodies, never one with an empty list: microcopy args echo verbatim, so an empty hostname list
     * would render a dangling colon.
     *
     * @param site the site, null for the record-less fallback
     */
    private static @NonNull ConfirmationSpec deleteBody(@Nullable Row site) {
        String hostnames = site == null ? ""
            : DeleteImpact.join(DeleteImpact.hostnamesOfSite(site.get(SiteModel.ID)));
        if (site == null || hostnames.isEmpty()) {
            return DeleteConfirmation.body(Microcopy.of("delete_confirm").withFilter("scope", "site"));
        }
        return DeleteConfirmation.body(Microcopy.of("delete_confirm_hostnames")
            .withFilter("scope", "site")
            .withArg("name", String.valueOf((Object) site.get(SiteModel.NAME)))
            .withArg("hostnames", hostnames));
    }

    /**
     * The named refusal when this site serves the hostname this request arrived on: switching it off or deleting it
     * from inside the panel removes the only route back to the surface that could put it back.
     *
     * AIDEV-NOTE: a request that reached the backend directly (an ssh forward to its port arrives at a hostname no
     * site domain covers) is refused nothing, which is exactly the recovery path this refusal must not close.
     *
     * @param key the refusal's microcopy key, one per verb
     */
    static @Nullable Microcopy panelLockoutReason(@NonNull String key, @NonNull Row site,
                                                  @Nullable AccessContext accessContext) {
        String host = DeleteImpact.adminHostnameOfSite(site.get(SiteModel.ID),
            accessContext == null ? null : accessContext.conduit());
        if (host == null) {
            return null;
        }
        return Microcopy.of(key).withFilter("scope", "site").withArg("host", host);
    }

    static @NonNull List<Row> domainsOf(@NonNull Row site) {
        return Models.get(SiteDomainModel.class).findBySiteId(site.get(SiteModel.ID));
    }

    static @NonNull SiteHostnamesCell hostnamesCellOf(@NonNull Row site) {
        return hostnamesCellOf(domainsOf(site));
    }

    /** The first of these names plus how many more there are. */
    static @NonNull SiteHostnamesCell hostnamesCellOf(@NonNull List<Row> domains) {
        if (domains.isEmpty()) {
            return new SiteHostnamesCell(null, 0);
        }
        return new SiteHostnamesCell(String.valueOf((Object) domains.get(0).get(SiteDomainModel.HOSTNAME)),
            domains.size() - 1);
    }

    /**
     * Whether HTTPS works for the site's names: read from the certificates that cover them, never from force_ssl, so a
     * name forced without a working certificate is the one red state.
     */
    static @NonNull SiteTlsCell tlsCellOf(@NonNull Row site) {
        return tlsCellOf(domainsOf(site), tlsPassthrough(site), CertificateCoverage.activeNames());
    }

    /**
     * {@link #tlsCellOf(Row)} over names already read, so a list of apps reads its domains and the working
     * certificates once.
     *
     * @param passthrough whether these names belong to a TLS passthrough site, which terminates nothing here
     */
    static @NonNull SiteTlsCell tlsCellOf(@NonNull List<Row> domains, boolean passthrough,
                                          @NonNull Set<String> working) {
        if (domains.isEmpty()) {
            return new SiteTlsCell(SiteTlsCell.NONE);
        }
        if (passthrough) {
            return new SiteTlsCell(SiteTlsCell.NOT_USED);
        }
        // The one red state is the health verdict's own rule, so this cell and the site's health can never disagree.
        if (AppHealth.forcedWithoutCertificate(domains, working, false) != null) {
            return new SiteTlsCell(SiteTlsCell.BROKEN);
        }
        int exact = 0;
        int covered = 0;
        for (Row domain : domains) {
            if (!AppHealth.exact(domain)) {
                continue;
            }
            exact++;
            if (CertificateCoverage.covers(working, domain.get(SiteDomainModel.HOSTNAME))) {
                covered++;
            }
        }
        if (exact == 0) {
            return new SiteTlsCell(SiteTlsCell.PATTERNS);
        }
        return new SiteTlsCell(covered == exact ? SiteTlsCell.WORKS
            : covered > 0 ? SiteTlsCell.PARTIAL : SiteTlsCell.MISSING);
    }

    /** What the site's upstream is, in the words its list cell, its overview and the Apps list use. */
    static @NonNull Microcopy upstreamLabel(@NonNull Row site) {
        UpstreamKindHandler handler = UpstreamKindHandlers.getHandler(
            String.valueOf((Object) site.get(SiteModel.UPSTREAM_KIND)));
        return handler != null ? handler.getLabel() : Microcopy.of("upstream").withFilter("scope", "site");
    }

    static @NonNull SiteUpstreamCell upstreamCellOf(@NonNull Row site) {
        String kindKey = String.valueOf((Object) site.get(SiteModel.UPSTREAM_KIND));
        UpstreamKindHandler handler = UpstreamKindHandlers.getHandler(kindKey);
        Microcopy label = upstreamLabel(site);
        String icon = handler != null && handler.getIcon() != null ? handler.getIcon().name() : null;
        String color = handler != null ? BadgeColor.tokenOf(handler.color()) : null;

        String instanceName = null;
        String instanceUrl = null;
        Integer instanceId = site.get(SiteModel.INSTANCE_ID);
        if (instanceId != null) {
            Row instance = Models.get(InstanceModel.class).findById(instanceId);
            if (instance != null) {
                instanceName = Models.get(InstanceModel.class).getDisplayTitle(instance);
                instanceUrl = InstanceParts.recordRoute(HohenheimPanel.SLUG, instance, null).toUrl();
            }
        }

        String summary = null;
        if (handler != null && instanceName == null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> settings = site.get(SiteModel.SETTINGS) instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
            summary = handler.upstreamSummary(settings);
        }
        return new SiteUpstreamCell(kindKey, label, icon, color, summary, instanceName, instanceUrl);
    }

    static boolean tlsPassthrough(@Nullable Row site) {
        return site != null && SiteModel.UPSTREAM_TLS_PASSTHROUGH.equals(site.get(SiteModel.UPSTREAM_KIND));
    }

    /**
     * TLS coverage of an exact-match hostname: which certificate (if any) covers it, and in what state. A wildcard or
     * regex entry has no single hostname to check, and a TLS passthrough site terminates none, so neither gets a
     * verdict.
     *
     * AIDEV-NOTE: the certificate's NAME and link are set only for a reader the walk lets OPEN the certificate
     * ({@code view}, the question the certificate resource's scope asks): the covering certificate is usually the
     * operator's wildcard, and printing its name to a tenant for whom it is a 404 was a leak.
     */
    private static @Nullable DomainCertCell certificateCell(@NonNull Row domain, @NonNull PanelRequest request) {
        if (tlsPassthrough(Models.get(SiteModel.class).findById(domain.get(SiteDomainModel.SITE_ID)))) {
            CertCoverage notUsed = CertCoverage.NOT_USED;
            return new DomainCertCell(notUsed.key(), notUsed.badgeVariant(), notUsed.label(), null, null, null);
        }
        if (!SiteDomainModel.MATCH_EXACT.equals(domain.get(SiteDomainModel.MATCH_TYPE))) {
            return null;
        }
        Row cert = CertificateCoverage.coveringCertificate(domain.get(SiteDomainModel.HOSTNAME));
        CertCoverage coverage = CertCoverage.ofCertificateStatus(
            cert == null ? null : cert.get(CertificateModel.STATUS));
        if (cert == null) {
            return new DomainCertCell(coverage.key(), coverage.badgeVariant(), coverage.label(), null, null, null);
        }
        Instant expiresOn = cert.get(CertificateModel.EXPIRES_ON);
        Integer certId = cert.get(CertificateModel.ID);
        boolean canOpen = HohenheimAccess.reachesRecord(request.access(), CertificateModel.MODEL_ID, certId,
            HohenheimAccess.VIEW);
        // The panel this tab renders under carries a certificates entry on both faces.
        return new DomainCertCell(coverage.key(), coverage.badgeVariant(), coverage.label(),
            canOpen ? String.valueOf((Object) cert.get(CertificateModel.NICE_NAME)) : null,
            canOpen ? CmsRoutes.detail(request.panelSlug(), HohenheimSlugs.CERTIFICATES, certId).toUrl() : null,
            expiresOn != null ? expiresOn.toString() : null);
    }
}
