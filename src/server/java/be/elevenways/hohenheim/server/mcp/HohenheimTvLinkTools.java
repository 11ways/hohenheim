package be.elevenways.hohenheim.server.mcp;

import be.elevenways.hohenheim.server.cms.TrafficTvLinks;
import be.elevenways.zenit.ai.server.mcp.host.McpCodeTool;
import be.elevenways.zenit.ai.server.mcp.host.McpHostTool;
import be.elevenways.zenit.ai.server.mcp.host.McpResponse;
import be.elevenways.zenit.ai.server.mcp.host.McpToolContext;
import be.elevenways.zenit.auth.model.ShareLinkModel;
import be.elevenways.zenit.server.operation.RecordCodes;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Hohenheim's tools over the TV page's links: make one, end one; {@link HohenheimTools} offers them beside its own.
 *
 * AIDEV-NOTE: declarations only: who may and what is done are the operations' (TrafficTvLinks), which the Activity
 * tab's TV page card runs too. A link's address is never answered: it waits for the caller's account on that tab.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class HohenheimTvLinkTools {

    private HohenheimTvLinkTools() {
    }

    /** @return every tool of this class, in the order an assistant is offered them */
    static @NonNull List<McpHostTool> declared() {
        McpHostTool set = McpCodeTool.single("tv_link_set", HohenheimTools.words("mcp_tool_tv_link_set"),
            HohenheimTools.writes(), TrafficTvLinks.MAKE, HohenheimTvLinkTools::parked);
        McpHostTool end = McpCodeTool.single("tv_link_end", HohenheimTools.words("mcp_tool_tv_link_end"),
            HohenheimTools.destroys(), TrafficTvLinks.END, (context, ended) -> McpResponse.create()
                .line(context.access().text(HohenheimTools.words("mcp_tv_link_ended"))));
        return List.of(set, end);
    }

    /** A link made, by its code: its address waits for the caller's account on the Activity tab, shown once. */
    private static @NonNull McpResponse parked(@NonNull McpToolContext context, @Nullable UUID link) {
        String code = link == null ? null : RecordCodes.codeUnjudged(ShareLinkModel.MODEL_ID, link.toString());
        return McpResponse.create().line(context.access().text(HohenheimTools.words("mcp_tv_link_parked")
            .withArg("link", String.valueOf(code))));
    }
}
