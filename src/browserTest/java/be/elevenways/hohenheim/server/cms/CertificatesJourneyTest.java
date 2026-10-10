package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.StateLineCell;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.test.ProxyTestSupport;
import be.elevenways.hohenheim.test.TlsCertificateTest;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.ui.BadgeVariant;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The Certificates list says what each certificate's state means: a failing renewal and how long the old one still
 * holds, a manual DNS order waiting for its record, or that it works and for how long; an upload that does not parse is
 * refused on its field.
 *
 * @author Jelle De Loecker
 * @since  0.2.0
 */
class CertificatesJourneyTest extends HohenheimTestBase {

    @Test
    void aCertificateStateReadsInWordsAndABadUploadIsRefused() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        // 1. A renewal that failed three times while the old certificate still serves is "failing", saying how many
        //    times and how long it still holds: the one an operator must act on.
        Row failing = certificate("failing-" + suffix, CertificateModel.STATUS_ACTIVE, 3, 12,
            CertificateModel.CHALLENGE_DNS, CertificateModel.DNS_PUBLISHER_COMMAND);
        StateLineCell failingCell = CertificateParts.stateCell(failing);
        assertThat(failingCell.state()).as("step 1: a failing renewal").isEqualTo("renewal_failing");
        assertThat(String.valueOf(failingCell.detail().args().get("count"))).as("step 1: three failures")
            .isEqualTo("3");
        assertThat(failingCell.detail().resolve(LocaleChain.ofTags("en"), Zenit.getMessageResolver()))
            .as("step 1: about twelve days left, in the one expiry wording (CertificateExpiry)")
            .matches("Failed 3 times · expires in 1[12] days");

        // 2. A manual DNS order waiting for its record says so.
        Row waiting = certificate("waiting-" + suffix, CertificateModel.STATUS_PENDING, 0, null,
            CertificateModel.CHALLENGE_DNS, CertificateModel.DNS_PUBLISHER_MANUAL);
        assertThat(CertificateParts.stateCell(waiting).state()).as("step 2: waiting for a DNS record")
            .isEqualTo("waiting_dns");

        // 3. A healthy Let's Encrypt certificate works, renews itself and says for how long it holds.
        Row works = certificate("works-" + suffix, CertificateModel.STATUS_ACTIVE, 0, 80,
            CertificateModel.CHALLENGE_HTTP, null);
        StateLineCell worksCell = CertificateParts.stateCell(works);
        assertThat(worksCell.state()).as("step 3: it works").isEqualTo("works");
        assertThat(worksCell.detail().key()).as("step 3: and renews itself").isEqualTo("state_renews_detail");
        assertThat(worksCell.detail().resolve(LocaleChain.ofTags("en"), Zenit.getMessageResolver()))
            .as("step 3: saying when it expires in whole days").matches("Renews itself; expires in (79|80) days");

        // 4. The list renders the three states as words.
        HttpResponse<String> page = adminGet("/admin/certificates");
        assertThat(page.statusCode()).as("step 4: the Certificates list renders").isEqualTo(200);
        assertThat(page.body()).as("step 4: the failing renewal")
            .contains("data-state=\"renewal_failing\"")
            .as("step 4: the waiting order").contains("data-state=\"waiting_dns\"")
            .as("step 4: the working certificate").contains("data-state=\"works\"");

