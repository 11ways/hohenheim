package be.elevenways.hohenheim.server.mcp;

import be.elevenways.hohenheim.server.proxy.AccessLogReader;
import be.elevenways.hohenheim.site.SiteAccessLogOperations;
import be.elevenways.zenit.ai.server.mcp.host.McpCodeTool;
import be.elevenways.zenit.ai.server.mcp.host.McpHostTool;
import be.elevenways.zenit.ai.server.mcp.host.McpResponse;
import be.elevenways.zenit.ai.server.mcp.host.McpToolContext;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * Hohenheim's hand-declared tools over a site's traffic: the access log read; {@link HohenheimTools} offers them
 * beside its own.
 *
 * AIDEV-NOTE: declarations only, as in HohenheimTools: who may read and what is read are the operation's
 * (SiteAccessLogOperations, SiteAccessLogPage), the panel's Access log tab reading through the same reader.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class HohenheimTrafficTools {

    private HohenheimTrafficTools() {
    }

    /** @return every tool of this class, in the order an assistant is offered them */
    static @NonNull List<McpHostTool> declared() {
        McpHostTool accessLog = McpCodeTool.builder("access_log_read", HohenheimTools.words("mcp_tool_access_log_read")
                    .withArg("lines", SiteAccessLogOperations.PAGE_LINES).withArg("bound", AccessLogReader.SCAN_BYTES),
                McpHostTool.Annotations.ofReadOnly())
            .subject("site", HohenheimTools.words("mcp_code_access_log_site"))
            .route(SiteAccessLogOperations.READ, HohenheimTrafficTools::lines)
            .build();
        return List.of(accessLog);
    }

    /** One page of a site's lines: which of how many first, then the lines, which visitors wrote, as untrusted text. */
    static @NonNull McpResponse lines(@NonNull McpToolContext context,
                                      SiteAccessLogOperations.@Nullable Lines lines) {
        McpResponse response = McpResponse.create();
        if (lines == null || lines.lines().isEmpty()) {
            response.line(lines != null && lines.matched() > 0 ? "lines: past the last page of " + lines.matched()
                : "lines: none match");
        } else {
            response.line("lines " + lines.first() + " to " + (lines.first() + lines.lines().size() - 1) + " of "
                + (lines.complete() ? "" : "at least ") + lines.matched() + ", newest first");
            response.untrusted("lines", String.join("\n", lines.lines()));
        }
        if (lines != null && !lines.complete()) {
            response.line("stopped: the read reached its bound at " + lines.readBackTo()
                + "; give from and to for older lines");
        }
        return response;
    }
}
