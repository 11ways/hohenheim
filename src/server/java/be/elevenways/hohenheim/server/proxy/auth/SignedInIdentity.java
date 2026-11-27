package be.elevenways.hohenheim.server.proxy.auth;

import be.elevenways.zenit.common.session.Session;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Who passed a site's sign-in, in the shape every gate hands over and the forwarding stage tells the app.
 *
 * AIDEV-NOTE: provider-agnostic on purpose: a gate fills it (Proteus all three, a password gate the user name
 * only), the session stores it at sign-in, and the proxy path reads only this, so no provider knowledge and no
 * per-request call to a provider sits between the visitor and the app.
 *
 * @param user         the stable id the provider names the person by
 * @param vouchedEmail the person's address, only when the provider vouched for it; never an unverified one
 * @param name         the person's name as the provider gave it
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.10.0
 */
public record SignedInIdentity(@NonNull String user, @Nullable String vouchedEmail, @Nullable String name) {

    public SignedInIdentity {
        if (user == null || user.isBlank()) {
            throw new IllegalArgumentException("A signed-in identity needs its user id");
        }
    }

    /** @return the identity of a password gate, which knows the user name alone */
    public static @NonNull SignedInIdentity userOnly(@NonNull String user) {
        return new SignedInIdentity(user, null, null);
    }

    /** @return the identity stored in the session, or null when it holds none */
    public static @Nullable SignedInIdentity of(@NonNull Session session) {
        String user = session.get(ProxyAuthKeys.IDENTITY_USER);
        if (user == null) {
            return null;
        }
        return new SignedInIdentity(user, session.get(ProxyAuthKeys.IDENTITY_EMAIL),
            session.get(ProxyAuthKeys.IDENTITY_NAME));
    }

    /** Store this identity in the session, as {@link #of} reads it back. */
    void storeIn(@NonNull Session session) {
        session.set(ProxyAuthKeys.IDENTITY_USER, this.user);
        session.set(ProxyAuthKeys.IDENTITY_EMAIL, this.vouchedEmail);
        session.set(ProxyAuthKeys.IDENTITY_NAME, this.name);
    }
}
