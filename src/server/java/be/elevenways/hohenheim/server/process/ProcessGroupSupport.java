package be.elevenways.hohenheim.server.process;

import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.server.SystemUsers;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.server.process.ProcessOutcome;
import be.elevenways.protoblast.server.process.StopOperator;
import be.elevenways.protoblast.server.process.Termination;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Stops a process started in its own session ({@code setsid}) as the whole process group, through a {@code kill}
 * helper running as the group's own user: the daemon cannot signal another uid, and a session member the child
 * started is no descendant the JVM can find once its parent exited.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class ProcessGroupSupport {

    private static final Duration HELPER_TIMEOUT = Duration.ofSeconds(2);

    /** How long a terminated process group gets to exit gracefully after TERM before KILL. */
    public static final long GRACEFUL_TERM_MS = 500;

    private ProcessGroupSupport() {}

    public enum Signal {
        TERM,
        KILL
    }

    public enum SignalResult {
        DELIVERED,
        GROUP_ABSENT,
        FAILED
    }

    public enum GroupState {
        EXISTS,
        ABSENT,
        UNKNOWN
    }

    /** Process-group operations are injectable so failure and race handling can be tested. */
    public interface Operator {
        SignalResult signal(SystemUsers.@Nullable RunAsUser runAs, long processGroupId, Signal signal);
        GroupState probe(SystemUsers.@Nullable RunAsUser runAs, long processGroupId);
    }

    private static final Operator SYSTEM_OPERATOR = new Operator() {
        @Override
        public SignalResult signal(SystemUsers.@Nullable RunAsUser runAs, long processGroupId,
                                   Signal signal) {
            return signalProcessGroup(runAs, processGroupId, signal);
        }

        @Override
        public GroupState probe(SystemUsers.@Nullable RunAsUser runAs, long processGroupId) {
            return probeProcessGroup(runAs, processGroupId);
        }
    };

    /** The stop of a session started as {@code runAs} (the daemon's own user when null). */
    public static @NonNull StopOperator stopOperator(SystemUsers.@Nullable RunAsUser runAs) {
        return stopOperator(runAs, SYSTEM_OPERATOR);
    }

    /**
     * The stop of a session through the supplied operator.
     *
     * AIDEV-NOTE: an absent group is a delivered stop (nothing is left to signal), and an UNKNOWN probe counts as
     * running, so a group whose state cannot be read is killed and then reported as surviving, never as gone.
     */
    public static @NonNull StopOperator stopOperator(SystemUsers.@Nullable RunAsUser runAs,
                                                     @NonNull Operator operator) {
        return new StopOperator() {
            @Override
            public boolean terminate(@NonNull ProcessHandle root) {
                return operator.signal(runAs, root.pid(), Signal.TERM) != SignalResult.FAILED;
            }

            @Override
            public boolean kill(@NonNull ProcessHandle root) {
                return operator.signal(runAs, root.pid(), Signal.KILL) != SignalResult.FAILED;
            }

            @Override
            public boolean running(@NonNull ProcessHandle root) {
                return operator.probe(runAs, root.pid()) != GroupState.ABSENT;
            }
        };
    }

    private static SignalResult signalProcessGroup(SystemUsers.@Nullable RunAsUser runAs,
                                                   long processGroupId, Signal signal) {
        HelperResult result = runHelper(runAs, List.of("/usr/bin/kill", "-" + signal,
            "--", "-" + processGroupId));
        if (result.exitCode() == 0) {
            return SignalResult.DELIVERED;
        }
        if (noSuchProcess(result.output())) {
            return SignalResult.GROUP_ABSENT;
        }
        Blast.log("PROCESS: group signal helper failed for group", processGroupId,
            "signal=" + signal, "exit=" + result.exitCode(), result.output());
        return SignalResult.FAILED;
    }

    private static GroupState probeProcessGroup(SystemUsers.@Nullable RunAsUser runAs,
                                                long processGroupId) {
        HelperResult result = runHelper(runAs, List.of("/usr/bin/kill", "-0", "--",
            "-" + processGroupId));
        if (result.exitCode() == 0) {
            return GroupState.EXISTS;
        }
        if (noSuchProcess(result.output())) {
            return GroupState.ABSENT;
        }
        Blast.log("PROCESS: group probe helper failed for group", processGroupId,
            "exit=" + result.exitCode(), result.output());
        return GroupState.UNKNOWN;
    }

    /**
     * Runs one kill helper as {@code runAs}.
     *
     * AIDEV-NOTE: the caller's interrupt flag is cleared for the helper and restored after it: a stop is often what
     * an interrupt asked for, and a helper that saw the flag would be abandoned before it could deliver the signal.
     */
    private static HelperResult runHelper(SystemUsers.@Nullable RunAsUser runAs,
                                          List<String> command) {
        Map<String, String> environment = SystemUsers.safeEnvironment(
            runAs != null ? runAs.home() : System.getProperty("user.home"));
        boolean interrupted = Thread.interrupted();
        try {
            ProcessOutcome outcome = SystemUsers.execution(runAs, environment, command, false)
                .mergeStderr()
                .collectStdout(8_192)
                .timeout(HELPER_TIMEOUT)
                .stopGrace(Duration.ZERO)
                .run();
            if (outcome.termination() == Termination.TIMED_OUT) {
                return new HelperResult(-1, "helper timed out");
            }
            return new HelperResult(outcome.exitCode(), outcome.stdout().text().trim());
        } catch (RuntimeException e) {
            return new HelperResult(-1, HohenheimViolations.reasonOf(e));
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // AIDEV-NOTE: this English-message parse only works because
    // SystemUsers.safeEnvironment pins LANG=C.UTF-8 for every helper process;
    // loosening that env turns ABSENT detection into FAILED on localized hosts.
    private static boolean noSuchProcess(String output) {
        return output.contains("No such process");
    }

    private record HelperResult(int exitCode, String output) {}
}
