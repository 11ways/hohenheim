package be.elevenways.hohenheim.server.proxy.auth;

import be.elevenways.protoblast.common.key.IdentifierKey;

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

    /** Persistent (remember-me) cookie, matching the Node parity target. */
    public static final String PERSISTENT_COOKIE = "acpl";

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

    /** The rlid of a started-but-unfinished Proteus login (pending state). */
    public static final IdentifierKey<String> PENDING_RLID = IdentifierKey.of(NS, "pending_rlid");

    /** The URL to return to once login completes. */
    public static final IdentifierKey<String> AFTER_LOGIN = IdentifierKey.of(NS, "after_login");

    private ProxyAuthKeys() {}
}
