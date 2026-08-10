package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.cms.AttentionCollector;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * force_ssl fails CLOSED: while HTTPS termination is unavailable a force-SSL route answers
 * plain HTTP with a 503 instead of silently serving cleartext, and the operator can SEE the
 * inert control (attention item). One behaviour journey, per the suite convention.
 */
class ForceSslAvailabilityTest {

    private static ProxyServer proxy;
    private static HttpServer upstream;
    private static final AtomicInteger upstreamHits = new AtomicInteger();

    @BeforeAll
    static void boot() throws Exception {
        SiteTypes.boot();
        HohenheimEndpoints.init();
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
        Zenit.getHawkeye().setClientScriptLocation("/cms.js");

        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", ex -> {
            upstreamHits.incrementAndGet();
            byte[] body = "cleartext-content".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        upstream.start();
    }

    @AfterAll
    static void stop() {
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
        ServerMain.adoptProxyServer(null);
        if (upstream != null) {
            upstream.stop(0);
            upstream = null;
        }
    }

    @Test
    @Timeout(30)
    void forceSslRefusesCleartextWhileHttpsIsDown() throws Exception {
        int upstreamPort = upstream.getAddress().getPort();

        // Step 1: two sites, one with force_ssl OFF (the positive anchor) and one with
        // force_ssl ON (the model default the operator ticked).
        Row plain = ProxyTestSupport.setupSite("hohenheim:proxy", "Plain Site", "plain-site",
            Map.of("forward_host", "127.0.0.1", "forward_port", upstreamPort));
        ProxyTestSupport.addDomain(plain, "plain.fssl.test", "exact", null, false);
        Row forced = ProxyTestSupport.setupSite("hohenheim:proxy", "Forced Site", "forced-site",
            Map.of("forward_host", "127.0.0.1", "forward_port", upstreamPort));
        addForceSslDomain(forced, "forced.fssl.test");

        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);
        proxy = ProxyTestSupport.startProxy();
        int port = ProxyTestSupport.httpPort(proxy);
        assertThat(proxy.isHttpsTerminationAvailable())
            .as("step 1: no certificate exists, so HTTPS termination must be down")
            .isFalse();

        String anchor = ProxyTestSupport.rawRequest(port, "plain.fssl.test", "/");
        assertThat(anchor)
            .as("step 1: a site without force_ssl still serves over plain HTTP")
            .contains("200").contains("cleartext-content");

        // Step 2: THE ATTACK (pre-fix counterfactual): the force-SSL site used to serve this
        // request in cleartext because httpsAvailable gated the redirect. Now it refuses.
        int hitsBefore = upstreamHits.get();
        String refused = ProxyTestSupport.rawRequest(port, "forced.fssl.test", "/");
        assertThat(refused)
            .as("step 2: a force-SSL route over plain HTTP while HTTPS is down is REFUSED")
            .contains("503");
        assertThat(refused)
            .as("step 2: the refusal must never leak the upstream content")
            .doesNotContain("cleartext-content");
        assertThat(upstreamHits.get())
            .as("step 2: the upstream must not be reached at all")
            .isEqualTo(hitsBefore);

        // Step 3: the operator can SEE the inert control on the dashboard.
        ServerMain.adoptProxyServer(proxy);
        List<AttentionItem> items = new ArrayList<>();
        AttentionCollector.httpsUnavailableWithForceSsl(items);
        assertThat(items)
            .as("step 3: HTTPS-down with force-SSL sites raises an attention item")
            .hasSize(1);
        assertThat(items.get(0).severity()).isEqualTo("error");

        // Step 4: global force_https gets the same fail-closed treatment for MATCHED routes.
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.FORCE_HTTPS, true);
        String globalRefused = ProxyTestSupport.rawRequest(port, "plain.fssl.test", "/");
        assertThat(globalRefused)
            .as("step 4: global force_https refuses cleartext on a matched route while HTTPS is down")
            .contains("503");
        String unmatched = ProxyTestSupport.rawRequest(port, "unknown.fssl.test", "/");
        assertThat(unmatched)
            .as("step 4: an unmatched hostname has nothing to protect and stays a 404")
            .contains("404");
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.FORCE_HTTPS, false);

        // Step 5: a certificate arrives; after reload the same request becomes the redirect.
        installCertificate("forced.fssl.test");
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTPS_PORT, 0);
        proxy.reload();
        assertThat(proxy.isHttpsTerminationAvailable())
            .as("step 5: HTTPS termination is up after the certificate reload")
            .isTrue();
        String redirected = ProxyTestSupport.rawRequest(port, "forced.fssl.test", "/x?q=1");
        assertThat(redirected)
            .as("step 5: with HTTPS up the force-SSL route redirects instead of refusing")
            .contains("301")
            .contains("Location: https://forced.fssl.test");

        // Step 6: the attention item clears once HTTPS termination is available again.
        List<AttentionItem> after = new ArrayList<>();
        AttentionCollector.httpsUnavailableWithForceSsl(after);
        assertThat(after)
            .as("step 6: no attention item while HTTPS termination is up")
            .isEmpty();
    }

    private static void addForceSslDomain(Row site, String hostname) {
        var domainModel = Models.get(SiteDomainModel.class);
        Row domain = domainModel.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, site.get(SiteModel.ID));
        domain.set(SiteDomainModel.HOSTNAME, hostname);
        domain.set(SiteDomainModel.MATCH_TYPE, "exact");
        domain.set(SiteDomainModel.FORCE_SSL, true);
        domainModel.save(domain);
    }

    private static void installCertificate(String hostname) throws Exception {
        var certModel = Models.get(CertificateModel.class);
        KeyPair keyPair = TlsCertificateTest.generateKeyPair();
        X509Certificate cert = TlsCertificateTest.generateSelfSignedCert(keyPair, hostname);
        Row certRow = certModel.createEmptyRow();
        certRow.set(CertificateModel.NICE_NAME, "Force SSL Test");
        certRow.set(CertificateModel.PROVIDER, "custom");
        certRow.set(CertificateModel.STATUS, "active");
        certRow.set(CertificateModel.CERTIFICATE_PEM, TlsCertificateTest.certToPem(cert));
        certRow.set(CertificateModel.PRIVATE_KEY_PEM, TlsCertificateTest.keyToPem(keyPair));
        certModel.save(certRow);
    }
}
