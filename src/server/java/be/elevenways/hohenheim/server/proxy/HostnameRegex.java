package be.elevenways.hohenheim.server.proxy;

import java.util.regex.Pattern;

/** Compiles the shared plain or slash-delimited hostname-regex syntax. */
final class HostnameRegex {

    private HostnameRegex() {}

    static Pattern compile(String hostname) {
        if (hostname == null || hostname.isBlank()) return null;
        String source = hostname.trim();
        int flags = Pattern.CASE_INSENSITIVE;
        if (source.startsWith("/") && source.length() > 1) {
            int lastSlash = source.lastIndexOf('/');
            if (lastSlash > 0) {
                String flagSection = source.substring(lastSlash + 1);
                source = source.substring(1, lastSlash);
                if (flagSection.contains("i")) flags |= Pattern.CASE_INSENSITIVE;
            }
        }
        return Pattern.compile(source, flags);
    }
}
