package be.elevenways.hohenheim.server.tls;

import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.query.SortOrder;

import java.io.*;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.*;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

/**
 * Manages an in-memory KeyStore for TLS certificate lookup.
 * Loads certificates from the database and supports runtime updates.
 * Uses copy-on-write: mutations build a new snapshot that is atomically
 * swapped in, so TLS handshakes never see a partially-loaded state.
 */
public class CertificateStore {

    private static final char[] KEYSTORE_PASSWORD =
        UUID.randomUUID().toString().toCharArray();

    /**
     * Immutable snapshot of loaded certificates.
     * Swapped atomically via volatile reference.
     */
    private static final class Snapshot {
        final javax.net.ssl.X509ExtendedKeyManager keyManager;
        final Map<String, String> hostnameToAlias;
        final Map<String, String> preferredHostnameToAlias;
        final int count;

        Snapshot(javax.net.ssl.X509ExtendedKeyManager keyManager,
                 Map<String, String> hostnameToAlias,
                 Map<String, String> preferredHostnameToAlias,
                 int count) {
            this.keyManager = keyManager;
            this.hostnameToAlias = hostnameToAlias;
            this.preferredHostnameToAlias = preferredHostnameToAlias;
            this.count = count;
        }
    }

    private volatile Snapshot snapshot;

    public CertificateStore() {
        this.snapshot = new Snapshot(null, Map.of(), Map.of(), 0);
    }

    /**
     * Load all active certificates from the database.
     * Builds a complete new snapshot and swaps it in atomically.
     */
    public synchronized void loadFromDatabase() {
        var ds = HohenheimDatabase.datasource();
        var certModel = Models.get(CertificateModel.class);
        var domainModel = Models.get(SiteDomainModel.class);

        List<Row> certs = certModel.find()
            .where(CertificateModel.CERTIFICATE_PEM.isNotNull())
            .where(CertificateModel.STATUS.eq("active"))
            .orderBy(CertificateModel.ID, SortOrder.ASC)
            .all();

        List<Row> domains = domainModel.find()
            .where(SiteDomainModel.CERTIFICATE_ID.isNotNull())
            .all();

        try {
            Snapshot newSnapshot = buildSnapshot(certs, domains);
            this.snapshot = newSnapshot;
            Blast.log("CertificateStore: loaded", newSnapshot.count, "certificates,",
                      newSnapshot.hostnameToAlias.size(), "hostname mappings,",
                      newSnapshot.preferredHostnameToAlias.size(), "preferred mappings");
        } catch (Exception e) {
            Blast.log("CertificateStore: failed to build snapshot:", e.getMessage());
        }
    }

    /**
     * Reload the full certificate snapshot from the database.
     */
    public void reload() {
        loadFromDatabase();
    }

    /**
     * Resolve a hostname to a KeyStore alias for SNI selection.
     * Reads from the current snapshot -- always consistent, never blocks.
     */
    public String resolveAlias(String hostname) {
        return resolveFromMap(hostname, snapshot.hostnameToAlias);
    }

    public String resolvePreferredAlias(String hostname) {
        return resolveFromMap(hostname, snapshot.preferredHostnameToAlias);
    }

    private static String resolveFromMap(String hostname, Map<String, String> map) {
        if (hostname == null) return null;
        hostname = hostname.toLowerCase();

        String alias = map.get(hostname);
        if (alias != null) return alias;

        int dot = hostname.indexOf('.');
        if (dot > 0) {
            alias = map.get("*" + hostname.substring(dot));
            if (alias != null) return alias;
        }

        return null;
    }

    public javax.net.ssl.X509ExtendedKeyManager getDelegateKeyManager() {
        return snapshot.keyManager;
    }

    public boolean isEmpty() {
        return snapshot.count == 0;
    }

    public int getCertificateCount() {
        return snapshot.count;
    }

    // -----------------------------------------------------------------------
    // Snapshot construction (all private, no shared mutable state)
    // -----------------------------------------------------------------------

    private static Snapshot buildSnapshot(List<Row> certs, List<Row> domains) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        Map<String, String> hostnameToAlias = new HashMap<>();
        Set<String> loadedAliases = new HashSet<>();

        int loaded = 0;
        for (Row cert : certs) {
            try {
                String alias = addCertToKeyStore(cert, keyStore, hostnameToAlias);
                if (alias != null) {
                    loadedAliases.add(alias);
                }
                loaded++;
            } catch (Exception e) {
                String name = cert.get(CertificateModel.NICE_NAME);
                Blast.log("CertificateStore: failed to load cert:", name, "-", e.getMessage());
            }
        }

