package be.elevenways.hohenheim.test;

import be.elevenways.protoblast.common.key.IdentifierKey;
import be.elevenways.zenit.common.api.ResponseCarrier;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.conduit.ConduitAttributes;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.routing.BodyDefinition;
import be.elevenways.zenit.common.routing.ParameterDefinition;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Principal;

import java.util.HashMap;
import java.util.Map;

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
     * The conduit stub carries the principal attribute and {@code AccessContext.of} resolves
     * the INSTALLED permission checker, so assertions exercise the same wiring a real request
     * does.
     */
    public static AccessContext contextFor(Principal principal) {
        StubConduit conduit = new StubConduit();
        conduit.setAttribute(ConduitAttributes.PRINCIPAL, principal);
        return AccessContext.of(conduit);
    }

    /** Attribute-only Conduit; every request-flavored method throws. */
    private static final class StubConduit implements Conduit {

        private final Map<IdentifierKey<?>, Object> attributes = new HashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getAttribute(IdentifierKey<T> key) {
            return (T) this.attributes.get(key);
        }

        @Override
        public <T> void setAttribute(IdentifierKey<T> key, T value) {
            if (value == null) {
                this.attributes.remove(key);
            } else {
                this.attributes.put(key, value);
            }
        }

        @Override
        public ResponseCarrier getResponseCarrier() {
            throw new UnsupportedOperationException("stub carries no response");
        }

        @Override
        public <T> T getParameter(ParameterDefinition<T> parameter) {
            throw new UnsupportedOperationException("stub carries no request");
        }

        @Override
        public <T> T getBody(BodyDefinition<T> definition) {
            throw new UnsupportedOperationException("stub carries no request");
        }

        @Override
        public boolean isHawkeyeRequest() {
            return false;
        }

        @Override
        public void enableStreamingResponse() {
            throw new UnsupportedOperationException("stub carries no response");
        }

        @Override
        public void notFound() {
            throw new UnsupportedOperationException("stub carries no response");
        }

        @Override
        public void forbidden() {
            throw new UnsupportedOperationException("stub carries no response");
        }

        @Override
        public void badRequest() {
            throw new UnsupportedOperationException("stub carries no response");
        }

        @Override
        public void badRequest(String message) {
            throw new UnsupportedOperationException("stub carries no response");
        }

        @Override
        public <T> ActionResult<T> softRedirect(String url) {
            throw new UnsupportedOperationException("stub carries no response");
        }

        @Override
        public <T> ActionResult<T> hardRedirect(String url) {
            throw new UnsupportedOperationException("stub carries no response");
        }
    }
}
