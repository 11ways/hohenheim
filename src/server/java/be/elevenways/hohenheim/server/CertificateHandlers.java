package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.server.cms.CertificateRequestForm;
import be.elevenways.hohenheim.server.cms.HohenheimFlash;
import be.elevenways.hohenheim.server.dns.InternalDnsTxtPublisher;
import be.elevenways.hohenheim.server.tls.AcmeService;
import be.elevenways.hohenheim.server.tls.CertificateAuthority;
import be.elevenways.hohenheim.server.tls.CommandDnsTxtPublisher;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.server.http.body.FormSubmissionRawValues;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Certificates: the Let's Encrypt request form (HTTP-01, DNS-01 manual/internal/hook)
 * and the PEM bundle download.
 */
final class CertificateHandlers {

    /** The certificate-request page's peer slug, shared with CertificateRequestPage. */
    private static final String CERTIFICATES_REQUEST_SLUG = "certificates-request";

    private CertificateHandlers() {
    }

    static void init() {
        CertificateModel certModel = Models.get(CertificateModel.class);

        HohenheimEndpoints.CERTIFICATES_REQUEST.setHandler(conduit -> {
            Map<String, Object> form = FormSubmissionRawValues.fromConduit(conduit);

            String manualToken = HandlerSupport.submittedString(form, "manual_token");
            if (!manualToken.isEmpty()) {
                var proxy = ServerMain.getProxyServer();
                if (proxy == null) {
                    return requestError(conduit, certificateError("proxy_unavailable"));
                }
                int certId = proxy.getAcmeService().completeManualDnsCertificate(manualToken);
                if (certId < 0) {
                    return requestError(conduit, certificateError("dns_validation_failed"));
                }
                ActivityLog.record(certModel, certId, "requested", "manual DNS-01");
                return HandlerSupport.redirect(CmsRoutes.list(HandlerSupport.ADMIN, "certificates"));
            }

            List<String> hostnames = CertificateRequestForm.submittedDomains(form);
            String niceName = HandlerSupport.submittedString(form, "nice_name");
            String email = HandlerSupport.submittedString(form, "letsencrypt_email");
            String challengeType = HandlerSupport.submittedString(form, "challenge_type");
            String dnsMode = HandlerSupport.submittedString(form, "dns_mode");
            if (challengeType.isEmpty()) challengeType = CertificateModel.CHALLENGE_HTTP;
            if (dnsMode.isEmpty()) dnsMode = CertificateModel.DNS_PUBLISHER_MANUAL;

            if (!email.isEmpty() && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                return requestError(conduit, certificateError("invalid_email").withArg("email", email));
            }
            if (hostnames.isEmpty()) {
                return requestError(conduit, certificateError("domain_required"));
            }
            if (niceName.isEmpty()) {
                niceName = hostnames.get(0);
            }

            boolean dns = CertificateModel.CHALLENGE_DNS.equals(challengeType);
            if (!dns && !CertificateModel.CHALLENGE_HTTP.equals(challengeType)) {
                return requestError(conduit, certificateError("unknown_validation")
                    .withArg("method", challengeType));
            }
            if (!dns && hostnames.stream().anyMatch(name -> name.startsWith("*."))) {
                return requestError(conduit, certificateError("wildcard_requires_dns"));
            }
            List<String> invalid = AcmeService.invalidHostnames(hostnames, dns);
            if (!invalid.isEmpty()) {
                return requestError(conduit, certificateError("invalid_hostnames")
                    .withArg("hostnames", String.join(", ", invalid)));
            }

            // Input validation first: only a request that could actually be
            // ordered gets refused on operational grounds.
            var proxy = ServerMain.getProxyServer();
            if (proxy == null) {
                return requestError(conduit, certificateError("proxy_unavailable"));
            }

            var requester = CertificateAuthority.Requester.of(AccessContext.of(conduit));

            if (dns && CertificateModel.DNS_PUBLISHER_MANUAL.equals(dnsMode)) {
                try {
                    var manual = proxy.getAcmeService().prepareManualDnsCertificate(
                        hostnames, niceName, email.isEmpty() ? null : email, requester);
                    // The manual token is addressable page STATE (the operator resumes
                    // the challenge on this URL), so it stays a query parameter.
                    return HandlerSupport.redirect(
                        CmsRoutes.list(HandlerSupport.ADMIN, CERTIFICATES_REQUEST_SLUG)
                            .with(HohenheimParams.MANUAL_CHALLENGE, manual.token()));
                } catch (CertificateAuthority.Refused refused) {
                    return requestError(conduit, refusalMessage(refused));
                } catch (Exception e) {
                    return requestError(conduit, certificateError("dns_start_failed")
                        .withArg("reason", e.getMessage()));
                }
            }

            if (dns && CertificateModel.DNS_PUBLISHER_INTERNAL.equals(dnsMode)) {
                var dnsServer = ServerMain.getDnsServer();
                if (dnsServer == null || !dnsServer.isRunning()) {
                    return requestError(conduit, certificateError("dns_server_disabled"));
                }
                InternalDnsTxtPublisher internal = new InternalDnsTxtPublisher();
                List<String> unhosted = hostnames.stream()
                    .map(name -> name.startsWith("*.") ? name.substring(2) : name)
                    .filter(name -> !internal.canPublishFor("_acme-challenge." + name))
                    .toList();
                if (!unhosted.isEmpty()) {
                    return requestError(conduit, certificateError("zone_not_hosted")
                        .withArg("hostnames", String.join(", ", unhosted)));
                }
            }
            else if (dns && !CommandDnsTxtPublisher.ID.equals(dnsMode)) {
                return requestError(conduit, certificateError("unknown_dns_mode")
                    .withArg("mode", dnsMode));
            }

            String publisher = dns ? dnsMode : null;
            if (dns && CommandDnsTxtPublisher.ID.equals(dnsMode) && !CommandDnsTxtPublisher.isConfigured()) {
                return requestError(conduit, certificateError("hook_not_configured"));
            }
            int certId;
            try {
                certId = proxy.getAcmeService().requestCertificate(hostnames, niceName,
                    email.isEmpty() ? null : email, challengeType, publisher, requester);
            } catch (CertificateAuthority.Refused refused) {
                return requestError(conduit, refusalMessage(refused));
            }

            if (certId < 0) {
                Row failed = certModel.find()
                    .where(CertificateModel.STATUS.eq("error"))
                    .orderBy(CertificateModel.CREATED_AT, SortOrder.DESC)
                    .first();
                String reason = failed != null ? failed.get(CertificateModel.RENEWAL_ERROR) : null;
                if (reason == null) {
                    reason = certificateError("unknown_reason")
                        .resolve(conduit.getLocales(), conduit.getMessageResolver());
                }
                return requestError(conduit, certificateError("request_failed")
                    .withArg("reason", reason));
            }

            ActivityLog.record(certModel, certId, "requested", niceName);
            return HandlerSupport.redirect(CmsRoutes.list(HandlerSupport.ADMIN, "certificates"));
        });

        HohenheimEndpoints.CERTIFICATES_DOWNLOAD.setHandler(conduit -> {
            Integer certId = conduit.getParameter(HohenheimEndpoints.CERT_ID);
            Row cert = certModel.findById(certId);
            if (cert == null) {
                return HandlerSupport.redirect(CmsRoutes.list(HandlerSupport.ADMIN, "certificates"));
            }

            String certPem = cert.get(CertificateModel.CERTIFICATE_PEM);
            String keyPem = cert.get(CertificateModel.PRIVATE_KEY_PEM);
            String niceName = cert.get(CertificateModel.NICE_NAME);
            if (certPem == null) certPem = "";
            if (keyPem == null) keyPem = "";

            String bundle = "# Certificate: " + niceName + "\n\n" + certPem + "\n" + keyPem;
            HandlerSupport.download(conduit, "application/x-pem-file",
                (niceName != null ? niceName : "certificate") + ".pem", bundle.getBytes(StandardCharsets.UTF_8));
            return null;
        });
    }

    private static Microcopy certificateError(String key) {
        return Microcopy.of(key).withFilter("scope", "certificate_request_error");
    }

    private static ActionResult<Object> requestError(Conduit conduit, Microcopy message) {
        HohenheimFlash.error(conduit, message);
        return HandlerSupport.redirect(
            CmsRoutes.list(HandlerSupport.ADMIN, CERTIFICATES_REQUEST_SLUG));
    }

    /**
     * The user-facing rendering of an authority refusal.
     *
     * AIDEV-NOTE: this MAPS a decision, it does not make one -- the decision lives in
     * CertificateAuthority, inside the service, so every entry point (this form, the manual
     * DNS lane, the renewal sweep) answers to the same rule. The old hostname-eligibility
     * check that lived here compared hostnames with HOSTNAME.eq, so a name covered only by
     * a wildcard row was never seen at all.
     */
    private static Microcopy refusalMessage(CertificateAuthority.Refused refused) {
        String key = switch (refused.refusal()) {
            case NOT_SERVED -> "hostname_not_served";
            case NOT_MANAGED -> "hostname_not_managed";
            case EXCLUDED -> "excluded_hostnames";
        };
        return certificateError(key).withArg("hostnames", refused.hostname());
    }
}
