package be.elevenways.hohenheim.server.runtime;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.util.Map;

/**
 * The install-step half of the driver seam (the VolumeSnapshotSupport shape): run a
 * template's install script in a ONE-SHOT sibling workload that shares the instance's
 * volumes and network, wait for it to exit, and report the outcome. A driver without
 * it does not implement this, and installs refuse with a named violation.
 */
public interface InstallSupport {

    /** The finished install run: the script's exit code and a bounded output tail. */
    record InstallOutcome(int exitCode, @NonNull String outputTail) {

        public boolean succeeded() {
            return this.exitCode == 0;
        }
    }

    /**
     * Create, run to completion and remove the install workload. Idempotent on retry:
     * a leftover install workload this record owns is replaced; a foreign same-named
     * one is a refusal.
     *
     * @param spec         the INSTANCE's spec (volumes, owner labels, network identity)
     * @param installImage image the script runs in
     * @param script       shell script body ({@code /bin/sh -c})
     * @param env          environment for the run (the instance's variables)
     * @param timeoutMs    hard wall-clock cap; expiry removes the workload and throws
     * @throws IOException when the daemon is unreachable, the image cannot be ensured,
     *                     or the run exceeds the timeout
     */
    @NonNull InstallOutcome runInstall(@NonNull InstanceSpec spec, @NonNull String installImage,
                                       @NonNull String script, @NonNull Map<String, String> env,
                                       long timeoutMs) throws IOException;
}
