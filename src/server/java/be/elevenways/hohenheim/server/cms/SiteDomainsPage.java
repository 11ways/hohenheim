package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.tls.CertificateCoverage;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
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
        boolean tlsPassthrough = SiteModel.SITE_TYPE_TLS_PASSTHROUGH
            .equals(site.get(SiteModel.SITE_TYPE));
        String basePath = CmsSupport.panelBase(conduit);
        List<Map<String, Object>> domains = new ArrayList<>();
        for (Row domain : Models.get(SiteDomainModel.class).findBySiteId(siteId)) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("id", domain.get(SiteDomainModel.ID));
            entry.put("hostname", domain.get(SiteDomainModel.HOSTNAME));
            entry.put("matchType", domain.get(SiteDomainModel.MATCH_TYPE));
            entry.put("forceSsl", Boolean.TRUE.equals(domain.get(SiteDomainModel.FORCE_SSL)));
            entry.put("editUrl", basePath + "/domains/" + domain.get(SiteDomainModel.ID));
            if (tlsPassthrough) entry.put("certStatus", "");
            else putCertCoverage(entry, domain);
            domains.add(entry);
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", site.get(SiteModel.NAME) + " - Domains");
        vars.put("siteId", siteId);
        vars.put("siteName", site.get(SiteModel.NAME));
        vars.put("domains", domains);
        vars.put("basePath", basePath);
        boolean administerDomains = HohenheimAccess.isAdmin(accessContext);
        // Hostname ownership and LE requests are installation administration.
        vars.put("canEditDomains", administerDomains);
        vars.put("canRequestCert", administerDomains && !tlsPassthrough);
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
        entry.put("certUrl", "/admin/certificates/" + cert.get(CertificateModel.ID));
    }
}
