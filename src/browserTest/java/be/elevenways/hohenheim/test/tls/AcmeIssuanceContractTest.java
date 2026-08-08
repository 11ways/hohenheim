package be.elevenways.hohenheim.test.tls;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.tls.AcmeService;
import be.elevenways.hohenheim.server.tls.CertificateAuthority;
import be.elevenways.hohenheim.server.tls.CertificateStore;
import be.elevenways.hohenheim.server.tls.DnsTxtPublisher;
import be.elevenways.hohenheim.server.tls.DnsTxtPublishers;
import be.elevenways.hohenheim.server.tls.DnsTxtRecord;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ACME certificate acquisition, hermetically: hohenheim's own acme4j client registers an
 * account, orders, answers an HTTP-01 and a wildcard DNS-01 challenge, signs a real CSR and
 * stores a real certificate -- against {@link FakeAcmeServer}, an RFC 8555 CA in this JVM.
 * Nothing here reaches Let's Encrypt; the test points {@code ssl.acme_directory_url} at
 * loopback, which is the setting this work added because the directory used to be hardcoded.
 *
 * WHAT THIS CANNOT PROVE, so nothing reads it as total coverage:
 *
 * <ul>
 *   <li>that a REAL CA accepts our requests. A fake directory cannot discover that Let's
 *       Encrypt tightened a validation, changed a rate limit, or requires a field we omit;
 *       only a staging order against the real service can, and that is deliberately not a
 *       test.</li>
 *   <li>that the CA can REACH us. The HTTP-01 validator calls the challenge responder
 *       in-process, so DNS resolution, port 80 reachability and the proxy's
 *       {@code .well-known} route are not exercised here.</li>
 *   <li>that a DNS-01 value is really queryable on the internet: the publisher is faked, so
 *       the authoritative-DNS serving path and propagation are the DNS tier's own tests.</li>
 *   <li>certificate TRUST. The chain is signed by a throwaway CA nothing trusts; what is
 *       proven is that the product stores, parses and dates a real X.509 chain.</li>
 * </ul>
 */
class AcmeIssuanceContractTest {

    private static SqliteDatasource datasource;
    private static FakeAcmeServer ca;
    private static AcmeService acme;
    private static RecordingTxtPublisher publisher;
    private static String savedDirectory;
    private static Integer savedPropagation;

    /** The DNS-01 publisher of this test: records what was published and what was removed. */
    private static final class RecordingTxtPublisher implements DnsTxtPublisher {

        final List<DnsTxtRecord> published = new CopyOnWriteArrayList<>();
        final List<DnsTxtRecord> removed = new CopyOnWriteArrayList<>();

        @Override
        public @NonNull String id() {
            return "recording_txt";
        }

        @Override
        public void publish(@NonNull DnsTxtRecord record) {
            this.published.add(record);
        }

        @Override
        public void cleanup(@NonNull DnsTxtRecord record) {
            this.removed.add(record);
        }

        @Override
        public boolean servesImmediately() {
            return true;
        }

