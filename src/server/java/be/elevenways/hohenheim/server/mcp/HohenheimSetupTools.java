package be.elevenways.hohenheim.server.mcp;

import be.elevenways.hohenheim.model.BuildHookModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.cms.BuildHookParts;
import be.elevenways.hohenheim.server.cms.CertificateOperations;
import be.elevenways.hohenheim.server.cms.CertificateParts;
import be.elevenways.hohenheim.server.cms.DnsOperations;
import be.elevenways.hohenheim.server.cms.DnsPeerParts;
import be.elevenways.hohenheim.server.cms.GameDomainResource;
import be.elevenways.hohenheim.server.cms.InstanceQuotaParts;
import be.elevenways.hohenheim.server.cms.TemplateChildParts;
import be.elevenways.zenit.ai.server.mcp.host.McpCodeTool;
import be.elevenways.zenit.ai.server.mcp.host.McpHostTool;
import be.elevenways.zenit.ai.server.mcp.host.McpResponse;
import be.elevenways.zenit.ai.server.mcp.host.McpToolContext;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.operation.RecordCodes;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Hohenheim's hand-declared tools that set up what apps are made from and reached by: a template's variables and
 * databases, game addresses, build hooks, owners' limits, certificates and DNS zone imports and peers, each over the
 * operations the panel's forms and buttons run; {@link HohenheimTools} offers them beside its own.
 *
 * AIDEV-NOTE: declarations only, as in HohenheimTools: a gate, a check or a writer of a tool here is its operation's.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class HohenheimSetupTools {

    private static final String TEMPLATE = "template";
    private static final String NEGOTIATE_KEY = "negotiate_key";

    private HohenheimSetupTools() {
    }

    /** @return every tool of this class, in the order an assistant is offered them */
    static @NonNull List<McpHostTool> declared() {
        // A template's variable and database have no code: the template's code and their key name them.
        McpHostTool templateVariableSet = McpCodeTool.builder("template_variable_set",
                HohenheimTools.words("mcp_tool_template_variable_set"), HohenheimTools.writes())
            .subject(TEMPLATE, HohenheimTools.words("mcp_code_template_part"))
            .route(TemplateChildParts.VARIABLE_WRITES.set(), HohenheimMcpAnswers::changed)
            .build();
        McpHostTool templateDatabaseSet = McpCodeTool.builder("template_database_set",
                HohenheimTools.words("mcp_tool_template_database_set"), HohenheimTools.writes())
            .subject(TEMPLATE, HohenheimTools.words("mcp_code_template_part"))
            .route(TemplateChildParts.DATABASE_WRITES.set(), HohenheimMcpAnswers::changed)
            .build();
        McpHostTool gameAddressSet = McpCodeTool.builder("game_address_set",
                HohenheimTools.words("mcp_tool_game_address_set"), HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_game_address_set"))
            .creates(GameDomainResource.WRITES.create(), null, List.of("address", "backend_app", "proxy_app"),
                HohenheimTools.words("game_address_thing"))
            .route(GameDomainResource.WRITES.change(), HohenheimMcpAnswers::changed)
            .build();
        // A hook's URL is a credential: it waits on the app's Build hooks tab, never in the answer.
        McpHostTool buildHookSet = McpCodeTool.builder("build_hook_set",
                HohenheimTools.words("mcp_tool_build_hook_set"), HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_build_hook_set"))
            .creates(BuildHookParts.MAKE, HohenheimSetupTools::hookParked, List.of(BuildHookParts.APP,
                BuildHookModel.NAME.getInputName()), HohenheimTools.words("build_hook_thing"))
            .route(BuildHookParts.REPLACE_URL, HohenheimSetupTools::hookParked)
            .build();
        McpHostTool quotaSet = McpCodeTool.single("quota_set", HohenheimTools.words("mcp_tool_quota_set"),
            HohenheimTools.writes(), InstanceQuotaParts.SET, HohenheimMcpAnswers::changed);
        McpHostTool certificateSet = McpCodeTool.builder("certificate_set",
                HohenheimTools.words("mcp_tool_certificate_set"), HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_certificate_set"))
            .creates(CertificateParts.WRITES.create(), null, List.of("name",
                CertificateModel.CERTIFICATE_PEM.getInputName(), CertificateModel.PRIVATE_KEY_PEM.getInputName()),
                HohenheimTools.words("certificate_upload_thing"))
            .route(CertificateParts.WRITES.change(), HohenheimMcpAnswers::changed)
            .build();
        McpHostTool certificateSign = McpCodeTool.builder("certificate_sign",
                HohenheimTools.words("mcp_tool_certificate_sign"), HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_certificate_sign"))
            .creates(CertificateOperations.SIGN, HohenheimSetupTools::signed, List.of(
                CertificateOperations.SIGNING_REQUEST.getInputName(), CertificateOperations.DOMAINS.getInputName()),
                HohenheimTools.words("certificate_signed_thing"))
            .route(CertificateOperations.RESIGN, HohenheimSetupTools::signed)
            .build();
        // A zone is named by its origin as well as its code, as every tool taking a zone takes it.
        McpHostTool zoneImport = McpCodeTool.builder("dns_zone_import",
                HohenheimTools.words("mcp_tool_dns_zone_import"), HohenheimTools.destroys())
            .code(HohenheimTools.words("mcp_code_dns_zone_import"))
            .route(DnsOperations.IMPORT_ZONE, HohenheimMcpAnswers::told)
            .build();
        // negotiate_key makes a new shared transfer key with a Hohenheim peer, on both sides, as the panel's button.
        McpHostTool peerSet = McpCodeTool.builder("dns_peer_set", HohenheimTools.words("mcp_tool_dns_peer_set"),
                HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_dns_peer_set"))
            .flag(NEGOTIATE_KEY, HohenheimTools.words("mcp_negotiate_key"))
            .creates(DnsPeerParts.WRITES.create(), null, "name", HohenheimTools.words("dns_peer_thing"))
            .route(DnsPeerParts.WRITES.change(), HohenheimMcpAnswers::changed, NEGOTIATE_KEY, "false")
            .route(DnsOperations.NEGOTIATE_KEY, HohenheimMcpAnswers::told, NEGOTIATE_KEY, "true")
            .build();
        return List.of(templateVariableSet, templateDatabaseSet, gameAddressSet, buildHookSet, quotaSet,
            certificateSet, certificateSign, zoneImport, peerSet);
    }

    /** A hook made or given a new URL, by its code: the URL waits for the caller's account on its app's tab. */
    private static @NonNull McpResponse hookParked(@NonNull McpToolContext context, @Nullable UUID hook) {
        Row row = hook == null ? null : Models.get(BuildHookModel.class).findById(hook);
        if (row == null) return McpResponse.create().line("done");
        String code = RecordCodes.codeUnjudged(BuildHookModel.MODEL_ID, String.valueOf(hook));
        String app = RecordCodes.codeUnjudged(InstanceModel.MODEL_ID,
            String.valueOf((Object) row.get(BuildHookModel.INSTANCE_ID)));
        return McpResponse.create().line(context.access().text(HohenheimTools.words("mcp_build_hook_parked")
            .withArg("hook", String.valueOf(code)).withArg("app", String.valueOf(app))));
    }

    /** A certificate a signing request ordered, by its code: issued with its expiry, or waiting with the reason. */
    private static @NonNull McpResponse signed(@NonNull McpToolContext context, @Nullable Integer certificate) {
        Row row = certificate == null ? null : Models.get(CertificateModel.class).findById(certificate);
        if (row == null) return McpResponse.create().line("done");
        String code = RecordCodes.codeUnjudged(CertificateModel.MODEL_ID, String.valueOf(certificate));
        Instant expires = row.get(CertificateModel.EXPIRES_ON);
        if (CertificateModel.STATUS_ACTIVE.equals(row.get(CertificateModel.STATUS)) && expires != null) {
            return McpResponse.create().line("certificate " + code + ": issued, expires "
                + context.text().moment(expires));
        }
        return McpResponse.create().line("certificate " + code + ": not issued yet; the renewal sweep tries it again")
            .untrustedLine("reason: ", "reason", row.get(CertificateModel.RENEWAL_ERROR));
    }
}
