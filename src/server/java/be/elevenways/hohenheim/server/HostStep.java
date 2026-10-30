package be.elevenways.hohenheim.server;

import be.elevenways.protoblast.common.Blast;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * One named step of Hohenheim's own runtime, on a role or always.
 *
 * @param role    the role the step needs, null for every node
 * @param skipped what a node without the role says did not start, null when it says nothing
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
record HostStep(@NonNull String name, HohenheimRoles.@Nullable Role role, @Nullable String skipped,
                @NonNull Runnable work) {

    static @NonNull HostStep always(@NonNull String name, @NonNull Runnable work) {
        return new HostStep(name, null, null, work);
    }

    static @NonNull HostStep on(HohenheimRoles.@NonNull Role role, @NonNull String name, @NonNull Runnable work) {
        return new HostStep(name, role, null, work);
    }

    /** @param skipped what a node without the role logs: a skipped role must never be silent */
    static @NonNull HostStep on(HohenheimRoles.@NonNull Role role, @NonNull String name, @NonNull String skipped,
                                @NonNull Runnable work) {
        return new HostStep(name, role, skipped, work);
    }

    /** Runs the step of a boot; what it throws ends the boot. */
    void start() {
        if (this.enabled()) {
            this.work.run();
        } else if (this.skipped != null) {
            Blast.slog("hohenheim.role_disabled", Map.of(
                "role", this.role.token(),
                "skipped", this.skipped));
        }
    }

    /** Runs the step of a stop, logging instead of propagating whatever it throws: the next step still runs. */
    void stop() {
        if (!this.enabled()) {
            return;
        }
        try {
            this.work.run();
        } catch (RuntimeException | Error failed) {
            Blast.slog("hohenheim.shutdown_step_failed", Map.of(
                "step", this.name,
                "error", String.valueOf(failed)));
        }
    }

    private boolean enabled() {
        return this.role == null || HohenheimRoles.enabled(this.role);
    }
}
