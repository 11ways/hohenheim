package be.elevenways.hohenheim.server.process;

import be.elevenways.hohenheim.server.SystemUsers;
import be.elevenways.protoblast.common.Blast;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Provides output draining and best-effort termination for processes started in a new session. */
public final class ProcessGroupSupport {

    private static final long HELPER_TIMEOUT_SECONDS = 2;
    private static final long FORCE_WAIT_MILLIS = 2_000;

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

    /** Terminates ordinary descendants that remain in the process's original session group. */
    public static TerminationResult terminate(Process process, SystemUsers.@Nullable RunAsUser runAs,
                                              long gracefulWaitMillis) {
        return terminate(process, runAs, gracefulWaitMillis, SYSTEM_OPERATOR);
    }

    /** Terminates a process group through the supplied operator. */
    public static TerminationResult terminate(Process process, SystemUsers.@Nullable RunAsUser runAs,
                                              long gracefulWaitMillis, Operator operator) {
        boolean interrupted = Thread.interrupted();
        long processGroupId = process.pid();
        try {
            SignalResult termResult = operator.signal(runAs, processGroupId, Signal.TERM);
            if (termResult == SignalResult.FAILED) {
                process.destroy();
            }
            interrupted |= waitFor(process, gracefulWaitMillis);

            GroupState stateAfterTerm = operator.probe(runAs, processGroupId);
            SignalResult killResult = SignalResult.GROUP_ABSENT;
            if (stateAfterTerm == GroupState.EXISTS) {
                killResult = operator.signal(runAs, processGroupId, Signal.KILL);
                if (killResult == SignalResult.FAILED) {
                    process.destroyForcibly();
                }
                interrupted |= waitFor(process, FORCE_WAIT_MILLIS);
            }

            GroupState finalGroupState = stateAfterTerm == GroupState.ABSENT
                ? GroupState.ABSENT
                : operator.probe(runAs, processGroupId);
            return new TerminationResult(processGroupId, termResult, stateAfterTerm, killResult,
                finalGroupState, process.isAlive());
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static boolean waitFor(Process process, long timeoutMillis) {
        try {
            process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            return false;
        } catch (InterruptedException e) {
            return true;
        }
    }

    /**
     * Signal ONE pid rather than a process group, through the workload's own identity.
     *
     * AIDEV-NOTE: the reaper's lane. A controller that is not root cannot signal another
     * uid's process at all, and an orphan has no Process object to destroy() -- but the
     * site's own uid may always signal its own processes, and sudo-to-that-uid is a grant
     * this tier already needs to spawn at all. Nothing new is asked for.
     */
    public static SignalResult signalPid(SystemUsers.@Nullable RunAsUser runAs, long pid,
                                         Signal signal) {
        HelperResult result = runHelper(runAs, List.of("/usr/bin/kill", "-" + signal,
            "--", String.valueOf(pid)));
        if (result.exitCode() == 0) {
            return SignalResult.DELIVERED;
        }
        if (noSuchProcess(result.output())) {
            return SignalResult.GROUP_ABSENT;
        }
        Blast.log("PROCESS: pid signal helper failed for pid", pid,
            "signal=" + signal, "exit=" + result.exitCode(), result.output());
        return SignalResult.FAILED;
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

    private static HelperResult runHelper(SystemUsers.@Nullable RunAsUser runAs,
                                          List<String> command) {
        Map<String, String> environment = SystemUsers.safeEnvironment(
            runAs != null ? runAs.home() : System.getProperty("user.home"));
        Process process = null;
        OutputCapture capture = null;
        try {
            process = SystemUsers.executionBuilder(runAs, environment, command, false)
                .redirectErrorStream(true)
                .start();
            capture = drain(process.getInputStream(), "process-group-helper-" + process.pid(), 8_192);
            if (!process.waitFor(HELPER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                capture.finish();
                return new HelperResult(-1, "helper timed out");
            }
            capture.finish();
            return new HelperResult(process.exitValue(), capture.output().trim());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            if (capture != null) {
                capture.finish();
            }
            return new HelperResult(-1, "helper interrupted");
        } catch (Exception e) {
            return new HelperResult(-1,
                e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    // AIDEV-NOTE: this English-message parse only works because
    // SystemUsers.safeEnvironment pins LANG=C.UTF-8 for every helper process;
    // loosening that env turns ABSENT detection into FAILED on localized hosts.
    private static boolean noSuchProcess(String output) {
        return output.contains("No such process");
    }

    /** Starts a platform thread that drains an input stream while retaining at most maxChars. */
    public static OutputCapture drain(InputStream input, String threadName, int maxChars) {
        return new OutputCapture(input, threadName, maxChars);
    }

    public record TerminationResult(long processGroupId, SignalResult termResult,
                                    GroupState stateAfterTerm, SignalResult killResult,
                                    GroupState finalGroupState, boolean leaderAlive) {

        public boolean successful() {
            return finalGroupState == GroupState.ABSENT && !leaderAlive;
        }
    }

    private record HelperResult(int exitCode, String output) {}

    /** An asynchronously drained, bounded process-output capture. */
    public static final class OutputCapture {

        private final InputStream input;
        private final int maxChars;
        private final StringBuilder output = new StringBuilder();
        private final Thread thread;

        private OutputCapture(InputStream input, String threadName, int maxChars) {
            this.input = input;
            this.maxChars = maxChars;
            this.thread = Thread.ofPlatform().daemon().name(threadName).start(this::read);
        }

        private void read() {
            try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                char[] buffer = new char[8_192];
                int count;
                while ((count = reader.read(buffer)) != -1) {
                    synchronized (output) {
                        int remaining = maxChars - output.length();
                        if (remaining > 0) {
                            output.append(buffer, 0, Math.min(count, remaining));
                        }
                    }
                }
            } catch (IOException ignored) {
                // Closing a pipe after the process has exited is normal cleanup.
            }
        }

        /** Waits briefly for EOF, then closes a pipe retained by an escaped descendant. */
        public void finish() {
            boolean interrupted = Thread.interrupted();
            try {
                try {
                    thread.join(2_000);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
                if (thread.isAlive()) {
                    try {
                        input.close();
                    } catch (IOException ignored) {
                    }
                    try {
                        thread.join(500);
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        public String output() {
            synchronized (output) {
                return output.toString();
            }
        }
    }
}
