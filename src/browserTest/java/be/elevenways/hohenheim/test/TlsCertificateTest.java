package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.hohenheim.server.tls.AcmeService;
import be.elevenways.hohenheim.server.tls.CertificateStore;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.server.ServerZenitRuntime;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

import java.io.File;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.math.BigInteger;
import java.util.Date;

/**
 * Tests the TLS certificate infrastructure: CertificateStore, SNI lookup,
 * ProxyServer HTTPS lifecycle, and certificate model lifecycle fields.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TlsCertificateTest {

    private static boolean initialized = false;

    @BeforeAll
    static void initRuntime() throws Exception {
        if (initialized) return;
        initialized = true;

        File db = File.createTempFile("hohenheim-test", ".db");
        db.delete();
        db.deleteOnExit();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.PATH, db.getAbsolutePath());

        SiteTypes.register();
        HohenheimEndpoints.init();
        HohenheimDatabase.init();
        ServerZenitRuntime.init();
        Zenit.getHawkeye().setClientScriptLocation("/hohenheim.js");
    }

    @Test
    @Order(1)
    void emptyCertificateStoreReportsEmpty() {
        CertificateStore store = new CertificateStore();
        assertThat(store.isEmpty()).isTrue();
        assertThat(store.getCertificateCount()).isEqualTo(0);
        assertThat(store.resolveAlias("example.com")).isNull();
    }

    @Test
    @Order(2)
    void certificateStoreLoadsFromDatabase() throws Exception {
        // Insert a self-signed cert into the database
        var ds = HohenheimDatabase.datasource();
        var certModel = new CertificateModel(ds);

        KeyPair keyPair = generateKeyPair();
        X509Certificate cert = generateSelfSignedCert(keyPair, "test.example.com");

        Row row = certModel.createEmptyRow();
        row.set(CertificateModel.NICE_NAME, "Test Cert");
        row.set(CertificateModel.PROVIDER, "custom");
        row.set(CertificateModel.STATUS, "active");
        row.set(CertificateModel.CERTIFICATE_PEM, certToPem(cert));
        row.set(CertificateModel.PRIVATE_KEY_PEM, keyToPem(keyPair));
        certModel.save(row);

        // Load into store
        CertificateStore store = new CertificateStore();
        store.loadFromDatabase();

        assertThat(store.isEmpty()).isFalse();
        assertThat(store.getCertificateCount()).isEqualTo(1);
    }

    @Test
    @Order(3)
    void sniResolvesExactHostname() throws Exception {
        var ds = HohenheimDatabase.datasource();
        var certModel = new CertificateModel(ds);

        // The cert from the previous test should still be in DB
        CertificateStore store = new CertificateStore();
        store.loadFromDatabase();

        // Should resolve the hostname from the cert's CN/SAN
        String alias = store.resolveAlias("test.example.com");
        assertThat(alias).isNotNull();
    }

    @Test
    @Order(4)
    void sniReturnsNullForUnknownHostname() throws Exception {
        CertificateStore store = new CertificateStore();
        store.loadFromDatabase();

        assertThat(store.resolveAlias("unknown.example.com")).isNull();
    }

    @Test
    @Order(5)
    void httpsNotStartedWithoutCertificates() throws Exception {
        // Use a fresh DB with no certs
        File db = File.createTempFile("hohenheim-test", ".db");
        db.delete();
        db.deleteOnExit();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.PATH, db.getAbsolutePath());
        HohenheimDatabase.init();

        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);
        ProxyServer proxy = new ProxyServer();
        proxy.start();

        assertThat(proxy.getHttpState()).isEqualTo(ProxyServer.State.RUNNING);
        assertThat(proxy.getHttpsState()).isEqualTo(ProxyServer.State.STOPPED);
        assertThat(proxy.getHttpsFailureReason()).contains("No certificates");

        proxy.stop();
    }

    @Test
    @Order(6)
    void httpsStartsWhenCertificatesAvailable() throws Exception {
        // Re-init DB and insert a cert
        File db = File.createTempFile("hohenheim-test", ".db");
        db.delete();
        db.deleteOnExit();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.PATH, db.getAbsolutePath());
        HohenheimDatabase.init();

        var ds = HohenheimDatabase.datasource();
        var certModel = new CertificateModel(ds);

        KeyPair keyPair = generateKeyPair();
        X509Certificate cert = generateSelfSignedCert(keyPair, "ssl.example.com");

        Row row = certModel.createEmptyRow();
        row.set(CertificateModel.NICE_NAME, "SSL Test");
        row.set(CertificateModel.PROVIDER, "custom");
        row.set(CertificateModel.STATUS, "active");
        row.set(CertificateModel.CERTIFICATE_PEM, certToPem(cert));
        row.set(CertificateModel.PRIVATE_KEY_PEM, keyToPem(keyPair));
        certModel.save(row);

        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTPS_PORT, 0);

        ProxyServer proxy = new ProxyServer();
        proxy.start();

        assertThat(proxy.getHttpState()).isEqualTo(ProxyServer.State.RUNNING);
        assertThat(proxy.getHttpsState()).isEqualTo(ProxyServer.State.RUNNING);

        proxy.stop();
    }

    @Test
    @Order(7)
    void certificateModelHasLifecycleFields() throws Exception {
        File db = File.createTempFile("hohenheim-test", ".db");
        db.delete();
        db.deleteOnExit();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.PATH, db.getAbsolutePath());
        HohenheimDatabase.init();

        var ds = HohenheimDatabase.datasource();
        var certModel = new CertificateModel(ds);

        Row row = certModel.createEmptyRow();
        row.set(CertificateModel.NICE_NAME, "Lifecycle Test");
        row.set(CertificateModel.PROVIDER, "letsencrypt");
        row.set(CertificateModel.STATUS, "pending");
        row.set(CertificateModel.RENEWAL_ERROR, "Test error message");
        row.set(CertificateModel.DOMAIN_NAMES_TEXT, "a.example.com,b.example.com");
        certModel.save(row);

        Row loaded = certModel.find().where(CertificateModel.NICE_NAME.eq("Lifecycle Test")).first();
        assertThat(loaded).isNotNull();
        assertThat(loaded.get(CertificateModel.STATUS)).isEqualTo("pending");
        assertThat(loaded.get(CertificateModel.RENEWAL_ERROR)).isEqualTo("Test error message");
        assertThat(loaded.get(CertificateModel.DOMAIN_NAMES_TEXT)).isEqualTo("a.example.com,b.example.com");
    }

    // -----------------------------------------------------------------------
    // Self-signed cert generation for testing
    // -----------------------------------------------------------------------

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    private static X509Certificate generateSelfSignedCert(KeyPair keyPair, String cn) throws Exception {
        // Use BouncyCastle to generate a self-signed cert
        var now = new Date();
        var until = new Date(now.getTime() + 365L * 86400000);

        org.bouncycastle.asn1.x500.X500Name issuer =
            new org.bouncycastle.asn1.x500.X500Name("CN=" + cn);

        org.bouncycastle.cert.X509v3CertificateBuilder builder =
            new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                issuer, BigInteger.valueOf(System.currentTimeMillis()),
                now, until, issuer, keyPair.getPublic());

        // Add SAN
        org.bouncycastle.asn1.x509.GeneralName san =
            new org.bouncycastle.asn1.x509.GeneralName(
                org.bouncycastle.asn1.x509.GeneralName.dNSName, cn);
        builder.addExtension(
            org.bouncycastle.asn1.x509.Extension.subjectAlternativeName, false,
            new org.bouncycastle.asn1.x509.GeneralNames(san));

        org.bouncycastle.operator.ContentSigner signer =
            new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
                .build(keyPair.getPrivate());

        return new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
            .getCertificate(builder.build(signer));
    }

    private static String certToPem(X509Certificate cert) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("-----BEGIN CERTIFICATE-----\n");
        sb.append(java.util.Base64.getMimeEncoder(64, "\n".getBytes())
            .encodeToString(cert.getEncoded()));
        sb.append("\n-----END CERTIFICATE-----\n");
        return sb.toString();
    }

    private static String keyToPem(KeyPair keyPair) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("-----BEGIN PRIVATE KEY-----\n");
        sb.append(java.util.Base64.getMimeEncoder(64, "\n".getBytes())
            .encodeToString(keyPair.getPrivate().getEncoded()));
        sb.append("\n-----END PRIVATE KEY-----\n");
        return sb.toString();
    }

    private static X509Certificate generateWildcardCert(KeyPair keyPair, String wildcardDomain)
            throws Exception {
        var now = new Date();
        var until = new Date(now.getTime() + 365L * 86400000);

        org.bouncycastle.asn1.x500.X500Name issuer =
            new org.bouncycastle.asn1.x500.X500Name("CN=" + wildcardDomain);

        org.bouncycastle.cert.X509v3CertificateBuilder builder =
            new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                issuer, BigInteger.valueOf(System.currentTimeMillis()),
                now, until, issuer, keyPair.getPublic());

        org.bouncycastle.asn1.x509.GeneralName san =
            new org.bouncycastle.asn1.x509.GeneralName(
                org.bouncycastle.asn1.x509.GeneralName.dNSName, wildcardDomain);
        builder.addExtension(
            org.bouncycastle.asn1.x509.Extension.subjectAlternativeName, false,
            new org.bouncycastle.asn1.x509.GeneralNames(san));

        org.bouncycastle.operator.ContentSigner signer =
            new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
                .build(keyPair.getPrivate());

        return new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
            .getCertificate(builder.build(signer));
    }

    // -----------------------------------------------------------------------
    // Additional coverage tests
    // -----------------------------------------------------------------------

    @Test
    @Order(10)
    void wildcardCertResolvesSubdomains() throws Exception {
        File db = File.createTempFile("hohenheim-test", ".db");
        db.delete();
        db.deleteOnExit();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.PATH, db.getAbsolutePath());
        HohenheimDatabase.init();

        var ds = HohenheimDatabase.datasource();
        var certModel = new CertificateModel(ds);

        KeyPair kp = generateKeyPair();
        X509Certificate cert = generateWildcardCert(kp, "*.wildcard.test");

        Row row = certModel.createEmptyRow();
        row.set(CertificateModel.NICE_NAME, "Wildcard Test");
        row.set(CertificateModel.PROVIDER, "custom");
        row.set(CertificateModel.STATUS, "active");
        row.set(CertificateModel.CERTIFICATE_PEM, certToPem(cert));
        row.set(CertificateModel.PRIVATE_KEY_PEM, keyToPem(kp));
        certModel.save(row);

        CertificateStore store = new CertificateStore();
        store.loadFromDatabase();

        // Wildcard should match subdomains
        assertThat(store.resolveAlias("app.wildcard.test")).isNotNull();
        assertThat(store.resolveAlias("api.wildcard.test")).isNotNull();

        // Should NOT match the bare domain itself
        assertThat(store.resolveAlias("wildcard.test")).isNull();

        // Should NOT match deeper subdomains
        assertThat(store.resolveAlias("deep.sub.wildcard.test")).isNull();
    }

    @Test
    @Order(11)
    void certificateRemovalClearsFromStore() throws Exception {
        File db = File.createTempFile("hohenheim-test", ".db");
        db.delete();
        db.deleteOnExit();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.PATH, db.getAbsolutePath());
        HohenheimDatabase.init();

        var ds = HohenheimDatabase.datasource();
        var certModel = new CertificateModel(ds);

        KeyPair kp = generateKeyPair();
        X509Certificate cert = generateSelfSignedCert(kp, "remove.test");

        Row row = certModel.createEmptyRow();
        row.set(CertificateModel.NICE_NAME, "Remove Test");
        row.set(CertificateModel.PROVIDER, "custom");
        row.set(CertificateModel.STATUS, "active");
        row.set(CertificateModel.CERTIFICATE_PEM, certToPem(cert));
        row.set(CertificateModel.PRIVATE_KEY_PEM, keyToPem(kp));
        certModel.save(row);

        CertificateStore store = new CertificateStore();
        store.loadFromDatabase();
        assertThat(store.resolveAlias("remove.test")).isNotNull();

        // Delete from DB and reload
        int certId = row.get(CertificateModel.ID);
        certModel.find().where(CertificateModel.ID.eq(certId)).delete();
        store.reload();

        assertThat(store.resolveAlias("remove.test")).isNull();
        assertThat(store.isEmpty()).isTrue();
    }

    @Test
    @Order(12)
    void acmeChallengeValidatesHostname() {
        var store = new CertificateStore();
        var acme = new AcmeService(store);

        // No pending challenges -- should return null
        assertThat(acme.getChallengeResponse("some-token", "example.com")).isNull();
    }

    @Test
    @Order(13)
    void acmeAccountKeyRowExcludedFromStore() throws Exception {
        File db = File.createTempFile("hohenheim-test", ".db");
        db.delete();
        db.deleteOnExit();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.PATH, db.getAbsolutePath());
        HohenheimDatabase.init();

        var ds = HohenheimDatabase.datasource();
        var certModel = new CertificateModel(ds);

        // Create an ACME account key row (should be excluded from TLS store)
        Row accountRow = certModel.createEmptyRow();
        accountRow.set(CertificateModel.NICE_NAME, "ACME Account Key");
        accountRow.set(CertificateModel.PROVIDER, "acme_account");
        accountRow.set(CertificateModel.PRIVATE_KEY_PEM, "-----BEGIN RSA PRIVATE KEY-----\nfake\n-----END RSA PRIVATE KEY-----");
        accountRow.set(CertificateModel.STATUS, "active");
        certModel.save(accountRow);

        CertificateStore store = new CertificateStore();
        store.loadFromDatabase();

        // Account key has no certificate_pem, so loadFromDatabase filters it out
        // (the query filters on CERTIFICATE_PEM.isNotNull() AND STATUS.eq("active"))
        assertThat(store.isEmpty()).isTrue();
    }

    @Test
    @Order(13)
    void forceSslRedirectsHttpToHttps() throws Exception {
        File db = File.createTempFile("hohenheim-test", ".db");
        db.delete();
        db.deleteOnExit();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.PATH, db.getAbsolutePath());
        HohenheimDatabase.init();

        var ds = HohenheimDatabase.datasource();
        var siteModel = new SiteModel(ds);
        var domainModel = new SiteDomainModel(ds);
        var certModel = new CertificateModel(ds);

        KeyPair keyPair = generateKeyPair();
        X509Certificate cert = generateSelfSignedCert(keyPair, "force-ssl.test");

        Row certRow = certModel.createEmptyRow();
        certRow.set(CertificateModel.NICE_NAME, "Force SSL Cert");
        certRow.set(CertificateModel.PROVIDER, "custom");
        certRow.set(CertificateModel.STATUS, "active");
        certRow.set(CertificateModel.CERTIFICATE_PEM, certToPem(cert));
        certRow.set(CertificateModel.PRIVATE_KEY_PEM, keyToPem(keyPair));
        certModel.save(certRow);

        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, "Force SSL Site");
        site.set(SiteModel.SLUG, "force-ssl");
        site.set(SiteModel.SITE_TYPE, "hohenheim:dead");
        site.set(SiteModel.ENABLED, true);
        site.set(SiteModel.STATUS, "active");
        siteModel.save(site);

        int siteId = site.get(SiteModel.ID);

        Row domain = domainModel.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, siteId);
        domain.set(SiteDomainModel.HOSTNAME, "force-ssl.test");
        domain.set(SiteDomainModel.MATCH_TYPE, "exact");
        domain.set(SiteDomainModel.FORCE_SSL, true);
        domainModel.save(domain);

        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTPS_PORT, 8443);

        ProxyServer proxy = new ProxyServer();
        proxy.start();

        int httpPort = ((java.net.InetSocketAddress)
            proxy.getHttpListenerInfo().getAddress()).getPort();

        // Send HTTP request with Host header matching the force-ssl domain
        // Java's HttpClient restricts the Host header, so use a raw socket
        try (java.net.Socket socket = new java.net.Socket("localhost", httpPort)) {
            var out = socket.getOutputStream();
            out.write(("GET /some/path?q=1 HTTP/1.1\r\n"
                     + "Host: force-ssl.test\r\n"
                     + "Connection: close\r\n"
                     + "\r\n").getBytes());
            out.flush();

            var in = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
            String statusLine = in.readLine();
            assertThat(statusLine).contains("301");

            // Read headers to find Location
            String location = null;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("location:")) {
                    location = line.substring("location:".length()).trim();
                }
            }

            assertThat(location).isEqualTo("https://force-ssl.test:8443/some/path?q=1");
        }

        proxy.stop();
    }

    @Test
    @Order(14)
    void httpsActuallyAcceptsTlsConnections() throws Exception {
        File db = File.createTempFile("hohenheim-test", ".db");
        db.delete();
        db.deleteOnExit();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.PATH, db.getAbsolutePath());
        HohenheimDatabase.init();

        var ds = HohenheimDatabase.datasource();
        var certModel = new CertificateModel(ds);

        KeyPair kp = generateKeyPair();
        X509Certificate cert = generateSelfSignedCert(kp, "localhost");

        Row row = certModel.createEmptyRow();
        row.set(CertificateModel.NICE_NAME, "Localhost TLS");
        row.set(CertificateModel.PROVIDER, "custom");
        row.set(CertificateModel.STATUS, "active");
        row.set(CertificateModel.CERTIFICATE_PEM, certToPem(cert));
        row.set(CertificateModel.PRIVATE_KEY_PEM, keyToPem(kp));
        certModel.save(row);

        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTPS_PORT, 0);

        ProxyServer proxy = new ProxyServer();
        proxy.start();

        assertThat(proxy.getHttpsState()).isEqualTo(ProxyServer.State.RUNNING);

        // Make an actual HTTPS connection to the proxy with a trust-all SSL context
        javax.net.ssl.SSLContext trustAll = javax.net.ssl.SSLContext.getInstance("TLS");
        trustAll.init(null, new javax.net.ssl.TrustManager[]{
            new javax.net.ssl.X509TrustManager() {
                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
            }
        }, null);

        // Get the actual port the HTTPS listener bound to
        var listenerInfo = proxy.getHttpsListenerInfo();
        int httpsPort = ((java.net.InetSocketAddress) listenerInfo.getAddress()).getPort();

        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
            .sslContext(trustAll)
            .build();

        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create("https://localhost:" + httpsPort + "/"))
            .GET()
            .build();

        java.net.http.HttpResponse<String> response = client.send(request,
            java.net.http.HttpResponse.BodyHandlers.ofString());

        // The proxy should respond (404 since no sites configured, but TLS handshake works)
        assertThat(response.statusCode()).isIn(200, 302, 404);

        proxy.stop();
    }

    @Test
    @Order(15)
    void preferredCertificateAliasOverridesHostnameSelection() throws Exception {
        File db = File.createTempFile("hohenheim-test", ".db");
        db.delete();
        db.deleteOnExit();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.PATH, db.getAbsolutePath());
        HohenheimDatabase.init();

        var ds = HohenheimDatabase.datasource();
        var certModel = new CertificateModel(ds);
        var siteModel = new SiteModel(ds);
        var domainModel = new SiteDomainModel(ds);

        KeyPair firstKeyPair = generateKeyPair();
        X509Certificate firstCert = generateSelfSignedCert(firstKeyPair, "preferred.test");
        Row firstRow = certModel.createEmptyRow();
        firstRow.set(CertificateModel.NICE_NAME, "First Preferred Cert");
        firstRow.set(CertificateModel.PROVIDER, "custom");
        firstRow.set(CertificateModel.STATUS, "active");
        firstRow.set(CertificateModel.CERTIFICATE_PEM, certToPem(firstCert));
        firstRow.set(CertificateModel.PRIVATE_KEY_PEM, keyToPem(firstKeyPair));
        certModel.save(firstRow);

        KeyPair secondKeyPair = generateKeyPair();
        X509Certificate secondCert = generateSelfSignedCert(secondKeyPair, "preferred.test");
        Row secondRow = certModel.createEmptyRow();
        secondRow.set(CertificateModel.NICE_NAME, "Second Preferred Cert");
        secondRow.set(CertificateModel.PROVIDER, "custom");
        secondRow.set(CertificateModel.STATUS, "active");
        secondRow.set(CertificateModel.CERTIFICATE_PEM, certToPem(secondCert));
        secondRow.set(CertificateModel.PRIVATE_KEY_PEM, keyToPem(secondKeyPair));
        certModel.save(secondRow);

        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, "Preferred TLS Site");
        site.set(SiteModel.SLUG, "preferred-tls-site");
        site.set(SiteModel.SITE_TYPE, "hohenheim:proxy");
        site.set(SiteModel.SETTINGS, java.util.Map.of("forward_host", "127.0.0.1", "forward_port", 8080));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        siteModel.save(site);

        Row domain = domainModel.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, site.get(SiteModel.ID));
        domain.set(SiteDomainModel.HOSTNAME, "preferred.test");
        domain.set(SiteDomainModel.MATCH_TYPE, "exact");
        domain.set(SiteDomainModel.CERTIFICATE_ID, firstRow.get(CertificateModel.ID));
        domainModel.save(domain);

        CertificateStore store = new CertificateStore();
        store.loadFromDatabase();

        assertThat(store.resolvePreferredAlias("preferred.test"))
            .isEqualTo("cert-" + firstRow.get(CertificateModel.ID));
    }
}
