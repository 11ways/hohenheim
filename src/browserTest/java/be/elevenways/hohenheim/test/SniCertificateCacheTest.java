package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.server.tls.CertificateStore;
import be.elevenways.hohenheim.server.tls.SniKeyManager;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import io.undertow.Undertow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SNI alias cache never outlives the certificate snapshot it was resolved from: a replaced
 * certificate is served on the very next handshake after the store reloads, not after the
 * five-minute cache TTL.
 */
class SniCertificateCacheTest {

    private static final String HOST = "sni-cache.test";

    @BeforeAll
    static void initDb() throws Exception {
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    @Timeout(30)
    void aReplacedCertificateIsServedOnTheNextHandshakeAfterReload() throws Exception {
        var certModel = Models.get(CertificateModel.class);

        // Step 1: certificate A covers the host and is what the handshake presents (this also
        // puts A's alias in the SNI cache).
        KeyPair first = TlsCertificateTest.generateKeyPair();
        Row firstRow = activeCertificate(first, "SNI cache A");
        CertificateStore store = new CertificateStore();
        store.loadFromDatabase();
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(new KeyManager[]{new SniKeyManager(store)}, null, null);
        Undertow server = Undertow.builder()
            .addHttpsListener(0, "127.0.0.1", serverContext)
            .setHandler(exchange -> exchange.getResponseSender().send("ok"))
            .build();
        server.start();
        try {
            int port = ((InetSocketAddress) server.getListenerInfo().get(0).getAddress()).getPort();
            assertThat(presentedKey(port))
                .as("step 1: the handshake presents certificate A")
                .isEqualTo(first.getPublic());

            // Step 2: A is retired and B replaces it; the store reloads.
            firstRow.set(CertificateModel.STATUS, "expired");
            certModel.save(firstRow);
            KeyPair second = TlsCertificateTest.generateKeyPair();
            activeCertificate(second, "SNI cache B");
            store.loadFromDatabase();

            // Step 3: the very next handshake presents B, well inside the cache TTL.
            assertThat(presentedKey(port))
                .as("step 3: the cached alias of A died with the snapshot it came from")
                .isEqualTo(second.getPublic());
        } finally {
            server.stop();
        }
    }

    private static Row activeCertificate(KeyPair keyPair, String name) throws Exception {
        var certModel = Models.get(CertificateModel.class);
        X509Certificate cert = TlsCertificateTest.generateSelfSignedCert(keyPair, HOST);
        Row row = certModel.createEmptyRow();
        row.set(CertificateModel.NICE_NAME, name);
        row.set(CertificateModel.PROVIDER, "custom");
        row.set(CertificateModel.STATUS, "active");
        row.set(CertificateModel.CERTIFICATE_PEM, TlsCertificateTest.certToPem(cert));
        row.set(CertificateModel.PRIVATE_KEY_PEM, TlsCertificateTest.keyToPem(keyPair));
        certModel.save(row);
        return row;
    }

    /** One handshake naming {@link #HOST} in SNI; the public key of the leaf it was shown. */
    private static PublicKey presentedKey(int port) throws Exception {
        SSLContext trustAll = SSLContext.getInstance("TLS");
        trustAll.init(null, new TrustManager[]{new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return null; }
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
        }}, null);
        try (SSLSocket socket = (SSLSocket) trustAll.getSocketFactory().createSocket("127.0.0.1", port)) {
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setServerNames(List.of(new SNIHostName(HOST)));
            socket.setSSLParameters(parameters);
            socket.setSoTimeout(10_000);
            socket.startHandshake();
            return socket.getSession().getPeerCertificates()[0].getPublicKey();
        }
    }
}
