package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.auth.SiteAuthDecision;
import io.undertow.server.HttpServerExchange;

/**
 * Enforces a route's access list by evaluating its {@link AccessRuleTree}.
 *
 * The gate itself decides nothing: the tree answers PASS / FAIL / PENDING and hands back
 * the leaf that could still flip a PENDING verdict, and this class only turns that into a
 * response. See {@link AccessRuleTree} for the semantics, including why a refusal that no
 * credential could change answers 403 without ever emitting a challenge.
 */
final class AccessListGate {

    private AccessListGate() {}

    /**
     * Check the access list. Returns true if the request is allowed, false if blocked.
     * When blocked, the response (401, 403 or a provider redirect) is already sent.
     */
    static boolean allows(HttpServerExchange exchange, RouteEntry entry, String clientIp) {
        AccessRuleTree tree = entry.accessTree;
        if (tree == null) {
            return true;
        }

        // AIDEV-NOTE: the loop exists because challenging an identity leaf can SATISFY it
        // instead of answering it -- an acpl remember-me cookie logs the visitor in and the
        // gate returns "forward". Re-evaluating is what lets the rest of the tree see that.
        // Each pass either allows, refuses, or satisfies one more leaf, so the bound is the
        // number of credential leaves; the constant keeps a broken gate from spinning.
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            AccessRuleTree.Result result = tree.evaluate(exchange, clientIp);
            if (result.allowed()) {
                return true;
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
