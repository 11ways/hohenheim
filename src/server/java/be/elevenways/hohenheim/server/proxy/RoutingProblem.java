package be.elevenways.hohenheim.server.proxy;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One enabled site the last route load could not route as configured, and why.
 *
 * AIDEV-NOTE: these used to be log lines only, so an enabled site could vanish from routing
 * (or answer 503 for every request) with nothing an operator would ever look at saying so.
 * The dispatcher records one per occurrence on the route generation it builds, and
 * {@link SiteDispatcher#routingProblems()} is the queryable face an attention surface reads.
 *
 * @param siteId   the site's id
 * @param siteName the site's name, for display
 * @param reason   what went wrong
 * @param detail   the specific cause (a hostname, a kind token, an exception message), or null
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public record RoutingProblem(int siteId, @NonNull String siteName, @NonNull Reason reason,
                             @Nullable String detail) {

    /** Why an enabled site is not routed as configured. */
    public enum Reason {

        /** The site's upstream kind is not registered in this build; it has no routes at all. */
        UNKNOWN_KIND(true),

        /** Building the site's request handler threw; it has no routes at all. */
        HANDLER_FAILED(true),

        /** The handler refuses every request (a fail-fast validation, a refused tenant upstream). */
        HANDLER_FAULTED(false),

        /** One of the site's routes is already claimed by another site; that route is ignored. */
        DUPLICATE_ROUTE(false),

        /** The site's auth provider cannot be built, so every request is refused (fail closed). */
        AUTH_UNAVAILABLE(false);

        private final boolean unrouted;

        Reason(boolean unrouted) {
            this.unrouted = unrouted;
        }

        /** Whether the site is missing from routing entirely, rather than routed but refusing. */
        public boolean unrouted() {
            return this.unrouted;
        }
    }
}
