package be.elevenways.hohenheim.net;

import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The {@code X-Hohenheim-*} request header family: Hohenheim's own, so no visitor and no operator's header rule may
 * hand one to an app.
 *
 * AIDEV-NOTE: a member is recognised by the prefix alone, in any case and with an underscore for a dash (CGI, PHP and
 * WSGI fold both to one variable), so a member added later is covered with no edit here. The proxy tests every header
 * name of every forwarded request, so the rule is one character at a time ({@link #prefixAt}): a caller holding the
 * name as bytes (Undertow's HttpString) compares in place and allocates nothing. The proxy removes the family from
 * every request it forwards, whoever sent it (proxy.OwnHeaders).
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.10.0
 */
public final class OwnRequestHeaders {

    /** The prefix every member of the family is spelled with. */
    public static final String PREFIX = "X-Hohenheim-";

    private OwnRequestHeaders() {
    }

    /** @return whether a request header of this name belongs to the family */
    public static boolean isOwn(@NonNull CharSequence name) {
        if (name.length() < PREFIX.length()) {
            return false;
        }
        for (int i = 0; i < PREFIX.length(); i++) {
            if (!prefixAt(i, name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param index a position inside {@link #PREFIX}
     * @return whether a name spelling {@code c} there may still be a member
     */
    public static boolean prefixAt(int index, char c) {
        char expected = PREFIX.charAt(index);
        return c == expected || (expected == '-' && c == '_')
            || Character.toLowerCase(c) == Character.toLowerCase(expected);
    }
}
