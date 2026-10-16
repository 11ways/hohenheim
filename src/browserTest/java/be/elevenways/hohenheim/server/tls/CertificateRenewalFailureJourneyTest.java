package be.elevenways.hohenheim.server.tls;

import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Let's Encrypt certificate whose renewal fails: its public chain downloads from its page without the key, the
 * failure is stored with its moment and read in plain words on the list and the page, and the page's health band
 * offers "Renew now", which runs the renewal again.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class CertificateRenewalFailureJourneyTest extends HohenheimTestBase {

    private static final String HOST = "renewfail-journey.example";
    private static final String CERT_PEM =
        "-----BEGIN CERTIFICATE-----\nUkVORVdGQUlMLUNIQUlO\n-----END CERTIFICATE-----";
    private static final String KEY_PEM =
        "-----BEGIN PRIVATE KEY-----\nUkVORVdGQUlMLUtFWQ==\n-----END PRIVATE KEY-----";

    private Integer certId;

    /** The proxy this class adopted when the fork runs none; nothing here contacts a CA. */
    private static ProxyServer adoptedProxy;

    @BeforeAll
    static void adoptAProxy() {
        if (ServerMain.getProxyServer() == null) {
            adoptedProxy = new ProxyServer();
            ServerMain.adoptProxyServer(adoptedProxy);
        }
    }

    @AfterAll
    static void releaseTheProxy() {
        if (adoptedProxy != null) {
            ServerMain.adoptProxyServer(null);
            adoptedProxy = null;
        }
    }

    @AfterEach
    void removeTheCertificate() {
        if (this.certId != null) {
            Models.get(CertificateModel.class).delete(this.certId);
        }
    }

    @Test
    void aFailingRenewalSaysWhyAndWhenAndOffersRenewNow() throws Exception {
        CertificateModel certificates = Models.get(CertificateModel.class);
        Row cert = certificates.createEmptyRow();
        cert.set(CertificateModel.NICE_NAME, HOST);
        cert.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_LETSENCRYPT);
        cert.set(CertificateModel.STATUS, CertificateModel.STATUS_ACTIVE);
        cert.set(CertificateModel.DOMAIN_NAMES_TEXT, HOST);
        cert.set(CertificateModel.CHALLENGE_TYPE, CertificateModel.CHALLENGE_HTTP);
        cert.set(CertificateModel.AUTO_RENEW, true);
        cert.set(CertificateModel.CERTIFICATE_PEM, CERT_PEM + "\n" + KEY_PEM);
        cert.set(CertificateModel.PRIVATE_KEY_PEM, KEY_PEM);
        certificates.save(cert);
        this.certId = cert.get(CertificateModel.ID);
        String recordPage = "/admin/certificates/" + this.certId + "/page/overview";

        // 1. A working certificate's page offers its public chain, and the chain carries no key, even where the stored
        //    certificate text holds one.
        navigateToApp(recordPage);
        waitForHydration();
        assertThat(page.locator("body").textContent()).as("step 1: the page offers the chain")
            .contains("Download certificate chain");
        HttpResponse<String> chain = adminGet("/certificates/" + this.certId + "/chain");
        assertThat(chain.statusCode()).as("step 1: the chain downloads").isEqualTo(200);
        assertThat(chain.body()).as("step 1: the certificate block and nothing of the key")
            .contains(CERT_PEM).doesNotContain("PRIVATE KEY");
        assertThat(page.locator("body").textContent())
            .as("step 1: a working certificate offers no renewal").doesNotContain("Renew now");

        // 2. The renewal sweep's own path fails it: this installation serves no such name. The failure is stored with
        //    its moment, the certificate's activity entry.
        ServerMain.getProxyServer().getAcmeService().renewCertificate(cert, certificates);
        assertThat(failures()).as("step 2: the failure is noted with its moment").isEqualTo(1);

        // 3. The list says why in plain words, and when.
        navigateToApp("/admin/certificates");
        waitForHydration();
        assertThat(page.locator("body").textContent()).as("step 3: the list's state line")
            .contains("The last renewal failed on")
            .contains("This installation does not serve " + HOST);

        // 4. The page's health band says the same and offers Renew now; the raw refusal is its technical detail.
        navigateToApp(recordPage);
        waitForHydration();
        String band = page.locator("[data-cms-record-health]").textContent();
        assertThat(band).as("step 4: the band's reason and moment")
            .contains("The last renewal failed on").contains("This installation does not serve " + HOST);
        assertThat(page.locator("[data-cms-record-health-fixes]").textContent())
            .as("step 4: the band offers the renewal").contains("Renew now");

        // 5. Renew now, confirmed, runs the renewal again: it is refused, and noted as a second failure.
        HttpResponse<String> renewed = adminPostForm(
            "/admin/certificates/invoke/hohenheim.renew_certificate?ids=" + this.certId, confirmed(""));
        assertThat(renewed.statusCode()).as("step 5: answered, its refusal flashed back to the page")
            .isIn(302, 303, 422);
        assertThat(failures()).as("step 5: the second failure is noted").isEqualTo(2);
        Row after = certificates.findById(this.certId);
        assertThat((Integer) after.get(CertificateModel.ERROR_COUNT)).as("step 5: counted").isEqualTo(2);
    }

    private long failures() {
        return Models.get(ActivityModel.class).find()
            .where(ActivityModel.MODEL.eq(CertificateModel.MODEL_ID.toString()))
            .where(ActivityModel.RECORD_ID.eq(String.valueOf(this.certId)))
            .where(ActivityModel.ACTION.eq("hohenheim:renewal_failed"))
            .all().size();
    }
}
