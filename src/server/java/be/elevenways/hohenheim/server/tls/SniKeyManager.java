package be.elevenways.hohenheim.server.tls;

import javax.net.ssl.*;
import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * SNI-aware key manager that selects certificates based on the hostname
 * in the TLS ClientHello. Delegates to CertificateStore for hostname resolution
 * and to a wrapped X509ExtendedKeyManager for actual key/cert retrieval.
 *
 * Includes a domain-level resolution cache with TTL and negative-result backoff
 * to avoid repeated lookups on the TLS handshake hot path.
 *
 * AIDEV-NOTE: the handshake-stage ban check below is defense-in-depth, NOT the live enforcer.
 * In the shipped topology HTTPS terminates on 127.0.0.1 behind {@code PublicTcpListener}, so
 * {@code engine.getPeerHost()} here is always the loopback hop and the check never fires on a
 * real client. The ban is actually enforced at {@code PublicTcpListener.handle}, on the real
 * (or PROXY-v2-declared) source, before any TLS byte reaches this listener. The check is kept
 * for a hypothetical direct-bind path; do not delete it as "unused" without confirming that
 * path stays impossible, and do not describe it as THE enforcer.
 */
public class SniKeyManager extends X509ExtendedKeyManager {

    private final CertificateStore store;
    private final Predicate<String> bannedIpCheck;

    private static final long POSITIVE_CACHE_TTL_MS = 5 * 60 * 1000;
    private static final long NEGATIVE_CACHE_TTL_MS = 30 * 1000;
    private static final int CACHE_MAX_SIZE = 10_000;

    private record CachedAlias(String alias, long cachedAt, boolean positive) {}

    private final ConcurrentHashMap<String, CachedAlias> aliasCache = new ConcurrentHashMap<>();

    public SniKeyManager(CertificateStore store) {
        this(store, ip -> false);
    }

    public SniKeyManager(CertificateStore store, Predicate<String> bannedIpCheck) {
        this.store = store;
        this.bannedIpCheck = bannedIpCheck;
    }

    @Override
    public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
        if (engine == null) return fallback(keyType, issuers);

        // Defense-in-depth only (see class docblock): in the shipped loopback topology peerIp is
        // the internal hop, so the live ban enforcement is at PublicTcpListener.handle instead.
        String peerIp = engine.getPeerHost();
        if (peerIp != null && bannedIpCheck.test(peerIp)) {
            return null;
        }

        SSLSession session = engine.getHandshakeSession();
        if (session instanceof ExtendedSSLSession extSession) {
            var serverNames = extSession.getRequestedServerNames();
            if (serverNames != null && !serverNames.isEmpty()) {
                for (SNIServerName name : serverNames) {
                    if (name instanceof SNIHostName hostName) {
                        String hostname = hostName.getAsciiName();
                        String cacheKey = hostname + "|" + keyType;

                        // Check cache first
                        CachedAlias cached = aliasCache.get(cacheKey);
                        if (cached != null) {
                            long ttl = cached.positive() ? POSITIVE_CACHE_TTL_MS : NEGATIVE_CACHE_TTL_MS;
                            if (System.currentTimeMillis() - cached.cachedAt() < ttl) {
                                return cached.alias();
                            }
                            aliasCache.remove(cacheKey);
                        }

                        // Resolve from store
                        String alias = store.resolvePreferredAlias(hostname);
                        if (alias == null) {
                            alias = store.resolveAlias(hostname);
                        }

                        if (alias != null && isKeyTypeCompatible(alias, keyType)) {
                            cacheResult(cacheKey, alias, true);
                            return alias;
                        }

                        // Negative cache: no cert found for this hostname
                        cacheResult(cacheKey, null, false);
                        return null;
                    }
                }
                return null;
            }
        }

        return fallback(keyType, issuers);
    }

    @Override
    public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
        return fallback(keyType, issuers);
    }

    private void cacheResult(String key, String alias, boolean positive) {
        if (aliasCache.size() >= CACHE_MAX_SIZE) {
            // Evict expired entries
            long now = System.currentTimeMillis();
            aliasCache.entrySet().removeIf(e -> {
                long ttl = e.getValue().positive() ? POSITIVE_CACHE_TTL_MS : NEGATIVE_CACHE_TTL_MS;
                return now - e.getValue().cachedAt() > ttl;
            });
        }
        aliasCache.put(key, new CachedAlias(alias, System.currentTimeMillis(), positive));
    }

    /**
     * Clear the alias cache. Call after certificate store reloads.
     */
    public void clearCache() {
        aliasCache.clear();
    }

    private boolean isKeyTypeCompatible(String alias, String keyType) {
        X509ExtendedKeyManager delegate = store.getDelegateKeyManager();
        if (delegate == null || keyType == null) return true;

        PrivateKey pk = delegate.getPrivateKey(alias);
        if (pk == null) return true;

        return pk.getAlgorithm().equalsIgnoreCase(keyType);
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
