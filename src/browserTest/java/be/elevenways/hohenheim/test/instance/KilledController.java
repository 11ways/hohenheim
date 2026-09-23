package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.server.instance.InstanceMigrations;
import be.elevenways.hohenheim.server.instance.InstanceService;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Daemon-free migrations whose controller dies at one checkpoint, for the crash-window tests.
 *
 * AIDEV-NOTE: the kill is an Error on purpose. The migration's failure net catches
 * IOException AND RuntimeException (a named refusal can land mid-window and must be settled
 * in-process), so an unchecked exception is now a FAILURE the migration settles itself, not a
 * kill. A dead process escapes every catch; an Error is the only throwable that still does.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class KilledController {

    /** The simulated death of the controller process at a named checkpoint. */
    static final class Killed extends Error {
        Killed(@NonNull String step) {
            super("controller killed at " + step);
        }
    }

    private KilledController() {
    }

    /** Migrations that die at {@code crashStep}, with the capacity probe stubbed. */
    static @NonNull InstanceMigrations migrationsCrashingAt(@NonNull String crashStep) {
        return new InstanceMigrations(new InstanceService(), step -> {
            if (crashStep.equals(step)) {
                throw new Killed(step);
            }
        }, (serverId, bytes) -> {});
    }
}
