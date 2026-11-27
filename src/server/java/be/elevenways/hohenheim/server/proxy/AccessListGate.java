package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.auth.SiteAuthDecision;
import be.elevenways.hohenheim.net.Hostnames;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Enforces a route's access list by evaluating its {@link AccessRuleTree}.
 *
 * The gate itself decides nothing: the tree answers PASS / FAIL / PENDING and hands back
 * the leaf that could still flip a PENDING verdict, and this class only turns that into a
 * response. See {@link AccessRuleTree} for the semantics, including why a refusal that no
 * credential could change answers 403 without ever emitting a challenge.
 * A tree holding a signed-link leaf answers that refusal with the ordinary not-found page instead
 * ({@link AccessRuleTree#refusesAsNotFound}).
 */
final class AccessListGate {

    private AccessListGate() {}

    /**
     * Check the access list. Returns true if the request is allowed, false if blocked.
     * When blocked, the response (401, 403 or a provider redirect) is already sent.
     */
    static boolean allows(HttpServerExchange exchange, RouteEntry entry) {
        return allows(exchange, entry.accessTree);
    }

    /**
     * The same gate over ONE compiled tree: the site-level list and every protected-path
     * guard answer through this single evaluation, so a guarded folder can never behave
     * differently from a guarded site.
     */
    static boolean allows(HttpServerExchange exchange, @Nullable AccessRuleTree tree) {
        if (tree == null) {
            return true;
        }

        // AIDEV-NOTE: the loop exists because challenging an identity leaf can SATISFY it
        // instead of answering it -- an acpl remember-me cookie logs the visitor in and the
        // gate returns "forward". Re-evaluating is what lets the rest of the tree see that.
        // Each pass either allows, refuses, or satisfies one more leaf, so the bound is the
        // number of credential leaves; the constant keeps a broken gate from spinning.
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            AccessRuleTree.Result result = tree.evaluate(exchange);
            if (result.allowed()) {
                return true;
            }
            if (tree.refusesAsNotFound() && result.verdict() == AccessRuleTree.Verdict.FAIL) {
                // A signed path refused looks like no path at all: the site's ordinary not-found page.
                ErrorPages.send404(exchange, Hostnames.fromHostHeader(
                    exchange.getRequestHeaders().getFirst(Headers.HOST)));
                return false;
            }
            SiteAuthDecision refusal = result.refusal(exchange);
            if (refusal != null) {
                SiteDispatcher.applySiteAuthDecision(exchange, refusal);
                return false;
            }
        }

        SiteDispatcher.applySiteAuthDecision(exchange, SiteAuthDecision.deny(403, "Forbidden"));
        return false;
    }

    /** How many credential leaves one request may satisfy in place before the gate gives up. */
    private static final int MAX_PASSES = 8;
}
