package be.elevenways.hohenheim.server.tls;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.tls.FakeAcmeServer;
import be.elevenways.hohenheim.test.tls.RecordingTxtPublisher;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Re-issuing a stored certificate IN PLACE: adding a hostname, switching HTTP-01 to DNS-01,
 * and what a failed re-issue must NOT do. Runs against {@link FakeAcmeServer}, the same
 * in-JVM RFC 8555 CA the issuance contract uses; nothing here reaches Let's Encrypt.
 *
 * The load-bearing property is asymmetric: a SUCCESSFUL re-issue rewrites the row's
 * certificate AND the names/challenge it was ordered for, while a FAILED one leaves the row
 * byte-identical -- because the row is what renewal re-orders from, and a row carrying names
 * no certificate was ever issued for would renew into that same failure forever.
 *
 * This lives in the service's own package because the renewal entry point is package-private
 * and "renewal thereafter uses the NEW names" is the assertion that makes the same-row write
 * mean anything.
 */
class AcmeReissueContractTest {

    /** Every hostname here ends in this, so no other class in the shared fork covers it. */
    private static final String ZONE = "reissue.test";

    private static SqlDatasource datasource;
    private static FakeAcmeServer ca;
    private static AcmeService acme;
    private static RecordingTxtPublisher publisher;
    private static String savedDirectory;
    private static Integer savedPropagation;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();

