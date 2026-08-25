package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.tls.CertificateCoverage;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.server.http.ReturnTarget;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Domains tab on a site: the site's hostnames, linking into the (nav-hidden)
 * domain resource forms.
 */
public final class SiteDomainsPage implements RecordScopedPage<Row> {

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "site_domains"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("domains").withFilter("scope", "site"); }
    @Override public @NonNull String slug() { return "domains"; }
    @Override public @NonNull Icon icon() { return Icon.of("at"); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row site) {
        Integer siteId = site.get(SiteModel.ID);
        boolean tlsPassthrough = SiteModel.UPSTREAM_TLS_PASSTHROUGH
            .equals(site.get(SiteModel.UPSTREAM_KIND));
        String panel = CmsSupport.panelSlug(conduit);
        boolean administerDomains = HohenheimAccess.isAdmin(accessContext);
        // Binding a hostname to a site the principal MANAGES is delegated (the write pipeline
        // decides what such a write may carry); requesting a certificate stays installation
        // administration, because an issued certificate is authority over a name.
        boolean canEditDomains = administerDomains
            || HohenheimAccess.canManageSite(accessContext, siteId);
        boolean canRequestCert = administerDomains && !tlsPassthrough;
        List<Map<String, Object>> domains = new ArrayList<>();
        for (Row domain : Models.get(SiteDomainModel.class).findBySiteId(siteId)) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("id", domain.get(SiteDomainModel.ID));
            entry.put("hostname", domain.get(SiteDomainModel.HOSTNAME));
            entry.put("matchType", domain.get(SiteDomainModel.MATCH_TYPE));
            entry.put("forceSsl", Boolean.TRUE.equals(domain.get(SiteDomainModel.FORCE_SSL)));
            entry.put("editTarget", CmsRoutes.detail(panel, "domains",
                domain.get(SiteDomainModel.ID)));
            // AIDEV-NOTE: the row's own remove, bound back to THIS tab. Detaching a hostname
            // used to be reachable only from the nav-hidden domains list or a hand-typed
            // /delete URL, so the tab that owns the hostnames could add one and never take
            // one away. Gated on the same boolean the edit link is: the endpoint re-decides
            // through the resource's writableBy, this only decides the affordance.
            if (canEditDomains) {
                entry.put("deleteTarget", ReturnTarget.bind(
                    CmsRoutes.delete(panel, "domains", domain.get(SiteDomainModel.ID)), conduit));
            }
            if (tlsPassthrough) entry.put("certStatus", "");
            else putCertCoverage(entry, domain);
            domains.add(entry);
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, "site_domains",
            site.get(SiteModel.NAME)));
        vars.put("siteId", siteId);
        vars.put("siteName", site.get(SiteModel.NAME));
        vars.put("domains", domains);
        vars.put("canEditDomains", canEditDomains);
        vars.put("canRequestCert", canRequestCert);
        // AIDEV-NOTE: a CMS route PLUS a query parameter cannot be built from CmsRoutes --
        // its builders return the RouteTarget interface, which has no with(...). Composing
        // off CmsEndpoints keeps it fully typed; the alternative is a concatenated URL.
        vars.put("addDomainTarget", canEditDomains ? CmsEndpoints.CREATE_FORM
            .with(CmsEndpoints.PANEL_PARAM, panel)
            .with(CmsEndpoints.RESOURCE_PARAM, "domains")
            .with(HohenheimParams.SITE_ID_PREFILL, siteId) : null);
        // AIDEV-NOTE: gated on the SAME boolean the template's {% if %} uses, not merely
        // provided and left for the template to hide. A declared template variable is
        // serialized into the hydration payload whether or not any element renders it, so
        // an ungated target would put "certificates-request" in the page source of a
        // /manage render -- which is exactly what ManagePanelTest forbids. The certificate
        // request page is installation administration and lives only on the admin panel,
        // so the panel slug is deliberately the literal "admin".
        vars.put("requestCertTarget", canRequestCert ? CmsEndpoints.LIST
            .with(CmsEndpoints.PANEL_PARAM, "admin")
            .with(CmsEndpoints.RESOURCE_PARAM, "certificates-request")
            .with(HohenheimParams.CERTIFICATE_REQUEST_SITE, siteId) : null);
        vars.put("recordTabs", recordTabs(conduit));
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/site-domains"), vars);
    }

    /**
     * TLS coverage for exact-match hostnames: which certificate (if any)
     * covers the domain, and in what state. Wildcard/regex match entries have
     * no single hostname to check, so they get no coverage verdict.
     */
    private static void putCertCoverage(Map<String, Object> entry, Row domain) {
        if (!SiteDomainModel.MATCH_EXACT.equals(domain.get(SiteDomainModel.MATCH_TYPE))) {
            entry.put("certStatus", "");
            return;
        }
        Row cert = CertificateCoverage.coveringCertificate(domain.get(SiteDomainModel.HOSTNAME));
        if (cert == null) {
            entry.put("certStatus", "none");
            return;
        }
        entry.put("certStatus", String.valueOf(cert.get(CertificateModel.STATUS)));
        entry.put("certName", String.valueOf(cert.get(CertificateModel.NICE_NAME)));
        // The certificate resource lives only on the admin panel: the literal slug is the
        // truth here, not an unconverted hardcode.
        entry.put("certTarget", CmsRoutes.detail("admin", "certificates",
            cert.get(CertificateModel.ID)));
    }
}
