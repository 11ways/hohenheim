package be.elevenways.hohenheim.test;

import be.elevenways.zenit.common.conduit.ConduitAttributes;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.test.support.EndpointConduit;

/**
 * A production-shaped {@link AccessContext} for a bare principal, for the authorization
 * tests that assert against the policy rather than over the wire.
 *
 * AIDEV-NOTE: a FRESH conduit per call is load-bearing, not tidiness. HohenheimAccess memoizes
 * the walk's set-wise answers on the conduit for the life of a request, so a test that changes
 * a grant and re-asks through the SAME context reads the pre-change answer and passes (or
 * fails) for a reason that has nothing to do with the policy.
 */
public final class TestAccessContexts {

    private TestAccessContexts() {
    }

    /**
     * The conduit carries the principal attribute and {@code AccessContext.of} resolves
     * the INSTALLED permission checker, so assertions exercise the same wiring a real request
     * does.
     */
    public static AccessContext contextFor(Principal principal) {
        EndpointConduit conduit = EndpointConduit.fullPageRequest();
        conduit.setAttribute(ConduitAttributes.PRINCIPAL, principal);
        return AccessContext.of(conduit);
    }
}
