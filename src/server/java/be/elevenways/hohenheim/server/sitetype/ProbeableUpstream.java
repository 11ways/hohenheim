package be.elevenways.hohenheim.server.sitetype;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * A site handler whose upstreams are fixed hosts and ports the proxy may check on its own, without a visitor.
 *
 * AIDEV-NOTE: only operator-configured fixed upstreams implement this. A tenant-owned address is vetted per request
 * against public-only rules, and a unix socket or placeholder target has no single address, so neither is probed.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public interface ProbeableUpstream {

    /** One address a handler forwards to. */
    record Target(@NonNull String host, int port) {}

    /** @return every address the handler forwards to, empty when it has none to probe */
    @NonNull List<Target> probeTargets();

    /** Hears whether one of {@link #probeTargets} accepted the dispatcher's probe, so a handler can pass it over. */
    default void probed(@NonNull Target target, boolean answered) {
    }
}
