package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.StateLineCell;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
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
        assertThat(Long.parseLong(String.valueOf(failingCell.detail().args().get("days"))))
            .as("step 1: about twelve days left").isBetween(11L, 12L);

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
