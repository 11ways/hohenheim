package be.elevenways.hohenheim.server.runtime;

import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.incus.IncusWebSocket;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.time.Now;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * The exec and console lane of {@link IncusInstanceRuntime}: commands, install and update
 * scripts run INSIDE the workload, and its console.
 *
 * AIDEV-NOTE: split out of IncusInstanceRuntime mechanically; the runtime still implements
 * {@link ExecSupport}, {@link ConsoleStreamSupport}, {@link InstallSupport} and
 * {@link AppUpdateSupport} and delegates here.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
final class IncusGuestLane {

    /** Output tail cap of one install/update run (the durable failure record). */
    private static final int OUTPUT_TAIL_CHARS = 16 * 1024;

    private final @NonNull IncusInstanceRuntime runtime;
    private final @NonNull IncusClient incus;
    private final @NonNull IncusWorkloadType type;

    IncusGuestLane(@NonNull IncusInstanceRuntime runtime, @NonNull IncusClient incus,
                   @NonNull IncusWorkloadType type) {
        this.runtime = runtime;
        this.incus = incus;
        this.type = type;
    }

    ExecSupport.@NonNull ExecOutcome runExec(@NonNull InstanceSpec spec,
                                             @NonNull List<String> command,
                                             ExecSupport.@NonNull ExecOptions options,
                                             long timeoutMs) throws IOException {
        if (!this.runtime.status(spec.handle()).running()) {
            throw new IOException("Instance '" + spec.handle() + "' is not running;"
                + " an exec runs inside the live system");
        }
        if (!spec.guestAgent()) {
            throw new IOException("Instance '" + spec.handle() + "' declares no guest agent"
                + " (guest_agent=false); its image cannot run an exec");
        }
        IncusClient.ExecResult result = this.incus.exec(spec.handle(), command,
            options.env(), spec.runUser(), options.workdir(), timeoutMs);
        return new ExecSupport.ExecOutcome(result.exitCode(), result.output());
    }

    // -- the console ----------------------------------------------------------------

    ConsoleStreamSupport.@NonNull Console openConsole(@NonNull String handle) throws IOException {
        Map<String, Object> operation = this.incus.startConsole(handle);
        Object id = operation.get("id");
        Object metadata = operation.get("metadata");
        String secret = metadata instanceof Map<?, ?> meta
            && meta.get("fds") instanceof Map<?, ?> fds
            && fds.get("0") instanceof String value ? value : null;
        if (id == null || secret == null) {
            throw new IOException("Incus console operation of '" + handle
                + "' carried no websocket secret");
        }
        IncusWebSocket socket = this.incus.operationWebSocket(
            "/1.0/operations/" + id, secret);
        // /dev/console is bidirectional by construction: what we write IS delivered to
        // the workload's console, unlike Docker's discarded attach-without-OpenStdin.
        // AIDEV-NOTE: NOT declared interactive, deliberately. /dev/console of a system
        // container or VM is a terminal, but what answers on it is the guest's own init
        // and getty, and the surface here stays the line console it always was (the form
        // POST); flipping it to keystrokes is a separate decision with its own consumer.
        return new ConsoleStreamSupport.Console(new IncusConsoleStream(socket), true, false);
    }

    @NonNull String consoleTail(@NonNull String handle, int lines) throws IOException {
        String log = this.incus.consoleLog(handle);
        String[] all = log.split("\n", -1);
        if (all.length <= lines) {
            return log;
        }
        return String.join("\n", List.of(all).subList(all.length - lines, all.length));
    }

    /**
     * @throws IOException ALWAYS for a stopped workload: Incus does not report the init
     *         process's exit status, and inventing one (0, -1) would misclassify a
     *         crash as a stop or vice versa -- the console hub's "could not confirm the
     *         exit" lane is the honest landing for this named refusal
     */
    @Nullable Integer exitCode(@NonNull String handle) throws IOException {
        if (this.runtime.status(handle).running()) {
            return null;
        }
        throw new IOException("Incus reports no init exit status for '" + handle
            + "'; the exit outcome cannot be observed on this driver");
    }

    // -- install and app update ------------------------------------------------------

