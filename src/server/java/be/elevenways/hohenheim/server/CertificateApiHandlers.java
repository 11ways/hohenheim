package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.server.api.ApiConduits;
import be.elevenways.hohenheim.server.api.PaasApi;
import be.elevenways.hohenheim.server.api.SiteApi;
import be.elevenways.hohenheim.server.cms.CertificateOperations;
import be.elevenways.hohenheim.server.cms.CertificateOperationHandlers;
import be.elevenways.hohenheim.server.cms.TenantScopes;
import be.elevenways.hohenheim.server.tls.AcmeService;
import be.elevenways.hohenheim.server.tls.CertificateCoverage;
import be.elevenways.hohenheim.server.tls.CertificatePem;
import be.elevenways.hohenheim.server.tls.RenewalFailure;
import be.elevenways.hohenheim.server.tls.SigningRequest;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.operation.ZenitPlacementSurface;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.http.body.FormSubmissionRawValues;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.operation.OperationRequest;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * The certificate API: a key orders a certificate from its own signing request, reads its status and fetches its chain;
 * the key it holds never reaches Hohenheim. An admin key also orders the certificate an address row's "Get a
 * certificate" orders, whose key Hohenheim holds.
 *
 * AIDEV-NOTE: a key reads exactly the certificates of THE /manage certificate scope ({@link TenantScopes#CERTIFICATES}:
 * the walk, where a requester owns what it ordered), and a certificate outside it answers the route's 404 whether it
 * exists or not. The chain is the admin chain download's own body ({@link CertificateHandlers#sendChain}). No key is
 * ever part of a projection: a certificate whose key Hohenheim holds reads its status and chain like any other, and
 * its key stays the interactive-only bundle's.
 *
 * AIDEV-NOTE: the address order is the row action's operation ({@link CertificateOperations#REQUEST_FOR_DOMAIN}) run
 * through the pipeline, so its applicability (an exact, included name on a site this proxy terminates TLS for), its
 * admin-only authorizer, its availability and its hh_le_request budget are the panel's. The one thing asked here is
 * that authorizer's own refusal ({@code CertificateOperationHandlers.adminRefusal}), once the site is admitted and
 * BEFORE the address is looked up, so a refused key cannot tell a known address from an unknown one.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
final class CertificateApiHandlers {

    private CertificateApiHandlers() {
    }

    static void init() {
        HohenheimEndpoints.API_CERTIFICATES.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            var query = Models.get(CertificateModel.class).find();
            Criteria scope = TenantScopes.CERTIFICATES.criteria(ctx);
            if (scope != null) {
                query.where(scope);
            }
            List<Map<String, Object>> certificates = new ArrayList<>();
            for (Row cert : query.orderBy(CertificateModel.ID, SortOrder.ASC).all()) {
                certificates.add(projection(conduit, cert));
            }
            return ApiConduits.json(Map.of("certificates", certificates));
        });

        HohenheimEndpoints.API_CERTIFICATE_CREATE.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            AcmeService.RequestOutcome outcome;
            try {
                outcome = CertificateOperationHandlers.orderFromSigningRequest(
                    ApiConduits.formValue(conduit, CertificateOperationHandlers.SIGNING_REQUEST_FIELD),
                    hostnamesOf(conduit), ApiConduits.formValue(conduit, "name"), ctx);
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            }
            // A replay (the same key for the same names, THE requester-key name-set rule in AcmeService) is the
            // existing certificate as it stands, 200. A new row is created either way: a failed first order is a
            // stored certificate the renewal sweep retries, and its status says why it is not issued yet.
            int status = outcome.replayed() ? 200 : outcome.issued() ? 201 : 202;
            return answer(conduit, outcome.certificateId(), cert -> status);
        });

        HohenheimEndpoints.API_V1_SITE_DOMAIN_CERTIFICATE.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            Row site = ctx == null ? null : PaasApi.visibleSite(conduit, ctx);
            if (site == null) {
                return null;
            }
            // The order's own authority before the address is looked up: a site manager it refuses is told the same
            // for every address of the site, an existing one or not.
            DomainRefusal withheld = CertificateOperationHandlers.adminRefusal(ctx);
            if (withheld != null) {
                return ApiConduits.refusal(conduit, withheld);
            }
            Row domain = SiteApi.domainOf(conduit, site);
            if (domain == null) {
                return null;
            }
            // The dialog's own input; the names default to the address itself, as the row's dialog opens with it.
            Map<String, Object> order = new LinkedHashMap<>(FormSubmissionRawValues.fromConduit(conduit));
            order.putIfAbsent("domains", String.valueOf((Object) domain.get(SiteDomainModel.HOSTNAME)));
            Integer certId;
            try {
                certId = OperationPipeline.invoke(OperationRequest.of(CertificateOperations.REQUEST_FOR_DOMAIN,
                        ZenitPlacementSurface.HTTP_API)
                    .caller(ctx)
                    .subjects(List.of(domain))
                    .form(order)).value();
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            } catch (DomainRefusal refused) {
                return ApiConduits.refusal(conduit, refused);
            }
            // Issued now, or written and waiting: a manual DNS-01 order waits for its TXT records.
            return answer(conduit, certId,
                cert -> CertificateModel.STATUS_ACTIVE.equals(cert.get(CertificateModel.STATUS)) ? 201 : 202);
        });

        HohenheimEndpoints.API_CERTIFICATE.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            Row cert = ctx == null ? null : visible(conduit, ctx);
            return cert == null ? null : ApiConduits.json(projection(conduit, cert));
        });

        HohenheimEndpoints.API_CERTIFICATE_CHAIN.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            Row cert = ctx == null ? null : visible(conduit, ctx);
            if (cert != null && !CertificateHandlers.sendChain(conduit, cert)) {
                // Not issued yet: no chain to send, the same answer as a certificate the key cannot read.
                conduit.notFound();
            }
            return null;
        });

        HohenheimEndpoints.API_CERTIFICATE_SIGNING_REQUEST.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            Row cert = ctx == null ? null : visible(conduit, ctx);
            if (cert == null) {
                return null;
            }
            try {
                CertificateOperationHandlers.reorderFromSigningRequest(cert,
                    ApiConduits.formValue(conduit, CertificateOperationHandlers.SIGNING_REQUEST_FIELD),
                    hostnamesOf(conduit), ctx);
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            }
            Row reordered = Models.get(CertificateModel.class).findById(cert.get(CertificateModel.ID));
            return ApiConduits.json(projection(conduit, reordered != null ? reordered : cert));
        });
    }

    /**
     * A placed order's answer: the certificate it wrote, with the status its row earns; the route's 404 when the row is
     * gone.
     *
     * @return the answer, or null when the response has already been ended
     */
    private static @Nullable ActionResult<Object> answer(@NonNull Conduit conduit, @Nullable Integer certId,
                                                         @NonNull ToIntFunction<Row> status) {
        Row cert = certId == null ? null : Models.get(CertificateModel.class).findById(certId);
        if (cert == null) {
            conduit.notFound();
            return null;
        }
        conduit.setResponseStatus(status.applyAsInt(cert));
        return ApiConduits.json(projection(conduit, cert));
    }

    /** @return the submitted {@code hostnames}, comma- or whitespace-separated, blanks dropped */
    private static @NonNull List<String> hostnamesOf(@NonNull Conduit conduit) {
        List<String> names = new ArrayList<>();
        for (String raw : ApiConduits.formValue(conduit, "hostnames").split("[,\\s]+")) {
            String name = raw.trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * The route's certificate when this key may read it, the response ended with the route's 404 otherwise: absent and
     * not readable are one answer, or the route would tell which certificate ids exist.
     */
    private static @Nullable Row visible(@NonNull Conduit conduit, @NonNull AccessContext ctx) {
        Integer certId = conduit.getParameter(HohenheimEndpoints.CERT_ID);
        Row cert = null;
        if (certId != null) {
            var query = Models.get(CertificateModel.class).find().where(CertificateModel.ID.eq(certId));
            Criteria scope = TenantScopes.CERTIFICATES.criteria(ctx);
            if (scope != null) {
                query.where(scope);
            }
            cert = query.first();
        }
        if (cert == null) {
            conduit.notFound();
        }
        return cert;
    }

    /** THE certificate projection of this API: an enumerated whitelist, never a row dump, and never a key. */
    private static @NonNull Map<String, Object> projection(@NonNull Conduit conduit, @NonNull Row cert) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", cert.get(CertificateModel.ID));
        entry.put("name", cert.get(CertificateModel.NICE_NAME));
        entry.put("hostnames", CertificateCoverage.namesOf(cert));
        entry.put("status", cert.get(CertificateModel.STATUS));
        entry.put("key_holder", cert.get(CertificateModel.KEY_HOLDER));
        entry.put("auto_renew", cert.get(CertificateModel.AUTO_RENEW));
        entry.put("challenge", cert.get(CertificateModel.CHALLENGE_TYPE));
        entry.put("issued_on", instant(cert.get(CertificateModel.ISSUED_ON)));
        entry.put("expires_on", instant(cert.get(CertificateModel.EXPIRES_ON)));
        entry.put("next_attempt_at", instant(cert.get(CertificateModel.NEXT_ATTEMPT_AT)));
        entry.put("public_key_sha256", issuedKeyPin(cert));
        RenewalFailure failure = RenewalFailure.of(cert);
        if (failure != null) {
            Microcopy sentence = failure.sentence();
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("code", sentence.key());
            failed.put("message", sentence.asString(conduit));
            failed.put("at", instant(failure.at()));
            entry.put("failure", failed);
        }
        else {
            entry.put("failure", null);
        }
        return entry;
    }

    /**
     * @return the SHA-256 of the issued leaf's SubjectPublicKeyInfo (the DANE 3 1 1 pin), null before issuance or for a
     *         stored chain that does not parse
     */
    private static @Nullable String issuedKeyPin(@NonNull Row cert) {
        try {
            List<X509Certificate> chain = CertificatePem.chainOf(cert.get(CertificateModel.CERTIFICATE_PEM));
            return chain.isEmpty() ? null : SigningRequest.publicKeyPin(chain.get(0).getPublicKey());
        } catch (Exception unreadable) {
            return null;
        }
    }

    private static @Nullable String instant(@Nullable Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
