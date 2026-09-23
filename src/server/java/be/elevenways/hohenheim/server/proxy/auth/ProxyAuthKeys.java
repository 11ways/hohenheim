package be.elevenways.hohenheim.server.proxy.auth;

import be.elevenways.protoblast.common.key.IdentifierKey;

import java.util.List;

/**
 * Cookie names and proxy-auth session attribute keys. Distinct from the admin auth keys
 * ({@code zenit_sid}) so proxy auth stays fully decoupled from admin auth.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public final class ProxyAuthKeys {

    /** Session cookie set on the proxied site's domain. */
    public static final String SESSION_COOKIE = "hh_site_session_id";

    /**
     * The sealed pending state of a started identity-provider login (rlid + browser nonce).
     *
     * AIDEV-NOTE: this replaced a server-side pending session. Minting a session for every
     * anonymous request let any client fill the session store; the pending login is now carried
     * by the browser, sealed with a per-process key, so nothing is stored until an identity has
     * actually been verified.
     */
    public static final String PENDING_LOGIN_COOKIE = "hh_site_login";

    /** Persistent (remember-me) cookie, matching the Node parity target. */
    public static final String PERSISTENT_COOKIE = "acpl";

    /**
     * Every cookie Hohenheim alone owns on a proxied host: the forwarding stage strips these from
     * every upstream request unconditionally. The shared {@link #PERSISTENT_COOKIE} is not here;
     * see {@link CredentialOwner}.
     */
    public static final List<String> HOHENHEIM_OWNED_COOKIES = List.of(SESSION_COOKIE, PENDING_LOGIN_COOKIE);

    private static final String NS = "hohenheim.proxy.session";

    /** The authenticated identity handle/username. Presence marks a session as logged in. */
    public static final IdentifierKey<String> SUBJECT = IdentifierKey.of(NS, "subject");

    /** The site this session was minted for (enforces per-site isolation). */
    public static final IdentifierKey<Integer> SITE_ID = IdentifierKey.of(NS, "site_id");

    /** The provider slug that authenticated this session. */
    public static final IdentifierKey<String> PROVIDER_SLUG = IdentifierKey.of(NS, "provider_slug");

    /**
     * The provider RECORD that authenticated this session.
     *
     * AIDEV-NOTE: the slug names a TYPE, so it cannot tell two configured providers apart.
     * An access-rule tree can carry one leaf per provider record, and each leaf must only
     * be satisfied by a session its OWN provider established.
     */
    public static final IdentifierKey<Integer> PROVIDER_ID = IdentifierKey.of(NS, "provider_id");

    /**
     * A digest of the provider configuration the session was minted under.
     *
     * AIDEV-NOTE: a gate recomputes its own binding on every check, so re-pointing a provider at
     * another realm or changing or deleting a Basic user ends the sessions minted before, instead
     * of leaving them valid until the TTL runs out.
     */
    public static final IdentifierKey<String> BINDING = IdentifierKey.of(NS, "binding");

    /**
     * The permission claim the identity provider returned at login, exactly as it arrived.
     *
     * AIDEV-NOTE: kept so a gate demanding a permission re-checks it against THIS session on every
     * request, without a network call. A session accepted by a permission-less gate must not
     * satisfy a leaf on the same provider that demands one.
     */
    public static final IdentifierKey<Object> PERMISSION_CLAIM = IdentifierKey.of(NS, "permission_claim");

    private ProxyAuthKeys() {}
}
