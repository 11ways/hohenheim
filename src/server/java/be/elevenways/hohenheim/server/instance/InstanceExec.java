package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceService.Resolved;
import be.elevenways.hohenheim.server.runtime.ExecSupport;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Run an ARBITRARY command inside an instance's workload -- the surface that ENFORCES the
 * {@code exec} capability, and the reason the Phase 3 gate's "cannot run exec" clause is
 * a real refusal rather than a statement about a feature that does not exist.
 *
 * AIDEV-NOTE: exec is NOT console, and the split is the whole point of this class. The
 * console reaches the workload's OWN primary process over stdin (a game server's chat
 * console); exec starts a NEW program as an arbitrary user, which is root-in-container and
 * therefore a host-escape amplifier. They are separate handlers asking separate
 * capabilities, exactly as docs/instance-tier-plan.md requires. Conflating them was the
 * defect this wave closed.
 *
 * AIDEV-NOTE: there is deliberately NO automation-API lane and NO /manage surface. exec is
 * ADMIN-sensitivity, so a tenant can never delegate it or mint it into an API-key scope;
 * offering it on the tenant projection would make the delegated panel the widest door.
 */
public final class InstanceExec {

    /** Hard wall-clock cap on one exec run. */
    static final long EXEC_TIMEOUT_MS = 2 * 60 * 1000;

    /** Output beyond this is truncated before it reaches a page. */
    private static final int OUTPUT_TAIL_LIMIT = 16 * 1024;

    private final @NonNull InstanceService instances;

    public InstanceExec() {
        this(new InstanceService());
    }

    public InstanceExec(@NonNull InstanceService instances) {
        this.instances = instances;
    }

    /** One completed exec run, as the page renders it. */
    public record Run(@NonNull String command, int exitCode, @NonNull String output) {

        public boolean succeeded() {
            return this.exitCode == 0;
        }
    }

    /**
     * Run {@code command} (a shell-style line, split on whitespace outside quotes) inside
     * the instance's running workload.
     *
     * @throws Violations {@code instance_not_permitted} when the caller lacks {@code exec},
     *         {@code exec_command_required}, {@code exec_unsupported}, {@code exec_failed}
     */
    public @NonNull Run run(int instanceId, @NonNull String command) {
        // The gate, FIRST and on the funnel: nothing about the instance is resolved for a
        // caller who may not exec, so this is not an existence oracle either.
        HohenheimAccess.requireOperationCapability(instanceId, HohenheimAccess.EXEC);

        List<String> argv = splitCommand(command);
        if (argv.isEmpty()) {
            throw Violations.ofField("command", command,
                Microcopy.of("exec_command_required").withFilter("scope", "violations"));
        }

        Resolved resolved = this.instances.resolve(instanceId);
        InstanceOperationGuard.requireOperable(resolved.row());
        if (!(resolved.runtime() instanceof ExecSupport support)) {
            throw refusal("exec_unsupported", resolved.row(), null);
        }

        ExecSupport.ExecOutcome outcome;
        try {
            outcome = support.runExec(resolved.spec(), argv, EXEC_TIMEOUT_MS);
        } catch (IOException e) {
            throw refusal("exec_failed", resolved.row(), e);
        }

        // Recorded on the record, never only in a log line: an arbitrary command inside a
        // tenant workload is exactly the act an audit trail exists for.
        ActivityLog.record(Models.get(InstanceModel.class), instanceId, "exec", command);

        String output = outcome.outputTail();
        if (output.length() > OUTPUT_TAIL_LIMIT) {
            output = output.substring(output.length() - OUTPUT_TAIL_LIMIT);
        }
        return new Run(command, outcome.exitCode(), output);
    }

    /**
     * Split a command line into argv on whitespace, honouring single and double quotes.
     * Deliberately NOT a shell: no expansion, no pipes, no redirection -- an operator who
     * wants a shell asks for one explicitly ({@code /bin/sh -c "..."}).
     */
    static @NonNull List<String> splitCommand(@NonNull String command) {
        List<String> argv = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                started = true;
            } else if (Character.isWhitespace(c)) {
                if (started) {
                    argv.add(current.toString());
                    current.setLength(0);
                    started = false;
                }
            } else {
                current.append(c);
                started = true;
            }
        }
        if (started) {
            argv.add(current.toString());
        }
        return argv;
    }

    private static @NonNull Violations refusal(@NonNull String key, @NonNull Row instance,
                                               IOException cause) {
        return Violations.ofForm(Microcopy.of(key).withFilter("scope", "violations")
            .withArg("name", String.valueOf((Object) instance.get(InstanceModel.NAME)))
            .withArg("reason", cause == null ? "" : String.valueOf(cause.getMessage())));
    }
}
