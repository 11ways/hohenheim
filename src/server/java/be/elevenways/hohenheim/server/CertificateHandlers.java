package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.server.tls.CertificatePem;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.StandardCharsets;

/**
 * Certificates: the PEM bundle download, the public chain download and the machine API beside them
 * ({@link CertificateApiHandlers}). Let's Encrypt orders from the admin are placed operations (CertificateOperations).
 */
final class CertificateHandlers {

    private CertificateHandlers() {
    }

    static void init() {
        CertificateModel certModel = Models.get(CertificateModel.class);

        HohenheimEndpoints.CERTIFICATES_DOWNLOAD.setHandler(conduit -> {
            Integer certId = conduit.getParameter(HohenheimEndpoints.CERT_ID);
            Row cert = certModel.findById(certId);
            // A certificate whose requester holds the key has no key here: there is no bundle to make, and a chain
            // with an empty key block would read as one.
            if (cert == null || !CertificateModel.keyHeldHere(cert)) {
                return HandlerSupport.redirect(CmsRoutes.list(HohenheimSlugs.ADMIN, HohenheimSlugs.CERTIFICATES));
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

        HohenheimEndpoints.CERTIFICATES_CHAIN_DOWNLOAD.setHandler(conduit -> {
            Row cert = certModel.findById(conduit.getParameter(HohenheimEndpoints.CERT_ID));
            if (!sendChain(conduit, cert)) {
                return HandlerSupport.redirect(CmsRoutes.list(HohenheimSlugs.ADMIN, HohenheimSlugs.CERTIFICATES));
            }
            return null;
        });

        CertificateApiHandlers.init();
    }

    /**
     * THE public chain download, for the admin route and the key-scoped machine read alike.
     *
     * @return whether a chain was sent; false when the certificate is absent or holds no certificate block
     */
    static boolean sendChain(@NonNull Conduit conduit, @Nullable Row cert) {
        // Only the CERTIFICATE blocks: whatever else the stored text holds is never served as the chain.
        String chain = CertificatePem.chainText(cert == null ? null : cert.get(CertificateModel.CERTIFICATE_PEM));
        if (chain.isEmpty()) {
            return false;
        }
        String niceName = cert.get(CertificateModel.NICE_NAME);
        HandlerSupport.download(conduit, "application/x-pem-file",
            (niceName != null ? niceName : "certificate") + "-chain.pem", chain.getBytes(StandardCharsets.UTF_8));
        return true;
    }
}
