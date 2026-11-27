package be.elevenways.hohenheim.server.mcp;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.instance.InstanceLogOperations;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cms.AddressWrites;
import be.elevenways.hohenheim.server.cms.BackupTargetParts;
import be.elevenways.hohenheim.server.cms.CertificateParts;
import be.elevenways.hohenheim.server.cms.GitConnectionWrites;
import be.elevenways.hohenheim.server.cms.SitePortMoves;
import be.elevenways.hohenheim.server.cms.SiteRegistrationTokens;
import be.elevenways.hohenheim.site.SitePortsMoved;
import be.elevenways.hohenheim.source.GitProviderOperations;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.text.TerminalText;
import be.elevenways.zenit.ai.server.mcp.host.McpResponse;
import be.elevenways.zenit.ai.server.mcp.host.McpToolContext;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.common.operation.AssistantNoun;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.delivery.server.DeliveryOutcome;
import be.elevenways.zenit.server.operation.RecordCodes;
import be.elevenways.zenit.server.security.SshPublicKey;
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

    /** The keys a variable write changed, and when the app sees them. */
    static @NonNull McpResponse variableWritten(@NonNull McpToolContext context, @Nullable String keys) {
        return McpResponse.create().untrustedLine("variables: ", "keys", keys)
            .line(context.text().words(HohenheimMicrocopy.INSTANCE_VARIABLE.of("reaches_at_deploy")));
    }

    /** The record a change wrote and the settings it wrote, as its writer says them ({@code S20 changed: name}). */
    static @NonNull McpResponse changed(@NonNull McpToolContext context, @Nullable String said) {
        return McpResponse.create().line(said == null ? "" : said);
    }

    /**
     * A new git connection's code, and the public key of its SSH key: no secret, and what the person adds to the
     * repository as a read-only deploy key.
     */
    static @NonNull McpResponse connectionMade(@NonNull McpToolContext context, @Nullable Integer key) {
        McpResponse response = made(context, GitProviderModel.MODEL_ID, key);
        Row connection = key == null ? null : Models.get(GitProviderModel.class).findById(key);
        SshPublicKey publicKey = connection == null ? null : GitConnectionWrites.publicKeyOf(connection);
        if (publicKey != null) response.line(GitConnectionWrites.PUBLIC_KEY + ": " + publicKey.fileLine());
        return response;
    }

    /**
     * A new dev namespace's code, and, when Hohenheim made its registration token, who is shown it once, where and
     * until when; never the token.
     */
    static @NonNull McpResponse devNamespaceMade(@NonNull McpToolContext context, @Nullable Integer key) {
        McpResponse response = made(context, SiteModel.MODEL_ID, key);
        Microcopy token = key == null ? null : SiteRegistrationTokens.waitingFor(context.access(), key);
        if (token != null) response.line(context.access().text(token));
        return response;
    }

    /**
     * A new address's code, and what HTTPS gives it now in plain words: working, waiting to be issued, or none yet and
     * why, with the call that orders one.
     */
    static @NonNull McpResponse addressMade(@NonNull McpToolContext context, @Nullable Integer key) {
        McpResponse response = made(context, SiteDomainModel.MODEL_ID, key);
        Row address = key == null ? null : Models.get(SiteDomainModel.class).findById(key);
        if (address == null) return response;
        String code = RecordCodes.code(SiteDomainModel.MODEL_ID, String.valueOf(key), context.access());
        return response.line(context.text().words(AddressWrites.httpsSaid(address,
            Objects.requireNonNullElse(code, String.valueOf(key)), context.access())));
    }

    /** @return an answer naming a new record by its code and kind ({@code GC3 (git connection)}); empty for none */
    private static @NonNull McpResponse made(@NonNull McpToolContext context, @NonNull Identifier model,
                                             @Nullable Integer key) {
        McpResponse response = McpResponse.create();
        if (key == null) return response;
        String code = RecordCodes.codes(model, List.of(String.valueOf(key)), context.access())
            .get(String.valueOf(key));
        return response.line(Objects.requireNonNullElse(code, "?") + " (" + AssistantNoun.of(model) + ")");
    }

    /** Which ports moved, from which stream (now in the trash) into which, by their codes. */
    static @NonNull McpResponse portsMoved(@NonNull McpToolContext context, @Nullable SitePortsMoved moved) {
        if (moved == null) return McpResponse.create().line("ports moved: unknown");
        return McpResponse.create().line(context.access().text(SitePortMoves.said(moved)));
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
