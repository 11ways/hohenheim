package be.elevenways.hohenheim.server.sitetype;

import io.undertow.server.HttpServerExchange;

/**
 * Mutates an outgoing response just before its headers are committed.
 *
 * Returned by {@link SiteRequestHandler#mutateResponse} and invoked from the dispatcher's
 * response-commit listener, after domain response-header rules and Location rewrite.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
@FunctionalInterface
public interface ResponseMutator {

    void mutate(HttpServerExchange exchange);
}
