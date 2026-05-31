package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.tls.CertificateStore;
import be.elevenways.hohenheim.server.tls.SniKeyManager;
import be.elevenways.zenit.common.orm.datasource.Row;
import io.undertow.Undertow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that IP reputation is enforced at the TLS handshake stage: a banned peer is refused a
 * certificate by {@link SniKeyManager}, which aborts the handshake before any content is served.
 * This also proves Undertow populates the per-connection {@code SSLEngine} peer address (the
 * mechanism the gate depends on) -- a "ban all" predicate must fail the handshake, a "ban none"
 * predicate must let it through.
 */
class TlsReputationTest {

    private static boolean initialized = false;

    @BeforeAll
    static void initDb() throws Exception {
        if (initialized) return;
        initialized = true;
        File db = File.createTempFile("hohenheim-tlsrep-test", ".db");
        db.delete();
        db.deleteOnExit();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.PATH, db.getAbsolutePath());
        HohenheimDatabase.init();
    }

    @Test
    void bannedPeerIsRefusedAtHandshake() throws Exception {
        Undertow server = startHttps(ip -> true);   // every peer banned
        try {
            assertThatThrownBy(() -> get(httpsPort(server)))
                .isInstanceOf(IOException.class);   // handshake_failure: no certificate served
        } finally {
            server.stop();
        }
    }

    @Test
    void allowedPeerCompletesHandshake() throws Exception {
        Undertow server = startHttps(ip -> false);   // nobody banned -- control
        try {
            HttpResponse<String> response = get(httpsPort(server));
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("ok");
        } finally {
            server.stop();
        }
    }

    // --- helpers ---

    private static Undertow startHttps(Predicate<String> bannedIpCheck) throws Exception {
        CertificateStore store = localhostStore();
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(new KeyManager[]{new SniKeyManager(store, bannedIpCheck)}, null, null);

        Undertow server = Undertow.builder()
            .addHttpsListener(0, "127.0.0.1", sslContext)
            .setHandler(exchange -> exchange.getResponseSender().send("ok"))
            .build();
        server.start();
        return server;
    }

    private static CertificateStore localhostStore() throws Exception {
        var certModel = new CertificateModel(HohenheimDatabase.datasource());

        KeyPair kp = TlsCertificateTest.generateKeyPair();
        X509Certificate cert = TlsCertificateTest.generateSelfSignedCert(kp, "localhost");
        Row row = certModel.createEmptyRow();
        row.set(CertificateModel.NICE_NAME, "Localhost TLS");
        row.set(CertificateModel.PROVIDER, "custom");
        row.set(CertificateModel.STATUS, "active");
        row.set(CertificateModel.CERTIFICATE_PEM, TlsCertificateTest.certToPem(cert));
        row.set(CertificateModel.PRIVATE_KEY_PEM, TlsCertificateTest.keyToPem(kp));
        certModel.save(row);

        CertificateStore store = new CertificateStore();
        store.loadFromDatabase();
        return store;
    }

    private static int httpsPort(Undertow server) {
        return ((InetSocketAddress) server.getListenerInfo().get(0).getAddress()).getPort();
    }

    private static HttpResponse<String> get(int port) throws Exception {
        SSLContext trustAll = SSLContext.getInstance("TLS");
        trustAll.init(null, new TrustManager[]{new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return null; }
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
        }}, null);

        HttpClient client = HttpClient.newBuilder().sslContext(trustAll).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://localhost:" + port + "/"))
            .GET()
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
