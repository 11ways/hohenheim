package be.elevenways.hohenheim.server.application;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.instance.InstanceKindFields;
import be.elevenways.hohenheim.server.docker.ContainerSettings;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.runtime.PreDeploySupport;
import be.elevenways.protoblast.common.time.DurationText;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static be.elevenways.hohenheim.RawValues.trimmed;

/**
 * An app's command before each forward deploy, run in a one-shot container of the created candidate before it starts.
 *
 * AIDEV-NOTE: every line goes through {@code ReleaseEngine.step}, so the step log's redactor masks a secret the
 * command prints. The output appears when the command ends, not line by line: the driver seam is single-shot.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
final class PreDeployCommand {

    /** How many of the command's last output lines the step log keeps. */
    static final int TAIL_LINES = 200;

    /** How many bytes of that output the step log keeps at most, the newest. */
    static final int TAIL_BYTES = 16_384;

    private PreDeployCommand() {
    }

    /**
     * Run the candidate's stored command, if it stores one.
     *
     * @throws Violations {@code release_pre_deploy_failed} for a non-zero exit, {@code release_pre_deploy_timed_out}
     *         when its time ran out; the caller's catch then refuses the candidate
     */
    static void run(@NonNull Row op, int candidateId, @NonNull Map<String, Object> desired) {
        List<String> argv = ContainerSettings.commandLine(desired, InstanceKindFields.PRE_DEPLOY_COMMAND);
        if (argv == null) {
            return;
        }
        int limit = ApplicationKind.preDeployTimeoutSeconds(desired);
        ReleaseEngine.step(op, "pre-deploy command: " + trimmed(desired.get(InstanceKindFields.PRE_DEPLOY_COMMAND))
            + " (limit " + DurationText.uptime(limit) + ")");
        Now.Stopwatch took = Now.stopwatch();
        PreDeploySupport.Outcome outcome = new InstanceService().runPreDeploy(candidateId, argv, limit * 1000L,
            TAIL_LINES);
        String output = indented(outcome.outputTail());
        if (outcome.timedOut()) {
            ReleaseEngine.step(op, "pre-deploy command still running after " + DurationText.uptime(limit)
                + "; ended" + output);
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("release_pre_deploy_timed_out")
                .withArg("limit", Duration.ofSeconds(limit)));
        }
        ReleaseEngine.step(op, "pre-deploy command exited " + outcome.exitCode() + " after "
            + DurationText.uptime(Duration.ofNanos(took.elapsedNanos()).toSeconds()) + output);
        if (outcome.exitCode() != 0) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("release_pre_deploy_failed")
                .withArg("exit", outcome.exitCode()));
        }
    }

    /** The output's newest {@link #TAIL_BYTES} bytes, each line indented on its own line under the step. */
    private static @NonNull String indented(@NonNull String tail) {
        byte[] bytes = tail.getBytes(StandardCharsets.UTF_8);
        String kept = bytes.length <= TAIL_BYTES ? tail
            : new String(bytes, bytes.length - TAIL_BYTES, TAIL_BYTES, StandardCharsets.UTF_8);
        StringBuilder lines = new StringBuilder();
        for (String line : kept.strip().split("\\R")) {
            if (!line.isBlank()) {
                lines.append("\n  ").append(line.stripTrailing());
            }
        }
        return lines.toString();
    }
}
