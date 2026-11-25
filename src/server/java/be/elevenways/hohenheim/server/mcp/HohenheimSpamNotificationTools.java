package be.elevenways.hohenheim.server.mcp;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.server.cms.NotificationChannelParts;
import be.elevenways.hohenheim.server.cms.SpamserviceInstallationResource;
import be.elevenways.hohenheim.server.cms.SpamserviceOperations;
import be.elevenways.hohenheim.server.cms.SpamserviceReputationPage;
import be.elevenways.hohenheim.server.cms.SpamserviceWordsResource;
import be.elevenways.zenit.ai.server.mcp.host.McpCodeTool;
import be.elevenways.zenit.ai.server.mcp.host.McpHostTool;
import be.elevenways.zenit.ai.server.mcp.host.McpResponse;
import be.elevenways.zenit.ai.server.mcp.host.McpToolContext;
import be.elevenways.zenit.common.operation.ResultStep;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hohenheim's hand-declared tools over the spam service it runs itself and its notification channels: the service's
 * installation, its start, stop and restart, its check, its word dictionary and its reputation explanation, and a
 * channel's create and change, each over the operation the panel's form, button or page runs; {@link HohenheimTools}
 * offers them beside its own.
 *
 * AIDEV-NOTE: declarations only, as in HohenheimTools: a gate, a check or a writer of a tool here is its operation's.
 * A channel's test and delete are routes of the shared test and delete tools. The spam service's clients, keys and
 * samples have no codes yet (they live in the service, named by UUID), so their tools wait for one.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class HohenheimSpamNotificationTools {

    private static final String ACTION = "action";

    private HohenheimSpamNotificationTools() {
    }

    /** @return every tool of this class, in the order an assistant is offered them */
    static @NonNull List<McpHostTool> declared() {
        McpHostTool serviceSet = McpCodeTool.single("spam_service_set",
            HohenheimTools.words("mcp_tool_spam_service_set"), HohenheimTools.writes(),
            SpamserviceInstallationResource.SET, HohenheimMcpAnswers::changed);
        // The service exists once and has no code: the action alone names the route.
        McpHostTool power = McpCodeTool.builder("spam_service_power",
                HohenheimTools.words("mcp_tool_spam_service_power"), HohenheimTools.writes())
            .choice(ACTION, HohenheimTools.words("mcp_spam_service_action"), List.of("start", "stop", "restart"), null)
            .routeWithoutRecord(SpamserviceOperations.START, requested("start"), ACTION, "start")
            .routeWithoutRecord(SpamserviceOperations.STOP, requested("stop"), ACTION, "stop")
            .routeWithoutRecord(SpamserviceOperations.RESTART, requested("restart"), ACTION, "restart")
            .build();
        McpHostTool test = McpCodeTool.single("spam_service_test", HohenheimTools.words("mcp_tool_spam_service_test"),
            HohenheimTools.writes(), SpamserviceOperations.TEST, HohenheimMcpAnswers::output);
        McpHostTool wordSet = McpCodeTool.single("spam_word_set", HohenheimTools.words("mcp_tool_spam_word_set"),
            HohenheimTools.writes(), SpamserviceWordsResource.SET, HohenheimMcpAnswers::changed);
        McpHostTool reputation = McpCodeTool.single("spam_reputation_read",
            HohenheimTools.words("mcp_tool_spam_reputation_read"), McpHostTool.Annotations.ofReadOnly(),
            SpamserviceReputationPage.READ, HohenheimSpamNotificationTools::explained);
        McpHostTool channelSet = McpCodeTool.builder("notification_channel_set",
                HohenheimTools.words("mcp_tool_notification_channel_set"), HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_notification_channel_set"))
            .creates(NotificationChannelParts.WRITES.create(), null, List.of(
                NotificationChannelModel.NAME.getInputName(), NotificationChannelModel.FORMAT.getInputName(),
                NotificationChannelModel.URL.getInputName()), HohenheimTools.words("notification_channel_thing"))
            .route(NotificationChannelParts.WRITES.change(), HohenheimMcpAnswers::changed)
            .build();
        return List.of(serviceSet, power, test, wordSet, reputation, channelSet);
    }

    /** @return the answer of a start, stop or restart: requested, in the words of the panel's toast */
    private static @NonNull ResultStep<McpToolContext, Void, McpResponse> requested(@NonNull String action) {
        return (context, none) -> HohenheimMcpAnswers.said(context,
            HohenheimMicrocopy.SPAMSERVICE.of(action + "_ok"));
    }

    /** An address's reputation: its datasets, its event counts and the weighed score against the ban threshold. */
    private static @NonNull McpResponse explained(@NonNull McpToolContext context,
                                                  SpamserviceReputationPage.@Nullable Explanation explained) {
        McpResponse response = McpResponse.create();
        if (explained == null) return response.line("reputation: unknown");
        response.untrustedLine("address: ", "address", explained.ip() + " (" + explained.subnet() + ")");
        response.untrustedLine("datasets: ", "datasets", rows(explained.datasets()));
        response.untrustedLine("negative events: ", "negative", rows(explained.negative()));
        response.untrustedLine("positive events: ", "positive", rows(explained.positive()));
        response.untrustedLine("weighted: ", "weighted", rows(explained.weighted()));
        return response.line("net score: " + explained.net() + " against the ban threshold " + explained.threshold()
            + " (a positive event weighs " + explained.positiveWeight() + ")");
    }

    /** @return the rows as one line each of its columns, "none" for no rows */
    private static @NonNull String rows(@NonNull List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return "none";
        List<String> said = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            List<String> cells = new ArrayList<>();
            row.forEach((name, value) -> cells.add(name + "=" + value));
            said.add(String.join(" ", cells));
        }
        return String.join("; ", said);
    }
}
