package be.elevenways.hohenheim.server.mcp;

import be.elevenways.hohenheim.server.cms.SpamRecordCodes;
import be.elevenways.zenit.ai.server.mcp.host.McpListTool;
import be.elevenways.zenit.ai.server.mcp.host.McpToolCollection;

/**
 * The lists of the spam service's clients, keys and samples (spam_client_list, spam_key_list, spam_sample_list), each
 * over its operation in {@link SpamRecordCodes}; {@link McpListTool} takes them over as kinds of the one list tool.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class HohenheimSpamListTools extends McpToolCollection {

    HohenheimSpamListTools() {
        this.addTool(McpListTool.foreign(SpamRecordCodes.CLIENTS, SpamRecordCodes.LIST_CLIENTS));
        this.addTool(McpListTool.foreign(SpamRecordCodes.KEYS, SpamRecordCodes.LIST_KEYS));
        this.addTool(McpListTool.foreign(SpamRecordCodes.SAMPLES, SpamRecordCodes.LIST_SAMPLES));
    }
}