        javax.net.ssl.X509ExtendedKeyManager keyManager = buildKeyManager(keyStore);
        Map<String, String> preferredAliases = buildPreferredAliases(domains, loadedAliases);
        return new Snapshot(keyManager, Map.copyOf(hostnameToAlias), Map.copyOf(preferredAliases), loaded);
    }

    private static Map<String, String> buildPreferredAliases(List<Row> domains,
                                                             Set<String> loadedAliases) {
        Map<String, String> result = new HashMap<>();

        for (Row domain : domains) {
            String hostname = domain.get(SiteDomainModel.HOSTNAME);
            Integer certificateId = domain.get(SiteDomainModel.CERTIFICATE_ID);

            if (hostname == null || hostname.isBlank() || certificateId == null) {
                continue;
            }

            String alias = "cert-" + certificateId;
            if (!loadedAliases.contains(alias)) {
                continue;
            }

            result.put(hostname.toLowerCase(), alias);
        }

        return result;
    }

    private static String addCertToKeyStore(Row cert, KeyStore keyStore,
                                            Map<String, String> hostnameToAlias) throws Exception {
        String certPem = cert.get(CertificateModel.CERTIFICATE_PEM);
        String keyPem = cert.get(CertificateModel.PRIVATE_KEY_PEM);
        Object idRaw = cert.get(CertificateModel.ID);
        String alias = "cert-" + idRaw;

        if (certPem == null || keyPem == null) return null;

        PrivateKey privateKey = parsePrivateKey(keyPem);
        X509Certificate[] chain = parseCertificateChain(certPem);

        if (chain.length == 0) {
            throw new IllegalArgumentException("Empty certificate chain");
        }

        keyStore.setKeyEntry(alias, privateKey, KEYSTORE_PASSWORD, chain);

        Set<String> hostnames = extractHostnames(chain[0]);
        for (String hostname : hostnames) {
            hostnameToAlias.put(hostname.toLowerCase(), alias);
        }

        return alias;
    }

    private static javax.net.ssl.X509ExtendedKeyManager buildKeyManager(KeyStore keyStore)
            throws Exception {
        javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory.getInstance(
            javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, KEYSTORE_PASSWORD);

        for (javax.net.ssl.KeyManager km : kmf.getKeyManagers()) {
            if (km instanceof javax.net.ssl.X509ExtendedKeyManager xkm) {
                return xkm;
            }
        }
        throw new IllegalStateException("No X509ExtendedKeyManager found in KeyManagerFactory");
    }

    // -----------------------------------------------------------------------
    // PEM parsing
    // -----------------------------------------------------------------------

    private static PrivateKey parsePrivateKey(String pem) throws Exception {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object parsed = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();

            if (parsed instanceof PEMKeyPair keyPair) {
                return converter.getPrivateKey(keyPair.getPrivateKeyInfo());
            } else if (parsed instanceof PrivateKeyInfo keyInfo) {
                return converter.getPrivateKey(keyInfo);
            } else {
                throw new IllegalArgumentException(
                    "Unsupported PEM object: " + (parsed != null ? parsed.getClass().getName() : "null"));
            }
        }
    }

    private static X509Certificate[] parseCertificateChain(String pem) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<X509Certificate> chain = new ArrayList<>();

        try (InputStream is = new ByteArrayInputStream(pem.getBytes())) {
            var certs = cf.generateCertificates(is);
            for (var cert : certs) {
                chain.add((X509Certificate) cert);
            }
        }

        return chain.toArray(new X509Certificate[0]);
    }

    private static Set<String> extractHostnames(X509Certificate cert) {
        Set<String> hostnames = new HashSet<>();

        // CN from subject
        String dn = cert.getSubjectX500Principal().getName();
        for (String part : dn.split(",")) {
            part = part.trim();
            if (part.startsWith("CN=")) {
                hostnames.add(part.substring(3).trim());
            }
        }

        // Subject Alternative Names
        try {
            var sans = cert.getSubjectAlternativeNames();
            if (sans != null) {
                for (List<?> san : sans) {
                    // Type 2 = dNSName
                    if (san.size() >= 2 && Integer.valueOf(2).equals(san.get(0))) {
                        hostnames.add(san.get(1).toString());
                    }
                }
            }
        } catch (Exception e) {
            Blast.log("CertificateStore: failed to parse SANs:", e.getMessage());
        }

        return hostnames;
    }
}
