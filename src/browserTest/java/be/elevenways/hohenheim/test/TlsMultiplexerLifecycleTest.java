package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.Map;

import static be.elevenways.hohenheim.test.ProxyTestSupport.addDomain;
import static be.elevenways.hohenheim.test.ProxyTestSupport.bootRuntime;
import static be.elevenways.hohenheim.test.ProxyTestSupport.resetDatabase;
import static be.elevenways.hohenheim.test.ProxyTestSupport.setupSite;
import static org.assertj.core.api.Assertions.assertThat;

/** Covers bidirectional HTTPS lifecycle reconciliation after route and certificate removal. */
class TlsMultiplexerLifecycleTest {

    private static boolean initialized;
    private ProxyServer proxy;
    private ServerSocket backend;

    @BeforeAll
    static void boot() throws Exception {
        if (initialized) return;
        initialized = true;
        bootRuntime();
    }

    @AfterEach
    void cleanup() throws Exception {
        if (proxy != null) proxy.stop();
        if (backend != null) backend.close();
    }

    @Test
    void removingLastCertificateStopsUnusedPublicTlsListener() throws Exception {
        resetDatabase();
        Row certificate = installCertificate("remove.example.test");
        startProxy();
        assertThat(proxy.getHttpsState()).isEqualTo(ProxyServer.State.RUNNING);

        Models.get(CertificateModel.class).delete(certificate);
        proxy.reload();

        assertThat(proxy.getHttpsState()).isEqualTo(ProxyServer.State.STOPPED);
        assertThat(proxy.getHttpsAddress()).isNull();
        assertThat(proxy.getHttpsListenerInfo()).isNull();
    }

    @Test
    void removingCertificateKeepsPassthroughButStopsLocalTermination() throws Exception {
        resetDatabase();
        Row certificate = installCertificate("remove.example.test");
        backend = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        Row site = setupSite(SiteModel.SITE_TYPE_TLS_PASSTHROUGH,
            "TLS backend", "tls-backend", Map.of(
                "forward_host", "127.0.0.1", "forward_port", backend.getLocalPort()));
        addDomain(site, "*.passthrough.test", "wildcard", null, false);
        startProxy();
        int publicPort = proxy.getHttpsAddress().getPort();

        Models.get(CertificateModel.class).delete(certificate);
        proxy.reload();

        assertThat(proxy.getHttpsState()).isEqualTo(ProxyServer.State.RUNNING);
        assertThat(proxy.getHttpsAddress().getPort()).isEqualTo(publicPort);
        assertThat(proxy.getHttpsListenerInfo()).isNull();
    }

    private void startProxy() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTPS_PORT, 0);
        proxy = new ProxyServer();
        proxy.start();
    }

    private static Row installCertificate(String hostname) throws Exception {
        KeyPair keyPair = TlsCertificateTest.generateKeyPair();
        X509Certificate certificate = TlsCertificateTest.generateSelfSignedCert(keyPair, hostname);
        var model = Models.get(CertificateModel.class);
        Row row = model.createEmptyRow();
        row.set(CertificateModel.NICE_NAME, "Lifecycle certificate");
        row.set(CertificateModel.PROVIDER, "custom");
        row.set(CertificateModel.STATUS, "active");
        row.set(CertificateModel.CERTIFICATE_PEM, TlsCertificateTest.certToPem(certificate));
        row.set(CertificateModel.PRIVATE_KEY_PEM, TlsCertificateTest.keyToPem(keyPair));
        model.save(row);
        return row;
    }
}
