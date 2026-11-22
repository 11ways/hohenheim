package be.elevenways.hohenheim.server.mcp;

import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.server.cms.BackupTargetParts;
import be.elevenways.hohenheim.source.GitProviderOperations;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.ai.server.mcp.host.McpResponse;
import be.elevenways.zenit.ai.server.mcp.host.McpToolContext;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.delivery.server.DeliveryOutcome;
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

    /** What a program, a console line or an update script printed. */
    static @NonNull McpResponse output(@NonNull McpToolContext context, @Nullable String output) {
        McpResponse response = McpResponse.create();
        if (output == null || output.isBlank()) return response.line("output: none");
        return response.untrusted("output", output);
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
}
