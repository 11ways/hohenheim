package be.elevenways.hohenheim.server.spamservice;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The lifecycle state of the managed Spamservice runtime: THE vocabulary the manager
 * writes and every surface reads, with what each state MEANS as facts on the member.
 *
 * AIDEV-NOTE: the tokens are the strings {@link SpamserviceManager.Snapshot#state()} has
 * always carried to the overview page and the attention item, so they are frozen; a
 * reader classifies through {@link #fromToken} and the facts, never by comparing a
 * literal, and an unknown token parses to null, which no fact counts as ready.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public enum SpamserviceState {

    STOPPED("stopped", Phase.AT_REST),
    DISABLED("disabled", Phase.AT_REST),
    STOPPING("stopping", Phase.TRANSITIONAL),
    RESTARTING("restarting", Phase.TRANSITIONAL),
    PREPARING("preparing", Phase.TRANSITIONAL),
    MIGRATING("migrating", Phase.TRANSITIONAL),
    STARTING("starting", Phase.TRANSITIONAL),
    READY("ready", Phase.READY),
    DEGRADED("degraded", Phase.FAILING),
    BACKOFF("backoff", Phase.FAILING),
    FAILED("failed", Phase.FAILING);

    /** What kind of state a member is; the facts below read it. */
    private enum Phase {
        AT_REST, TRANSITIONAL, READY, FAILING
    }

    private final String token;
    private final Phase phase;

    SpamserviceState(@NonNull String token, @NonNull Phase phase) {
        this.token = token;
        this.phase = phase;
    }

    /** The token the snapshot and the admin surfaces carry. */
    public @NonNull String token() {
        return this.token;
    }

    /** Whether the runtime answers and its integration is installed. */
    public boolean ready() {
        return this.phase == Phase.READY;
    }

    /** Whether the manager is on its way somewhere (starting, migrating, stopping, ...). */
    public boolean transitional() {
        return this.phase == Phase.TRANSITIONAL;
    }

    /** Whether the runtime is down or half-up for a reason an operator has to read. */
    public boolean failing() {
        return this.phase == Phase.FAILING;
    }

    /**
     * Whether an ENABLED installation in this state is worth an operator's attention:
     * everything that is not ready, the way the attention item has always judged it.
     */
    public boolean needsAttention() {
        return !ready();
    }

    /** @return the member carrying {@code token}, or null for an unknown one (never ready) */
    public static @Nullable SpamserviceState fromToken(@Nullable String token) {
        for (SpamserviceState state : values()) {
            if (state.token.equals(token)) {
                return state;
            }
        }
        return null;
    }
}
