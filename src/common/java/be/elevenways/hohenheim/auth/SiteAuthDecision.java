package be.elevenways.hohenheim.auth;

/**
 * The outcome of a {@code SiteAuthGate} evaluation: either deny with a status + body, or
 * redirect the browser elsewhere. A null gate result (not a decision) means "allow, forward".
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public sealed interface SiteAuthDecision permits SiteAuthDecision.Deny, SiteAuthDecision.Redirect {

    /** Send {@code statusCode} with {@code body} instead of forwarding upstream. */
    record Deny(int statusCode, String body) implements SiteAuthDecision {}

    /** Redirect the browser to {@code url} instead of forwarding upstream. */
    record Redirect(String url) implements SiteAuthDecision {}

    static SiteAuthDecision deny(int statusCode, String body) {
        return new Deny(statusCode, body);
    }

    static SiteAuthDecision redirect(String url) {
        return new Redirect(url);
    }
}
