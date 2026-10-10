package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.dns.InternalDnsTxtPublisher;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.tls.AcmeProblem;
import be.elevenways.hohenheim.server.tls.AcmeService;
import be.elevenways.hohenheim.server.tls.CertificateAuthority;
import be.elevenways.hohenheim.server.tls.CommandDnsTxtPublisher;
import be.elevenways.hohenheim.server.tls.HostnameReach;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.validation.validator.Email;
import be.elevenways.zenit.common.validation.ValidationContext;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.Authorizer;
import be.elevenways.zenit.server.operation.OperationHandlers;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

import static be.elevenways.hohenheim.RawValues.trimmed;

/**
 * The certificate operations' handlers: every Let's Encrypt order the admin places goes through {@link #order}.
 *
 * AIDEV-NOTE: the order of checks is the old request handler's, kept on purpose: input first (email, names, the
 * wildcard rule), then whether Let's Encrypt may be used, then operational state (hosted DNS, hook, proxy), then the
 * reach pre-check, and only then the CA. Who may obtain which name is CertificateAuthority's decision inside
 * AcmeService, never this class's; the operator-only gate here is the admin surface's.
 */
final class CertificateOperationHandlers {

    static {
        OperationHandlers.attach(CertificateOperations.REQUEST).authorize(admin())
            .availability((none, access) -> letsEncryptUnavailable())
            .handle(call -> order(require(call.input()), call.subjectAccess(), null));
        OperationHandlers.attach(CertificateOperations.REQUEST_FOR_DOMAIN).authorize(admin())
            .applies(CertificateOperationHandlers::certifiable)
            .availability((domain, access) -> letsEncryptUnavailable())
            .handle(call -> order(require(call.input()), call.subjectAccess(), null));
        OperationHandlers.attach(CertificateOperations.REISSUE).authorize(admin())
            .applies(cert -> CertificateModel.PROVIDER_LETSENCRYPT.equals(cert.get(CertificateModel.PROVIDER)))
            .availability((cert, access) -> letsEncryptUnavailable())
            .handle(call -> order(require(call.input()), call.subjectAccess(), call.subject()));
        OperationHandlers.attach(CertificateOperations.CONTINUE_DNS).authorize(admin())
            .applies(cert -> {
                ProxyServer proxy = ServerMain.getProxyServer();
                return proxy != null && proxy.getAcmeService().manualDnsRequestFor(cert.get(CertificateModel.ID)) != null;
            })
            .handle(call -> finishManual(call.subject()));
    }

    private CertificateOperationHandlers() {
    }

    static void init() {
    }

    /** @return the reason no order may be placed now, or null */
    static @Nullable Microcopy letsEncryptUnavailable() {
        return HohenheimSettings.isOn(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED)
            ? null : HohenheimMicrocopy.CERTIFICATE_REQUEST_ERROR.of("letsencrypt_disabled");
    }

    /** A hostname a certificate can be ordered for: one exact name on a site this proxy terminates TLS for. */
    private static boolean certifiable(@NonNull Row domain) {
        if (!SiteDomainModel.MATCH_EXACT.equals(domain.get(SiteDomainModel.MATCH_TYPE))
                || Boolean.TRUE.equals(domain.get(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT))) {
            return false;
        }
        Row site = Models.get(SiteModel.class).findById(domain.get(SiteDomainModel.SITE_ID));
        return site != null && !SiteModel.UPSTREAM_TLS_PASSTHROUGH.equals(site.get(SiteModel.UPSTREAM_KIND));
    }