        /** The value published for one record name, or null. */
        String valueOf(String name) {
            for (DnsTxtRecord record : this.published) {
                if (record.name().equals(name)) {
                    return record.value();
                }
            }
            return null;
        }
    }

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-acme-contract-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        Datasources.register(Datasources.DEFAULT, datasource);
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
            "acme-test@example.com");

        acme = new AcmeService(new CertificateStore());
        publisher = new RecordingTxtPublisher();
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
     * The whole HTTP-01 acquisition: account, order, challenge answered by the product's own
     * responder, CSR, chain, stored row -- and the account key reused rather than re-minted.
     */
    @Test
    void anHttpOrderIssuesARealCertificateAndStoresIt() {
        Db.run(datasource, () -> {
            int siteId = site("acme-http");
            domain(siteId, "acme.test");

            // The CA validates by asking the PRODUCT: the responder must answer the token
            // for the ordered hostname and must REFUSE the same token for a hostname it was
            // never offered -- the gate that stops one order's token authorizing any host.
            List<String> answered = new ArrayList<>();
            List<String> refused = new ArrayList<>();
            ca.validateHttpWith((token, identifier) -> {
                String response = acme.getChallengeResponse(token, identifier);
                if (response != null && response.startsWith(token + ".")) {
                    answered.add(identifier);
                }
                if (acme.getChallengeResponse(token, "somewhere-else.test") == null) {
                    refused.add(identifier);
                }
                return response != null && response.startsWith(token + ".");
            });

            // 1. The order succeeds and yields a stored certificate row.
            int certId = acme.requestCertificate(List.of("acme.test"), "ACME http order",
                null, CertificateAuthority.Requester.SYSTEM);
            assertThat(certId).as("step 1: the order produced a certificate row")
                .isGreaterThan(0);

            // 2. THE PRODUCT'S OWN RESPONDER answered the challenge, and refused the token
            //    for an unordered hostname while doing it.
            assertThat(answered)
                .as("step 2: hohenheim's challenge responder answered for the ordered name")
                .containsExactly("acme.test");
            assertThat(refused)
                .as("step 2: and refused the SAME token for a hostname it never offered")
                .containsExactly("acme.test");

            // 3. STATE: a real X.509 chain, dated, keyed and active on the row.
            Row stored = Models.get(CertificateModel.class).findById(certId);
            assertThat((String) stored.get(CertificateModel.STATUS))
                .as("step 3: the certificate is active")
                .isEqualTo(CertificateModel.STATUS_ACTIVE);
            String pem = stored.get(CertificateModel.CERTIFICATE_PEM);
            assertThat(pem).as("step 3: a certificate chain was stored").isNotNull();
            X509Certificate leaf = leafOf(pem);
            assertThat(subjectAltNames(leaf))
                .as("step 3: whose SANs are exactly the ordered names")
                .containsExactly("acme.test");
            assertThat((String) stored.get(CertificateModel.PRIVATE_KEY_PEM))
                .as("step 3: with the key the CSR was signed by")
                .contains("PRIVATE KEY");
            assertThat((Instant) stored.get(CertificateModel.EXPIRES_ON))
                .as("step 3: and the expiry read off the CERTIFICATE, not guessed")
                .isEqualTo(leaf.getNotAfter().toInstant())
                .isAfter(Instant.now().plus(80, ChronoUnit.DAYS));
            assertThat((Instant) stored.get(CertificateModel.ISSUED_ON))
                .as("step 3: issuance is stamped").isNotNull();

            // 4. The CA really walked the protocol; nothing here short-circuited.
            assertThat(ca.requests())
                .as("step 4: every RFC 8555 step was served")
                .contains("/directory", "/new-account", "/new-order");
            assertThat(ca.requests().stream().anyMatch(p -> p.startsWith("/challenge/")))
                .as("step 4: including a triggered challenge").isTrue();
            assertThat(ca.requests().stream().anyMatch(p -> p.startsWith("/certificate/")))
                .as("step 4: and a downloaded certificate").isTrue();

            // 5. The ACCOUNT KEY is persisted and REUSED: a second order must not register
            //    a second account (a new account per order is how a CA rate-limits you out).
            int accountRows = accountKeyRows();
            assertThat(accountRows).as("step 5: exactly one account key row exists")
                .isEqualTo(1);
            domain(siteId, "second.test");
            int secondId = acme.requestCertificate(List.of("second.test"), "ACME second order",
                null, CertificateAuthority.Requester.SYSTEM);
            assertThat(secondId).as("step 5: the second order also succeeded")
                .isGreaterThan(0);
            assertThat(accountKeyRows())
                .as("step 5: and reused the stored account key rather than minting another")
                .isEqualTo(accountRows);
        });
    }

    /**
     * DNS-01 for a WILDCARD: the TXT record is published under the BASE identifier (the ACME
     * identifier of {@code *.x} is {@code x}), the value is the challenge digest, and the
     * record is removed again whatever happens -- a left-behind TXT is a standing
     * authorization for anyone who can order.
     */
    @Test
    void aWildcardDnsOrderPublishesTheDigestAndCleansItUp() {
        Db.run(datasource, () -> {
            int siteId = site("acme-wild");
            domain(siteId, "*.wild.test");
            publisher.published.clear();
            publisher.removed.clear();

            // The CA accepts only when a value for the name it would look up was really
            // published. The lookup is by the RR name the CA would query, which is what
            // makes "published under the wrong name" a FAILING order rather than a pass.
            // AIDEV-NOTE: the RR name acme4j hands the publisher is FULLY QUALIFIED
            // ("_acme-challenge.wild.test."), so the lookup a CA would do carries the
            // trailing dot too. A publisher that wrote the name verbatim into a zone without
            // normalizing it would create the wrong owner name; the internal publisher
            // strips it (InternalDnsTxtPublisher.relativeOwner).
            ca.validateDnsWith((token, identifier) ->
                publisher.valueOf("_acme-challenge." + identifier + ".") != null);
            assertThat(publisher.published)
                .as("precondition: nothing is published before the order starts").isEmpty();

            int certId = acme.requestCertificate(List.of("*.wild.test"), "ACME wildcard",
                null, CertificateModel.CHALLENGE_DNS, publisher.id(),
                CertificateAuthority.Requester.SYSTEM);

            // 1. The order succeeded through the DNS-01 lane.
            assertThat(certId)
                .as("step 1: the wildcard order produced a certificate; published so far: %s",
                    publisher.published.stream().map(DnsTxtRecord::name).toList())
                .isGreaterThan(0);

            // 2. The record name drops the wildcard label -- publishing under
            //    "_acme-challenge.*.wild.test" would never validate.
            assertThat(publisher.published)
                .as("step 2: exactly one TXT record was published")
                .hasSize(1);
            assertThat(publisher.published.get(0).name())
                .as("step 2: under the BASE identifier of the wildcard SAN, fully qualified")
                .isEqualTo("_acme-challenge.wild.test.")
                .doesNotContain("*");
            assertThat(publisher.published.get(0).value())
                .as("step 2: carrying the challenge digest (a base64url SHA-256)")
                .hasSize(43)
                .matches("[A-Za-z0-9_-]+");

            // 3. It is REMOVED again: the TXT value authorizes issuance for the name, so
            //    leaving it published outlives the order it belonged to.
            assertThat(publisher.removed)
                .as("step 3: the TXT record was cleaned up")
                .extracting(DnsTxtRecord::name)
                .containsExactly("_acme-challenge.wild.test.");

            // 4. STATE: the stored certificate really carries the wildcard SAN.
            Row stored = Models.get(CertificateModel.class).findById(certId);
            assertThat(subjectAltNames(leafOf(stored.get(CertificateModel.CERTIFICATE_PEM))))
                .as("step 4: the issued certificate covers the wildcard")
                .containsExactly("*.wild.test");
            assertThat((String) stored.get(CertificateModel.CHALLENGE_TYPE))
                .as("step 4: recorded as the DNS-01 order it was")
                .isEqualTo(CertificateModel.CHALLENGE_DNS);
        });
    }

    /**
     * The refusals, which are what make the successes mean anything: a validation the CA
     * fails and an order the CA rejects at finalize must both leave NO certificate and a
     * recorded reason -- never a row that looks issued.
     */
    @Test
    void aRefusedOrderStoresNoCertificateAndRecordsWhy() {
        Db.run(datasource, () -> {
            int siteId = site("acme-refused");
            domain(siteId, "refused.test");
            domain(siteId, "badcsr.test");

            // 1. THE CA CANNOT VALIDATE: the shape of a host that is not actually reachable.
            ca.refuseValidation(true);
            int failed = acme.requestCertificate(List.of("refused.test"), "ACME refused",
                null, CertificateAuthority.Requester.SYSTEM);
            ca.refuseValidation(false);
            assertThat(failed)
                .as("step 1: a refused order reports failure, never a row id").isEqualTo(-1);

            Row refusedRow = latestCertificateNamed("ACME refused");
            assertThat((String) refusedRow.get(CertificateModel.STATUS))
                .as("step 1: the row is stamped error, not left looking pending")
                .isEqualTo(CertificateModel.STATUS_ERROR);
            assertThat((String) refusedRow.get(CertificateModel.CERTIFICATE_PEM))
                .as("step 1: and holds NO certificate").isNull();
            assertThat((String) refusedRow.get(CertificateModel.RENEWAL_ERROR))
                .as("step 1: the failure names the challenge and the identifier")
                .contains("refused.test");
            assertThat((Instant) refusedRow.get(CertificateModel.NEXT_ATTEMPT_AT))
                .as("step 1: with a backed-off retry rather than a tight loop")
                .isAfter(Instant.now());

            // 2. The CA rejects at FINALIZE, after the authorizations passed: a different
            //    failure point that must land in the same recorded shape.
            ca.validateHttpWith((token, identifier) ->
                acme.getChallengeResponse(token, identifier) != null);
            ca.refuseFinalize(true);
            int rejected = acme.requestCertificate(List.of("badcsr.test"), "ACME rejected",
                null, CertificateAuthority.Requester.SYSTEM);
            ca.refuseFinalize(false);
            assertThat(rejected)
                .as("step 2: a finalize rejection reports failure too").isEqualTo(-1);
            Row rejectedRow = latestCertificateNamed("ACME rejected");
            assertThat((String) rejectedRow.get(CertificateModel.CERTIFICATE_PEM))
                .as("step 2: no certificate was stored").isNull();
            assertThat((String) rejectedRow.get(CertificateModel.RENEWAL_ERROR))
                .as("step 2: and the CA's own problem detail is what got recorded")
                .contains("rejected the certificate signing request");
        });
    }

    // -- fixture plumbing -----------------------------------------------------

    private static int site(String slug) {
        Row site = Models.get(SiteModel.class).createEmptyRow();
        site.set(SiteModel.NAME, "ACME contract " + slug);
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

    private static int accountKeyRows() {
        return Models.get(CertificateModel.class).find()
            .where(CertificateModel.PROVIDER.eq(CertificateModel.PROVIDER_ACME_ACCOUNT))
            .all().size();
    }

    private static Row latestCertificateNamed(String niceName) {
        return Models.get(CertificateModel.class).find()
            .where(CertificateModel.NICE_NAME.eq(niceName))
            .first();
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
