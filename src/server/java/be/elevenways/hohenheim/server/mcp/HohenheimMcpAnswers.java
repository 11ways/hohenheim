package be.elevenways.hohenheim.server.mcp;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.instance.InstanceLogOperations;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.instance.InstanceVariableOperations;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.server.cms.BackupTargetParts;
import be.elevenways.hohenheim.server.cms.CertificateParts;
import be.elevenways.hohenheim.source.GitProviderOperations;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.text.TerminalText;
import be.elevenways.zenit.ai.server.mcp.host.McpResponse;
import be.elevenways.zenit.ai.server.mcp.host.McpToolContext;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.delivery.server.DeliveryOutcome;
import be.elevenways.zenit.server.operation.RecordCodes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The answers of Hohenheim's tools whose result is no record: what a power step, a run program, a test or a check
 * found, in the words the panel's toast says it.
 *
 * AIDEV-NOTE: what a workload, a host or an outside service printed is someone else's text, so it leaves inside an
 * untrusted block, never as a line of the answer's own.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class HohenheimMcpAnswers {

    private HohenheimMcpAnswers() {
    }

    /** The app's stored status after a start, stop or restart, and whether it already was so. */
    static @NonNull McpResponse power(@NonNull McpToolContext context,
                                      InstanceOperations.@Nullable PowerResult result) {
        return McpResponse.create().line("status: " + (result == null ? "unknown" : result.toString()));
    }

    /**
     * What a program, a console line or an update script printed, as the words it shows: a console behind a terminal
     * writes escape sequences, which an answer of text never carries.
     */
    static @NonNull McpResponse output(@NonNull McpToolContext context, @Nullable String output) {
        McpResponse response = McpResponse.create();
        String shown = TerminalText.plain(output);
        if (shown.isBlank()) return response.line("output: none");
        return response.untrusted("output", shown);
    }

    /** One program's exit code and what it printed. */
    static @NonNull McpResponse exec(@NonNull McpToolContext context, InstanceOperations.@Nullable ExecRun run) {
        if (run == null) return McpResponse.create().line("exit code: unknown");
        return output(context, run.output()).line("exit code: " + run.exitCode());
    }

    /** Whether a git connection reached its forge, and how many repositories it sees there. */
    static @NonNull McpResponse connection(@NonNull McpToolContext context,
                                           GitProviderOperations.@Nullable ConnectionTest test) {
        McpResponse response = McpResponse.create();
        if (test == null) return response.line("connection: unknown");
        if (test.failure() != null) return response.untrustedLine("connection failed: ", "failure", test.failure());
        return response.line("connection: reached"
            + (test.repositories() == null ? "" : ", " + test.repositories() + " repositories"));
    }

    /** Whether a backup target's health check passed, and why not. */
    static @NonNull McpResponse tested(@NonNull McpToolContext context,
                                       BackupTargetParts.@Nullable TestOutcome outcome) {
        McpResponse response = McpResponse.create();
        if (outcome == null) return response.line("test: unknown");
        if (outcome.failure() != null) return response.untrustedLine("test failed: ", "failure", outcome.failure());
        return response.line("test: passed");
    }

    /** Whether a channel's test message was delivered, only handed off, or not sent, and why. */
    static @NonNull McpResponse delivered(@NonNull McpToolContext context, @Nullable DeliveryOutcome outcome) {
        McpResponse response = McpResponse.create();
        if (outcome == null) return response.line("test message: unknown");
        String state = !outcome.sent() ? "not sent" : outcome.delivered() ? "delivered" : "handed off";
        response.line("test message: " + state);
        if (outcome.reason() == null) return response;
        return response.line("reason: " + context.access().text(outcome.reason()));
    }

    /** What a check or a key exchange told, in the words of the panel's toast. */
    static @NonNull McpResponse told(@NonNull McpToolContext context, @Nullable CmsActionResult result) {
        Microcopy message = result instanceof CmsActionResult.Refresh refresh ? refresh.toastMessage()
            : result instanceof CmsActionResult.Toast toast ? toast.message() : null;
        return said(context, message);
    }

    /** The one sentence a host step answers. */
    static @NonNull McpResponse said(@NonNull McpToolContext context, @Nullable Microcopy message) {
        McpResponse response = McpResponse.create();
        return message == null ? response.line("done") : response.line(context.access().text(message));
    }

    /**
     * What a workload printed or a build or a deploy wrote, as the words a terminal shows (a console's escape
     * sequences gone); the daemon cuts a live log to the lines asked.
     */
    static @NonNull McpResponse log(@NonNull McpToolContext context, @Nullable String log) {
        McpResponse response = McpResponse.create();
        String shown = TerminalText.plain(log);
        if (shown.isBlank()) return response.line("log: empty");
        return response.untrusted("log", shown);
    }

    /** One page of an app's deploys, newest first, each named by its code. */
    static @NonNull McpResponse deploys(@NonNull McpToolContext context,
                                        InstanceLogOperations.@Nullable Deploys deploys) {
        McpResponse response = McpResponse.create();
        if (deploys == null || deploys.deploys().isEmpty()) return response.line("deploys: none");
        StringBuilder rows = new StringBuilder();
        for (InstanceLogOperations.Deploy deploy : deploys.deploys()) {
            List<String> facts = new ArrayList<>(List.of(deploy.kind(), deploy.status()));
            if (!deploy.commit().isEmpty()) facts.add(deploy.commit());
            if (!deploy.trigger().isEmpty()) facts.add(deploy.trigger());
            if (deploy.started() != null) facts.add(context.text().moment(deploy.started()));
            if (!deploy.length().isEmpty()) facts.add(deploy.length());
            rows.append("- ").append(deploy.code()).append(' ').append(String.join(", ", facts));
            if (!deploy.failure().isEmpty()) rows.append(": ").append(deploy.failure());
            rows.append('\n');
        }
        response.untrusted("deploys", rows.toString().stripTrailing());
        if (deploys.pages() > 1) response.line("page: " + deploys.page() + " of " + deploys.pages());
        return response;
    }

    /** An app's own variables: a plain one with its value, a secret only as set or not. */
    static @NonNull McpResponse variables(@NonNull McpToolContext context,
                                          InstanceVariableOperations.@Nullable Variables variables) {
        McpResponse response = McpResponse.create();
        if (variables == null || variables.variables().isEmpty()) return response.line("variables: none");
        StringBuilder rows = new StringBuilder();
        for (InstanceVariableOperations.Variable variable : variables.variables()) {
            rows.append("- ").append(variable.key()).append(variable.kind().isSecret()
                ? (variable.set() ? " (secret, set)" : " (secret, not set)")
                : " = " + Objects.requireNonNullElse(variable.value(), "")).append('\n');
        }
        return response.untrusted("variables", rows.toString().stripTrailing());
    }

    /** The keys a variable write changed, and when the app sees them. */
    static @NonNull McpResponse variableWritten(@NonNull McpToolContext context, @Nullable String keys) {
        return McpResponse.create().untrustedLine("variables: ", "keys", keys)
            .line(context.text().words(HohenheimMicrocopy.INSTANCE_VARIABLE.of("reaches_at_deploy")));
    }

    /** The record a change wrote and the settings it wrote, as its writer says them ({@code S20 changed: name}). */
    static @NonNull McpResponse changed(@NonNull McpToolContext context, @Nullable String said) {
        return McpResponse.create().line(said == null ? "" : said);
    }

    /** A zone's record lines; their names and values are what someone typed, so they come as untrusted text. */
    static @NonNull McpResponse records(@NonNull McpToolContext context, @Nullable String records) {
        return McpResponse.create().untrusted("records", records == null ? "" : records);
    }

    /** What setting a zone's records added, changed, kept and removed, one line per name and type. */
    static @NonNull McpResponse recordsSet(@NonNull McpToolContext context, @Nullable String said) {
        return McpResponse.create().untrusted("records", said == null ? "" : said);
    }

    /**
     * The certificate an order wrote, by its code, and the TXT records a manual DNS order waits for: published with
     * dns_record_set, certificate_renew then finishes it.
     */
    static @NonNull McpResponse ordered(@NonNull McpToolContext context, @Nullable Integer certificateId) {
        McpResponse response = McpResponse.create();
        if (certificateId == null) return response.line("done");
        String key = String.valueOf(certificateId);
        String code = RecordCodes.codes(CertificateModel.MODEL_ID, List.of(key), context.access()).get(key);
        response.line(code == null ? key : code);
        List<String> waiting = CertificateParts.waitingDnsRecords(certificateId);
        if (waiting.isEmpty()) return response;
        response.untrusted("txt_records", String.join("\n", waiting));
        return response.line(context.text().words(HohenheimMicrocopy.CERTIFICATE_REQUEST.of("mcp_publish_then_renew")
            .withArg("code", code == null ? key : code)));
    }
}