    /**
     * Places one order, new or written back into {@code reissue}.
     *
     * @return the certificate row the order wrote
     * @throws Violations a refusal the input form shows, the typed values kept
     */
    static int order(CertificateOperations.@NonNull Order order, @NonNull AccessContext access,
                     @Nullable Row reissue) {
        String email = trimmed(order.email());
        if (!Email.instance().validate(email, ValidationContext.of("email")).isValid()) {
            throw refused(HohenheimMicrocopy.CERTIFICATE_REQUEST_ERROR.of("invalid_email").withArg("email", email));
        }
        List<String> hostnames = order.hostnames();
        if (hostnames.isEmpty()) {
            throw refused(HohenheimMicrocopy.CERTIFICATE_REQUEST_ERROR.of("domain_required"));
        }
        String niceName = order.niceName() == null || order.niceName().isBlank() ? hostnames.get(0) : order.niceName();
        boolean dns = CertificateModel.CHALLENGE_DNS.equals(order.challenge());
        String publisher = dns ? (order.publisher() == null ? CertificateModel.DNS_PUBLISHER_MANUAL : order.publisher())
            : null;
        if (!dns && hostnames.stream().anyMatch(name -> AcmeService.wildcardSanBase(name) != null)) {
            throw refused(HohenheimMicrocopy.CERTIFICATE_REQUEST_ERROR.of("wildcard_requires_dns"));
        }
        List<String> invalid = AcmeService.invalidHostnames(hostnames, dns);
        if (!invalid.isEmpty()) {
            throw refused(HohenheimMicrocopy.CERTIFICATE_REQUEST_ERROR.of("invalid_hostnames")
                .withArg("hostnames", String.join(", ", invalid)));
        }
        Microcopy unavailable = letsEncryptUnavailable();
        if (unavailable != null) {
            throw refused(unavailable);
        }
        if (reissue != null && CertificateModel.DNS_PUBLISHER_MANUAL.equals(publisher)) {
            throw refused(HohenheimMicrocopy.CERTIFICATE_REQUEST_ERROR.of("reissue_manual_unsupported"));
        }
        if (CertificateModel.DNS_PUBLISHER_INTERNAL.equals(publisher)) {
            refuseUnhostedZones(hostnames);
        } else if (CertificateModel.DNS_PUBLISHER_COMMAND.equals(publisher) && !CommandDnsTxtPublisher.isConfigured()) {
            throw refused(HohenheimMicrocopy.CERTIFICATE_REQUEST_ERROR.of("hook_not_configured"));
        }
        ProxyServer proxy = ServerMain.getProxyServer();
        if (proxy == null) {
            throw refused(HohenheimMicrocopy.CERTIFICATE_REQUEST_ERROR.of("proxy_unavailable"));
        }
        if (!dns) {
            refuseUnreachable(hostnames);
        }

        var requester = CertificateAuthority.Requester.of(access);
        AcmeService acme = proxy.getAcmeService();
        try {
            if (CertificateModel.DNS_PUBLISHER_MANUAL.equals(publisher)) {
                // The order waits for its TXT records: the certificate page shows them with "Verify DNS and finish".
                return acme.prepareManualDnsCertificate(hostnames, niceName, email.isEmpty() ? null : email, requester)
                    .certificateId();
            }
            if (reissue != null) {
                int certId = reissue.get(CertificateModel.ID);
                String previous = String.valueOf((Object) reissue.get(CertificateModel.DOMAIN_NAMES_TEXT));
                AcmeService.ReissueResult outcome = acme.reissueCertificate(reissue, hostnames,
                    email.isEmpty() ? null : email, order.challenge(), publisher, requester);
                if (!outcome.issued()) {
                    throw refused(AcmeProblem.sentenceFor(outcome.failureReason()));
                }
                // The names are the point of the entry; no key material is ever logged.
                ActivityLog.record(Models.get(CertificateModel.class), certId, HohenheimActivityAction.REISSUED,
                    previous + " -> " + String.join(",", hostnames));
                return certId;
            }
            AcmeService.RequestOutcome outcome = acme.requestCertificate(hostnames, niceName,
                email.isEmpty() ? null : email, order.challenge(), publisher, requester);
            Integer certId = outcome.certificateId();
            if (!outcome.issued() || certId == null) {
                Row failed = Models.get(CertificateModel.class).findById(certId);
                throw refused(AcmeProblem.sentenceFor(failed != null ? failed.get(CertificateModel.RENEWAL_ERROR) : null));
            }
            ActivityLog.record(Models.get(CertificateModel.class), certId, HohenheimActivityAction.REQUESTED, niceName);
            return certId;
        } catch (CertificateAuthority.Refused refusal) {
            throw refused(refusalMessage(refusal));
        } catch (Violations violations) {
            throw violations;
        } catch (Exception failure) {
            Blast.log("ACME: order for", String.join(", ", hostnames), "failed:", failure.getMessage());
            throw refused(AcmeProblem.sentenceFor(failure.getMessage()));
        }
    }

    /** Finishes a manual DNS-01 order whose records the operator says are published. */
    private static int finishManual(@NonNull Row cert) {
        ProxyServer proxy = ServerMain.getProxyServer();
        AcmeService.ManualDnsRequest pending = proxy == null ? null
            : proxy.getAcmeService().manualDnsRequestFor(cert.get(CertificateModel.ID));
        if (pending == null) {
            throw refused(HohenheimMicrocopy.CERTIFICATE_REQUEST_ERROR.of("manual_expired"));
        }
        int certId = proxy.getAcmeService().completeManualDnsCertificate(pending.token());
        if (certId < 0) {
            Row failed = Models.get(CertificateModel.class).findById(cert.get(CertificateModel.ID));
            throw refused(AcmeProblem.sentenceFor(failed != null ? failed.get(CertificateModel.RENEWAL_ERROR) : null));
        }
        ActivityLog.record(Models.get(CertificateModel.class), certId, HohenheimActivityAction.REQUESTED, "manual DNS-01");
        return certId;
    }

