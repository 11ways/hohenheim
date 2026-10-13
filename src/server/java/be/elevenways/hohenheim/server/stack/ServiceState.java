package be.elevenways.hohenheim.server.stack;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.ui.BadgeVariant;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * THE live state of one stack service: what the daemon reports for its workload, as the services page shows it and
 * the stack's status aggregates it. Its token is the {@code stack_state} copy key.
 *
 * AIDEV-NOTE: the states were strings spelled three times (the runtime's token switch, the aggregate's good set and
 * the services page's badge switch, whose default arm painted an unknown state neutral). Each look is now a fact on
 * the member, so a new state cannot render without one; ORPHANED is a workload the stack owns whose service left its
 * records, never good, so a stack holding one reads degraded.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public enum ServiceState {

    /** Running, and its declared health check passes. */
    HEALTHY("healthy", BadgeVariant.SUCCESS),

    /** Running, with no health check declared. */
    RUNNING("running", BadgeVariant.SUCCESS),

    /** Running, inside its health check's start period: good, so a warming check never flaps the stack. */
    STARTING("starting", BadgeVariant.WARNING),

    /** Running, and its declared health check fails. */
    UNHEALTHY("unhealthy", BadgeVariant.DESTRUCTIVE),

    /** Its workload exists and is stopped. */
    STOPPED("stopped", BadgeVariant.SECONDARY),

    /** The host did not answer, so nobody knows. */
    UNREACHABLE("unreachable", BadgeVariant.OUTLINE),

    /** No workload exists for the service. */
    MISSING("missing", BadgeVariant.OUTLINE),

    /** A workload the stack owns whose service left the records. */
    ORPHANED("orphaned", BadgeVariant.OUTLINE);

    private final String token;
    private final BadgeVariant variant;

    ServiceState(@NonNull String token, @NonNull BadgeVariant variant) {
        this.token = token;
        this.variant = variant;
    }

    /** @return the state's stable spelling, its {@code stack_state} copy key */
    public @NonNull String token() {
        return this.token;
    }

    /** @return the badge the services page paints the state with */
    public @NonNull BadgeVariant variant() {
        return this.variant;
    }

    /** @return the state in the reader's words */
    public @NonNull Microcopy label() {
        return HohenheimMicrocopy.STACK_STATE.of(this.token);
    }

    /** @return whether the service counts as serving when the stack's status is aggregated */
    public boolean good() {
        return switch (this) {
            case HEALTHY, RUNNING, STARTING -> true;
            case UNHEALTHY, STOPPED, UNREACHABLE, MISSING, ORPHANED -> false;
        };
    }

    /** @return the state of a declared service, from the typed status its driver reports */
    public static @NonNull ServiceState of(@NonNull InstanceStatus status) {
        return switch (status.state()) {
            case ABSENT -> MISSING;
            case UNREACHABLE -> UNREACHABLE;
            case STOPPED -> STOPPED;
            case RUNNING -> switch (status.health()) {
                case HEALTHY -> HEALTHY;
                case UNHEALTHY -> UNHEALTHY;
                case STARTING -> STARTING;
                case NONE -> RUNNING;
            };
        };
    }
}
