package be.elevenways.hohenheim.server.mcp;

import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.server.cms.HostMediaOperations;
import be.elevenways.hohenheim.server.cms.HostParts;
import be.elevenways.hohenheim.server.cms.HostingRecordWrites;
import be.elevenways.hohenheim.server.cms.StackOperations;
import be.elevenways.zenit.ai.server.mcp.host.McpCodeTool;
import be.elevenways.zenit.ai.server.mcp.host.McpHostTool;
import be.elevenways.zenit.ai.server.mcp.host.McpResponse;
import be.elevenways.zenit.ai.server.mcp.host.McpToolContext;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * Hohenheim's hand-declared tools over hosts and stacks: a host's admission, trust, client key and leftovers, its
 * install media, the image cleanup and a stack's volume purge, each over the operations the panel runs;
 * {@link HohenheimTools} offers them beside its own.
 *
 * AIDEV-NOTE: declarations only, as in HohenheimTools: a gate, a check or a writer of a tool here is its operation's.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class HohenheimHostTools {

    private static final String STATE = "state";
    private static final String ADMITTED = "admitted";
    private static final String PAUSED = "paused";
    private static final String MOVE_APPS_OFF = "move_apps_off";
    private static final String KIND = "kind";
    private static final String SSH = "ssh";
    private static final String INCUS = "incus";
    private static final String HOST = "host";
    private static final String REMOVE = "remove";

    private HohenheimHostTools() {
    }

    /** @return every tool of this class, in the order an assistant is offered them */
    static @NonNull List<McpHostTool> declared() {
        McpHostTool hostSet = McpCodeTool.builder("host_set", HohenheimTools.words("mcp_tool_host_set"),
                HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_host_set"))
            .creates(HostingRecordWrites.HOSTS.create(), null, HostModel.NAME.getInputName(),
                HohenheimTools.words("host_thing"))
            .route(HostingRecordWrites.HOSTS.change(), HohenheimMcpAnswers::changed)
            .build();
        // admitted checks a host and admits a waiting one; on a paused host it ends the pause (its own step).
        McpHostTool admission = McpCodeTool.builder("host_admission_set",
                HohenheimTools.words("mcp_tool_host_admission_set"), HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_host"))
            .choice(STATE, HohenheimTools.words("mcp_host_state"), List.of(ADMITTED, PAUSED), null)
            .flag(MOVE_APPS_OFF, HohenheimTools.words("mcp_move_apps_off"))
            .route(HostParts.CHECK, HohenheimMcpAnswers::said, STATE, ADMITTED, MOVE_APPS_OFF, "false")
            .routeByState(HostParts.RESUME, HohenheimMcpAnswers::said, STATE, ADMITTED, MOVE_APPS_OFF, "false")
            .route(HostParts.PAUSE, HohenheimMcpAnswers::said, STATE, PAUSED, MOVE_APPS_OFF, "false")
            .route(HostParts.MOVE_APPS_OFF, HohenheimMcpAnswers::said, STATE, PAUSED, MOVE_APPS_OFF, "true")
            .build();
        McpHostTool trust = McpCodeTool.builder("host_trust", HohenheimTools.words("mcp_tool_host_trust"),
                HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_host"))
            .choice(KIND, HohenheimTools.words("mcp_trust_kind"), List.of(SSH, INCUS), null)
            .route(HostParts.TRUST_SSH, HohenheimMcpAnswers::said, KIND, SSH)
            .route(HostParts.TRUST_INCUS, HohenheimMcpAnswers::said, KIND, INCUS)
            .build();
        McpHostTool keyReset = McpCodeTool.builder("host_client_key_reset",
                HohenheimTools.words("mcp_tool_host_client_key_reset"), HohenheimTools.destroys())
            .code(HohenheimTools.words("mcp_code_host"))
            .choice(KIND, HohenheimTools.words("mcp_client_key_kind"), List.of(SSH, INCUS), null)
            .route(HostParts.NEW_SSH_KEY, HohenheimMcpAnswers::said, KIND, SSH)
            .route(HostParts.NEW_INCUS_CERT, HohenheimMcpAnswers::said, KIND, INCUS)
            .build();
        McpHostTool leftovers = McpCodeTool.builder("host_leftover_cleanup",
                HohenheimTools.words("mcp_tool_host_leftover_cleanup"), HohenheimTools.destroys())
            .code(HohenheimTools.words("mcp_code_incus_host"))
            .route(HostParts.CLEAN_LEFTOVERS, HohenheimMcpAnswers::said)
            .build();
        McpHostTool images = McpCodeTool.single("image_cleanup", HohenheimTools.words("mcp_tool_image_cleanup"),
            HohenheimTools.destroys(), StackOperations.RECLAIM_IMAGES, HohenheimHostTools::cleanupStarted);
        // One effect per tool: removing a medium destroys, a fetch only writes; the tool says destroy.
        McpHostTool media = McpCodeTool.builder("host_media_set", HohenheimTools.words("mcp_tool_host_media_set"),
                HohenheimTools.destroys())
            .subject(HOST, HohenheimTools.words("mcp_code_media_host"))
            .flag(REMOVE, HohenheimTools.words("mcp_remove_media"))
            .route(HostMediaOperations.FETCH, HohenheimMcpAnswers::said, REMOVE, "false")
            .route(HostMediaOperations.DELETE, HohenheimMcpAnswers::said, REMOVE, "true")
            .build();
        McpHostTool purge = McpCodeTool.builder("stack_volumes_purge",
                HohenheimTools.words("mcp_tool_stack_volumes_purge"), HohenheimTools.destroys())
            .code(HohenheimTools.words("mcp_code_stack_purge"))
            .route(StackOperations.PURGE_VOLUMES, HohenheimHostTools::purgeQueued)
            .build();
        McpHostTool projectSet = McpCodeTool.builder("project_set", HohenheimTools.words("mcp_tool_project_set"),
                HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_project_set"))
            .creates(HostingRecordWrites.PROJECTS.create(), null, ProjectModel.NAME.getInputName(),
                HohenheimTools.words("project_thing"))
            .route(HostingRecordWrites.PROJECTS.change(), HohenheimMcpAnswers::changed)
            .build();
        McpHostTool environmentSet = McpCodeTool.builder("environment_set",
                HohenheimTools.words("mcp_tool_environment_set"), HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_environment_set"))
            .creates(HostingRecordWrites.ENVIRONMENTS.create(), null, List.of(HostingRecordWrites.PROJECT,
                EnvironmentModel.NAME.getInputName()), HohenheimTools.words("environment_thing"))
            .route(HostingRecordWrites.ENVIRONMENTS.change(), HohenheimMcpAnswers::changed)
            .build();
        McpHostTool stackSet = McpCodeTool.builder("stack_set", HohenheimTools.words("mcp_tool_stack_set"),
                HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_stack_set"))
            .creates(HostingRecordWrites.STACKS.create(), null, StackModel.NAME.getInputName(),
                HohenheimTools.words("stack_thing"))
            .route(HostingRecordWrites.STACKS.change(), HohenheimMcpAnswers::changed)
            .build();
        McpHostTool serviceSet = McpCodeTool.builder("stack_service_set",
                HohenheimTools.words("mcp_tool_stack_service_set"), HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_stack_service_set"))
            .creates(HostingRecordWrites.SERVICES.create(), null, List.of(HostingRecordWrites.STACK,
                StackServiceModel.NAME.getInputName(), StackServiceModel.IMAGE.getInputName()),
                HohenheimTools.words("stack_service_thing"))
            .route(HostingRecordWrites.SERVICES.change(), HohenheimMcpAnswers::changed)
            .build();
        return List.of(hostSet, admission, trust, keyReset, leftovers, images, media, projectSet, environmentSet,
            stackSet, serviceSet, purge);
    }

    /** The image cleanup runs on after the call: what it removed reaches only Hohenheim's own log. */
    private static @NonNull McpResponse cleanupStarted(@NonNull McpToolContext context, @Nullable Void none) {
        return McpResponse.create().line("image cleanup started");
    }

    /** A purge is queued on the stack's worker; its history records it once it settled. */
    private static @NonNull McpResponse purgeQueued(@NonNull McpToolContext context, @Nullable Void none) {
        return McpResponse.create().line("volume purge queued; history of the stack shows it once it settled");
    }
}
