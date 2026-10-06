package be.elevenways.hohenheim.server.sitetype;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A site handler whose upstream is one fixed host and port the proxy may check on its own, without a visitor.
 *
 * AIDEV-NOTE: only operator-configured fixed upstreams implement this. A tenant-owned address is vetted per request
 * against public-only rules, and a unix socket or placeholder target has no single address, so neither is probed.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public interface ProbeableUpstream {

    /** The host the handler forwards to, or null when it has none to probe. */
    @Nullable String probeHost();

    /** The port the handler forwards to. */
    int probePort();
}
