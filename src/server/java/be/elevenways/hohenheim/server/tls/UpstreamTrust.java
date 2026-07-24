package be.elevenways.hohenheim.server.tls;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

/**
 * SSL contexts for validated upstream TLS dials. Hostname verification is NOT done here:
 * Undertow's https/h2 client providers enable JDK endpoint identification by default, which
 * performs the SAN check; ignore_certificates targets explicitly disable that option.
 */
public final class UpstreamTrust {

    private UpstreamTrust() {
    }

    /**
     * @return the platform default context (default trust anchors)
     * @throws IllegalStateException when the platform default cannot be loaded
     */
    public static SSLContext defaultContext() {
        try {
            return SSLContext.getDefault();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize upstream trust", e);
        }
    }

    /**
     * @return a context trusting exactly the given anchors (tests, custom CAs)
     */
    public static SSLContext contextTrusting(X509Certificate... anchors) {
        try {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            for (int i = 0; i < anchors.length; i++) {
                store.setCertificateEntry("anchor-" + i, anchors[i]);
            }
            TrustManagerFactory factory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
            factory.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, factory.getTrustManagers(), null);
            return context;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build upstream trust context", e);
        }
    }
}
