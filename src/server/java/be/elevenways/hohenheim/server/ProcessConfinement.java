package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimSettings;

/**
 * The per-user process cap ({@code prlimit --nproc}) {@link SystemUsers#execution} sets on a host process Hohenheim
 * runs as another system user.
 *
 * AIDEV-NOTE: RLIMIT_NPROC is counted per REAL UID, so it is applied only under a dedicated run-as user, never to the
 * daemon's own identity (see SystemUsers.execution).
 */
public final class ProcessConfinement {

    private ProcessConfinement() {
    }

    /** @return the configured per-user process cap, never below 1 */
    public static int pidsLimit() {
        return HohenheimSettings.positiveOrDefault(HohenheimSettings.Process.PIDS_LIMIT);
    }
}