        ca = new FakeAcmeServer();
        savedDirectory = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Ssl.ACME_DIRECTORY_URL);
        savedPropagation = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Ssl.DNS_PROPAGATION_SECONDS);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Ssl.ACME_DIRECTORY_URL,
            ca.directoryUrl());
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Ssl.DNS_PROPAGATION_SECONDS, 0);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_EMAIL,
            "reissue-test@example.com");

        acme = new AcmeService(new CertificateStore());
        publisher = new RecordingTxtPublisher("recording_reissue_txt");
        DnsTxtPublishers.INSTANCE.register(publisher);
    }

    @AfterAll
    static void tearDown() {
        if (ca != null) {
            ca.close();
            ca = null;
        }
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Ssl.ACME_DIRECTORY_URL,
            savedDirectory);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Ssl.DNS_PROPAGATION_SECONDS,
            savedPropagation);
    }

    /**
     * The whole re-issue journey on ONE row: add a name, prove renewal follows the new set,
     * switch the challenge type, then fail an order and prove the row did not move.
     */
    @Test
    void aReissueRewritesTheRowOnSuccessAndNeverOnFailure() {
        Db.run(datasource, () -> {
            var certModel = Models.get(CertificateModel.class);
            int siteId = site("reissue-owned");
            domain(siteId, "one." + ZONE);
            domain(siteId, "two." + ZONE);
            domain(siteId, "three." + ZONE);
            answerHttpChallenges();

            // 1. A perfectly ordinary certificate exists first: one name, HTTP-01.
            int certId = acme.requestCertificate(List.of("one." + ZONE), "Reissue subject",
                null, CertificateAuthority.Requester.SYSTEM);
            assertThat(certId).as("step 1: the initial order produced a row").isGreaterThan(0);
            Row cert = certModel.findById(certId);
            String firstPem = cert.get(CertificateModel.CERTIFICATE_PEM);
            // A stored requester the re-issue must overwrite with the actor that re-issued.
            cert.set(CertificateModel.REQUESTED_BY_USER_ID, 4242);
            certModel.save(cert);

            // 2. RE-ISSUE WITH AN ADDED NAME: same row, new material, new name list.
            AcmeService.ReissueResult added = acme.reissueCertificate(certModel.findById(certId),
                List.of("one." + ZONE, "two." + ZONE), null, CertificateModel.CHALLENGE_HTTP,
                null, CertificateAuthority.Requester.SYSTEM);
            assertThat(added.issued())
                .as("step 2: the re-issue succeeded (%s)", added.failureReason()).isTrue();
            assertThat(added.failureReason())
                .as("step 2: a successful re-issue reports no reason").isNull();

            Row afterAdd = certModel.findById(certId);
            assertThat(afterAdd).as("step 2: the SAME row is what was updated").isNotNull();
            assertThat(subjectAltNames(leafOf(afterAdd.get(CertificateModel.CERTIFICATE_PEM))))
                .as("step 2: the stored certificate covers both names")
                .containsExactlyInAnyOrder("one." + ZONE, "two." + ZONE);
            assertThat((String) afterAdd.get(CertificateModel.CERTIFICATE_PEM))
                .as("step 2: and is genuinely new material, not the old chain")
                .isNotEqualTo(firstPem);
            assertThat((String) afterAdd.get(CertificateModel.DOMAIN_NAMES_TEXT))
                .as("step 2: the row's own name list moved with it")
                .isEqualTo("one." + ZONE + ",two." + ZONE);
            assertThat((String) afterAdd.get(CertificateModel.STATUS))
                .as("step 2: and the row is active").isEqualTo(CertificateModel.STATUS_ACTIVE);
            assertThat((Integer) afterAdd.get(CertificateModel.REQUESTED_BY_USER_ID))
                .as("step 2: the re-issuing actor is who renewal re-authorizes as from now on")
                .isNull();

            // 3. RENEWAL FOLLOWS: the sweep re-orders the NEW set, not the one the row was
            //    created with. This is the whole point of writing the names on success.
            Row toRenew = certModel.findById(certId);
            acme.renewCertificate(toRenew, certModel);
            Row renewed = certModel.findById(certId);
            assertThat((String) renewed.get(CertificateModel.STATUS))
                .as("step 3: the renewal succeeded (%s)",
                    renewed.get(CertificateModel.RENEWAL_ERROR))
                .isEqualTo(CertificateModel.STATUS_ACTIVE);
            assertThat(subjectAltNames(leafOf(renewed.get(CertificateModel.CERTIFICATE_PEM))))
                .as("step 3: the renewed certificate carries the RE-ISSUED name set")
                .containsExactlyInAnyOrder("one." + ZONE, "two." + ZONE);

            // 4. CHALLENGE SWITCH http -> dns: the publisher is really used and recorded.
            ca.validateDnsWith((token, identifier) ->
                publisher.valueOf("_acme-challenge." + identifier + ".") != null);
            publisher.published.clear();
            AcmeService.ReissueResult switched = acme.reissueCertificate(
                certModel.findById(certId), List.of("one." + ZONE, "two." + ZONE), null,
                CertificateModel.CHALLENGE_DNS, publisher.id(),
                CertificateAuthority.Requester.SYSTEM);
            assertThat(switched.issued())
                .as("step 4: the DNS-01 re-issue succeeded (%s)", switched.failureReason())
                .isTrue();
            assertThat(publisher.published)
                .as("step 4: a TXT record was published for every name")
                .hasSize(2);
            Row afterSwitch = certModel.findById(certId);
            assertThat((String) afterSwitch.get(CertificateModel.CHALLENGE_TYPE))
                .as("step 4: the row now says DNS-01")
                .isEqualTo(CertificateModel.CHALLENGE_DNS);
            assertThat((String) afterSwitch.get(CertificateModel.DNS_PUBLISHER))
                .as("step 4: with the publisher that answered for it")
                .isEqualTo(publisher.id());

            // 5. THE FAILURE PATH. A refused order must leave the row untouched -- every
            //    column, not just the certificate: the old certificate keeps serving and
            //    keeps renewing against the names it was actually issued for.
            Map<String, Object> before = snapshot(certModel.findById(certId));
            ca.refuseValidation(true);
            AcmeService.ReissueResult failed = acme.reissueCertificate(certModel.findById(certId),
                List.of("one." + ZONE, "two." + ZONE, "three." + ZONE), null,
                CertificateModel.CHALLENGE_HTTP, null, CertificateAuthority.Requester.SYSTEM);
            ca.refuseValidation(false);
            assertThat(failed.issued()).as("step 5: the re-issue reports failure").isFalse();
            assertThat(failed.failureReason())
                .as("step 5: and reports the CA's own refusal, which names the identifier "
                    + "it could not validate (the CA fails the first authorization it walks, "
                    + "so that is not necessarily the ADDED name)")
                .isNotNull()
                .contains("Challenge failed for")
                .contains(ZONE);
            assertThat(snapshot(certModel.findById(certId)))
                .as("step 5: the row is byte-identical: no error state, no new names, "
                    + "no lost certificate")
                .isEqualTo(before);

            // 6. And it is still a healthy, renewable certificate afterwards.
            answerHttpChallenges();
            Row survivor = certModel.findById(certId);
            acme.renewCertificate(survivor, certModel);
            Row renewedAgain = certModel.findById(certId);
            assertThat((String) renewedAgain.get(CertificateModel.STATUS))
                .as("step 6: the surviving certificate renews (%s)",
                    renewedAgain.get(CertificateModel.RENEWAL_ERROR))
                .isEqualTo(CertificateModel.STATUS_ACTIVE);
            assertThat(subjectAltNames(leafOf(renewedAgain.get(CertificateModel.CERTIFICATE_PEM))))
                .as("step 6: on the name set the LAST SUCCESSFUL order used")
                .containsExactlyInAnyOrder("one." + ZONE, "two." + ZONE);
        });
    }

    /**
     * The refusals a re-issue owes, none of which the UI may be trusted to enforce: the FULL
     * new name set is authorized (never "the old ones were once allowed"), a manual upload
     * has no order to repeat, and the manual DNS lane cannot write into an existing row.
     */
    @Test
    void aReissueRefusesUnauthorizedNamesAndNonAcmeRows() {
        Db.run(datasource, () -> {
            var certModel = Models.get(CertificateModel.class);
            int siteId = site("reissue-refused");
            domain(siteId, "served." + ZONE);
            answerHttpChallenges();

            int certId = acme.requestCertificate(List.of("served." + ZONE), "Reissue refusals",
                null, CertificateAuthority.Requester.SYSTEM);
            assertThat(certId).as("precondition: there is a certificate to re-issue")
                .isGreaterThan(0);
            Map<String, Object> before = snapshot(certModel.findById(certId));

            // 1. AN ADDED NAME THIS INSTALLATION DOES NOT SERVE is refused, even though the
            //    row's existing name is authorized -- authority is decided over the WHOLE
            //    new set, at re-issue time, never inherited from the stored row.
            assertThatThrownBy(() -> acme.reissueCertificate(certModel.findById(certId),
                    List.of("served." + ZONE, "unserved." + ZONE), null,
                    CertificateModel.CHALLENGE_HTTP, null,
                    CertificateAuthority.Requester.SYSTEM))
                .as("step 1: the unserved name is refused")
                .isInstanceOf(CertificateAuthority.Refused.class)
                .hasMessageContaining("unserved." + ZONE);
            assertThat(snapshot(certModel.findById(certId)))
                .as("step 1: a refused re-issue never touches the row either")
                .isEqualTo(before);

            // 2. MANUAL DNS cannot re-issue in place: that lane mints its own row by
            //    construction, so accepting it here would silently orphan this one.
            assertThatThrownBy(() -> acme.reissueCertificate(certModel.findById(certId),
                    List.of("served." + ZONE), null, CertificateModel.CHALLENGE_DNS,
                    CertificateModel.DNS_PUBLISHER_MANUAL,
                    CertificateAuthority.Requester.SYSTEM))
                .as("step 2: manual DNS-01 re-issue is refused")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Manual");

            // 3. A CUSTOM UPLOAD IS NOT AN ORDER. The row action hides itself for these and
            //    the handler refuses them; this is the layer that answers for both.
            Row uploaded = certModel.createEmptyRow();
            uploaded.set(CertificateModel.NICE_NAME, "Reissue uploaded");
            uploaded.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_CUSTOM);
            uploaded.set(CertificateModel.STATUS, CertificateModel.STATUS_ACTIVE);
            uploaded.set(CertificateModel.DOMAIN_NAMES_TEXT, "served." + ZONE);
            certModel.save(uploaded);
            assertThatThrownBy(() -> acme.reissueCertificate(uploaded, List.of("served." + ZONE),
                    null, CertificateModel.CHALLENGE_HTTP, null,
                    CertificateAuthority.Requester.SYSTEM))
                .as("step 3: a manual upload cannot be re-ordered")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Let's Encrypt");
        });
    }

    // -- fixture plumbing -----------------------------------------------------

    /** The CA validates by asking the product's own HTTP-01 responder. */
    private static void answerHttpChallenges() {
        ca.validateHttpWith((token, identifier) ->
            acme.getChallengeResponse(token, identifier) != null);
    }

    /** Every stored column of a certificate row, which is what "untouched" is asserted on. */
    private static Map<String, Object> snapshot(Row row) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String name : CertificateModel.SCHEMA.getFields().keySet()) {
            values.put(name, String.valueOf(row.get(name)));
        }
        return values;
    }

    private static int site(String slug) {
        Row site = Models.get(SiteModel.class).createEmptyRow();
        site.set(SiteModel.NAME, "ACME re-issue " + slug);
        site.set(SiteModel.SLUG, slug);
        site.set(SiteModel.SITE_TYPE, "hohenheim:redirect");
        site.set(SiteModel.SETTINGS, Map.of("target", "https://example.com"));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        Models.get(SiteModel.class).save(site);
        return site.get(SiteModel.ID);
    }

    private static void domain(int siteId, String hostname) {
        Row domain = Models.get(SiteDomainModel.class).createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, siteId);
        domain.set(SiteDomainModel.HOSTNAME, hostname);
        domain.set(SiteDomainModel.MATCH_TYPE, hostname.startsWith("*.")
            ? "wildcard" : "exact");
        domain.set(SiteDomainModel.FORCE_SSL, false);
        Models.get(SiteDomainModel.class).save(domain);
    }

    private static X509Certificate leafOf(String pem) {
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(
                    pem.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception malformed) {
            throw new IllegalStateException("the stored chain is not a certificate", malformed);
        }
    }

    private static List<String> subjectAltNames(X509Certificate certificate) {
        List<String> names = new ArrayList<>();
        try {
            var alternatives = certificate.getSubjectAlternativeNames();
            if (alternatives != null) {
                for (List<?> entry : alternatives) {
                    if (entry.size() > 1) {
                        names.add(String.valueOf(entry.get(1)));
                    }
                }
            }
        } catch (Exception unreadable) {
            throw new IllegalStateException(unreadable);
        }
        return names;
    }
}
