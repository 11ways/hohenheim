package be.elevenways.hohenheim.server.auth;

import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.session.SessionStore;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Everything a provider type needs to construct a gate: the provider record's config blob, the
 * (provider-agnostic) required permission, and the proxy-owned session store. Bundled so future
 * provider types can read more from it without churning the createGate signature.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public record SiteAuthContext(Row config, @Nullable String requiredPermission,
                              SessionStore sessionStore) {
}
