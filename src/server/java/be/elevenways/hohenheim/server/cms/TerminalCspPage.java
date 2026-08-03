package be.elevenways.hohenheim.server.cms;

/**
 * Marker for record subpages that boot the ghostty terminal (pl-terminal) and therefore
 * need {@link SiteTerminalCsp}'s widened admin CSP on their exact route. Implementing
 * this is the ONLY way a page joins that claim -- the CSP variant resolves the request's
 * real subpage and checks this type, so a path that cannot render a terminal can never
 * receive the widened policy.
 */
public interface TerminalCspPage {
}
