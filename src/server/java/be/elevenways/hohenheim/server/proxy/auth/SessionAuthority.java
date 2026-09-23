package be.elevenways.hohenheim.server.proxy.auth;

import be.elevenways.zenit.common.session.Session;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * A gate that judges whether an established proxy session still satisfies it.
 *
 * AIDEV-NOTE: a session proves WHO logged in, under which provider record and configuration;
 * whether that is enough is the gate's question, asked on every request. A site-level gate and
 * an access-rule leaf on the same provider record can demand different permissions, so the
 * session must never carry "accepted" as a fact of its own. A gate that does not implement this
 * accepts no session at all (the access-rule leaf fails closed).
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public interface SessionAuthority {

    /**
     * @param session an authenticated session minted for the gate's own site
     * @return whether it was minted by this gate's provider record under its current
     *         configuration, and satisfies this gate's own requirement
     */
    boolean accepts(@NonNull Session session);
}
