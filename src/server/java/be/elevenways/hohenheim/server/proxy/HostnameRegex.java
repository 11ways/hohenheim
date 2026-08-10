package be.elevenways.hohenheim.server.proxy;

import java.util.regex.Pattern;

/**
 * Compiles the shared plain or slash-delimited hostname-regex syntax.
 *
 * AIDEV-NOTE: matching is ALWAYS case-insensitive, by decision: the request-side hostname
 * is folded to lowercase before any pattern runs (Hostnames.fromHostHeader on the HTTP
 * path, the SNI fold in TlsPassthroughRoutes.resolve), so case-sensitive semantics would
 * only make patterns with uppercase literals dead routes. The {@code /pattern/i} flag is
 * accepted and redundant. The stored pattern SOURCE keeps its case (lowercasing regex TEXT
 * would flip class escapes like \S into \s); it is the CLAIM KEY that folds case, in
 * RouteClaims.keyOf, so two case-variant spellings of one pattern are one route.
 */
final class HostnameRegex {

    private HostnameRegex() {}

    static Pattern compile(String hostname) {
        if (hostname == null || hostname.isBlank()) return null;
        String source = hostname.trim();
        if (source.startsWith("/") && source.length() > 1) {
            int lastSlash = source.lastIndexOf('/');
            if (lastSlash > 0) {
                source = source.substring(1, lastSlash);
            }
        }
        return Pattern.compile(source, Pattern.CASE_INSENSITIVE);
    }
}
