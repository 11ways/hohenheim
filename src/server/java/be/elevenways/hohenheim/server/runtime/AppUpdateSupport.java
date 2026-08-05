package be.elevenways.hohenheim.server.runtime;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.util.Map;

/**
 * The in-place app-update half of the driver seam (the community-scripts
 * {@code update_script()} capability): run a template's update script INSIDE the
 * instance's own RUNNING workload and report the outcome. A driver without it does not
 * implement this, and updates refuse with a named violation.
 */
public interface AppUpdateSupport {

    /**
     * Run the update script to completion inside the running workload.
     *
     * @param env environment for the run (the instance's variables + the function library)
     * @throws IOException when the workload is not running, the daemon is unreachable,
     *                     or the run exceeds the timeout
     */
    InstallSupport.@NonNull InstallOutcome runAppUpdate(@NonNull InstanceSpec spec,
                                                       @NonNull String script,
                                                       @NonNull Map<String, String> env,
                                                       long timeoutMs) throws IOException;
}