    /**
     * Run the install script INSIDE the instance's own system container: create it if
     * absent (the converge path keeps an existing owned rootfs), start it, exec the
     * script with {@code bash -ec}, and stop it again -- the platform's "installed but
     * not running" state stays true at the daemon.
     *
     * @throws IOException for a separate install image: the rootfs IS the install
     *         target, so "run the install elsewhere" cannot be honoured, only refused
     */
    InstallSupport.@NonNull InstallOutcome runInstall(@NonNull InstanceSpec spec,
                                                      @NonNull String installImage,
                                                      @NonNull String script,
                                                      @NonNull Map<String, String> env,
                                                      long timeoutMs) throws IOException {
        if (!installImage.equals(spec.image())) {
            throw new IOException("The incus driver runs install steps inside the"
                + " instance's own rootfs; a separate install image ('" + installImage
                + "') cannot be honoured. Leave the template's install image empty.");
        }
        // The readiness wait exists to ride out a real guest agent's bring-up; an
        // agent-less image would never answer it, so burning the full
        // execReadyTimeoutMs and reporting a timeout would misreport an absent
        // capability as a broken guest. Refuse by name, and refuse BEFORE create so a
        // workload is never born just to be torn down again.
        if (!spec.guestAgent()) {
            throw new IOException("Instance '" + spec.handle() + "' declares no guest"
                + " agent (guest_agent=false); its image cannot run an exec-driven"
                + " install");
        }
        this.runtime.create(spec);
        boolean started = false;
        try {
            this.runtime.start(spec.handle());
            started = true;
            IncusClient.ExecResult result = execWhenReady(spec.handle(),
                List.of("bash", "-ec", script), env, timeoutMs);
            return new InstallSupport.InstallOutcome(result.exitCode(), tailOf(result.output()));
        } finally {
            if (started) {
                try {
                    this.runtime.stop(spec.handle(), 10);
                } catch (IOException stopFailed) {
                    Blast.log("INCUS: could not stop", spec.handle(),
                        "after its install run:", stopFailed.getMessage());
                }
            }
        }
    }

    /** Run the update script inside the RUNNING workload (services restart in place). */
    InstallSupport.@NonNull InstallOutcome runAppUpdate(@NonNull InstanceSpec spec,
                                                        @NonNull String script,
                                                        @NonNull Map<String, String> env,
                                                        long timeoutMs) throws IOException {
        if (!this.runtime.status(spec.handle()).running()) {
            throw new IOException("Instance '" + spec.handle() + "' is not running;"
                + " the in-place app update runs inside the live system");
        }
        if (!spec.guestAgent()) {
            throw new IOException("Instance '" + spec.handle() + "' declares no guest agent"
                + " (guest_agent=false); its image cannot run an exec-driven app update");
        }
        IncusClient.ExecResult result = this.incus.exec(spec.handle(),
            List.of("bash", "-ec", script), env, timeoutMs);
        return new InstallSupport.InstallOutcome(result.exitCode(), tailOf(result.output()));
    }

    /**
     * Exec with a bring-up retry: the daemon refuses execs while the workload's init
     * (container) or incus agent (VM -- tens of seconds after start) is still coming
     * up, and that refusal must not fail the install. The window is the DECLARED
     * per-flavour one ({@link IncusWorkloadType#execReadyTimeoutMs()}).
     */
    private IncusClient.@NonNull ExecResult execWhenReady(@NonNull String handle,
                                                          @NonNull List<String> command,
                                                          @NonNull Map<String, String> env,
                                                          long timeoutMs) throws IOException {
        long deadline = Now.millis() + this.type.execReadyTimeoutMs();
        while (true) {
            try {
                return this.incus.exec(handle, command, env, timeoutMs);
            } catch (IncusClient.ApiException refused) {
                if (Now.millis() >= deadline) {
                    throw refused;
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw refused;
                }
            }
        }
    }

    private static @NonNull String tailOf(@NonNull String output) {
        if (output.length() <= OUTPUT_TAIL_CHARS) {
            return output;
        }
        return output.substring(output.length() - OUTPUT_TAIL_CHARS);
    }
}
