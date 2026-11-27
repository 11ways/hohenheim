package be.elevenways.hohenheim.server.mcp;

import be.elevenways.hohenheim.server.cms.SignedLinkSecrets;
import be.elevenways.hohenheim.site.SignedLinks;
import be.elevenways.zenit.ai.server.mcp.host.McpCodeTool;
import be.elevenways.zenit.ai.server.mcp.host.McpHostTool;
import be.elevenways.zenit.ai.server.mcp.host.McpResponse;
import be.elevenways.zenit.ai.server.mcp.host.McpToolContext;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Hohenheim's tool making a signed link to a path protected with one; {@link HohenheimTools} offers it beside its own.
 *
 * AIDEV-NOTE: a declaration only: who may and how the link is signed are the operation's (SignedLinkSecrets), which
 * the path's Signed link tab runs too. A link is no stored secret, so the answer carries it; the secrets themselves
 * are replaced and ended through protected_path_set and never answered.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class HohenheimSignedLinkTools {

    /** The argument naming the protected path a link opens. */
    static final String PROTECTED_PATH = "protected_path";

    private HohenheimSignedLinkTools() {
    }

    /** @return every tool of this class, in the order an assistant is offered them */
    static @NonNull List<McpHostTool> declared() {
        McpHostTool make = McpCodeTool.builder("site_link_make", HohenheimTools.words("mcp_tool_site_link_make"),
                HohenheimTools.writes())
            .subject(PROTECTED_PATH, HohenheimTools.words("mcp_code_site_link_make"))
            .route(SignedLinks.MAKE_LINK, HohenheimSignedLinkTools::made)
            .build();
        return List.of(make);
    }

    /** The link made, and until when it works. */
    private static @NonNull McpResponse made(@NonNull McpToolContext context, @Nullable String link) {
        String made = Objects.requireNonNull(link, "a made link");
        Instant until = Objects.requireNonNull(SignedLinkSecrets.expiresOf(made), "a made link carries its expiry");
        return McpResponse.create().line(context.access().text(HohenheimTools.words("mcp_signed_link_made")
            .withArg("link", made).withArg("until", until)));
    }
}