    /** Hosted DNS publishes only into zones this server is the primary of. */
    private static void refuseUnhostedZones(@NonNull List<String> hostnames) {
        var dnsServer = ServerMain.getDnsServer();
        if (dnsServer == null || !dnsServer.isRunning()) {
            throw refused(HohenheimMicrocopy.CERTIFICATE_REQUEST_ERROR.of("dns_server_disabled"));
        }
        InternalDnsTxtPublisher internal = new InternalDnsTxtPublisher();
        List<String> unhosted = new ArrayList<>();
        List<String> replicated = new ArrayList<>();
        String owningPeer = null;
        for (String hostname : hostnames) {
            String wildcardBase = AcmeService.wildcardSanBase(hostname);
            String base = wildcardBase != null ? wildcardBase : hostname;
            InternalDnsTxtPublisher.Refusal refusal = internal.refusalFor("_acme-challenge." + base);
            if (refusal == null) {
                continue;
            }
            if (InternalDnsTxtPublisher.REFUSAL_NOT_PRIMARY.equals(refusal.key())) {
                replicated.add(base);
                if (owningPeer == null) {
                    owningPeer = refusal.peer();
                }
            } else {
                unhosted.add(base);
            }
        }
        if (!unhosted.isEmpty()) {
            throw refused(HohenheimMicrocopy.CERTIFICATE_REQUEST_ERROR.of("zone_not_hosted")
                .withArg("hostnames", String.join(", ", unhosted)));
        }
        if (!replicated.isEmpty()) {
            throw refused(HohenheimMicrocopy.CERTIFICATE_REQUEST_ERROR.of("zone_not_primary")
                .withArg("hostnames", String.join(", ", replicated))
                .withArg("peer", String.valueOf(owningPeer)));
        }
    }

    /** HTTP-01 needs each name to reach this proxy: refuse before an order a name that visibly points elsewhere. */
    private static void refuseUnreachable(@NonNull List<String> hostnames) {
        for (String hostname : hostnames) {
            HostnameReach.Reach reach = HostnameReach.of(hostname);
            switch (reach.verdict()) {
                // Refused only where a public address is DECLARED, the basis this pre-check always had: a held one
                // may sit behind a CDN or a floating address that still forwards the challenge (Reach#declared).
                case POINTS_ELSEWHERE -> {
                    if (reach.declared()) {
                        throw refused(HohenheimMicrocopy.CERTIFICATE_REQUEST_ERROR.of("does_not_point_here")
                            .withArg("hostname", hostname)
                            .withArg("addresses", String.join(", ", reach.addresses())));
                    }
                }
                case UNRESOLVED -> {
                    if (reach.declared()) {
                        throw refused(HohenheimMicrocopy.CERTIFICATE_REQUEST_ERROR.of("does_not_resolve")
                            .withArg("hostname", hostname));
                    }
                }
                // of() waits for the resolver, so CHECKING and NOT_CHECKED never come from it; nobody is refused on
                // them either way.
                case POINTS_HERE, UNKNOWN, CHECKING, NOT_CHECKED -> {
                }
            }
        }
    }

    /**
     * The user-facing rendering of an authority refusal; the decision lives in CertificateAuthority, inside the
     * service, so every entry point answers to the same rule.
     */
    private static @NonNull Microcopy refusalMessage(CertificateAuthority.@NonNull Refused refused) {
        String key = switch (refused.refusal()) {
            case NOT_SERVED -> "hostname_not_served";
            case NOT_MANAGED -> "hostname_not_managed";
            case EXCLUDED -> "excluded_hostnames";
        };
        return HohenheimMicrocopy.CERTIFICATE_REQUEST_ERROR.of(key).withArg("hostnames", refused.hostname());
    }

    private static CertificateOperations.@NonNull Order require(CertificateOperations.@Nullable Order order) {
        if (order == null) {
            throw refused(HohenheimMicrocopy.CERTIFICATE_REQUEST_ERROR.of("domain_required"));
        }
        return order;
    }

    private static @NonNull Violations refused(@NonNull Microcopy message) {
        return Violations.ofForm(message);
    }

    private static <S, I> @NonNull Authorizer<S, I> admin() {
        return (subject, input, access) -> HohenheimAccess.isAdmin(access) ? null
            : new DomainRefusal(ZenitRefusalReason.FORBIDDEN, "certificates are installation administration");
    }
}
