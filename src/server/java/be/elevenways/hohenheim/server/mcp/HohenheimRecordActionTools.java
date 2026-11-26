package be.elevenways.hohenheim.server.mcp;

import be.elevenways.hohenheim.instance.InstanceLogOperations;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.site.SiteOperations;
import be.elevenways.zenit.ai.server.mcp.host.McpCodeTool;
import be.elevenways.zenit.ai.server.mcp.host.McpHostTool;
import be.elevenways.zenit.ai.server.mcp.host.McpResponse;
import be.elevenways.zenit.ai.server.mcp.host.McpToolContext;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * Hohenheim's tools over the acts of one app or site that no create-or-change tool takes: a site's addresses taken out
 * of the route table and back, a machine's install ISO put in and taken out, an app's push webhook registered, and an
 * app's deploys and builds listed.
 *
 * AIDEV-NOTE: each was a tool per panel button (site_pause, site_resume, app_attach_media, app_detach_media,
 * app_connect_webhook, app_list_deploys); the variants of one act are now one tool's argument. They stay tools of
 * their own because an operation is reached by exactly one tool (HohenheimMcpTest): the site kinds' and the app
 * kinds' tools are several, so none of them can take the act.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class HohenheimRecordActionTools {

    private static final String PAUSED = "paused";
    private static final String REMOVE = "remove";

    private HohenheimRecordActionTools() {
    }

    /** @return every tool of this class, in the order an assistant is offered them */
    static @NonNull List<McpHostTool> declared() {
        McpHostTool pause = McpCodeTool.builder("site_pause", HohenheimTools.words("mcp_tool_site_pause"),
                HohenheimTools.destroys())
            .code(HohenheimTools.words("mcp_code_site_pause"))
            .optionalFlag(PAUSED, HohenheimTools.words("mcp_site_paused"))
            .route(SiteOperations.DISABLE, null, PAUSED, "true")
            .route(SiteOperations.ENABLE, null, PAUSED, "false")
            .build();
        McpHostTool media = McpCodeTool.builder("app_install_media_set",
                HohenheimTools.words("mcp_tool_app_install_media_set"), HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_app_install_media_set"))
            .argument(InstanceOperations.DRIVE_MEDIA.getName(), "install_media")
            .flag(REMOVE, HohenheimTools.words("mcp_install_media_remove"))
            .route(InstanceOperations.ATTACH_INSTALL_MEDIA, null, REMOVE, "false")
            .route(InstanceOperations.DETACH_INSTALL_MEDIA, null, REMOVE, "true")
            .build();
        McpHostTool webhook = McpCodeTool.builder("app_webhook_set", HohenheimTools.words("mcp_tool_app_webhook_set"),
                HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_app_webhook_set"))
            .route(InstanceOperations.CONNECT_WEBHOOK, HohenheimRecordActionTools::webhook)
            .build();
        McpHostTool deploys = McpCodeTool.builder("app_deploy_list", HohenheimTools.words("mcp_tool_app_deploy_list"),
                McpHostTool.Annotations.ofReadOnly())
            .code(HohenheimTools.words("mcp_code_app_deploy_list"))
            .route(InstanceLogOperations.LIST_DEPLOYS, HohenheimMcpAnswers::deploys)
            .build();
        return List.of(pause, media, webhook, deploys);
    }

    /** @return whether the webhook was made at the provider or brought up to date there */
    private static @NonNull McpResponse webhook(@NonNull McpToolContext context, @Nullable Boolean created) {
        return McpResponse.create().line(Boolean.TRUE.equals(created) ? "webhook: created"
            : "webhook: brought up to date");
    }
}
