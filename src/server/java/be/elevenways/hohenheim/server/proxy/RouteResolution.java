package be.elevenways.hohenheim.server.proxy;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * What route selection found: the route, or no route and the first route whose name matched the hostname (a
 * wrong-path 404 of that site rather than a domain miss).
 *
 * @param knownBy the first route, in selection order, serving the hostname at another path; null when a route matched
 *                or no route serves the hostname
 * @author Jelle De Loecker
 * @since  0.1.0
 */
record RouteResolution(@Nullable RouteMatch match, @Nullable RouteEntry knownBy) {

    /** @return whether some route serves the hostname, matched or not */
    boolean hostnameKnown() {
        return this.match != null || this.knownBy != null;
    }
}
