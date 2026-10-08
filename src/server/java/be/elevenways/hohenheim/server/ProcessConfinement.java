package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.zenit.common.Zenit;

/**
 * The per-user process cap ({@code prlimit --nproc}) {@link SystemUsers#execution} sets on a host process Hohenheim
 * runs as another system user.
 *
 * AIDEV-NOTE: RLIMIT_NPROC is counted per REAL UID, so it is applied only under a dedicated run-as user, never to the
 * daemon's own identity (see SystemUsers.execution).
 */
public final class ProcessConfinement {

    /** Fallback per-user process cap when the setting is unreadable; also its default. */
    public static final int DEFAULT_PIDS_LIMIT = 512;

    private ProcessConfinement() {
    }

    /** @return the configured per-user process cap, never below 1 */
    public static int pidsLimit() {
        Integer configured = Zenit.SETTINGS_VALUES.getValue(
            HohenheimSettings.Process.PIDS_LIMIT);
        return configured != null && configured > 0 ? configured : DEFAULT_PIDS_LIMIT;
    }
}
