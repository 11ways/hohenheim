package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.tls.AcmeService;
import be.elevenways.hohenheim.server.dns.InternalDnsTxtPublisher;
import be.elevenways.hohenheim.server.tls.CommandDnsTxtPublisher;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.resource.PanelPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Let's Encrypt request form, reached from the certificate list header. The
 * POST is the host-declared CERTIFICATES_REQUEST endpoint.
 */
public final class CertificateRequestPage extends PanelPage {

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "certificates_request"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("request_le").withFilter("scope", "certificate"); }
    @Override public @NonNull String slug() { return "certificates-request"; }
    @Override public @NonNull Icon icon() { return Icon.of("lock"); }
    @Override public boolean showInNav() { return false; }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit, @NonNull AccessContext accessContext) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", Microcopy.of("request_certificate")
            .withFilter("scope", "certificate_request")
            .resolve(conduit.getLocales(), conduit.getMessageResolver()));
        vars.put("reissueCertId", 0);
        vars.put("challengeType", CertificateModel.CHALLENGE_HTTP);
        vars.put("dnsMode", CertificateModel.DNS_PUBLISHER_MANUAL);
        vars.put("accountEmail", "");
        // Render-time condition only (an expired manual challenge); redirect outcomes
        // ride the session flash, never a query parameter.
        vars.put("error", "");
        vars.put("dnsHookConfigured", CommandDnsTxtPublisher.isConfigured());
        var dnsServer = ServerMain.getDnsServer();
        vars.put("internalDnsAvailable", dnsServer != null && dnsServer.isRunning()
            && new InternalDnsTxtPublisher().hasZones());
        vars.put("manualToken", "");
        vars.put("dnsRecords", List.of());
        // Both are admin-panel routes; the page is an operator-only peer.
        vars.put("startOverTarget", CmsRoutes.list("admin", this.slug()));
        vars.put("certificatesTarget", CmsRoutes.list("admin", "certificates"));
        String manualToken = conduit.getQueryParam("manual");
        var proxy = ServerMain.getProxyServer();
        if (manualToken != null && proxy != null) {
            AcmeService.ManualDnsRequest manual = proxy.getAcmeService().manualDnsRequest(manualToken);
            if (manual != null) {
                vars.put("manualToken", manual.token());
                vars.put("dnsRecords", manual.records().stream()
                    .map(record -> Map.<String, Object>of(
                        "name", record.name(),
                        "value", record.value()))
                    .toList());
            } else {
                vars.put("error", Microcopy.of("manual_expired")
                    .withFilter("scope", "certificate_request_error")
                    .resolve(conduit.getLocales(), conduit.getMessageResolver()));
            }
        }
        List<String> domains = prefillFromSite(conduit, accessContext, vars);
        List<String> reissued = prefillFromCertificate(conduit, accessContext, vars);
        vars.put("domainForm", CertificateRequestForm.state(accessContext,
            reissued.isEmpty() ? domains : reissued));
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/certificate-request"), vars);
    }

    /**
     * A ?cert_id= link (the certificate list's re-issue action) turns this into the EDIT
     * form of an existing order: same page, same validation, prefilled with what the row
     * was last issued for, and a hidden id that makes the POST write back into that row.
     *
     * AIDEV-NOTE: scoped for the same reason {@link #prefillFromSite} is -- the answer is
     * a hostname list, which is exactly what a reader who may not see the certificate must
     * not be handed. A row that is not a Let's Encrypt certificate is refused here AND in
     * the handler: this one only decides what to render.
     *
     * @return the row's stored hostnames, or empty when this is not a re-issue
     */
    private static @NonNull List<String> prefillFromCertificate(@NonNull Conduit conduit,
                                                                @NonNull AccessContext accessContext,
                                                                @NonNull Map<String, Object> vars) {
        String raw = conduit.getQueryParam(HohenheimParams.CERTIFICATE_REISSUE_NAME);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        int certId;
        try {
            certId = Integer.parseInt(raw);
        } catch (NumberFormatException invalid) {
            return List.of();
        }
        if (certId <= 0) {
            return List.of();
        }
        Row cert = HohenheimAccess.isAdmin(accessContext)
            ? Models.get(CertificateModel.class).findById(certId) : null;
        if (cert == null || !CertificateModel.PROVIDER_LETSENCRYPT
                .equals(cert.get(CertificateModel.PROVIDER))) {
            vars.put("error", Microcopy.of("reissue_unavailable")
                .withFilter("scope", "certificate_request_error")
                .resolve(conduit.getLocales(), conduit.getMessageResolver()));
            return List.of();
        }

        vars.put("reissueCertId", certId);
        vars.put("title", Microcopy.of("reissue_certificate")
            .withFilter("scope", "certificate_request")
            .resolve(conduit.getLocales(), conduit.getMessageResolver()));
        vars.put("niceName", text(cert.get(CertificateModel.NICE_NAME)));
        vars.put("accountEmail", text(cert.get(CertificateModel.LETSENCRYPT_EMAIL)));

        String challengeType = text(cert.get(CertificateModel.CHALLENGE_TYPE));
        vars.put("challengeType", challengeType.isEmpty()
            ? CertificateModel.CHALLENGE_HTTP : challengeType);
        String publisher = text(cert.get(CertificateModel.DNS_PUBLISHER));
        vars.put("dnsMode", publisher.isEmpty()
            ? CertificateModel.DNS_PUBLISHER_MANUAL : publisher);

        String stored = text(cert.get(CertificateModel.DOMAIN_NAMES_TEXT));
        if (stored.isEmpty()) {
            return List.of();
        }
        return List.of(stored.split(","));
    }

    private static @NonNull String text(@Nullable Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * A ?site= link (the site's Domains tab) prefills the LE-eligible exact hostnames.
     *
     * AIDEV-NOTE: the ?site= id is SCOPED, not merely parsed. This page is a HohenheimPanel
     * peer today, so only an operator reaches it and the check answers true every time --
     * that is exactly why it is written down: without it the read is an unauthenticated-by-
     * construction hostname enumerator over every site in the install, and the only thing
     * standing in front of it is which panel the page happens to be registered in. A page
     * moved or contributed to /manage later must not silently become that enumerator.
     */
    private static @NonNull List<String> prefillFromSite(@NonNull Conduit conduit,
                                                         @NonNull AccessContext accessContext,
                                                         @NonNull Map<String, Object> vars) {
        vars.put("niceName", "");
        String siteParam = conduit.getQueryParam("site");
        if (siteParam == null || siteParam.isEmpty()) {
            return List.of();
        }
        int siteId;
        try {
            siteId = Integer.parseInt(siteParam);
        } catch (NumberFormatException invalid) {
            return List.of();
        }
        if (!HohenheimAccess.isAdmin(accessContext)
                && !HohenheimAccess.canManageSite(accessContext, siteId)) {
            return List.of();
        }
        Row site = Models.get(SiteModel.class).find().where(SiteModel.ID.eq(siteId)).first();
        if (site == null) {
            return List.of();
        }
        if (SiteModel.UPSTREAM_TLS_PASSTHROUGH.equals(site.get(SiteModel.UPSTREAM_KIND))) {
            return List.of();
        }
        List<String> hostnames = new ArrayList<>();
        for (Row domain : Models.get(SiteDomainModel.class).findBySiteId(siteId)) {
            if (!SiteDomainModel.MATCH_EXACT.equals(domain.get(SiteDomainModel.MATCH_TYPE))
                || Boolean.TRUE.equals(domain.get(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT))) {
                continue;
            }
            String hostname = domain.get(SiteDomainModel.HOSTNAME);
            if (hostname != null && !hostname.isEmpty()) {
                hostnames.add(hostname);
            }
        }
        vars.put("niceName", String.valueOf(site.get(SiteModel.NAME)));
        return List.copyOf(hostnames);
    }
}
