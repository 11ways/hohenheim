package be.elevenways.hohenheim.server.mcp;

import be.elevenways.hohenheim.server.cms.DnsOperations;
import be.elevenways.zenit.ai.server.mcp.host.McpCodeTool;
import be.elevenways.zenit.ai.server.mcp.host.McpHostTool;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * Hohenheim's hand-declared tool that moves a DNS zone between two Hohenheims, over the operation the zone page's
 * Take over action places; {@link HohenheimTools} offers it beside its own.
 *
 * AIDEV-NOTE: a declaration only, as in HohenheimTools: who may, what is checked and the move itself are the
 * operation's (DnsOperations.TAKE_OVER, DnsZoneMoves).
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
final class HohenheimDnsMoveTools {

    private HohenheimDnsMoveTools() {
    }

    /** @return every tool of this class, in the order an assistant is offered them */
    static @NonNull List<McpHostTool> declared() {
        McpHostTool takeOver = McpCodeTool.builder("dns_zone_take_over",
                HohenheimTools.words("mcp_tool_dns_zone_take_over"), HohenheimTools.writes())
            .subject("dns_zone", HohenheimTools.words("mcp_code_dns_zone_take_over"))
            .route(DnsOperations.TAKE_OVER, HohenheimMcpAnswers::told)
            .build();
        return List.of(takeOver);
    }
}
