package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cms.PutOnline;
import be.elevenways.hohenheim.server.cms.SiteWrites;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.ProxyTestSupport;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.cms.common.action.CmsPlacementSurface;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.operation.OperationRunStatus;
import be.elevenways.zenit.server.http.RateLimitMiddleware;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.operation.OperationRequest;
import be.elevenways.zenit.server.operation.OperationRun;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The HTTPS step of "Put something online", npmplus's certificate and access tabs in one step: an address served by
 * the proxy itself is asked no host, a certificate Hohenheim already holds (a wildcard one) serves it, an uploaded
 * chain and key are refused in words until they are right and then stored, and an access list picked on the step is
 * enforced by the real proxy.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
class PutOnlineHttpsJourneyTest extends HohenheimTestBase {

    private static final String DOMAIN = "r3b-https.test";
    private static final AtomicLong SERIAL = new AtomicLong(Now.millis());

    private static ProxyServer proxy;

    @BeforeAll
    static void lettingEncryptRest() {
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED, false);
    }

    @AfterAll
    static void restore() {
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED, true);
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
    }

    @BeforeEach
    void freshCreateBudget() {
        RateLimitMiddleware.limiter().clear();
    }

    @Test
    void aRedirectGetsItsCertificateAndAccessListOnTheHttpsStepAndTheProxyEnforcesThem() throws Exception {
        AccessContext operator = TenantConduits.operator();

        // 1. A redirect is served by the proxy itself: its wizard asks no host, draws no Runs on card and never says
        //    where it runs; its HTTPS step offers every certificate choice and the access list.
        String wizard = adminGet("/admin/" + HohenheimSlugs.PUT_ONLINE + "?kind=hohenheim:redirect").body();
        assertThat(wizard).as("step 1: the redirect's first step names what the proxy answers, not a host")
            .contains("data-zf-step=\"where\"").contains("Nothing runs on a host for it.")
            .doesNotContain("and where it runs").doesNotContain("Runs on").doesNotContain("name=\"serverId\"");
        assertThat(wizard).as("step 1: the HTTPS step offers all five certificate choices")
            .contains("value=\"automatic\"").contains("value=\"wildcard\"").contains("value=\"existing\"")
            .contains("value=\"upload\"").contains("value=\"later\"");
        assertThat(wizard).as("step 1: and the access list and the upload's chain and key")
            .contains("name=\"access_list_id\"").contains("name=\"certificate_pem\"")
            .contains("name=\"private_key_pem\"");

        // 2. "A certificate Hohenheim has" while none covers the address: refused on the HTTPS choice, in words.
        DomainRefusal none = refusal(redirect("existing-none", "shop." + DOMAIN, "existing", null, null, null));
        assertThat(List.of(String.valueOf(none.field()), Objects.requireNonNull(none.words()).key()))
            .as("step 2: no certificate covers it yet")
            .containsExactly(PutOnline.HTTPS.getName(), "outcome_https_existing_none");

        // 3. Once a wildcard certificate for the domain is in Hohenheim, the same choice serves the address with it;
        //    the run's certificate step names that certificate.
        KeyPair wildcardKeys = keys();
        storeCertificate("r3b wildcard", issue("*." + DOMAIN, wildcardKeys, null, null, 0, 90, false),
            wildcardKeys, "*." + DOMAIN);
        OperationRun served = run(redirect("existing", "shop." + DOMAIN, "existing", null, null, null), operator);
        assertThat(served.status()).as("step 3: the run succeeded (%s)", served.outcome())
            .isEqualTo(OperationRunStatus.SUCCEEDED);
        Microcopy certificateLine = Objects.requireNonNull(served.stepDetail(1), "step 3: the certificate's line");
        assertThat(List.of(certificateLine.key(), String.valueOf(certificateLine.args().get("name"))))
            .as("step 3: the certificate step names the wildcard it serves with")
            .containsExactly("done_certificate_existing", "r3b wildcard");
        assertThat(Objects.requireNonNull(served.outcome()).key())
            .as("step 3: its outcome says HTTPS answers now, never that a certificate is still to be issued")
            .isEqualTo("outcome_live_https");

        // 4. An upload is judged whole before anything exists, each fault on its field and in its words.
        KeyPair caKeys = keys();
        KeyPair leafKeys = keys();
        X509Certificate ca = issue("R3b Test CA", caKeys, null, null, 0, 365, true);
        X509Certificate leaf = issue("up." + DOMAIN, leafKeys, ca, caKeys.getPrivate(), 0, 90, false);
        String goodChain = pem(leaf) + pem(ca);
        assertUploadRefused("step 4a: a key of another pair", goodChain, keyPem(keys()),
            "private_key_pem", "cert_key_mismatch");
        assertUploadRefused("step 4b: the issuer before the server certificate", pem(ca) + pem(leaf),
            keyPem(caKeys), "certificate_pem", "cert_chain_order");
        X509Certificate expired = issue("up." + DOMAIN, leafKeys, ca, caKeys.getPrivate(), -30, -1, false);
        assertUploadRefused("step 4c: an expired certificate", pem(expired) + pem(ca), keyPem(leafKeys),
            "certificate_pem", "cert_expired");
        X509Certificate elsewhere = issue("other.example", leafKeys, ca, caKeys.getPrivate(), 0, 90, false);
        assertUploadRefused("step 4d: a certificate for another name", pem(elsewhere) + pem(ca), keyPem(leafKeys),
            "certificate_pem", "cert_not_covering");
        DomainRefusal unreadable = refusal(redirect("refused", "up." + DOMAIN, "upload", goodChain,
            "-----BEGIN PRIVATE KEY-----\nbm90IGEga2V5\n-----END PRIVATE KEY-----\n", null));
        assertThat(List.of(String.valueOf(unreadable.field()), Objects.requireNonNull(unreadable.words()).key(),
                String.valueOf(unreadable.words().args().get("detail"))))
            .as("step 4e: a key that does not parse is refused in words, never in the parser's")
            .containsExactly("private_key_pem", "key_pem_invalid", "null");

        // 5. The right chain and key: the run stores the certificate (provider "uploaded", its names and expiry read
        //    from it), and its step says so.
        OperationRun uploaded = run(redirect("upload", "up." + DOMAIN, "upload", goodChain, keyPem(leafKeys), null),
            operator);
        assertThat(uploaded.status()).as("step 5: the run succeeded (%s)", uploaded.outcome())
            .isEqualTo(OperationRunStatus.SUCCEEDED);
        assertThat(Objects.requireNonNull(uploaded.stepDetail(1)).key()).as("step 5: the upload was stored")
            .isEqualTo("done_certificate_uploaded");
        assertThat(Objects.requireNonNull(uploaded.outcome()).key())
            .as("step 5: its outcome says HTTPS answers now, never that a certificate is still to be issued")
            .isEqualTo("outcome_live_https");
        Row stored = Models.get(CertificateModel.class).find()
            .where(CertificateModel.NICE_NAME.eq("up." + DOMAIN)).first();
        assertThat(stored).as("step 5: the certificate row exists").isNotNull();
        assertThat(List.of(String.valueOf((Object) stored.get(CertificateModel.PROVIDER)),
                String.valueOf((Object) stored.get(CertificateModel.DOMAIN_NAMES_TEXT))))
            .as("step 5: uploaded, covering the address")
            .containsExactly(CertificateModel.PROVIDER_CUSTOM, "up." + DOMAIN);

        // 6. An access list picked on the step: the website follows it, and the REAL proxy refuses a request from a
        //    denied network with 403 while a twin redirect without a list answers its redirect.
        Row list = Models.get(AccessListModel.class).createEmptyRow();
        list.set(AccessListModel.NAME, "r3b office only");
        list.set(AccessListModel.SATISFY, AccessListModel.SATISFY_ALL);
        Models.get(AccessListModel.class).save(list);
        Row deny = Models.get(AccessRuleModel.class).createEmptyRow();
        deny.set(AccessRuleModel.ACCESS_LIST_ID, list.get(AccessListModel.ID));
        deny.set(AccessRuleModel.TYPE, AccessRuleModel.TYPE_IP_DENY);
        deny.set(AccessRuleModel.DATA, new LinkedHashMap<>(Map.of("network", "127.0.0.0/8")));
        deny.set(AccessRuleModel.ENABLED, true);
        Models.get(AccessRuleModel.class).save(deny);
        //    Another domain than the wildcard's: a name a working certificate covers is forced onto HTTPS, which this
        //    plain-HTTP proxy cannot serve.
        OperationRun guarded = run(redirect("guarded", "guarded.r3b-acl.test", "later", null, null,
            list.get(AccessListModel.ID)), operator);
        OperationRun open = run(redirect("open", "open.r3b-acl.test", "later", null, null, null), operator);
        assertThat(List.of(guarded.status(), open.status())).as("step 6: both runs succeeded")
            .containsExactly(OperationRunStatus.SUCCEEDED, OperationRunStatus.SUCCEEDED);
        Row guardedSite = Models.get(SiteModel.class).find().where(SiteModel.NAME.eq("r3b-guarded")).first();
        assertThat((Object) guardedSite.get(SiteModel.ACCESS_LIST_ID)).as("step 6: the website follows the list")
            .isEqualTo(list.get(AccessListModel.ID));
        proxy = ProxyTestSupport.startProxy();
        int port = ProxyTestSupport.httpPort(proxy);
        assertThat(ProxyTestSupport.rawRequest(port, "guarded.r3b-acl.test", "/"))
            .as("step 6: THE ASSERTION, the proxy refuses the denied network").contains(" 403 ");
        assertThat(ProxyTestSupport.rawRequest(port, "open.r3b-acl.test", "/"))
            .as("step 6: the twin without a list redirects").contains(" 301 ").contains("https://target.example/");
    }

    @Test
    void aTlsPassthroughAddressIsRefusedAnAccessListUpFrontInTheWizardAndOnTheSiteForm() throws Exception {
        AccessContext operator = TenantConduits.operator();
        Row list = Models.get(AccessListModel.class).createEmptyRow();
        list.set(AccessListModel.NAME, "r3b passthrough list");
        list.set(AccessListModel.SATISFY, AccessListModel.SATISFY_ALL);
        Models.get(AccessListModel.class).save(list);
        Integer listId = list.get(AccessListModel.ID);

        // 1. The wizard refuses a TLS passthrough address with an access list in the request, on the list's pick and
        //    in the words the site's own write uses: no run is recorded and no site exists.
        DomainRefusal gated = refusal(passthrough("pass-gated", "gated.r3b-pass.test", listId));
        assertThat(List.of(String.valueOf(gated.field()), Objects.requireNonNull(gated.words()).key()))
            .as("step 1: refused on the access list, before any run")
            .containsExactly(SiteModel.ACCESS_LIST_ID.getName(), "tls_passthrough_no_access_list");
        assertThat(Models.get(SiteModel.class).find().where(SiteModel.NAME.eq("r3b-pass-gated")).first())
            .as("step 1: nothing was created").isNull();

        // 2. Without the list it goes live; Automatic orders nothing for it, since its backend answers TLS itself.
        OperationRun live = run(passthrough("pass-open", "open.r3b-pass.test", null), operator);
        assertThat(live.status()).as("step 2: the run succeeded (%s)", live.outcome())
            .isEqualTo(OperationRunStatus.SUCCEEDED);
        assertThat(Objects.requireNonNull(live.outcome()).key())
            .as("step 2: its words say the backend's certificate answers")
            .isEqualTo("outcome_https_passthrough_note");
        Row site = Models.get(SiteModel.class).find().where(SiteModel.NAME.eq("r3b-pass-open")).first();
        assertThat(site).as("step 2: the site exists").isNotNull();

        // 3. The site form refuses the same list on the same pick, through the same rule.
        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put(SiteModel.NAME.getName(), "r3b-pass-open");
        edit.put(SiteModel.UPSTREAM_KIND.getName(), SiteModel.UPSTREAM_TLS_PASSTHROUGH);
        edit.put(SiteModel.SETTINGS.getName(), Map.of("forward_host", "127.0.0.1", "forward_port", 8443,
            "connect_timeout", 10));
        edit.put(SiteModel.ENABLED.getName(), true);
        edit.put(SiteModel.ACCESS_LIST_ID.getName(), listId);
        Throwable refused = catchThrowable(() -> OperationPipeline.invoke(
            OperationRequest.of(SiteWrites.UPDATE, CmsPlacementSurface.ADMIN_ACTION).caller(operator)
                .subjects(List.of(site)).form(edit)));
        assertThat(refused).as("step 3: the site form refuses the list").isInstanceOf(Violations.class);
        Violation first = ((Violations) refused).all().getFirst();
        assertThat(List.of(first.fieldName(), first.message().key())).as("step 3: on its pick, in the same words")
            .containsExactly(SiteModel.ACCESS_LIST_ID.getName(), "tls_passthrough_no_access_list");
        assertThat((Object) Models.get(SiteModel.class).findById(site.get(SiteModel.ID)).get(SiteModel.ACCESS_LIST_ID))
            .as("step 3: the site keeps no list").isNull();
    }

    private static Map<String, Object> passthrough(String name, String hostname, Integer accessList) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put(SiteModel.NAME.getName(), "r3b-" + name);
        form.put(PutOnline.HOSTNAME.getName(), hostname);
        form.put(SiteModel.UPSTREAM_KIND.getName(), SiteModel.UPSTREAM_TLS_PASSTHROUGH);
        form.put(SiteModel.SETTINGS.getName(), Map.of("forward_host", "127.0.0.1", "forward_port", 8443,
            "connect_timeout", 10));
        form.put(PutOnline.HTTPS.getName(), PutOnline.HTTPS_AUTOMATIC);
        if (accessList != null) {
            form.put(SiteModel.ACCESS_LIST_ID.getName(), accessList);
        }
        return form;
    }

    private static void assertUploadRefused(String step, String chain, String key, String field, String words) {
        DomainRefusal refused = refusal(redirect("refused", "up." + DOMAIN, "upload", chain, key, null));
        assertThat(List.of(String.valueOf(refused.field()), Objects.requireNonNull(refused.words(), step).key()))
            .as(step).containsExactly(field, words);
    }

    private static Map<String, Object> redirect(String name, String hostname, String https, String chain, String key,
                                                Integer accessList) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put(SiteModel.NAME.getName(), "r3b-" + name);
        form.put(PutOnline.HOSTNAME.getName(), hostname);
        form.put(SiteModel.UPSTREAM_KIND.getName(), "hohenheim:redirect");
        form.put(SiteModel.SETTINGS.getName(), Map.of("target_url", "https://target.example/", "http_status", "301"));
        form.put(PutOnline.HTTPS.getName(), https);
        if (chain != null) {
            form.put(CertificateModel.CERTIFICATE_PEM.getName(), chain);
        }
        if (key != null) {
            form.put(CertificateModel.PRIVATE_KEY_PEM.getName(), key);
        }
        if (accessList != null) {
            form.put(SiteModel.ACCESS_LIST_ID.getName(), accessList);
        }
        return form;
    }

    private static OperationRequest request(Map<String, Object> form) {
        return OperationRequest.of(PutOnline.PUT_ADDRESS_ONLINE, CmsPlacementSurface.ADMIN_ACTION)
            .caller(TenantConduits.operator()).form(form);
    }

    private static DomainRefusal refusal(Map<String, Object> form) {
        DomainRefusal refused = catchThrowableOfType(DomainRefusal.class,
            () -> OperationPipeline.invoke(request(form)));
        assertThat(refused).as("refused in the request, before any run").isNotNull();
        return refused;
    }

    private static OperationRun run(Map<String, Object> form, AccessContext viewer) throws InterruptedException {
        return PutOnlineJourneyTest.ended(PutOnlineJourneyTest.runOf(OperationPipeline.invoke(request(form))), viewer,
            String.valueOf(form.get(SiteModel.NAME.getName())));
    }

    private static void storeCertificate(String name, X509Certificate certificate, KeyPair keys, String names)
            throws Exception {
        Row row = Models.get(CertificateModel.class).createEmptyRow();
        row.set(CertificateModel.NICE_NAME, name);
        row.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_CUSTOM);
        row.set(CertificateModel.STATUS, CertificateModel.STATUS_ACTIVE);
        row.set(CertificateModel.CERTIFICATE_PEM, pem(certificate));
        row.set(CertificateModel.PRIVATE_KEY_PEM, keyPem(keys));
        row.set(CertificateModel.DOMAIN_NAMES_TEXT, names);
        row.set(CertificateModel.EXPIRES_ON, certificate.getNotAfter().toInstant());
        Models.get(CertificateModel.class).save(row);
    }

    private static KeyPair keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    /** A certificate for {@code name}, self-signed when no issuer is given, valid between the two day offsets. */
    private static X509Certificate issue(String name, KeyPair subject, X509Certificate issuer, PrivateKey issuerKey,
                                         int fromDays, int untilDays, boolean ca) throws Exception {
        Instant now = Now.instant();
        X500Name subjectName = new X500Name("CN=" + name);
        X500Name issuerName = issuer == null ? subjectName
            : X500Name.getInstance(issuer.getSubjectX500Principal().getEncoded());
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(issuerName,
            BigInteger.valueOf(SERIAL.incrementAndGet()), Date.from(now.plus(Duration.ofDays(fromDays))),
            Date.from(now.plus(Duration.ofDays(untilDays))), subjectName, subject.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
        if (!ca) {
            builder.addExtension(Extension.subjectAlternativeName, false,
                new GeneralNames(new GeneralName(GeneralName.dNSName, name)));
        }
        PrivateKey signer = issuerKey == null ? subject.getPrivate() : issuerKey;
        return new JcaX509CertificateConverter().getCertificate(
            builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(signer)));
    }

    private static String pem(X509Certificate certificate) throws Exception {
        return "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".getBytes())
            .encodeToString(certificate.getEncoded()) + "\n-----END CERTIFICATE-----\n";
    }

    private static String keyPem(KeyPair keys) {
        return "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes())
            .encodeToString(keys.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n";
    }
}
