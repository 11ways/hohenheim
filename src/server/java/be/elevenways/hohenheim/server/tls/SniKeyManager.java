package be.elevenways.hohenheim.server.tls;

import javax.net.ssl.*;
import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/**
 * SNI-aware key manager that selects certificates based on the hostname
 * in the TLS ClientHello. Delegates to CertificateStore for hostname resolution
 * and to a wrapped X509ExtendedKeyManager for actual key/cert retrieval.
 */
public class SniKeyManager extends X509ExtendedKeyManager {

    private final CertificateStore store;

    public SniKeyManager(CertificateStore store) {
        this.store = store;
    }

    @Override
    public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
        if (engine == null) return fallback(keyType, issuers);

        SSLSession session = engine.getHandshakeSession();
        if (session instanceof ExtendedSSLSession extSession) {
            var serverNames = extSession.getRequestedServerNames();
            if (serverNames != null && !serverNames.isEmpty()) {
                for (SNIServerName name : serverNames) {
                    if (name instanceof SNIHostName hostName) {
                        String alias = store.resolveAlias(hostName.getAsciiName());
                        if (alias != null) return alias;
                    }
                }
                // SNI was provided but no certificate matched: reject the handshake
                // by returning null (no suitable alias), which causes a TLS alert
                return null;
            }
        }

        return fallback(keyType, issuers);
    }

    @Override
    public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
        return fallback(keyType, issuers);
    }

    private String fallback(String keyType, Principal[] issuers) {
        X509ExtendedKeyManager delegate = store.getDelegateKeyManager();
        if (delegate == null) return null;
        return delegate.chooseServerAlias(keyType, issuers, null);
    }

    @Override
    public X509Certificate[] getCertificateChain(String alias) {
        X509ExtendedKeyManager delegate = store.getDelegateKeyManager();
        return delegate != null ? delegate.getCertificateChain(alias) : null;
    }

    @Override
    public PrivateKey getPrivateKey(String alias) {
        X509ExtendedKeyManager delegate = store.getDelegateKeyManager();
        return delegate != null ? delegate.getPrivateKey(alias) : null;
    }

    @Override
    public String[] getServerAliases(String keyType, Principal[] issuers) {
        X509ExtendedKeyManager delegate = store.getDelegateKeyManager();
        return delegate != null ? delegate.getServerAliases(keyType, issuers) : new String[0];
    }

    @Override
    public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
        return null; // Server only
    }

    @Override
    public String[] getClientAliases(String keyType, Principal[] issuers) {
        return new String[0]; // Server only
    }
}
