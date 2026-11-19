package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.server.api.ApiConduits;
import be.elevenways.hohenheim.server.cms.CertificateOperationHandlers;
import be.elevenways.hohenheim.server.cms.TenantScopes;
import be.elevenways.hohenheim.server.tls.AcmeService;
import be.elevenways.hohenheim.server.tls.CertificateCoverage;
import be.elevenways.hohenheim.server.tls.CertificatePem;
import be.elevenways.hohenheim.server.tls.RenewalFailure;
import be.elevenways.hohenheim.server.tls.SigningRequest;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The certificate API: a key orders a certificate from its own signing request, reads its status and fetches its chain;
 * the key it holds never reaches Hohenheim.
 *
 * AIDEV-NOTE: a key reads exactly the certificates of THE /manage certificate scope ({@link TenantScopes#CERTIFICATES}:
 * the walk, where a requester owns what it ordered), and a certificate outside it answers the route's 404 whether it
 * exists or not. The chain is the admin chain download's own body ({@link CertificateHandlers#sendChain}). No key is
 * ever part of a projection: a certificate whose key Hohenheim holds reads its status and chain like any other, and
 * its key stays the interactive-only bundle's.
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
            Row cert = Models.get(CertificateModel.class).findById(outcome.certificateId());
            if (cert == null) {
                conduit.notFound();
                return null;
            }
            // Created either way: a failed first order is a stored certificate the renewal sweep retries, and its
            // status says why it is not issued yet.
            conduit.setResponseStatus(outcome.issued() ? 201 : 202);
            return ApiConduits.json(projection(conduit, cert));
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
