package be.elevenways.hohenheim.test.tls;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.auth.HostnameAuthority;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.tls.AcmeService;
import be.elevenways.hohenheim.server.tls.CertificateCoverage;
import be.elevenways.hohenheim.server.tls.CertificatePem;
import be.elevenways.hohenheim.server.tls.SigningRequest;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HohenheimHttpTestBase;
import be.elevenways.zenit.auth.CapabilityScopes;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.GrantService;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.protoblast.common.time.Pace;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A machine orders a certificate from its OWN signing request and Hohenheim never holds the key: the order stores the
 * chain and the request and no key, renews from the same request so the pinned key never changes, is never loaded
 * into the proxy's store, offers no key bundle, and serves its status and chain to the owning key alone. Refusals
 * tell a key that is no admin nothing about which names this installation serves.
 *
 * AIDEV-NOTE: the CA is {@link FakeAcmeServer}, which signs the REAL request it is sent, so "the chain certifies the
 * requester's key" is a fact read off the issued leaf, never assumed. HTTP-01 is answered in-process by the served
 * proxy's own challenge responder; no DNS server runs here, so the automatic challenge is HTTP-01.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class CertificateSigningRequestApiTest extends HohenheimHttpTestBase {

    private static final String ZONE = "csrapi.test";
    private static final String MX = "mx." + ZONE;
    private static final String MAIL = "mail." + ZONE;
    private static final String FOREIGN = "foreign." + ZONE;
    private static final String UNSERVED = "nobody." + ZONE;
    private static final String SMTP = "smtp." + ZONE;
    private static final String IMAP = "imap." + ZONE;
    private static final String POP = "pop." + ZONE;
    private static final String SUBMISSION = "submission." + ZONE;
    private static final String WEB = "www." + ZONE;
    private static final Pattern SUBJECT_ID = Pattern.compile("\"subject\"\\s*:\\s*\\{[^}]*\"id\"\\s*:\\s*\"(\\d+)\"");

    private static FakeAcmeServer ca;
    private static ProxyServer adoptedProxy;
    private static String savedDirectory;
    private static String savedEmail;
    private static Boolean savedEnabled;

    private static String mailKey;
    private static String strangerKey;
    private static String adminKey;
    private static String coManagerKey;
    private static KeyPair mailServerKey;

    @BeforeAll
    static void seed() throws Exception {
        if (ServerMain.getProxyServer() == null) {
            adoptedProxy = new ProxyServer();
            ServerMain.adoptProxyServer(adoptedProxy);
        }
        ca = new FakeAcmeServer();
        savedDirectory = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Ssl.ACME_DIRECTORY_URL);
        savedEmail = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Ssl.LETSENCRYPT_EMAIL);
        savedEnabled = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED, true);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.ACME_DIRECTORY_URL, ca.directoryUrl());
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_EMAIL, "acme-test@example.com");
        ca.validateHttpWith((token, identifier) -> acme().getChallengeResponse(token, identifier) != null);

        // The mail host's own site declares its mail names as domains; another site serves the foreign name.
        int mailSite = site("csrapi-mail");
        domain(mailSite, MX);
        domain(mailSite, MAIL);
        domain(mailSite, SMTP);
        domain(mailSite, IMAP);
        domain(mailSite, POP);
        domain(mailSite, SUBMISSION);
        domain(site("csrapi-foreign"), FOREIGN);

        UUID mailOwner = ApiSupport.user("csrapi-mail@hohenheim.local", "Mail Host Owner");
        RecordGrants.grant(GrantSubjectType.USER, mailOwner, SiteModel.MODEL_ID, mailSite,
            HohenheimCapabilities.MANAGE, true);
        List<String> tenantScopes = List.of(CapabilityScopes.format(SiteModel.MODEL_ID, HohenheimCapabilities.MANAGE),
            CapabilityScopes.format(CertificateModel.MODEL_ID, HohenheimCapabilities.VIEW));
        mailKey = ApiKeyService.create(mailOwner, "csrapi-mail", tenantScopes, null).plaintext();
        UUID stranger = ApiSupport.user("csrapi-stranger@hohenheim.local", "Stranger");
        strangerKey = ApiKeyService.create(stranger, "csrapi-stranger", tenantScopes, null).plaintext();
        // A second account managing the same site: it may obtain the mail names too, as its own certificates.
        UUID coManager = ApiSupport.user("csrapi-comanager@hohenheim.local", "Co-manager");
        RecordGrants.grant(GrantSubjectType.USER, coManager, SiteModel.MODEL_ID, mailSite,
            HohenheimCapabilities.MANAGE, true);
        coManagerKey = ApiKeyService.create(coManager, "csrapi-comanager", tenantScopes, null).plaintext();
        UUID adminId = Models.get(UserModel.class).find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first().get(UserModel.ID);
        adminKey = ApiKeyService.create(adminId, "csrapi-admin", List.of("hohenheim.*"), null).plaintext();

        mailServerKey = TestSigningRequests.rsa(2048);
    }

    @AfterAll
    static void tearDown() {
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.ACME_DIRECTORY_URL, savedDirectory);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_EMAIL, savedEmail);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED, savedEnabled);
        if (ca != null) {
            ca.close();
        }
        if (adoptedProxy != null) {
            ServerMain.adoptProxyServer(null);
        }
    }

    /** Ordered, read, renewed and re-keyed: the requester's key is the only key the certificate ever certifies. */
    @Test
    void aMailHostOrdersRenewsAndRekeysFromItsOwnSigningRequest() throws Exception {
        String pin = SigningRequest.publicKeyPin(mailServerKey.getPublic());

        // 1. The order: issued, from the request, with no key stored and the request kept.
        HttpResponse<String> ordered = order(mailKey, TestSigningRequests.pem(mailServerKey, List.of(MX, MAIL)),
            MX + "," + MAIL);
        assertThat(ordered.statusCode()).as("step 1: issued (%s)", ordered.body()).isEqualTo(201);
        int certId = ApiSupport.idOf(ordered.body());
        Row cert = Models.get(CertificateModel.class).findById(certId);
        assertThat((String) cert.get(CertificateModel.KEY_HOLDER)).as("step 1: the requester holds the key")
            .isEqualTo(CertificateModel.KEY_HOLDER_REQUESTER);
        assertThat((String) cert.get(CertificateModel.PRIVATE_KEY_PEM)).as("step 1: no key is stored").isNull();
        String storedRequest = cert.get(CertificateModel.SIGNING_REQUEST_PEM);
        assertThat(storedRequest).as("step 1: the request is kept").contains("BEGIN CERTIFICATE REQUEST");
        assertThat(leafPin(cert)).as("step 1: the chain certifies the requester's key").isEqualTo(pin);

        // 2. Never loaded: the proxy cannot serve a certificate whose key it does not hold, and no reader counts it.
        ServerMain.getProxyServer().getCertificateStore().loadFromDatabase();
        assertThat(ServerMain.getProxyServer().getCertificateStore().holds(certId))
            .as("step 2: the proxy's store never loads it").isFalse();
        assertThat(CertificateModel.served(cert)).as("step 2: and it is no served certificate").isFalse();
        assertThat(CertificateCoverage.coveringCertificate(MX)).as("step 2: nor does it cover the name for HTTPS")
            .isNull();

        // 3. Status and chain, read by the owning key; another key is told the certificate does not exist.
        HttpResponse<String> status = keyGet(mailKey, "/api/v1/certificates/" + certId);
        assertThat(status.statusCode()).as("step 3: the owner reads its status").isEqualTo(200);
        assertThat(status.body()).as("step 3: the status names the holder and the pin")
            .contains("\"key_holder\":\"requester\"").contains(pin).doesNotContain("PRIVATE KEY");
        HttpResponse<String> chain = keyGet(mailKey, "/api/v1/certificates/" + certId + "/chain");
        assertThat(chain.statusCode()).as("step 3: the owner fetches its chain").isEqualTo(200);
        assertThat(chain.body()).as("step 3: the chain alone").contains("BEGIN CERTIFICATE")
            .doesNotContain("PRIVATE KEY");
        assertThat(keyGet(strangerKey, "/api/v1/certificates/" + certId).statusCode())
            .as("step 3: another key reads no status").isEqualTo(404);
        assertThat(keyGet(strangerKey, "/api/v1/certificates/" + certId + "/chain").statusCode())
            .as("step 3: and fetches no chain").isEqualTo(404);
        assertThat(keyGet(strangerKey, "/api/v1/certificates").body())
            .as("step 3: nor lists it").doesNotContain(MX);

        // 4. Renewal orders from the stored request: same key, same request, still no key stored.
        String firstChain = cert.get(CertificateModel.CERTIFICATE_PEM);
        assertThat(acme().renewCertificate(cert, Models.get(CertificateModel.class)))
            .as("step 4: the sweep's renewal issues").isTrue();
        Row renewed = Models.get(CertificateModel.class).findById(certId);
        assertThat((String) renewed.get(CertificateModel.CERTIFICATE_PEM)).as("step 4: a new chain")
            .isNotEqualTo(firstChain);
        assertThat(leafPin(renewed)).as("step 4: certifying the SAME key").isEqualTo(pin);
        assertThat((String) renewed.get(CertificateModel.SIGNING_REQUEST_PEM)).as("step 4: from the same request")
            .isEqualTo(storedRequest);
        assertThat((String) renewed.get(CertificateModel.PRIVATE_KEY_PEM)).as("step 4: still no key").isNull();

        // 5. No key bundle: the action is not offered and the route sends none, interactively or by key.
        HttpResponse<String> bundle = adminGet("/certificates/" + certId + "/download");
        assertThat(bundle.statusCode()).as("step 5: the interactive bundle route sends no bundle")
            .isBetween(300, 399);
        assertThat(bundle.body()).as("step 5: no key material").doesNotContain("PRIVATE KEY");
        HttpResponse<String> keyedBundle = keyGet(adminKey, "/certificates/" + certId + "/download");
        assertThat(keyedBundle.statusCode()).as("step 5: and stays interactive-only").isEqualTo(403);
        assertThat(keyedBundle.body()).contains("INTERACTIVE_LOGIN_REQUIRED");
        // The record offers the chain and no bundle; the list says who holds the key under its provider.
        String record = httpGetFollowingRedirects(CmsRoutes.detail(HohenheimSlugs.ADMIN, HohenheimSlugs.CERTIFICATES,
            certId).toUrl(), sessionToken).body();
        assertThat(record).as("step 5: the record offers the public chain")
            .contains("/certificates/" + certId + "/chain")
            .doesNotContain("/certificates/" + certId + "/download");
        assertThat(adminGet(CmsRoutes.list(HohenheimSlugs.ADMIN, HohenheimSlugs.CERTIFICATES).toUrl()).body())
            .as("step 5: the list says the requester holds the key")
            .contains(ApiSupport.shippedText(HohenheimMicrocopy.CERTIFICATE.of("key_held_by_requester")));

        // 6. Rotation is the requester's deliberate act: a new request, a new key, on the same certificate.
        KeyPair rotated = TestSigningRequests.ec("secp256r1");
        HttpResponse<String> rekeyed = keyPost(mailKey, "/api/v1/certificates/" + certId + "/signing-request",
            "csr=" + encoded(TestSigningRequests.pem(rotated, List.of(MX, MAIL))));
        assertThat(rekeyed.statusCode()).as("step 6: re-ordered (%s)", rekeyed.body()).isEqualTo(200);
        Row after = Models.get(CertificateModel.class).findById(certId);
        assertThat(leafPin(after)).as("step 6: the chain now certifies the new key")
            .isEqualTo(SigningRequest.publicKeyPin(rotated.getPublic()));
        assertThat((String) after.get(CertificateModel.PRIVATE_KEY_PEM)).as("step 6: and still no key").isNull();
    }

    /** A key that is no admin learns nothing about names it does not answer for; an admin keeps the reasons. */
    @Test
    void refusalsTellATenantNothingAndAnAdminTheReason() throws Exception {
        String neutral = HostnameAuthority.HOSTNAME_UNAVAILABLE;

        // 1. A name another site serves and a name nobody serves: ONE answer, the same words, no row.
        HttpResponse<String> foreign = order(mailKey, TestSigningRequests.pem(mailServerKey, List.of(FOREIGN)),
            FOREIGN);
        HttpResponse<String> unserved = order(mailKey, TestSigningRequests.pem(mailServerKey, List.of(UNSERVED)),
            UNSERVED);
        assertThat(foreign.statusCode()).as("step 1: foreign is refused").isEqualTo(422);
        assertThat(ApiSupport.codeOf(foreign.body())).as("step 1: neutrally").isEqualTo(neutral);
        assertThat(unserved.body()).as("step 1: byte-identical to an unserved name").isEqualTo(foreign.body());
        assertThat(Models.get(CertificateModel.class).find()
                .where(CertificateModel.DOMAIN_NAMES_TEXT.eq(FOREIGN)).first())
            .as("step 1: and no certificate row exists").isNull();

        // 2. The admin is told which half refused.
        HttpResponse<String> admin = order(adminKey, TestSigningRequests.pem(mailServerKey, List.of(UNSERVED)),
            UNSERVED);
        assertThat(ApiSupport.codeOf(admin.body())).as("step 2: the admin keeps the reason")
            .isEqualTo("hostname_not_served");

        // 3. A request breaking a rule is refused on its field, by name, before any order.
        HttpResponse<String> differ = order(mailKey, TestSigningRequests.pem(mailServerKey, List.of(MX)),
            MX + "," + MAIL);
        assertThat(ApiSupport.codeOf(differ.body())).as("step 3: names differ").isEqualTo("signing_request_names_differ");
        assertThat(ApiSupport.fieldOf(differ.body())).as("step 3: on the csr field").isEqualTo("csr");
        HttpResponse<String> weak = order(mailKey, TestSigningRequests.pem(TestSigningRequests.rsa(1024),
            List.of(MX)), MX);
        assertThat(ApiSupport.codeOf(weak.body())).as("step 3: a weak key").isEqualTo("signing_request_weak_key");
        byte[] tampered = TestSigningRequests.der(mailServerKey, List.of(MX), TestSigningRequests.Extra.NONE);
        tampered[tampered.length - 1] ^= 0x01;
        HttpResponse<String> forged = order(mailKey, TestSigningRequests.pem(tampered), MX);
        assertThat(ApiSupport.codeOf(forged.body())).as("step 3: no proof of possession")
            .isEqualTo("signing_request_bad_signature");

        // 4. The neutral words are THE hostname-authority refusal's, shipped in both languages.
        assertThat(foreign.body()).as("step 4: the neutral sentence")
            .contains(ApiSupport.shippedText(HohenheimMicrocopy.VIOLATIONS.of(neutral)));
    }

    /**
     * One certificate per name set: the same key for the same names again is that certificate, another key for them is
     * its rotation, and a rotation onto names another certificate holds is refused the same way.
     */
    @Test
    void aSecondOrderForTheSameNamesIsTheFirstCertificateOrItsRotation() throws Exception {
        KeyPair key = TestSigningRequests.ec("secp256r1");
        String first = TestSigningRequests.pem(key, List.of(SMTP, IMAP));

        // 1. The first order creates the certificate.
        HttpResponse<String> ordered = order(mailKey, first, SMTP + "," + IMAP);
        assertThat(ordered.statusCode()).as("step 1: issued (%s)", ordered.body()).isEqualTo(201);
        int certId = ApiSupport.idOf(ordered.body());

        // 2. The same request again, its names in another order and case: the same certificate, 200, no new row.
        HttpResponse<String> again = order(mailKey, first, IMAP.toUpperCase(Locale.ROOT) + "," + SMTP);
        assertThat(again.statusCode()).as("step 2: a replay is the certificate as it stands (%s)", again.body())
            .isEqualTo(200);
        assertThat(ApiSupport.idOf(again.body())).as("step 2: the same certificate").isEqualTo(certId);
        assertThat(again.body()).as("step 2: with its status").contains("\"status\":\"active\"");

        // 3. A request signed again over the same key differs byte for byte (ECDSA) and is still the same order.
        String resigned = TestSigningRequests.pem(key, List.of(SMTP, IMAP));
        assertThat(resigned).as("step 3: precondition, another request over the same key").isNotEqualTo(first);
        HttpResponse<String> resent = order(mailKey, resigned, SMTP + "," + IMAP);
        assertThat(resent.statusCode()).as("step 3: the key decides, not the bytes (%s)", resent.body())
            .isEqualTo(200);
        assertThat(ApiSupport.idOf(resent.body())).as("step 3: the same certificate").isEqualTo(certId);
        assertThat(rowsHolding(SMTP, IMAP)).as("step 3: still one certificate for the names").isEqualTo(1);

        // 4. Another key for the same names is refused, naming the certificate to rotate; nothing is ordered.
        KeyPair other = TestSigningRequests.ec("secp256r1");
        HttpResponse<String> conflict = order(mailKey, TestSigningRequests.pem(other, List.of(SMTP, IMAP)),
            SMTP + "," + IMAP);
        assertThat(conflict.statusCode()).as("step 4: a conflict (%s)", conflict.body()).isEqualTo(409);
        assertThat(ApiSupport.codeOf(conflict.body())).as("step 4: by its code")
            .isEqualTo("certificate_names_held");
        assertThat(conflict.body()).as("step 4: a certificate subject")
            .contains("\"kind\":\"" + CertificateModel.MODEL_ID + "\"");
        assertThat(subjectIdOf(conflict.body())).as("step 4: naming the held certificate").isEqualTo(certId);
        assertThat(conflict.body()).as("step 4: in the shipped words")
            .contains(ApiSupport.shippedText(HohenheimMicrocopy.VIOLATIONS.of("certificate_names_held")));
        assertThat(rowsHolding(SMTP, IMAP)).as("step 4: and no second certificate").isEqualTo(1);

        // 5. The rotation the refusal points to takes the new key; ordering with it afterwards is a replay again.
        HttpResponse<String> rotated = keyPost(mailKey, "/api/v1/certificates/" + certId + "/signing-request",
            "csr=" + encoded(TestSigningRequests.pem(other, List.of(SMTP, IMAP))));
        assertThat(rotated.statusCode()).as("step 5: rotated (%s)", rotated.body()).isEqualTo(200);
        HttpResponse<String> afterRotation = order(mailKey, TestSigningRequests.pem(other, List.of(SMTP, IMAP)),
            SMTP + "," + IMAP);
        assertThat(afterRotation.statusCode()).as("step 5: the new key replays (%s)", afterRotation.body())
            .isEqualTo(200);
        assertThat(ApiSupport.idOf(afterRotation.body())).as("step 5: onto the same certificate").isEqualTo(certId);
        assertThat(order(mailKey, first, SMTP + "," + IMAP).statusCode())
            .as("step 5: and the old key is now the conflict").isEqualTo(409);

        // 6. Another name set is another certificate; rotating it onto the held names is refused the same way.
        KeyPair third = TestSigningRequests.ec("secp256r1");
        HttpResponse<String> smtpOnly = order(mailKey, TestSigningRequests.pem(third, List.of(SMTP)), SMTP);
        assertThat(smtpOnly.statusCode()).as("step 6: a certificate for another name set (%s)", smtpOnly.body())
            .isEqualTo(201);
        int smtpId = ApiSupport.idOf(smtpOnly.body());
        assertThat(smtpId).as("step 6: a row of its own").isNotEqualTo(certId);
        String smtpChain = Models.get(CertificateModel.class).findById(smtpId).get(CertificateModel.CERTIFICATE_PEM);
        HttpResponse<String> widened = keyPost(mailKey, "/api/v1/certificates/" + smtpId + "/signing-request",
            "csr=" + encoded(TestSigningRequests.pem(third, List.of(SMTP, IMAP))) + "&hostnames="
                + encoded(SMTP + "," + IMAP));
        assertThat(widened.statusCode()).as("step 6: rotating onto held names conflicts (%s)", widened.body())
            .isEqualTo(409);
        assertThat(subjectIdOf(widened.body())).as("step 6: naming the holder").isEqualTo(certId);
        Row smtpAfter = Models.get(CertificateModel.class).findById(smtpId);
        assertThat(CertificateCoverage.namesOf(smtpAfter)).as("step 6: the rotated one is unchanged")
            .containsExactly(SMTP);
        assertThat((String) smtpAfter.get(CertificateModel.CERTIFICATE_PEM)).as("step 6: chain and all")
            .isEqualTo(smtpChain);
    }

    /**
     * An order is never shared across owners: another account ordering the very same request for the same names while
     * the first account's order is in flight joins nothing. It is refused when it does not manage the names, and
     * otherwise gets its own certificate, never the first account's id, status or chain.
     */
    @Test
    void anotherAccountNeverJoinsAnOrderInFlight() throws Exception {
        String request = TestSigningRequests.pem(TestSigningRequests.ec("secp256r1"), List.of(POP, SUBMISSION));
        String names = POP + "," + SUBMISSION;
        CompletableFuture<Void> held = new CompletableFuture<>();
        CompletableFuture<Void> release = new CompletableFuture<>();
        AtomicBoolean holdNext = new AtomicBoolean(true);
        // The first authorization poll waits for the test's word, so the mail host's order stays in flight.
        acme().setPollPauseForTesting(wait -> {
            if (holdNext.compareAndSet(true, false)) {
                held.complete(null);
                try {
                    release.get(30, TimeUnit.SECONDS);
                } catch (ExecutionException | TimeoutException unreleased) {
                    throw new IllegalStateException(unreleased);
                }
            }
        });
        ca.validateSlowly(1);
        try {
            // 1. The mail host orders; its order is in flight, held at its first poll.
            CompletableFuture<HttpResponse<String>> mailOrder = CompletableFuture.supplyAsync(
                () -> orderUnchecked(mailKey, request, names));
            assertThat(held).as("step 1: the mail host's order is in flight").succeedsWithin(Duration.ofSeconds(10));

            // 2. A key that does not manage the names is refused neutrally, as without an order in flight.
            HttpResponse<String> stranger = order(strangerKey, request, names);
            assertThat(stranger.statusCode()).as("step 2: refused (%s)", stranger.body()).isEqualTo(422);
            assertThat(ApiSupport.codeOf(stranger.body())).as("step 2: neutrally")
                .isEqualTo(HostnameAuthority.HOSTNAME_UNAVAILABLE);

            // 3. A co-manager ordering the same request is answered while that order is still held: it joined none.
            CompletableFuture<HttpResponse<String>> coOrder = CompletableFuture.supplyAsync(
                () -> orderUnchecked(coManagerKey, request, names));
            assertThat(coOrder).as("step 3: answered while the mail host's order is in flight")
                .succeedsWithin(Duration.ofSeconds(10));
            HttpResponse<String> co = coOrder.join();
            assertThat(co.statusCode()).as("step 3: its own certificate, issued (%s)", co.body()).isEqualTo(201);
            int coId = ApiSupport.idOf(co.body());

            // 4. Released, the mail host's order issues its own certificate.
            release.complete(null);
            assertThat(mailOrder).as("step 4: the mail host is answered").succeedsWithin(Duration.ofSeconds(10));
            HttpResponse<String> mail = mailOrder.join();
            assertThat(mail.statusCode()).as("step 4: issued (%s)", mail.body()).isEqualTo(201);
            int mailId = ApiSupport.idOf(mail.body());
            assertThat(coId).as("step 4: two certificates, one per owner").isNotEqualTo(mailId);
            Row mailCert = Models.get(CertificateModel.class).findById(mailId);
            Row coCert = Models.get(CertificateModel.class).findById(coId);
            assertThat(CertificateModel.requesterOf(coCert)).as("step 4: each stored under its own owner")
                .isNotEqualTo(CertificateModel.requesterOf(mailCert));

            // 5. The co-manager never sees the mail host's certificate: not its id, status or chain.
            assertThat(co.body()).as("step 5: its answer is its own row").doesNotContainPattern(idField(mailId));
            assertThat(keyGet(coManagerKey, "/api/v1/certificates/" + mailId).statusCode())
                .as("step 5: no status").isEqualTo(404);
            assertThat(keyGet(coManagerKey, "/api/v1/certificates/" + mailId + "/chain").statusCode())
                .as("step 5: no chain").isEqualTo(404);
            assertThat(ApiSupport.idOf(keyGet(coManagerKey, "/api/v1/certificates/" + coId).body()))
                .as("step 5: its own status").isEqualTo(coId);
            X509Certificate coLeaf = CertificatePem.chainOf(
                keyGet(coManagerKey, "/api/v1/certificates/" + coId + "/chain").body()).get(0);
            assertThat(coLeaf).as("step 5: its chain is its own certificate's")
                .isEqualTo(CertificatePem.chainOf(coCert.get(CertificateModel.CERTIFICATE_PEM)).get(0))
                .as("step 5: never the mail host's")
                .isNotEqualTo(CertificatePem.chainOf(mailCert.get(CertificateModel.CERTIFICATE_PEM)).get(0));
            assertThat(keyGet(coManagerKey, "/api/v1/certificates").body()).as("step 5: nor lists it")
                .doesNotContainPattern(idField(mailId));
        } finally {
            release.complete(null);
            ca.validateSlowly(0);
            acme().setPollPauseForTesting(Pace.Pause.REAL);
        }
    }

    /**
     * The address row's "Get a certificate" over the API: an admin key orders the certificate Hohenheim holds the key
     * of, through the row's own operation, and is answered the record to poll; a key that is no admin is refused by
     * that operation even on an address it manages, a key that does not manage the site is told nothing exists, and
     * a caller without a key is refused before anything is ordered.
     */
    @Test
    void anAdminKeyOrdersTheCertificateAnAddressRowOrders() throws Exception {
        int webSite = site("csrapi-web");
        int webDomain = domain(webSite, WEB);
        String route = "/api/v1/sites/" + webSite + "/domains/" + webDomain + "/certificate";
        Row mx = Models.get(SiteDomainModel.class).find().where(SiteDomainModel.HOSTNAME.eq(MX)).first();
        String mxRoute = "/api/v1/sites/" + mx.get(SiteDomainModel.SITE_ID) + "/domains"
            + "/" + mx.get(SiteDomainModel.ID) + "/certificate";
        // A /manage tenant of the mail site: the site lane sees a site only through the /manage gate as well, and an
        // operation reads its address subject through the address model's own visibility scope, so the key names it.
        UUID tenant = ApiSupport.user("csrapi-address-tenant@hohenheim.local", "Address Tenant");
        GrantService.createDirectGrant(GrantSubjectType.USER, tenant, "hohenheim.manage.access", true);
        RecordGrants.grant(GrantSubjectType.USER, tenant, SiteModel.MODEL_ID, mx.get(SiteDomainModel.SITE_ID),
            HohenheimCapabilities.MANAGE, true);
        String tenantKey = ApiKeyService.create(tenant, "csrapi-address-tenant", List.of("hohenheim.manage.access",
            CapabilityScopes.format(SiteModel.MODEL_ID, HohenheimCapabilities.MANAGE),
            CapabilityScopes.format(SiteDomainModel.MODEL_ID, HohenheimCapabilities.VIEW)), null).plaintext();

        // 1. Refusals first, and none of them orders anything: a key that is no admin on the address its owner
        //    manages (the row action's own authorizer, asked before the address is looked up, so an address of the
        //    site that does not exist answers the same), a key that does not manage the site (the uniform 404), an
        //    address of another site under this site's route (404) and a caller with no key at all.
        assertThat(keyPost(tenantKey, mxRoute, "").statusCode()).as("step 1: a site manager's key is refused")
            .isEqualTo(403);
        assertThat(keyPost(tenantKey, "/api/v1/sites/" + mx.get(SiteDomainModel.SITE_ID) + "/domains/0/certificate",
            "").statusCode()).as("step 1: and told the same for an address that does not exist").isEqualTo(403);
        assertThat(keyPost(strangerKey, mxRoute, "").statusCode()).as("step 1: a stranger is told nothing")
            .isEqualTo(404);
        assertThat(keyPost(adminKey, "/api/v1/sites/" + webSite + "/domains/" + mx.get(SiteDomainModel.ID)
            + "/certificate", "").statusCode()).as("step 1: another site's address under this site is 404")
            .isEqualTo(404);
        assertThat(keyPost(adminKey, "/api/v1/sites/" + webSite + "/domains/0/certificate", "").statusCode())
            .as("step 1: an unknown address is the same 404").isEqualTo(404);
        assertThat(sendRequest(requestTo(route).header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(""))).statusCode())
            .as("step 1: a caller without a key is refused").isEqualTo(401);
        assertThat(Models.get(CertificateModel.class).find().where(CertificateModel.DOMAIN_NAMES_TEXT.eq(WEB))
            .first()).as("step 1: and nothing was ordered").isNull();

        // 2. The admin key orders it: the names default to the address, the key is Hohenheim's, and the answer is the
        //    certificate record a client polls.
        HttpResponse<String> ordered = keyPost(adminKey, route, "challenge_type=http");
        assertThat(ordered.statusCode()).as("step 2: issued (%s)", ordered.body()).isEqualTo(201);
        int certId = ApiSupport.idOf(ordered.body());
        Row cert = Models.get(CertificateModel.class).findById(certId);
        assertThat(CertificateCoverage.namesOf(cert)).as("step 2: for the address").containsExactly(WEB);
        assertThat(CertificateModel.keyHeldHere(cert)).as("step 2: Hohenheim holds the key").isTrue();
        assertThat(ordered.body()).as("step 2: the answer is the record, never its key")
            .contains("\"status\":\"active\"").doesNotContain("PRIVATE KEY");
        HttpResponse<String> polled = keyGet(adminKey, "/api/v1/certificates/" + certId);
        assertThat(polled.statusCode()).as("step 2: and its status reads back").isEqualTo(200);

        // 3. The dialog's refusals answer by name: a wildcard needs DNS-01.
        HttpResponse<String> wildcard = keyPost(adminKey, route, "domains=*." + ZONE + "&challenge_type=http");
        assertThat(wildcard.statusCode()).as("step 3: refused (%s)", wildcard.body()).isEqualTo(422);
        assertThat(ApiSupport.codeOf(wildcard.body())).as("step 3: by the dialog's key")
            .isEqualTo("wildcard_requires_dns");
    }

    // -- fixtures -------------------------------------------------------------

    private HttpResponse<String> order(String key, String csr, String hostnames) throws Exception {
        return keyPost(key, "/api/v1/certificates", "csr=" + encoded(csr) + "&hostnames=" + encoded(hostnames));
    }

    private HttpResponse<String> orderUnchecked(String key, String csr, String hostnames) {
        try {
            return order(key, csr, hostnames);
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String leafPin(Row cert) throws Exception {
        return SigningRequest.publicKeyPin(CertificatePem.chainOf(cert.get(CertificateModel.CERTIFICATE_PEM)).get(0)
            .getPublicKey());
    }

    /** @return a pattern matching the JSON field {@code "id": id} and no longer id */
    private static String idField(int id) {
        return "\"id\"\\s*:\\s*" + id + "(?!\\d)";
    }

    /** @return the requester-key certificates whose names are exactly these */
    private static long rowsHolding(String... names) {
        Set<String> asked = Set.of(names);
        return Models.get(CertificateModel.class).find()
            .where(CertificateModel.KEY_HOLDER.eq(CertificateModel.KEY_HOLDER_REQUESTER)).all().stream()
            .filter(row -> Set.copyOf(CertificateCoverage.namesOf(row)).equals(asked)).count();
    }

    private static int subjectIdOf(String json) {
        Matcher matcher = SUBJECT_ID.matcher(json);
        assertThat(matcher.find()).as("a refusal subject in %s", json).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private static AcmeService acme() {
        return ServerMain.getProxyServer().getAcmeService();
    }

    private static int site(String slug) {
        Row site = Models.get(SiteModel.class).createEmptyRow();
        site.set(SiteModel.NAME, slug);
        site.set(SiteModel.SLUG, slug);
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        Models.get(SiteModel.class).save(site);
        return site.get(SiteModel.ID);
    }

    private static int domain(int siteId, String hostname) {
        Row domain = Models.get(SiteDomainModel.class).createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, siteId);
        domain.set(SiteDomainModel.HOSTNAME, hostname);
        domain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        Models.get(SiteDomainModel.class).save(domain);
        return domain.get(SiteDomainModel.ID);
    }
}
