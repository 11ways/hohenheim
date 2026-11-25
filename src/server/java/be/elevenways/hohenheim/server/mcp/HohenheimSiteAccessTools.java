package be.elevenways.hohenheim.server.mcp;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.SiteRedirectModel;
import be.elevenways.hohenheim.server.cms.AccessWrites;
import be.elevenways.hohenheim.server.cms.SitePartWrites;
import be.elevenways.zenit.ai.server.mcp.host.McpCodeTool;
import be.elevenways.zenit.ai.server.mcp.host.McpHostTool;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * Hohenheim's hand-declared tools over a site's parts and access: protected paths, path redirects, the marks on a
 * site's revisions, access lists and their rules, sign-in providers and bans, each over the operations the panel's
 * forms and buttons write through; {@link HohenheimTools} offers them beside its own.
 *
 * AIDEV-NOTE: declarations only, as in HohenheimTools: a gate, a check or a writer of a tool here is its operation's.
 * The two site kinds of this group (passthrough, dev namespace) are SiteKindWrites kinds, offered by HohenheimTools'
 * kind loop.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class HohenheimSiteAccessTools {

    private HohenheimSiteAccessTools() {
    }

    /** @return every tool of this class, in the order an assistant is offered them */
    static @NonNull List<McpHostTool> declared() {
        McpHostTool protectedPath = McpCodeTool.builder("protected_path_set",
                HohenheimTools.words("mcp_tool_protected_path_set"), HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_protected_path_set"))
            .creates(SitePartWrites.PROTECTED_PATHS.create(), null, List.of(SitePartWrites.SITE,
                ProtectedPathModel.PATH.getInputName()), HohenheimTools.words("protected_path_thing"))
            .route(SitePartWrites.PROTECTED_PATHS.change(), HohenheimMcpAnswers::changed)
            .build();
        McpHostTool pathRedirect = McpCodeTool.builder("path_redirect_set",
                HohenheimTools.words("mcp_tool_path_redirect_set"), HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_path_redirect_set"))
            .creates(SitePartWrites.PATH_REDIRECTS.create(), null, List.of(SitePartWrites.SITE,
                SiteRedirectModel.FROM_PATH.getInputName(), SitePartWrites.TARGET_URL),
                HohenheimTools.words("path_redirect_thing"))
            .route(SitePartWrites.PATH_REDIRECTS.change(), HohenheimMcpAnswers::changed)
            .build();
        // A mark has no code: its site, its revision and its kind name it.
        McpHostTool revisionMark = McpCodeTool.builder("site_revision_mark_set",
                HohenheimTools.words("mcp_tool_site_revision_mark_set"), HohenheimTools.writes())
            .subject(SitePartWrites.SITE, HohenheimTools.words("mcp_code_site_revision_mark"))
            .route(SitePartWrites.SET_MARK, HohenheimMcpAnswers::changed)
            .build();
        McpHostTool accessList = McpCodeTool.builder("access_list_set",
                HohenheimTools.words("mcp_tool_access_list_set"), HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_access_list_set"))
            .creates(AccessWrites.ACCESS_LISTS.create(), null, AccessListModel.NAME.getInputName(),
                HohenheimTools.words("access_list_thing"))
            .route(AccessWrites.ACCESS_LISTS.change(), HohenheimMcpAnswers::changed)
            .build();
        McpHostTool accessRule = McpCodeTool.builder("access_rule_set",
                HohenheimTools.words("mcp_tool_access_rule_set"), HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_access_rule_set"))
            .creates(AccessWrites.CREATE_RULE, null, AccessWrites.KIND, HohenheimTools.words("access_rule_thing"))
            .route(AccessWrites.CHANGE_RULE, HohenheimMcpAnswers::changed)
            .build();
        McpHostTool provider = McpCodeTool.builder("sign_in_provider_set",
                HohenheimTools.words("mcp_tool_sign_in_provider_set"), HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_sign_in_provider_set"))
            .creates(AccessWrites.CREATE_PROVIDER, null, List.of(SiteAuthProviderModel.NAME.getInputName(),
                AccessWrites.KIND), HohenheimTools.words("sign_in_provider_thing"))
            .route(AccessWrites.CHANGE_PROVIDER, HohenheimMcpAnswers::changed)
            .build();
        // A ban has no code: its address names it.
        McpHostTool ban = McpCodeTool.single("ban_set", HohenheimTools.words("mcp_tool_ban_set"),
            HohenheimTools.writes(), AccessWrites.SET_BAN, HohenheimMcpAnswers::said);
        return List.of(protectedPath, pathRedirect, revisionMark, accessList, accessRule, provider, ban);
    }
}
