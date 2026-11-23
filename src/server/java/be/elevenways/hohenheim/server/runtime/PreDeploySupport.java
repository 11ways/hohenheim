package be.elevenways.hohenheim.server.runtime;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * The pre-deploy half of the driver seam (the InstallSupport shape): run an app's command before a deploy in a
 * ONE-SHOT sibling of a release that is created but not yet started, made from that release's own spec, wait for it
 * to end, and remove it. A driver without it does not implement this, and the deploy refuses by name.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public interface PreDeploySupport {

    /**
     * The finished run.
     *
     * @param timedOut   true when the time limit ended it; {@code exitCode} is then meaningless
     * @param outputTail the last lines the command printed, read before the container was removed
     */
    record Outcome(boolean timedOut, int exitCode, @NonNull String outputTail) {
    }

    /** Joins the one-shot to whatever its release joins before start (database link networks); may refuse. */
    @FunctionalInterface
    interface BeforeStart {

        void run(@NonNull String oneShotHandle) throws IOException;
    }

    /**
     * Create, run to its end and remove the one-shot.
     *
     * AIDEV-NOTE: the container is the release's own spec (image, user, workdir, environment, volumes, tmpfs, limits,
     * hardening, owner labels, network) with the command replaced and its port publications and health check
     * removed, so a migration runs exactly as the app will, never as root on a shared volume or without the tmpfs a
     * read-only root needs. Ended by force-removal when its time is up: no SIGTERM.
     *
     * @param spec      the RELEASE's spec
     * @param argv      the command, split on whitespace, no shell
     * @param env       the environment for the run (the release's variables)
     * @param timeoutMs the time limit
     * @param tailLines how many of the last output lines to keep
     * @throws IOException when the daemon is unreachable, refuses the container, or the hook refuses
     */
    @NonNull Outcome runPreDeploy(@NonNull InstanceSpec spec, @NonNull List<String> argv,
                                  @NonNull Map<String, String> env, long timeoutMs, int tailLines,
                                  @NonNull BeforeStart beforeStart) throws IOException;
}
