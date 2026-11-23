package be.elevenways.hohenheim.server;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.net.InetAddress;

/**
 * The host name of the machine this Hohenheim runs on, read once per process.
 *
 * AIDEV-NOTE: THE one read of the machine's own name: a DNS peer's federation name and a linked server's own name
 * both fall back to it. A machine that cannot name itself reads as {@link #FALLBACK}, so neither is ever blank.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class MachineName {

    /** The name of a machine that cannot name itself. */
    static final String FALLBACK = "hohenheim";

    private MachineName() {
    }

    /** @return the machine's host name as the system reports it, or {@link #FALLBACK} */
    public static @NonNull String hostName() {
        return Read.NAME;
    }

    /** @return the host name up to its first dot ("robbedoes" of "robbedoes.example.com"); an address stays whole */
    public static @NonNull String firstLabel() {
        String name = hostName();
        boolean address = name.indexOf(':') >= 0 || name.chars().allMatch(c -> Character.isDigit(c) || c == '.');
        int dot = name.indexOf('.');
        return address || dot <= 0 ? name : name.substring(0, dot);
    }

    /** The read, done at first use: a resolver that is slow to answer is waited for once. */
    private static final class Read {

        static final String NAME = read();

        private static @NonNull String read() {
            try {
                String hostname = InetAddress.getLocalHost().getHostName();
                if (hostname != null && !hostname.isBlank()) {
                    return hostname.trim();
                }
            } catch (Exception unresolvable) {
                // A machine that cannot name itself still gets a name.
            }
            return FALLBACK;
        }
    }
}
