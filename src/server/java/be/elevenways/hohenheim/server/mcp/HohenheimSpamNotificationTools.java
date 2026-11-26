package be.elevenways.hohenheim.server.mcp;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.server.cms.HohenheimPanel;
import be.elevenways.hohenheim.server.cms.NotificationChannelParts;
import be.elevenways.hohenheim.server.cms.SpamserviceClientKeysResource;
import be.elevenways.hohenheim.server.cms.SpamserviceClientsResource;
import be.elevenways.hohenheim.server.cms.SpamserviceInstallationResource;
import be.elevenways.hohenheim.server.cms.SpamserviceOperations;
import be.elevenways.hohenheim.server.cms.SpamserviceReputationPage;
import be.elevenways.hohenheim.server.cms.SpamserviceSamplesResource;
import be.elevenways.hohenheim.server.cms.SpamserviceWordsResource;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.ai.server.mcp.host.McpCodeTool;
import be.elevenways.zenit.ai.server.mcp.host.McpHostTool;
import be.elevenways.zenit.ai.server.mcp.host.McpResponse;
import be.elevenways.zenit.ai.server.mcp.host.McpToolContext;
import be.elevenways.zenit.common.operation.ResultStep;
import be.elevenways.zenit.delivery.server.DeliveryCopy;
import be.elevenways.zenit.delivery.server.cms.DeliveryAdmin;
import be.elevenways.zenit.notifications.server.NotificationOperations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hohenheim's hand-declared tools over the spam service it runs itself and its notifications: the service's
 * installation, its start, stop and restart, its check, its clients, their keys, its samples' verdicts, its word
 * dictionary and its reputation explanation, a channel's create and change, a failed delivery's retry and the caller's
 * own inbox marked read, each over the operation the panel's form, button or page runs; {@link HohenheimTools} offers
 * them beside its own.
 *
 * AIDEV-NOTE: declarations only, as in HohenheimTools: a gate, a check or a writer of a tool here is its operation's.
 * A channel's test and delete are routes of the shared test and delete tools. The retry is zenit-delivery's own (the
 * delivery log's row button), the inbox marks zenit-notifications' (the inbox's own). The spam service's clients, keys
 * and samples are named by the codes the service allocates and resolves itself (SPC, SPK, SPS): Hohenheim keeps no
 * copy and registers no kind of them, so their routes take a code of any kind and the service's own read refuses the
 * rest.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class HohenheimSpamNotificationTools {

    private static final String ACTION = "action";
    private static final String ABOUT = "about";
    private static final String VERDICT = "verdict";
    private static final String RESCORE = "rescore";

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
        // The delivery log's own installation, the one the panel mounts (whichever asks first installs it).
        DeliveryAdmin deliveries = HohenheimPanel.deliveryAdmin();
        McpHostTool retry = McpCodeTool.builder("delivery_retry", HohenheimTools.words("mcp_tool_delivery_retry"),
                HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_delivery_retry"))
            .route(deliveries.retryDelivery(), HohenheimSpamNotificationTools::retried)
            .build();
        // One item by its code, every item about a record by that record's code (which says its own kind, so the
        // mark's model entry is never asked), or every item.
        McpHostTool markRead = McpCodeTool.builder("notification_mark_read",
                HohenheimTools.words("mcp_tool_notification_mark_read"), HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_notification_mark_read"))
            .leavesOut(NotificationOperations.MODEL_ENTRY)
            .route(NotificationOperations.MARK_READ, HohenheimSpamNotificationTools::markedOne)
            .routeWithoutRecordBy(ABOUT, HohenheimTools.words("mcp_notification_mark_read_about"),
                NotificationOperations.MARK_SUBJECT_READ, NotificationOperations.RECORD_ENTRY,
                HohenheimSpamNotificationTools::markedAbout)
            .routeWithoutRecord(NotificationOperations.MARK_ALL_READ,
                (context, none) -> McpResponse.create().line("marked read: every item of yours"))
            .build();
        return List.of(serviceSet, power, test, clientSet(), keySet(), sampleSet(), wordSet, reputation, channelSet,
            retry, markRead);
    }

    /** @return whether the one item was marked read now or had been read already */
    private static @NonNull McpResponse markedOne(@NonNull McpToolContext context, @Nullable Boolean marked) {
        return McpResponse.create().line(Boolean.TRUE.equals(marked) ? "marked read: 1 item"
            : "marked read: none, it was read already");
    }

    /** @return how many of the caller's items about the record were marked read */
    private static @NonNull McpResponse markedAbout(@NonNull McpToolContext context, @Nullable Long marked) {
        long count = marked == null ? 0 : marked;
        return McpResponse.create().line(count == 0 ? "marked read: none" : count == 1 ? "marked read: 1 item"
            : "marked read: " + count + " items");
    }

    /** @return whether the retry delivered it or queued it again, in the words of the delivery log's toast */
    private static @NonNull McpResponse retried(@NonNull McpToolContext context, @Nullable Boolean delivered) {
        return HohenheimMcpAnswers.said(context, DeliveryCopy.DELIVERY.of(Boolean.TRUE.equals(delivered)
            ? "retry_sent" : "retry_queued"));
    }

    /** @return spam_client_set: a client created without a code, changed by what a call sends with one */
    private static @NonNull McpHostTool clientSet() {
        return McpCodeTool.builder("spam_client_set", HohenheimTools.words("mcp_tool_spam_client_set"),
                HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_spam_client_set"))
            .creates(SpamserviceClientsResource.CREATE, HohenheimSpamNotificationTools::clientMade, "name",
                HohenheimTools.words("spam_client_thing"))
            .route(SpamserviceClientsResource.CHANGE, HohenheimMcpAnswers::changed)
            .build();
    }

    /** @return spam_key_set: a key made under its client, renamed and turned on or off by its code */
    private static @NonNull McpHostTool keySet() {
        // A generated key is a credential: it waits on its client's Keys tab, never in the answer.
        return McpCodeTool.builder("spam_key_set", HohenheimTools.words("mcp_tool_spam_key_set"),
                HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_spam_key_set"))
            .creates(SpamserviceClientKeysResource.MAKE, HohenheimSpamNotificationTools::keyMade,
                List.of(SpamserviceClientKeysResource.SPAM_CLIENT, "name"), HohenheimTools.words("spam_key_thing"))
            .route(SpamserviceClientKeysResource.CHANGE, HohenheimMcpAnswers::changed)
            .build();
    }

    /** @return spam_sample_set: a sample's verdict, or its score again, one of the two per call */
    private static @NonNull McpHostTool sampleSet() {
        return McpCodeTool.builder("spam_sample_set", HohenheimTools.words("mcp_tool_spam_sample_set"),
                HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_spam_sample_set"))
            .optionalChoice(VERDICT, HohenheimTools.words("mcp_spam_sample_verdict"), List.of("spam", "ham"))
            .flag(RESCORE, HohenheimTools.words("mcp_spam_sample_rescore"))
            .route(SpamserviceSamplesResource.MARK_SPAM, sampleSaid("marked_spam"), VERDICT, "spam", RESCORE, "false")
            .route(SpamserviceSamplesResource.MARK_HAM, sampleSaid("marked_ham"), VERDICT, "ham", RESCORE, "false")
            .route(SpamserviceSamplesResource.RESCORE, HohenheimSpamNotificationTools::rescored, RESCORE, "true")
            .build();
    }

    /** @return the answer of a verdict, in the words of the panel's toast */
    private static @NonNull ResultStep<McpToolContext, Void, McpResponse> sampleSaid(@NonNull String toast) {
        return (context, none) -> HohenheimMcpAnswers.said(context, HohenheimMicrocopy.SPAMSERVICE_SAMPLE.of(toast));
    }

    /** @return a rescore's answer, the new score in the words of the panel's toast */
    private static @NonNull McpResponse rescored(@NonNull McpToolContext context, @Nullable String score) {
        return HohenheimMcpAnswers.said(context, HohenheimMicrocopy.SPAMSERVICE_SAMPLE.of("rescored")
            .withArg("score", score == null ? 0 : Integer.parseInt(score)));
    }

    /** @return the new client's code, as the spam service gave it */
    private static @NonNull McpResponse clientMade(@NonNull McpToolContext context, @Nullable String id) {
        String code = id == null ? null : SpamserviceClientsResource.codeOf(id);
        return code == null ? McpResponse.create().line("done") : McpResponse.create().line(context.access().text(
            HohenheimTools.words("mcp_spam_client_made").withArg("code", code)));
    }

    /** @return the new key's code and its client's; where a generated key waits, never the key itself */
    private static @NonNull McpResponse keyMade(@NonNull McpToolContext context,
                                                SpamserviceClientKeysResource.@Nullable MadeKey made) {
        if (made == null) return McpResponse.create().line("done");
        Microcopy said = made.waiting() ? HohenheimTools.words("mcp_spam_key_made")
            : HohenheimTools.words("mcp_spam_key_adopted");
        return McpResponse.create().line(context.access().text(said.withArg("key", made.code())
            .withArg("client", made.client())));
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