        // 5. An upload whose certificate does not parse is refused on the certificate field, and nothing is written.
        long before = Models.get(CertificateModel.class).find().count();
        Violations refused = catchThrowableOfType(Violations.class, () -> CertificateParts.create(Map.of(
            CertificateModel.NICE_NAME.getName(), "Bad upload " + suffix,
            CertificateModel.CERTIFICATE_PEM.getName(), "not a certificate",
            CertificateModel.PRIVATE_KEY_PEM.getName(), "not a key")));
        assertThat((Object) refused).as("step 5: a bad PEM is refused").isNotNull();
        assertThat(refused.all()).as("step 5: on the certificate field, as unparseable")
            .anySatisfy(violation -> {
                assertThat(violation.fieldName()).isEqualTo("certificate_pem");
                assertThat(violation.message().key()).isEqualTo("cert_pem_invalid");
            });
        assertThat(Models.get(CertificateModel.class).find().count()).as("step 5: nothing was written")
            .isEqualTo(before);
    }

    @Test
    void everyCertificateIsListedByTheStateTheDashboardCounts() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        // 1. A working certificate inside the expiry alert's window is "Expiring", the dashboard's item and tile:
        //    a Let's Encrypt one that should have renewed already, an upload that never renews itself.
        Row late = certificate("late-" + suffix, CertificateModel.STATUS_ACTIVE, 0, 10,
            CertificateModel.CHALLENGE_HTTP, null);
        StateLineCell lateCell = CertificateParts.stateCell(late);
        assertThat(lateCell.state()).as("step 1: expiring").isEqualTo("expiring");
        assertThat(lateCell.variant()).as("step 1: needs a look, so the dashboard tile counts it")
            .isNotEqualTo(BadgeVariant.SUCCESS);
        assertThat(lateCell.detail().resolve(LocaleChain.ofTags("en"), Zenit.getMessageResolver()))
            .as("step 1: not renewed yet, in the one expiry wording").matches("Not renewed yet; expires in (9|10) days");
        Row upload = certificate("upload-" + suffix, CertificateModel.STATUS_ACTIVE, 0, 10,
            CertificateModel.CHALLENGE_HTTP, null);
        upload.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_CUSTOM);
        Models.get(CertificateModel.class).save(upload);
        assertThat(CertificateParts.stateCell(upload).detail().key()).as("step 1: an upload never renews itself")
            .isEqualTo("state_expiring_upload_detail");
        assertThat(adminGet("/admin/certificates?q=upload-" + suffix).body())
            .as("step 1: its provider reads as an upload in words").containsPattern(">\\s*Uploaded\\s*<");

        // 2. An order not issued yet and an expired certificate say so.
        Row issuing = certificate("issuing-" + suffix, CertificateModel.STATUS_PENDING, 0, null,
            CertificateModel.CHALLENGE_HTTP, null);
        StateLineCell issuingCell = CertificateParts.stateCell(issuing);
        assertThat(issuingCell.state()).as("step 2: not issued yet").isEqualTo("issuing");
        assertThat(issuingCell.label().resolve(LocaleChain.ofTags("en"), Zenit.getMessageResolver()))
            .as("step 2: in plain words").isEqualTo("Not issued yet");
        Row expired = certificate("expired-" + suffix, CertificateModel.STATUS_ACTIVE, 0, -3,
            CertificateModel.CHALLENGE_HTTP, null);
        assertThat(CertificateParts.stateCell(expired).state()).as("step 2: expired").isEqualTo("expired");
        assertThat(adminGet("/admin/certificates?q=issuing-" + suffix).body())
            .as("step 2: an order not issued yet has no expiry, and its cell says why")
            .contains("None - not issued yet");

        // 2b. The name's second line holds the names it covers besides its own, so a certificate named after its one
        //     domain does not repeat it.
        Row self = certificate("self-" + suffix, CertificateModel.STATUS_ACTIVE, 0, 80,
            CertificateModel.CHALLENGE_HTTP, null);
        self.set(CertificateModel.DOMAIN_NAMES_TEXT, "self-" + suffix);
        assertThat(CertificateParts.otherNames(self)).as("step 2b: named after its one domain").isNull();
        self.set(CertificateModel.DOMAIN_NAMES_TEXT, "self-" + suffix + ",www.self-" + suffix);
        assertThat(CertificateParts.otherNames(self)).as("step 2b: only the other names")
            .isEqualTo("www.self-" + suffix);
        assertThat(CertificateParts.otherNames(late)).as("step 2b: a name that is no domain keeps every name")
            .isEqualTo("late-" + suffix + ".test");

        // 3. A row stored as working that the running proxy did not load serves no one: "Failed to load", never
        //    "Works" (the dashboard's HTTPS verdicts read the same store).
        Row works = certificate("works-" + suffix, CertificateModel.STATUS_ACTIVE, 0, 80,
            CertificateModel.CHALLENGE_HTTP, null);
        assertThat(CertificateParts.stateCell(works).state()).as("step 3: without a proxy the stored row is all there is")
            .isEqualTo("works");
        ProxyServer previous = ServerMain.getProxyServer();
        ProxyServer proxy = ProxyTestSupport.startProxy();
        ServerMain.adoptProxyServer(proxy);
        try {
            StateLineCell unloaded = CertificateParts.stateCell(works);
            assertThat(unloaded.state()).as("step 3: the proxy loaded nothing for it").isEqualTo("not_loaded");
            assertThat(unloaded.label().resolve(LocaleChain.ofTags("en"), Zenit.getMessageResolver()))
                .as("step 3: in words").isEqualTo("Failed to load");
            assertThat(unloaded.variant()).as("step 3: a destructive state").isEqualTo(BadgeVariant.DESTRUCTIVE);
            assertThat(adminGet("/admin/certificates?q=works-" + suffix).body())
                .as("step 3: the list says so").contains("data-state=\"not_loaded\"");
        } finally {
            ServerMain.adoptProxyServer(previous);
            proxy.stop();
        }

        // 4. The list shows every certificate with its state, and the header offers the two ways to get
        //    one: "Get a certificate" (Let's Encrypt) beside "Upload a certificate", no generic "New certificate".
        String page = adminGet("/admin/certificates?q=" + suffix).body();
        assertThat(page).as("step 4: expiring").contains("data-state=\"expiring\"")
            .as("step 4: not issued yet").contains("data-state=\"issuing\"")
            .as("step 4: expired").contains("data-state=\"expired\"")
            .as("step 4: working").contains("data-state=\"works\"");
        assertThat(page).as("step 4: get one from Let's Encrypt").contains("Get a certificate")
            .as("step 4: or upload your own").contains("Upload a certificate")
            .as("step 4: the generic create is gone").doesNotContain("New certificate");

        // 5. An uploaded certificate stores what it says about itself: the names it covers and when it expires, so it
        //    reads "Works" with its expiry (and "Expiring" in its last days) like an issued one.
        KeyPair keys = TlsCertificateTest.generateKeyPair();
        String uploadedName = "uploaded-" + suffix + ".test";
        Object id = CertificateParts.create(Map.of(
            CertificateModel.NICE_NAME.getName(), "Uploaded " + suffix,
            CertificateModel.CERTIFICATE_PEM.getName(),
            TlsCertificateTest.certToPem(TlsCertificateTest.generateSelfSignedCert(keys, uploadedName)),
            CertificateModel.PRIVATE_KEY_PEM.getName(), TlsCertificateTest.keyToPem(keys)));
        Row uploaded = Models.get(CertificateModel.class).findById((Integer) id);
        assertThat((String) uploaded.get(CertificateModel.DOMAIN_NAMES_TEXT)).as("step 5: the names it covers")
            .isEqualTo(uploadedName);
        assertThat((Object) uploaded.get(CertificateModel.EXPIRES_ON)).as("step 5: its expiry is stored").isNotNull();
        StateLineCell uploadedCell = CertificateParts.stateCell(uploaded);
        assertThat(uploadedCell.state()).as("step 5: it works").isEqualTo("works");
        assertThat(uploadedCell.detail().resolve(LocaleChain.ofTags("en"), Zenit.getMessageResolver()))
            .as("step 5: saying when it expires").matches("Expires in 36[45] days");
    }

    private static Row certificate(String name, String status, int errors, Integer daysLeft, String challenge,
                                   String publisher) {
        var certs = Models.get(CertificateModel.class);
        Row cert = certs.createEmptyRow();
        cert.set(CertificateModel.NICE_NAME, name);
        cert.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_LETSENCRYPT);
        cert.set(CertificateModel.STATUS, status);
        cert.set(CertificateModel.DOMAIN_NAMES_TEXT, name + ".test");
        cert.set(CertificateModel.CHALLENGE_TYPE, challenge);
        if (publisher != null) {
            cert.set(CertificateModel.DNS_PUBLISHER, publisher);
        }
        cert.set(CertificateModel.ERROR_COUNT, errors);
        cert.set(CertificateModel.AUTO_RENEW, true);
        if (daysLeft != null) {
            cert.set(CertificateModel.EXPIRES_ON, Now.instant().plus(Duration.ofDays(daysLeft)).plusSeconds(60));
        }
        certs.save(cert);
        return certs.findById(cert.get(CertificateModel.ID));
    }
}
