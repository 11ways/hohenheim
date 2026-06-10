package be.elevenways.hohenheim.server.handlers;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.AuditLogModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.tls.AcmeService;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.result.RenderTemplateResult;

import org.bouncycastle.openssl.PEMParser;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Certificate request handlers.
 */
public final class CertificateHandlers {

    private CertificateHandlers() {
    }

    public static void init() {
        CertificateModel certModel = Models.get(CertificateModel.class);

        HohenheimEndpoints.CERTIFICATES_LIST.setHandler(conduit -> {
            List<Row> rows = certModel.find()
                .orderBy(CertificateModel.CREATED_AT, SortOrder.DESC)
                .all();

            // Filter out the internal ACME account key row
            rows.removeIf(r -> CertificateModel.PROVIDER_ACME_ACCOUNT.equals(r.get(CertificateModel.PROVIDER)));

            Instant now = Instant.now();
            List<Map<String, Object>> certs = new ArrayList<>();
            for (Row row : rows) {
                Map<String, Object> cert = new HashMap<>();
                cert.put("id", row.get(CertificateModel.ID));
                cert.put("niceName", row.get(CertificateModel.NICE_NAME));
                cert.put("provider", row.get(CertificateModel.PROVIDER));

                Object expiresOnObj = row.get(CertificateModel.EXPIRES_ON);
                cert.put("expiresOn", expiresOnObj);
                cert.put("autoRenew", row.get(CertificateModel.AUTO_RENEW));

                String status = row.get(CertificateModel.STATUS);
                cert.put("status", status != null ? status : CertificateModel.STATUS_ACTIVE);
                cert.put("renewalError", row.get(CertificateModel.RENEWAL_ERROR));
                cert.put("domains", row.get(CertificateModel.DOMAIN_NAMES_TEXT));

                // Compute expiry warning level
                if (expiresOnObj instanceof Instant expiresAt && CertificateModel.STATUS_ACTIVE.equals(status)) {
                    long daysLeft = Duration.between(now, expiresAt).toDays();
                    if (daysLeft < 0) {
                        cert.put("expiryStatus", "expired");
                    } else if (daysLeft <= 7) {
                        cert.put("expiryStatus", "critical");
                    } else if (daysLeft <= 30) {
                        cert.put("expiryStatus", "warning");
                    } else {
                        cert.put("expiryStatus", "ok");
                    }
                    cert.put("daysLeft", daysLeft);
                }

                certs.add(cert);
            }

            return new RenderTemplateResult(
                Identifier.of("hohenheim", "hohenheim/certificates/list"),
                Map.of("certificates", certs)
            );
        });

        // Upload form (GET)
        HohenheimEndpoints.CERTIFICATES_UPLOAD_FORM.setHandler(conduit ->
            new RenderTemplateResult(
                Identifier.of("hohenheim", "hohenheim/certificates/upload"),
                Map.of("error", "")
            )
        );

        // Upload (POST)
        HohenheimEndpoints.CERTIFICATES_UPLOAD.setHandler(conduit -> {
            Map<String, String> form = HandlerSupport.formMap(conduit);

            String niceName = form.getOrDefault("nice_name", "").trim();
            String certPem = form.getOrDefault("certificate_pem", "").trim();
            String keyPem = form.getOrDefault("private_key_pem", "").trim();

            if (niceName.isEmpty() || certPem.isEmpty() || keyPem.isEmpty()) {
                return HandlerSupport.renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/certificates/upload"),
                    Map.of("error", "Name, certificate, and private key are required")
                );
            }

            // Validate PEM format before saving
            try {
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                cf.generateCertificates(new ByteArrayInputStream(certPem.getBytes()));
            } catch (Exception e) {
                return HandlerSupport.renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/certificates/upload"),
                    Map.of("error", "Invalid certificate PEM: " + e.getMessage())
                );
            }

            try {
                new PEMParser(new StringReader(keyPem)).readObject();
            } catch (Exception e) {
                return HandlerSupport.renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/certificates/upload"),
                    Map.of("error", "Invalid private key PEM: " + e.getMessage())
                );
            }

            Row cert = certModel.createEmptyRow();
            cert.set(CertificateModel.NICE_NAME, niceName);
            cert.set(CertificateModel.CERTIFICATE_PEM, certPem);
            cert.set(CertificateModel.PRIVATE_KEY_PEM, keyPem);
            cert.set(CertificateModel.PROVIDER, "custom");
            cert.set(CertificateModel.STATUS, CertificateModel.STATUS_ACTIVE);
            certModel.save(cert);

