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
import be.elevenways.hohenheim.server.cms.AccessRuleParts;
import be.elevenways.hohenheim.server.cms.AppKindWrites;
import be.elevenways.hohenheim.server.cms.BuildHookParts;
import be.elevenways.hohenheim.server.cms.DatabaseDumpParts;
import be.elevenways.hohenheim.server.cms.DatabaseParts;
import be.elevenways.hohenheim.server.cms.DnsOperations;
import be.elevenways.hohenheim.server.cms.HostParts;
import be.elevenways.hohenheim.server.cms.ProjectParts;
import be.elevenways.hohenheim.server.cms.ReconcileFindingParts;
import be.elevenways.hohenheim.server.cms.ReleasedClaimParts;
import be.elevenways.hohenheim.server.cms.RuntimeImageParts;
import be.elevenways.hohenheim.server.cms.SiteWrites;
import be.elevenways.hohenheim.server.cms.StackOperations;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.ai.server.mcp.host.McpCodeTool;
import be.elevenways.zenit.ai.server.mcp.host.McpHostTool;
import be.elevenways.zenit.ai.server.mcp.host.McpToolCollection;
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
    private static final String ACTION = "action";
    private static final String CONSOLE = "console";
    private static final String OWNER = "owner";

    private HohenheimTools() {
        for (McpHostTool tool : declared()) this.addTool(tool);
    }

    /** @return the collection of every hand-declared tool */
    public static @NonNull HohenheimTools build() {
        return new HohenheimTools();
    }

    private static @NonNull Microcopy words(@NonNull String key) {
        return HohenheimMicrocopy.HOHENHEIM.of(key);
    }

    private static @NonNull List<McpHostTool> declared() {
        McpHostTool logs = McpCodeTool.builder("logs", words("mcp_tool_logs"), McpHostTool.Annotations.ofReadOnly())
            .code(words("mcp_code_logs"))
            .route(InstanceLogOperations.READ_LOG, HohenheimMcpAnswers::log)
            .route(InstanceLogOperations.READ_BUILD_LOG, HohenheimMcpAnswers::log)
            .route(InstanceLogOperations.READ_DEPLOY_LOG, HohenheimMcpAnswers::log)
            .build();
        McpHostTool delete = McpCodeTool.builder("delete", words("mcp_tool_delete"), destroys())
            .code(words("mcp_code_delete"))
            .flag(WITH_DATA, words("mcp_with_data"))
            .flag(FORCE, words("mcp_force"))
            .route(InstanceOperations.DELETE, null, WITH_DATA, "false")
            .route(InstanceOperations.DESTROY_WITH_DATA, null, WITH_DATA, "true")
            .route(InstanceBackupOperations.DELETE_BACKUP, null)
            .route(InstanceSnapshotOperations.DELETE, null)
            .route(InstanceAttachmentOperations.DETACH_DEVICE, null)
            .route(InstanceAttachmentOperations.DELETE_DATABASE_LINK, null)
            .route(VolumeOperations.DESTROY, null)
            .route(GameDomainOperations.DELETE, null)
            .route(PreviewOperations.EXPIRE, HohenheimMcpAnswers::output)
            .route(SiteWrites.DELETE, null)
            .route(AccessRuleParts.DELETE, null)
            .route(BuildHookParts.REVOKE, null)
            .route(DnsOperations.DELETE_ZONE, null)
            .route(DnsOperations.DELETE_PEER, null)
            .route(DatabaseParts.DELETE, null, FORCE, "false")
            .route(DatabaseParts.FORCE_DELETE, null, FORCE, "true")
            .route(DatabaseParts.DELETE_ENGINE, null, FORCE, "false")
            .route(DatabaseParts.FORCE_DELETE_ENGINE, null, FORCE, "true")
            .route(DatabaseDumpParts.DELETE, null)
            .route(StackOperations.DELETE_STACK, null)
            .route(StackOperations.DELETE_SERVICE, null)
            .route(HostParts.DELETE, null)
            .route(RuntimeImageParts.DELETE, null)
            .route(ProjectParts.DELETE, null)
            .route(ReconcileFindingParts.REMOVE, null)
            .route(ReleasedClaimParts.LIFT, null)
            .build();
        McpHostTool deploy = McpCodeTool.builder("deploy", words("mcp_tool_deploy"), writes())
            .code(words("mcp_code_deploy"))
            .route(InstanceOperations.DEPLOY, null)
            .route(StackOperations.DEPLOY, null)
            .build();
        McpHostTool rollBack = McpCodeTool.builder("roll_back", words("mcp_tool_roll_back"), writes())
            .code(words("mcp_code_roll_back"))
            .route(InstanceOperations.ROLLBACK, null)
            .route(StackOperations.ROLLBACK, null)
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
        List<McpHostTool> tools = new ArrayList<>(List.of(logs, delete, deploy, rollBack, power, backUp,
            exec, variables));
        for (AppKindWrites.KindWrites kind : AppKindWrites.all()) {
            String name = AppKindWrites.toolName(kind.kind());
            tools.add(McpCodeTool.builder(name, words("mcp_tool_" + name), writes())
                .code(words("mcp_code_set"))
                .creates(kind.create(), null, "name", kind.thing())
                .route(kind.change(), HohenheimMcpAnswers::changed)
                // describe code=AP12 names its own kind's tool only; a call still turns another kind (change_kind).
                .describedFor(kind::holds)
                .build());
        }
        return tools;
    }

    private static McpHostTool.@NonNull Annotations writes() {
        return new McpHostTool.Annotations(null, false, false, false, false);
    }

    private static McpHostTool.@NonNull Annotations destroys() {
        return new McpHostTool.Annotations(null, false, true, false, false);
    }
}
