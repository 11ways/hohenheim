package be.elevenways.hohenheim.server.incus;

import org.checkerframework.checker.nullness.qual.NonNull;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * TLS assembly for the Incus HTTPS lane: the per-host CLIENT identity (a self-signed
 * certificate Incus trusts by fingerprint) and the PINNED-server trust decision. There
 * is deliberately NO CA validation and NO hostname verification here -- trust is the
 * exact pinned certificate or nothing, the TLS twin of the ssh known_hosts pin.
 */
final class IncusTls {

    private IncusTls() {
    }

    /** SHA-256 of a certificate's DER encoding, lowercase hex -- what `incus info` prints. */
    static @NonNull String fingerprintOf(@NonNull X509Certificate certificate) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(certificate.getEncoded());
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException | CertificateEncodingException e) {
            throw new IllegalStateException("cannot fingerprint a certificate", e);
        }
    }

    /** Parse one PEM certificate. */
    static @NonNull X509Certificate parseCertificate(@NonNull String pem) throws IOException {
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(
                    pem.getBytes(StandardCharsets.US_ASCII)));
        } catch (CertificateException e) {
            throw new IOException("not a PEM certificate: " + e.getMessage(), e);
        }
    }

    /** Re-encode a certificate as PEM (the pin's stored spelling). */
    static @NonNull String toPem(@NonNull X509Certificate certificate) throws IOException {
        try {
            return "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'})
                    .encodeToString(certificate.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
        } catch (CertificateEncodingException e) {
            throw new IOException("cannot encode a certificate", e);
        }
    }

    /** Parse a PKCS#8 PEM private key ({@code BEGIN PRIVATE KEY}, EC or RSA). */
    static @NonNull PrivateKey parsePrivateKey(@NonNull String pem) throws IOException {
        String base64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        byte[] der;
        try {
            der = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IOException("not a PEM private key");
        }
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        for (String algorithm : new String[] {"EC", "RSA", "Ed25519"}) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec);
            } catch (GeneralSecurityException tryNext) {
                // the spec does not name its algorithm; probing is the JDK way
            }
        }
        throw new IOException("unsupported private key (expected PKCS#8 EC/RSA/Ed25519)");
    }

    /**
     * A connected, HANDSHAKEN TLS socket to {@code host:port} that presents the client
     * identity and accepts ONLY the pinned certificate; the handshake fails closed with
     * the mismatch text {@code HostProbe} classifies as {@code host_key_changed}.
     */
    static @NonNull SSLSocket connectPinned(@NonNull String host, int port,
                                            @NonNull String identityCertPem,
                                            @NonNull String identityKeyPem,
                                            @NonNull String pinnedCertPem,
                                            int connectTimeoutMs) throws IOException {
        X509Certificate pinned = parseCertificate(pinnedCertPem);
        String pinnedFingerprint = fingerprintOf(pinned);
        TrustManager pinTrust = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                throw new UnsupportedOperationException("client mode only");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                if (chain == null || chain.length == 0) {
                    throw new CertificateException("the server presented no certificate");
                }
                String offered = fingerprintOf(chain[0]);
                if (!offered.equals(pinnedFingerprint)) {
                    throw new CertificateException("server certificate does not match the"
                        + " pinned certificate (pinned " + pinnedFingerprint
                        + ", offered " + offered + ")");
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        return handshake(host, port, identity(identityCertPem, identityKeyPem),
            pinTrust, connectTimeoutMs);
    }

    /**
     * The SCAN half of the trust ceremony: connect WITHOUT trusting anything, capture
     * the certificate the server offers, and hang up. The result is only ever compared
     * or shown -- exactly ssh-keyscan's stance.
     */
    static @NonNull X509Certificate scanServerCertificate(@NonNull String host, int port,
                                                          int connectTimeoutMs)
            throws IOException {
        X509Certificate[] captured = new X509Certificate[1];
        TrustManager captureAll = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                throw new UnsupportedOperationException("client mode only");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                if (chain == null || chain.length == 0) {
                    throw new CertificateException("the server presented no certificate");
                }
                captured[0] = chain[0];
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLSocket socket = handshake(host, port, null, captureAll, connectTimeoutMs);
        try {
            socket.close();
        } catch (IOException ignored) {
            // the capture already happened during the handshake
        }
        if (captured[0] == null) {
            throw new IOException("the TLS handshake completed without a server certificate");
        }
        return captured[0];
    }

    private static @NonNull SSLSocket handshake(@NonNull String host, int port,
                                                KeyManager[] identity,
                                                @NonNull TrustManager trust,
                                                int connectTimeoutMs) throws IOException {
        SSLContext context;
        try {
            context = SSLContext.getInstance("TLSv1.3");
            context.init(identity, new TrustManager[] {trust}, null);
        } catch (GeneralSecurityException e) {
            throw new IOException("cannot assemble the TLS context: " + e.getMessage(), e);
        }
        SSLSocketFactory factory = context.getSocketFactory();
        Socket plain = new Socket();
        try {
            plain.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            SSLSocket socket = (SSLSocket) factory.createSocket(plain, host, port, true);
            socket.setSoTimeout(connectTimeoutMs);
            socket.startHandshake();
            // Streams block indefinitely once handed over; per-request deadlines are the
            // transport watchdog's job, not a socket-level read timeout that would kill
            // long-lived websockets.
            socket.setSoTimeout(0);
            return socket;
        } catch (IOException e) {
            try {
                plain.close();
            } catch (IOException ignored) {
                // already failing
            }
            throw e;
        }
    }

    /** KeyManagers presenting the per-host client certificate + key. */
    private static KeyManager @NonNull [] identity(@NonNull String certPem,
                                                   @NonNull String keyPem) throws IOException {
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setKeyEntry("identity", parsePrivateKey(keyPem), new char[0],
                new Certificate[] {parseCertificate(certPem)});
            KeyManagerFactory factory = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
            factory.init(store, new char[0]);
            return factory.getKeyManagers();
        } catch (GeneralSecurityException e) {
            throw new IOException("cannot load the client identity: " + e.getMessage(), e);
        }
    }
}
