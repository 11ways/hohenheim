package be.elevenways.hohenheim.server.proxy.auth;

/**
 * Which of the request's credential carriers a Hohenheim gate consumes itself, so the forwarding
 * stage can strip them before the request reaches the upstream.
 *
 * AIDEV-NOTE: a FACT on the gate, never a type switch in the forwarding stage. The cookies in
 * {@link ProxyAuthKeys#HOHENHEIM_OWNED_COOKIES} are always Hohenheim's and are stripped
 * unconditionally; these two carriers are shared names an upstream may legitimately use for itself
 * (its own Basic realm, an Alchemy app's own acpl remember-me cookie), so they are stripped only on
 * routes where a Hohenheim gate claims them.
 *
 * AIDEV-NOTE: acpl. ProteusAuthGate writes it on the site's host (Path=/, no Domain) after a verified
 * login and registers its identifier + token with the realm; presented back, it logs the browser in
 * without a password. It is therefore a replayable credential for the Proteus IDENTITY, and handing
 * it to the upstream would let that upstream (a tenant's app) log in as the visitor anywhere the
 * realm accepts the cookie. On a route a Proteus gate guards, the acpl on that host IS Hohenheim's
 * (the two could not coexist under one name and path anyway), so it is stripped there; an upstream
 * that wants the visitor's identity must get it from Hohenheim, never from the cookie.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public interface CredentialOwner {

    /** Whether this gate reads the {@code Authorization} header as its own credential. */
    default boolean ownsAuthorizationHeader() {
        return false;
    }

    /** Whether this gate owns the persistent {@link ProxyAuthKeys#PERSISTENT_COOKIE} on the site's host. */
    default boolean ownsPersistentCookie() {
        return false;
    }
}
