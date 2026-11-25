package be.elevenways.hohenheim.server.mcp;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.game.GameDomainOperations;
import be.elevenways.hohenheim.instance.InstanceAttachmentOperations;
import be.elevenways.hohenheim.instance.InstanceBackupOperations;
import be.elevenways.hohenheim.instance.InstanceLogOperations;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.instance.InstanceSnapshotOperations;
import be.elevenways.hohenheim.instance.InstanceVariableOperations;
import be.elevenways.hohenheim.instance.VolumeOperations;
import be.elevenways.hohenheim.preview.PreviewOperations;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.server.cms.AccessRuleParts;
import be.elevenways.hohenheim.server.cms.AddressWrites;
import be.elevenways.hohenheim.server.cms.AppKindWrites;
import be.elevenways.hohenheim.server.cms.BackupTargetParts;
import be.elevenways.hohenheim.server.cms.BuildHookParts;
import be.elevenways.hohenheim.server.cms.CertificateOperations;
import be.elevenways.hohenheim.server.cms.CertificateParts;
import be.elevenways.hohenheim.server.cms.DatabaseDumpParts;
import be.elevenways.hohenheim.server.cms.DatabaseParts;
import be.elevenways.hohenheim.server.cms.DnsOperations;
import be.elevenways.hohenheim.server.cms.DnsRecordSets;
import be.elevenways.hohenheim.server.cms.DnsZoneWrites;
import be.elevenways.hohenheim.server.cms.DomainParts;
import be.elevenways.hohenheim.server.cms.EnvironmentParts;
import be.elevenways.hohenheim.server.cms.GitConnectionWrites;
import be.elevenways.hohenheim.server.cms.KindSettingsWrites;
import be.elevenways.hohenheim.server.cms.HostParts;
import be.elevenways.hohenheim.server.cms.InstanceTemplateParts;
import be.elevenways.hohenheim.server.cms.ProjectParts;
import be.elevenways.hohenheim.server.cms.ReconcileFindingParts;
import be.elevenways.hohenheim.server.cms.ReleasedClaimParts;
import be.elevenways.hohenheim.server.cms.RuntimeImageParts;
import be.elevenways.hohenheim.server.cms.SiteKindWrites;
import be.elevenways.hohenheim.server.cms.SitePortMoves;
import be.elevenways.hohenheim.server.cms.SiteWrites;
import be.elevenways.hohenheim.server.cms.StackOperations;
import be.elevenways.hohenheim.site.SiteOperations;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.ai.server.mcp.host.McpCodeTool;
import be.elevenways.zenit.ai.server.mcp.host.McpHostTool;
import be.elevenways.zenit.ai.server.mcp.host.McpToolCollection;
import be.elevenways.zenit.auth.server.mcp.AuthTools;
import be.elevenways.zenit.common.operation.Operation;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Hohenheim's hand-declared tools: one act over every kind of record it applies to, routed by the code's kind
 * ({@link McpCodeTool}), and each app kind's create-or-change tool, each over the operations the panel runs.
 *
 * AIDEV-NOTE: a tool here names its operations and its words, nothing else: a gate, a check or a writer of its own is
 * the operation's. Every route's operation is placed by no other tool (McpCoverage.placedTwice pins that).
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class HohenheimTools extends McpToolCollection {

    private static final String WITH_DATA = "with_data";
    private static final String FORCE = "force";
    private static final String PERMANENT = "permanent";
    private static final String ACTION = "action";
    private static final String CONSOLE = "console";
    private static final String OWNER = "owner";
    private static final String DNS_ZONE = "dns_zone";
    private static final String ADDRESS = "address";

    private HohenheimTools() {
        for (McpHostTool tool : declared()) this.addTool(tool);
        for (McpHostTool tool : HohenheimDataTools.declared()) this.addTool(tool);
        for (McpHostTool tool : HohenheimHostTools.declared()) this.addTool(tool);
        for (McpHostTool tool : HohenheimCatalogTools.declared()) this.addTool(tool);
    }

    /** @return the collection of every hand-declared tool */
    public static @NonNull HohenheimTools build() {
        return new HohenheimTools();
    }

    static @NonNull Microcopy words(@NonNull String key) {
        return HohenheimMicrocopy.HOHENHEIM.of(key);
    }

    private static @NonNull List<McpHostTool> declared() {
        McpHostTool logs = McpCodeTool.builder("logs", words("mcp_tool_logs"), McpHostTool.Annotations.ofReadOnly())
            .code(words("mcp_code_logs"))
            .route(InstanceLogOperations.READ_LOG, HohenheimMcpAnswers::log)
            .route(InstanceLogOperations.READ_BUILD_LOG, HohenheimMcpAnswers::log)
            .route(InstanceLogOperations.READ_DEPLOY_LOG, HohenheimMcpAnswers::log)
            .build();
        McpCodeTool.Builder deletes = McpCodeTool.builder("delete", words("mcp_tool_delete"), destroys())
            .code(words("mcp_code_delete"))
            .flag(WITH_DATA, words("mcp_with_data"))
            .flag(FORCE, words("mcp_force"))
            .flag(PERMANENT, words("mcp_permanent"))
            .route(InstanceOperations.DELETE, null, WITH_DATA, "false")
            .route(InstanceOperations.DESTROY_WITH_DATA, null, WITH_DATA, "true")
            .route(InstanceBackupOperations.DELETE_BACKUP, null)
            .route(InstanceSnapshotOperations.DELETE, null)
            .route(InstanceAttachmentOperations.DETACH_DEVICE, null)
            .route(InstanceAttachmentOperations.DELETE_DATABASE_LINK, null)
            .route(VolumeOperations.DESTROY, null)
            .route(GameDomainOperations.DELETE, null)
            .route(PreviewOperations.EXPIRE, HohenheimMcpAnswers::output)
            // A site goes to the trash; one already there is removed for good, as the panel's trash does.
            .route(SiteWrites.DELETE, null, PERMANENT, "false")
            .route(SiteWrites.PURGE, null, PERMANENT, "true")
            .route(AccessRuleParts.DELETE, null)
            .route(BuildHookParts.REVOKE, null)
            .route(DnsOperations.DELETE_ZONE, null)
            .route(DnsOperations.DELETE_PEER, null)
            .route(DatabaseParts.DELETE, null, FORCE, "false")
            .route(DatabaseParts.FORCE_DELETE, null, FORCE, "true")
            .route(DatabaseParts.DELETE_ENGINE, null, FORCE, "false")
            .route(DatabaseParts.FORCE_DELETE_ENGINE, null, FORCE, "true")
            .route(DatabaseDumpParts.DELETE, null)
            .route(BackupTargetParts.DELETE, null)
            .route(StackOperations.DELETE_STACK, null)
            .route(StackOperations.DELETE_SERVICE, null)
            .route(HostParts.DELETE, null)
            .route(RuntimeImageParts.DELETE, null)
            .route(ProjectParts.DELETE, null)
            .route(EnvironmentParts.DELETE, null)
            .route(ReconcileFindingParts.REMOVE, null)
            .route(ReleasedClaimParts.LIFT, null)
            .route(GitConnectionWrites.DELETE, null)
            .route(CertificateParts.DELETE, null)
            .route(InstanceTemplateParts.DELETE, null);
        // zenit-auth's people and roles are deleted here too, by their own operations.
        for (Operation<?, ?, ?> operation : AuthTools.DELETES) deletes.route(operation, null);
        McpHostTool delete = deletes.build();
        McpHostTool deploy = McpCodeTool.builder("deploy", words("mcp_tool_deploy"), writes())
            .code(words("mcp_code_deploy"))
            .route(InstanceOperations.DEPLOY, null)
            .route(StackOperations.DEPLOY, null)
            .build();
        McpHostTool rollBack = McpCodeTool.builder("roll_back", words("mcp_tool_roll_back"), writes())
            .code(words("mcp_code_roll_back"))
            .route(InstanceOperations.ROLLBACK, null)
            .route(StackOperations.ROLLBACK, null)
            .route(SiteOperations.ROLLBACK_RELEASE, null)
            .build();
        // A trashed site comes back as it was deleted, through the panel trash's own restore; a live one goes back to a
        // saved revision, through its history tab's own restore.
        McpHostTool restore = McpCodeTool.builder("site_restore", words("mcp_tool_site_restore"), writes())
            .code(words("mcp_code_site_restore"))
            .route(SiteWrites.RESTORE, null)
            .routeByState(SiteWrites.RESTORE_REVISION, HohenheimMcpAnswers::changed)
            .build();
        McpHostTool power = McpCodeTool.builder("power", words("mcp_tool_power"), writes())
            .code(words("mcp_code_power"))
            .choice(ACTION, words("mcp_action"), List.of("start", "stop", "restart"), null)
            .route(InstanceOperations.START, HohenheimMcpAnswers::power, ACTION, "start")
            .route(InstanceOperations.STOP, HohenheimMcpAnswers::power, ACTION, "stop")
            .route(InstanceOperations.RESTART, HohenheimMcpAnswers::power, ACTION, "restart")
            .route(StackOperations.STOP, null, ACTION, "stop")
            .build();
        McpHostTool backUp = McpCodeTool.builder("back_up", words("mcp_tool_back_up"), writes())
            .code(words("mcp_code_back_up"))
            .route(InstanceOperations.BACKUP, null)
            .route(DatabaseParts.BACK_UP_NOW, null)
            .build();
        McpHostTool exec = McpCodeTool.builder("app_exec", words("mcp_tool_app_exec"), writes())
            .code(words("mcp_code_exec"))
            .flag(CONSOLE, words("mcp_console"))
            .route(InstanceOperations.EXEC, HohenheimMcpAnswers::exec, CONSOLE, "false")
            .route(InstanceOperations.CONSOLE_COMMAND, HohenheimMcpAnswers::output, CONSOLE, "true")
            .build();
        // The record whose variables are set is their owner: later an app, an environment or a template.
        McpHostTool variables = McpCodeTool.builder("variable_set", words("mcp_tool_variable_set"), writes())
            .subject(OWNER, words("mcp_code_variables"))
            .route(InstanceVariableOperations.SET_VARIABLES, HohenheimMcpAnswers::variableWritten)
            .build();
        McpHostTool test = McpCodeTool.builder("test", words("mcp_tool_test"), writes())
            .code(words("mcp_code_test"))
            .route(DomainParts.CHECK_REACH, null)
            .route(DnsOperations.CHECK_HEALTH, HohenheimMcpAnswers::told)
            .route(BackupTargetParts.TEST, HohenheimMcpAnswers::tested)
            .route(HostParts.PROBE, HohenheimMcpAnswers::said)
            .build();
        // DNS records have no code: they are read and set by their zone, each by its name and type.
        McpHostTool recordList = McpCodeTool.builder("dns_record_list", words("mcp_tool_dns_record_list"),
                McpHostTool.Annotations.ofReadOnly())
            .subject(DNS_ZONE, words("mcp_code_dns_zone_read"))
            .route(DnsRecordSets.LIST, HohenheimMcpAnswers::records)
            .build();
        McpHostTool recordSet = McpCodeTool.builder("dns_record_set", words("mcp_tool_dns_record_set"), writes())
            .subject(DNS_ZONE, words("mcp_code_dns_zone_set"))
            .route(DnsRecordSets.SET, HohenheimMcpAnswers::recordsSet)
            .build();
        // A zone is named by its origin as well as its code, as every tool taking a zone takes it.
        McpHostTool zoneSet = McpCodeTool.builder("dns_zone_set", words("mcp_tool_dns_zone_set"), writes())
            .code(words("mcp_code_dns_zone"))
            .creates(DnsZoneWrites.CREATE, null, DnsZoneModel.ORIGIN.getInputName(), words("dns_zone_thing"))
            .route(DnsZoneWrites.CHANGE, HohenheimMcpAnswers::changed)
            .build();
        McpHostTool address = McpCodeTool.builder("address_set", words("mcp_tool_address_set"), writes())
            .code(words("mcp_code_address"))
            .creates(AddressWrites.CREATE, null,
                List.of(AddressWrites.SITE, SiteDomainModel.HOSTNAME.getInputName()),
                words("address_thing"))
            .route(AddressWrites.CHANGE, HohenheimMcpAnswers::changed)
            .build();
        // A new certificate for names, one for an address (address=D4), or the same one ordered again (code=C8).
        McpHostTool order = McpCodeTool.builder("certificate_order", words("mcp_tool_certificate_order"), writes())
            .code(words("mcp_code_certificate_order"))
            .creates(CertificateOperations.REQUEST, HohenheimMcpAnswers::ordered,
                CertificateOperations.DOMAINS.getInputName(), words("certificate_thing"))
            .route(CertificateOperations.REISSUE, HohenheimMcpAnswers::ordered)
            .routeBy(ADDRESS, words("mcp_address_certificate_order"), CertificateOperations.REQUEST_FOR_DOMAIN,
                HohenheimMcpAnswers::ordered)
            .build();
        // A waiting manual DNS order is finished, any other Let's Encrypt certificate renewed: as the record stands.
        McpHostTool renew = McpCodeTool.builder("certificate_renew", words("mcp_tool_certificate_renew"), writes())
            .code(words("mcp_code_certificate_renew"))
            .route(CertificateOperations.RENEW, null)
            .routeByState(CertificateOperations.CONTINUE_DNS, null)
            .build();
        // A connection is a credential: a key made for it shows its public half in the answer, never the private one.
        McpHostTool gitConnection = McpCodeTool.builder("git_connection_set", words("mcp_tool_git_connection_set"),
                writes())
            .code(words("mcp_code_git_connection"))
            .creates(GitConnectionWrites.CREATE, HohenheimMcpAnswers::connectionMade,
                List.of(GitProviderModel.NAME.getName(), GitConnectionWrites.KIND), words("git_connection_thing"))
            .route(GitConnectionWrites.CHANGE, HohenheimMcpAnswers::changed)
            .build();
        // Its own tool, never an argument of site_stream_set: a set tool never trashes the site it is given.
        McpHostTool movePorts = McpCodeTool.builder("site_ports_move", words("mcp_tool_site_ports_move"), destroys())
            .code(words("mcp_code_site_ports_move"))
            .route(SitePortMoves.MOVE, HohenheimMcpAnswers::portsMoved)
            .build();
        List<McpHostTool> tools = new ArrayList<>(List.of(logs, delete, deploy, rollBack, restore, power, backUp,
            exec, variables, test, recordList, recordSet, zoneSet, address, order, renew, gitConnection, movePorts));
        for (KindSettingsWrites.Kind kind : AppKindWrites.all()) {
            tools.add(McpCodeTool.builder(kind.tool(), words("mcp_tool_" + kind.tool()), writes())
                .code(words("mcp_code_set"))
                .creates(kind.create(), null, kind.needs(), kind.thing())
                .route(kind.change(), HohenheimMcpAnswers::changed)
                // describe code=AP12 names its own kind's tool only; a call still turns another kind (change_kind).
                .describedFor(kind::holds)
                .build());
        }
        for (KindSettingsWrites.Kind kind : SiteKindWrites.all()) {
            tools.add(McpCodeTool.builder(kind.tool(), words("mcp_tool_" + kind.tool()), writes())
                .code(words("mcp_code_site"))
                .creates(kind.create(), null, kind.needs(), kind.thing())
                .route(kind.change(), HohenheimMcpAnswers::changed)
                // describe code=S20 names its own kind's tool only; a call still turns another kind (change_kind).
                .describedFor(kind::holds)
                .build());
        }
        return tools;
    }

    static McpHostTool.@NonNull Annotations writes() {
        return new McpHostTool.Annotations(null, false, false, false, false);
    }

    static McpHostTool.@NonNull Annotations destroys() {
        return new McpHostTool.Annotations(null, false, true, false, false);
    }
}
