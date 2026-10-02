package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.CertCoverage;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.tls.CertificateCoverage;
import be.elevenways.hohenheim.site.DomainCertCell;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.render.panel.ChildListSectionState;
import be.elevenways.zenit.cms.common.resource.ChildList;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.resource.Resource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.server.page.ChildListSections;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Domains tab on a site: the framework's child list section over the panel's domain resource, narrowed to the site.
 *
 * AIDEV-NOTE: the rows, their edit and remove, the add link with its parent preset, the scope (a /manage tenant
 * lists its own domain twin) and a trashed site's read-only state are the child list's own, never a host copy; this
 * page keeps only its wrapper, the hostless guidance, the certificate each hostname is covered by and the
 * certificate request link (stage 4 contract 10, the legacy-parent embedding until SiteResource is a PanelResource).
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class SiteDomainsPage implements RecordScopedPage<Row> {

    /** The tab's slug, which the domain entries name as their parent tab. */
    public static final String SLUG = DomainParts.SLUG;

    /** The extra certificate coverage column of the tab's section. */
    static final String CERTIFICATE_COLUMN = "certificate";

    /** The tab's one section: the site's hostnames, without the site column every row would repeat. */
    static final ChildList<Row> SECTIONS = ChildList.<Row>sections(SLUG,
            Microcopy.of("domains").withFilter("scope", "site"), DomainParts.SLUG)
        .hide(DomainParts.SLUG, SiteDomainModel.SITE_ID.getName())
        .column(DomainParts.SLUG, SubjectType.record(SiteDomainModel.MODEL_ID),
            ColumnSpec.virtual(CERTIFICATE_COLUMN, Microcopy.of("certificate").withFilter("scope", "site_domains"))
                .renderer(HohenheimTemplateIds.CELL_DOMAIN_CERTIFICATE).build(),
            SiteDomainsPage::certificateCell)
        .headerLink(DomainParts.SLUG, requestCertificateLink());

    @Override public @NonNull Identifier id() { return HohenheimIds.id("site_domains"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("domains").withFilter("scope", "site"); }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Icon icon() { return Icon.of("at"); }

    @Override
    @SuppressWarnings("unchecked")
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request, @NonNull Row site) {
        Conduit conduit = request.conduit();
        Resource<Row> parent = (Resource<Row>) request.panel().entryBySlug(HohenheimSlugs.SITES);
        List<ChildListSectionState> sections = ChildListSections.embedded(request,
            Objects.requireNonNull(parent, "the site entry dispatched this tab"), site, SECTIONS);
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, "site_domains", site.get(SiteModel.NAME)));
        vars.put("siteName", site.get(SiteModel.NAME));
        vars.put("sections", sections);
        vars.put("panelSlug", request.panelSlug());
        // A new site has no hostname yet and routes nothing: where a hostname can be added, the tab says so.
        vars.put("hostless", sections.stream().allMatch(section -> section.total() == 0)
            && sections.stream().anyMatch(section -> section.createUrl() != null));
        vars.put("recordTabs", recordTabs(conduit));
        return new RenderTemplateResult(HohenheimTemplateIds.SITE_DOMAINS, vars);
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit, @NonNull AccessContext accessContext,
                                           @NonNull Row site) {
        throw new UnsupportedOperationException("The domains tab renders through its PanelRequest");
    }

    /**
     * Requesting a certificate for the site's hostnames stays installation administration, because an issued
     * certificate is authority over a name; the request page lives only on the admin panel. A TLS passthrough site
     * terminates no TLS here, so it has nothing to request.
     */
    private static @NonNull PanelAction<Row> requestCertificateLink() {
        return PanelAction.<Row>link(HohenheimIds.id("request_certificate"), ActionPlacement.HEADER)
            .label(Microcopy.of("request_certificate").withFilter("scope", "site_domains"))
            .route((site, request) -> CmsEndpoints.LIST
                .with(CmsEndpoints.PANEL_PARAM, HohenheimSlugs.ADMIN)
                .with(CmsEndpoints.RESOURCE_PARAM, "certificates-request")
                .with(HohenheimParams.CERTIFICATE_REQUEST_SITE, site.get(SiteModel.ID)))
            .shownWhen((site, access) -> HohenheimAccess.isAdmin(access) && !tlsPassthrough(site))
            .build();
    }

    private static boolean tlsPassthrough(@Nullable Row site) {
        return site != null && SiteModel.UPSTREAM_TLS_PASSTHROUGH.equals(site.get(SiteModel.UPSTREAM_KIND));
    }

    /**
     * TLS coverage of an exact-match hostname: which certificate (if any) covers it, and in what state. A wildcard or
     * regex entry has no single hostname to check, and a TLS passthrough site terminates none, so neither gets a
     * verdict.
     *
     * AIDEV-NOTE: the certificate's NAME and link are set only for a reader the walk lets OPEN the certificate
     * ({@code view}, the question the certificate resource's scope asks). The covering certificate is usually the
     * operator's wildcard, named after the operator's site, and this column used to print that name to a tenant for
     * whom /manage/certificates is empty and the certificate's own page a 404.
     */
    private static @Nullable DomainCertCell certificateCell(@NonNull Row domain, @NonNull PanelRequest request) {
        if (!SiteDomainModel.MATCH_EXACT.equals(domain.get(SiteDomainModel.MATCH_TYPE))
                || tlsPassthrough(Models.get(SiteModel.class).findById(domain.get(SiteDomainModel.SITE_ID)))) {
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
        // The panel this tab renders under carries a certificates entry on both faces (CertificateResource and its
        // /manage projection share the slug).
        return new DomainCertCell(coverage.key(), coverage.badgeVariant(), coverage.label(),
            canOpen ? String.valueOf((Object) cert.get(CertificateModel.NICE_NAME)) : null,
            canOpen ? CmsRoutes.detail(request.panelSlug(), HohenheimSlugs.CERTIFICATES, certId).toUrl() : null,
            expiresOn != null ? expiresOn.toString() : null);
    }
}