            HandlerSupport.audit(conduit, AuditLogModel.ACTION_UPLOADED, AuditLogModel.RESOURCE_CERTIFICATE,
                cert.get(CertificateModel.ID), niceName);
            HandlerSupport.reloadProxy();
            return HandlerSupport.redirectUntyped("/certificates");
        });

        // Let's Encrypt request form (GET)
        HohenheimEndpoints.CERTIFICATES_REQUEST_FORM.setHandler(conduit ->
            new RenderTemplateResult(
                Identifier.of("hohenheim", "hohenheim/certificates/request"),
                Map.of("error", "")
            )
        );

        // Let's Encrypt request (POST)
        HohenheimEndpoints.CERTIFICATES_REQUEST.setHandler(conduit -> {
            Map<String, String> form = HandlerSupport.formMap(conduit);

            String domains = form.getOrDefault("domains", "").trim();
            String niceName = form.getOrDefault("nice_name", "").trim();
            String email = form.getOrDefault("letsencrypt_email", "").trim();

            if (!email.isEmpty() && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                return HandlerSupport.renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/certificates/request"),
                    Map.of("error", "Invalid account email: " + email)
                );
            }

            if (domains.isEmpty()) {
                return HandlerSupport.renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/certificates/request"),
                    Map.of("error", "At least one domain is required")
                );
            }

            if (niceName.isEmpty()) {
                niceName = domains.split("[,\\s]+")[0];
            }

            var proxy = ServerMain.getProxyServer();
            if (proxy == null) {
                return HandlerSupport.renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/certificates/request"),
                    Map.of("error", "Proxy server not initialized")
                );
            }

            List<String> hostnames = new ArrayList<>();
            for (String d : domains.split("[,\\s]+")) {
                d = d.trim();
                if (!d.isEmpty()) hostnames.add(d);
            }

            // Immediate UI feedback instead of a doomed CA round-trip.
            List<String> invalid = AcmeService.invalidHostnames(hostnames);
            if (!invalid.isEmpty()) {
                return HandlerSupport.renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/certificates/request"),
                    Map.of("error", "Invalid hostnames: " + String.join(", ", invalid))
                );
            }

            // Domains explicitly excluded from Let's Encrypt must not be submitted.
            // Reject (rather than silently dropping) so the user resubmits deliberately.
            List<String> excluded = excludedFromLetsencrypt(hostnames);
            if (!excluded.isEmpty()) {
                return HandlerSupport.renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/certificates/request"),
                    Map.of("error", "Excluded from Let's Encrypt by their domain settings: "
                        + String.join(", ", excluded))
                );
            }

            int certId = proxy.getAcmeService().requestCertificate(hostnames, niceName,
                email.isEmpty() ? null : email);

            if (certId < 0) {
                // Lookup the specific cert row to get the error message
                // requestCertificate creates a row even on failure -- find it by provider+status
                Row failed = certModel.find()
                    .where(CertificateModel.STATUS.eq("error"))
                    .orderBy(CertificateModel.CREATED_AT, SortOrder.DESC)
                    .first();
                String error = failed != null ? failed.get(CertificateModel.RENEWAL_ERROR) : "Unknown error";
                return HandlerSupport.renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/certificates/request"),
                    Map.of("error", "Certificate request failed: " + (error != null ? error : "Unknown"))
                );
            }

            HandlerSupport.audit(conduit, AuditLogModel.ACTION_REQUESTED, AuditLogModel.RESOURCE_CERTIFICATE,
                certId, niceName);
            HandlerSupport.reloadProxy();
            return HandlerSupport.redirectUntyped("/certificates");
        });

        // Download (GET /certificates/:id/download)
        HohenheimEndpoints.CERTIFICATES_DOWNLOAD.setHandler(conduit -> {
            Integer certId = conduit.getParameter(HohenheimEndpoints.CERT_ID);
            Row cert = certModel.findById(certId);
            if (cert == null) return HandlerSupport.redirectUntyped("/certificates");

            String certPem = cert.get(CertificateModel.CERTIFICATE_PEM);
            String keyPem = cert.get(CertificateModel.PRIVATE_KEY_PEM);
            String niceName = cert.get(CertificateModel.NICE_NAME);
            if (certPem == null) certPem = "";
            if (keyPem == null) keyPem = "";

            // Build a simple concatenated PEM bundle
            String bundle = "# Certificate: " + niceName + "\n\n" + certPem + "\n" + keyPem;

            HandlerSupport.download(conduit, "application/x-pem-file",
                (niceName != null ? niceName : "certificate") + ".pem", bundle);
            return null;
        });

        // Delete (POST)
        HohenheimEndpoints.CERTIFICATES_DELETE.setHandler(conduit -> {
            Integer certId = conduit.getParameter(HohenheimEndpoints.CERT_ID);
            Row cert = certModel.findById(certId);
            String niceName = cert != null ? cert.get(CertificateModel.NICE_NAME) : null;
            certModel.find().where(CertificateModel.ID.eq(certId)).delete();
            HandlerSupport.audit(conduit, AuditLogModel.ACTION_DELETED, AuditLogModel.RESOURCE_CERTIFICATE,
                certId, niceName);
            HandlerSupport.reloadProxy();
            return HandlerSupport.redirectUntyped("/certificates");
        });
    }

    /** The subset of hostnames whose domain record opted out of Let's Encrypt. */
    private static List<String> excludedFromLetsencrypt(List<String> hostnames) {
        var domainModel = Models.get(SiteDomainModel.class);
        List<String> excluded = new ArrayList<>();
        for (String hostname : hostnames) {
            Row domain = domainModel.find()
                .where(SiteDomainModel.HOSTNAME.eq(hostname))
                .where(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT.eq(true))
                .first();
            if (domain != null) {
                excluded.add(hostname);
            }
        }
        return excluded;
    }
}
