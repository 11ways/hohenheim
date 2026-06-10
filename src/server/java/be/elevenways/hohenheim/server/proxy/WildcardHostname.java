package be.elevenways.hohenheim.server.proxy;

import java.util.regex.Pattern;

/**
 * Glob-style hostname patterns: {@code ?} matches one non-dot character, {@code *} matches
 * within a single label, and a leading {@code *.} matches one or more whole labels.
 *
 * AIDEV-NOTE: A leading "*." deliberately does NOT match the apex domain (one-or-more labels,
 * not zero-or-more), preserving the semantics of both the Node original and the previous
 * endsWith()-based matcher.
 */
public final class WildcardHostname {

    private WildcardHostname() {
    }

    /** @return whether the pattern contains any glob characters */
    public static boolean isWildcard(String pattern) {
        return pattern.indexOf('*') >= 0 || pattern.indexOf('?') >= 0;
    }

    /**
     * Compile a glob pattern to an anchored, case-insensitive hostname regex.
     * Patterns are compiled once at route load, not per request.
     */
    public static Pattern compile(String glob) {
        StringBuilder regex = new StringBuilder();
        int start = 0;

        if (glob.startsWith("*.")) {
            regex.append("(?:[^.]+\\.)+");
            start = 2;
        }

        for (int i = start; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> regex.append("[^.]*");
                case '?' -> regex.append("[^.]");
                default -> {
                    if ("\\.[]{}()<>+-=!^$|".indexOf(c) >= 0) {
                        regex.append('\\');
                    }
                    regex.append(c);
                }
            }
        }

        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }

    /** Convenience for tests; production routing keeps the compiled {@link Pattern}. */
    public static boolean matches(String hostname, String glob) {
        return compile(glob).matcher(hostname).matches();
    }
}
