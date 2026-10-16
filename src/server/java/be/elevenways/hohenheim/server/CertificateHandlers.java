package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.server.tls.CertificatePem;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;

import java.nio.charset.StandardCharsets;

/**
 * Certificates: the PEM bundle download and the public chain download. Let's Encrypt orders are placed operations
 * (CertificateOperations).
 */
final class CertificateHandlers {

    private CertificateHandlers() {
    }

    static void init() {
        CertificateModel certModel = Models.get(CertificateModel.class);

        HohenheimEndpoints.CERTIFICATES_DOWNLOAD.setHandler(conduit -> {
            Integer certId = conduit.getParameter(HohenheimEndpoints.CERT_ID);
            Row cert = certModel.findById(certId);
            if (cert == null) {
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
            // Only the CERTIFICATE blocks: whatever else the stored text holds is never served as the chain.
            String chain = CertificatePem.chainText(cert == null ? null : cert.get(CertificateModel.CERTIFICATE_PEM));
            if (chain.isEmpty()) {
                return HandlerSupport.redirect(CmsRoutes.list(HohenheimSlugs.ADMIN, HohenheimSlugs.CERTIFICATES));
            }
            String niceName = cert.get(CertificateModel.NICE_NAME);
            HandlerSupport.download(conduit, "application/x-pem-file",
                (niceName != null ? niceName : "certificate") + "-chain.pem",
                chain.toString().getBytes(StandardCharsets.UTF_8));
            return null;
        });
    }
}
