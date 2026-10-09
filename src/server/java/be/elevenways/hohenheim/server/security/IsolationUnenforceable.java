package be.elevenways.hohenheim.server.security;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;

/**
 * A workload refused because its host cannot enforce per-workload network policy (security.nftables_enabled is off).
 *
 * AIDEV-NOTE: typed so the start lane records its cause as a host fact (HohenheimActivityAction
 * WORKLOAD_ISOLATION_REFUSED) and the dashboard folds the workload under its host's one item, never by reading the
 * message. The message stays the technical English the log and the tests key on.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class IsolationUnenforceable extends IOException {

    /** @param message the refusal in its technical words */
    public IsolationUnenforceable(@NonNull String message) {
        super(message);
    }

    /** @return whether this failure, or one it was caused by, is this refusal */
    public static boolean in(@Nullable Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof IsolationUnenforceable) {
                return true;
            }
            if (cause.getCause() == cause) {
                return false;
            }
        }
        return false;
    }
}
