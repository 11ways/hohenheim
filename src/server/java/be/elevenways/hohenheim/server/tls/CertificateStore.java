package be.elevenways.hohenheim.server.tls;

import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;

import java.io.*;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

/**
 * Manages an in-memory KeyStore for TLS certificate lookup.
 * Loads certificates from the database and supports runtime updates.
 * Used by SniKeyManager for SNI-based certificate selection during TLS handshake.
 */
public class CertificateStore {

    private static final char[] KEYSTORE_PASSWORD = "hohenheim".toCharArray();

    private final KeyStore keyStore;
    private final ConcurrentHashMap<String, String> hostnameToAlias = new ConcurrentHashMap<>();
    private volatile javax.net.ssl.X509ExtendedKeyManager delegateKeyManager;

    public CertificateStore() {
        try {
            keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, null);
            rebuildDelegateKeyManager();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize certificate store", e);
        }
    }

    /**
     * Load all active certificates from the database.
     * Called on startup and after certificate changes.
     */
    public void loadFromDatabase() {
        var ds = HohenheimDatabase.datasource();
        var certModel = new CertificateModel(ds);

        List<Row> certs = certModel.find()
            .where(CertificateModel.CERTIFICATE_PEM.isNotNull())
            .all();

        // Clear existing entries
        try {
            for (String alias : Collections.list(keyStore.aliases())) {
                keyStore.deleteEntry(alias);
            }
            hostnameToAlias.clear();
        } catch (KeyStoreException e) {
            Blast.log("Failed to clear certificate store:", e.getMessage());
        }

        int loaded = 0;
        for (Row cert : certs) {
            try {
                addCertificateFromRow(cert);
                loaded++;
            } catch (Exception e) {
                String name = (String) cert.get(CertificateModel.NICE_NAME);
                Blast.log("Failed to load certificate:", name, "-", e.getMessage());
            }
        }

        rebuildDelegateKeyManager();
        Blast.log("CertificateStore: loaded", loaded, "certificates,",
                  hostnameToAlias.size(), "hostname mappings");
    }

    /**
     * Add a certificate from a database row to the in-memory store.
     */
    public void addCertificateFromRow(Row cert) throws Exception {
        String certPem = (String) cert.get(CertificateModel.CERTIFICATE_PEM);
        String keyPem = (String) cert.get(CertificateModel.PRIVATE_KEY_PEM);
        Object idRaw = cert.get(CertificateModel.ID);
        String alias = "cert-" + idRaw;

        if (certPem == null || keyPem == null) return;

        PrivateKey privateKey = parsePrivateKey(keyPem);
        X509Certificate[] chain = parseCertificateChain(certPem);

        if (chain.length == 0) {
            throw new IllegalArgumentException("Empty certificate chain");
        }

        keyStore.setKeyEntry(alias, privateKey, KEYSTORE_PASSWORD, chain);

        // Map each SAN and the CN to this alias
        Set<String> hostnames = extractHostnames(chain[0]);
        for (String hostname : hostnames) {
            hostnameToAlias.put(hostname.toLowerCase(), alias);
        }

        rebuildDelegateKeyManager();
    }

    /**
     * Remove a certificate by its database ID.
     */
    public void removeCertificate(int certId) {
        String alias = "cert-" + certId;
        try {
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias);
            }
        } catch (KeyStoreException e) {
            Blast.log("Failed to remove certificate:", alias, "-", e.getMessage());
        }

        hostnameToAlias.values().removeIf(v -> v.equals(alias));
        rebuildDelegateKeyManager();
    }

    /**
     * Resolve a hostname to a KeyStore alias for SNI selection.
     * Tries exact match first, then wildcard match.
     */
    public String resolveAlias(String hostname) {
        if (hostname == null) return null;
        hostname = hostname.toLowerCase();

        // Exact match
        String alias = hostnameToAlias.get(hostname);
        if (alias != null) return alias;

        // Wildcard match: *.example.com matches sub.example.com
        int dot = hostname.indexOf('.');
        if (dot > 0) {
            alias = hostnameToAlias.get("*" + hostname.substring(dot));
            if (alias != null) return alias;
        }

        return null;
    }

    public javax.net.ssl.X509ExtendedKeyManager getDelegateKeyManager() {
        return delegateKeyManager;
    }

    public boolean isEmpty() {
        try {
            return !keyStore.aliases().hasMoreElements();
        } catch (KeyStoreException e) {
            return true;
        }
    }

    public int getCertificateCount() {
        try {
            return keyStore.size();
        } catch (KeyStoreException e) {
            return 0;
        }
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
        } catch (Exception ignored) {}

        return hostnames;
    }

    private void rebuildDelegateKeyManager() {
        try {
            javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory.getInstance(
                javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, KEYSTORE_PASSWORD);

            for (javax.net.ssl.KeyManager km : kmf.getKeyManagers()) {
                if (km instanceof javax.net.ssl.X509ExtendedKeyManager xkm) {
                    this.delegateKeyManager = xkm;
                    return;
                }
            }
        } catch (Exception e) {
            Blast.log("Failed to rebuild delegate key manager:", e.getMessage());
        }
    }
}
