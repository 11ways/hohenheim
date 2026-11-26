package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.model.SpamserviceInstallationModel;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.hohenheim.server.spamservice.SpamserviceManager;
import be.elevenways.hohenheim.server.spamservice.SpamserviceState;
import be.elevenways.protoblast.testsupport.time.Poll;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.delivery.DeliveryChannel;
import be.elevenways.zenit.delivery.server.DeliveryModel;
import be.elevenways.zenit.delivery.server.DeliveryStatus;
import be.elevenways.zenit.notifications.server.NotificationInboxModel;
import be.elevenways.zenit.notifications.server.NotificationInboxOwners;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;

import static be.elevenways.hohenheim.test.ApiSupport.user;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The spam service Hohenheim runs and its notification channels through the real MCP endpoint as an administrator's
 * key: the service checked, a word added, changed and removed, an address's reputation explained against a fake
 * service, the installation changed and its start refused while disabled; a channel made, read, changed, tested and
 * deleted; each wrong call refused by the argument it names, and a person who is no administrator refused.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class HohenheimSpamNotificationToolsTest extends HohenheimHttpTestBase {

    private static final Pattern CHANNEL = Pattern.compile("NC\\d+");

    @Test
    void anAdministratorRunsTheSpamServiceAndItsChannelsByToolsAlone() throws Exception {
        URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
        McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
        String tag = HohenheimMcpTest.letters();
        McpHttpSession admin = HohenheimMcpTest.presetSession(server, endpoint,
            TenantConduits.operatorUser().get(UserModel.ID), "Test Admin");
        SpamserviceInstallationModel installations = Models.get(SpamserviceInstallationModel.class);
        Row before = installations.installation();
        Integer port = before == null ? null : before.get(SpamserviceInstallationModel.PORT);
        Integer heap = before == null ? null : before.get(SpamserviceInstallationModel.MAX_HEAP_MB);
        String channel = null;
        try {
            // 1. The spam service and the channels are tools of their own; the one-per-button tools are gone.
            assertThat(admin.toolNames()).as("step 1: the tools")
                .contains("spam_service_set", "spam_service_power", "spam_service_test", "spam_word_set",
                    "spam_reputation_read", "notification_channel_set")
                .doesNotContain("spamservice_installation_start", "spamservice_installation_stop",
                    "spamservice_installation_restart", "spamservice_installation_test", "notification_channel_test");
            assertThat(admin.call("spam_service_power", Map.of("action", "pause")).text())
                .as("step 1: an action of no allowed value is refused by its name").contains("action");

            // 2. Against a running service: its status, a word added, changed, refused and removed, an address
            //    explained.
            String word = "relief" + tag;
            try (FakeSpamservice fake = FakeSpamservice.start(Map.of())) {
                McpHttpSession.Answer status = admin.call("spam_service_test", Map.of());
                assertThat(status.error()).as("step 2: " + status.text()).isFalse();
                assertThat(status.text()).as("step 2: the status it reports").containsIgnoringCase("ready");

                McpHttpSession.Answer unscored = admin.call("spam_word_set", Map.of("word", word));
                assertThat(unscored.error()).as("step 2: a new word without a score: " + unscored.text()).isTrue();
                assertThat(unscored.text()).as("step 2: names what it needs").contains("score");
                McpHttpSession.Answer added = admin.call("spam_word_set", Map.of("word", word, "word_language", "nld",
                    "score", 40));
                assertThat(added.error()).as("step 2: " + added.text()).isFalse();
                assertThat(added.text()).as("step 2: added").contains(word + " added");
                McpHttpSession.Answer leet = admin.call("spam_word_set", Map.of("word", word, "word_language", "nld",
                    "leet", true));
                assertThat(leet.text()).as("step 2: the same word and language changes it").contains(word + " changed");
                Map<String, Object> stored = fake.words().values().iterator().next();
                assertThat(fake.words()).as("step 2: one entry").hasSize(1);
                assertThat(stored).as("step 2: the score left out stays, leet changed")
                    .containsEntry("score", 40).containsEntry("leet", true).containsEntry("language", "nld");
                assertThat(admin.call("spam_word_set", Map.of("word", word, "score", 5)).text())
                    .as("step 2: another language is another word").contains(word + " added");
                assertThat(fake.words()).as("step 2: two entries now").hasSize(2);
                McpHttpSession.Answer removed = admin.call("spam_word_set", Map.of("word", word, "word_language",
                    "nld", "remove", true));
                assertThat(removed.text()).as("step 2: removed").contains(word + " removed");
                assertThat(admin.call("spam_word_set", Map.of("word", word, "word_language", "nld", "remove", true))
                    .text()).as("step 2: removing it again finds nothing").contains("absent");

                McpHttpSession.Answer reputation = admin.call("spam_reputation_read", Map.of("ip", "203.0.113.9"));
                assertThat(reputation.error()).as("step 2: " + reputation.text()).isFalse();
                assertThat(reputation.text()).as("step 2: the address, its events and its net score")
                    .contains("203.0.113.9").contains("auth_failure").contains("net score:");
            }

            // 3. The installation, changed by what is sent alone; a port out of range refused by its name.
            McpHttpSession.Answer wrongPort = admin.call("spam_service_set", Map.of("port", 70000));
            assertThat(wrongPort.error()).as("step 3: " + wrongPort.text()).isTrue();
            assertThat(wrongPort.text()).as("step 3: names the port").contains("port");
            McpHttpSession.Answer set = admin.call("spam_service_set", Map.of("port", 8097, "max_heap_mb", 256));
            assertThat(set.error()).as("step 3: " + set.text()).isFalse();
            assertThat(set.text()).as("step 3: what it wrote").contains("port").contains("max_heap_mb");
            Row installed = Objects.requireNonNull(installations.installation(), "step 3: the installation is saved");
            assertThat((Integer) installed.get(SpamserviceInstallationModel.PORT)).as("step 3: port").isEqualTo(8097);
            assertThat((Integer) installed.get(SpamserviceInstallationModel.MAX_HEAP_MB)).as("step 3: heap")
                .isEqualTo(256);
            assertThat((Boolean) installed.get(SpamserviceInstallationModel.ENABLED)).as("step 3: still disabled")
                .isFalse();
            SpamserviceManager manager = SpamserviceManager.get();
            Poll.until("step 3: the service is brought in line: disabled", Duration.ofSeconds(10),
                () -> manager.snapshot().configured()
                    && SpamserviceState.DISABLED.token().equals(manager.snapshot().state()));

            // 4. A disabled service is neither started nor stopped nor checked, each refused in its own words.
            McpHttpSession.Answer start = admin.call("spam_service_power", Map.of("action", "start"));
            assertThat(start.error()).as("step 4: start: " + start.text()).isTrue();
            assertThat(start.text()).as("step 4: says it is disabled").contains("disabled");
            assertThat(admin.call("spam_service_power", Map.of("action", "stop")).error()).as("step 4: stop").isTrue();
            assertThat(admin.call("spam_service_test", Map.of()).error()).as("step 4: test").isTrue();
            assertThat(admin.call("spam_word_set", Map.of("word", word, "score", 1)).error())
                .as("step 4: no word while the service does not answer").isTrue();

            // 5. A channel: a create lacking its URL refused, then made, read without its URL, changed and refused.
            String hook = "http://127.0.0.1:1/hook-" + tag;
            McpHttpSession.Answer unaddressed = admin.call("notification_channel_set", Map.of("name", "ops " + tag,
                "format", "generic"));
            assertThat(unaddressed.error()).as("step 5: " + unaddressed.text()).isTrue();
            assertThat(unaddressed.text()).as("step 5: names what a create needs").contains("url");
            McpHttpSession.Answer made = admin.call("notification_channel_set", Map.of("name", "ops " + tag,
                "format", "generic", "url", hook));
            assertThat(made.error()).as("step 5: " + made.text()).isFalse();
            Matcher code = CHANNEL.matcher(made.text());
            assertThat(code.find()).as("step 5: answers the code: " + made.text()).isTrue();
            channel = code.group();
            String read = admin.call("read", Map.of("codes", List.of(channel))).text();
            assertThat(read).as("step 5: read back").contains("ops " + tag).doesNotContain(hook);
            McpHttpSession.Answer changed = admin.call("notification_channel_set", Map.of("code", channel,
                "name", "alerts " + tag, "events", List.of("deploy_failed", "backup_failed")));
            assertThat(changed.error()).as("step 5: " + changed.text()).isFalse();
            Row row = Objects.requireNonNull(Models.get(NotificationChannelModel.class).find()
                .where(NotificationChannelModel.NAME.eq("alerts " + tag)).first(), "step 5: the changed channel");
            List<String> events = row.get(NotificationChannelModel.EVENTS);
            assertThat(events).as("step 5: its events").containsExactly("deploy_failed", "backup_failed");
            assertThat((String) row.get(NotificationChannelModel.FORMAT)).as("step 5: its format stays")
                .isEqualTo("generic");
            McpHttpSession.Answer unknown = admin.call("notification_channel_set", Map.of("code", channel,
                "events", List.of("no_such_event")));
            assertThat(unknown.error()).as("step 5: an unknown event: " + unknown.text()).isTrue();
            assertThat(unknown.text()).as("step 5: names the events").contains("events");

            // 6. Its test alert goes nowhere, and says so; its delete takes it away.
            McpHttpSession.Answer tested = admin.call("test", Map.of("code", channel));
            assertThat(tested.error()).as("step 6: " + tested.text()).isFalse();
            assertThat(tested.text()).as("step 6: whether it was sent").contains("test message: not sent");

            // 7. A person who is no administrator changes neither.
            McpHttpSession person = HohenheimMcpTest.presetSession(server, endpoint,
                user("mcp-spam-" + tag + "@surface.test", "Mcp Spam"), "Mcp Spam");
            assertThat(person.call("spam_service_set", Map.of("port", 8098)).error())
                .as("step 7: no installation change").isTrue();
            assertThat(person.call("notification_channel_set", Map.of("code", channel, "name", "taken " + tag))
                .error()).as("step 7: no channel change").isTrue();

            McpHttpSession.Answer deleted = admin.call("delete", Map.of("code", channel));
            assertThat(deleted.error()).as("step 6: " + deleted.text()).isFalse();
            assertThat(Models.get(NotificationChannelModel.class).find()
                .where(NotificationChannelModel.NAME.eq("alerts " + tag)).first()).as("step 6: deleted").isNull();
            channel = null;
        } finally {
            if (channel != null) admin.call("delete", Map.of("code", channel));
            if (before == null) {
                HardDeletes.byId(installations, SpamserviceInstallationModel.SINGLETON_ID);
            } else {
                Row restored = Objects.requireNonNull(installations.installation());
                restored.set(SpamserviceInstallationModel.PORT, port);
                restored.set(SpamserviceInstallationModel.MAX_HEAP_MB, heap);
                installations.save(restored);
            }
            SpamserviceManager.get().reconcile();
        }
    }

    /**
     * A failed delivery retried and a delivered one refused, both listed by their codes; the caller's own inbox items
     * listed, read and marked read by theirs, by the record they are about, and all at once, a resent request answering
     * its first result; each wrong call refused by the argument it names, and someone without the delivery log's right
     * refused, as is a mark of another person's item.
     */
    @Test
    void anAdministratorRetriesADeliveryAndReadsTheInboxByToolsAlone() throws Exception {
        URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
        McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
        String tag = HohenheimMcpTest.letters();
        UUID operatorId = TenantConduits.operatorUser().get(UserModel.ID);
        McpHttpSession admin = HohenheimMcpTest.presetSession(server, endpoint, operatorId, "Test Admin");
        AccessContext operator = TenantConduits.operator();
        DeliveryModel deliveries = Models.get(DeliveryModel.class);
        NotificationInboxModel inbox = Models.get(NotificationInboxModel.class);
        try {
            // 1. The retry is a tool, and both kinds are listed.
            assertThat(admin.toolNames()).as("step 1: the tools").contains("delivery_retry");
            Row failed = delivery("mcp.failed." + tag, DeliveryStatus.FAILED);
            Row sent = delivery("mcp.sent." + tag, DeliveryStatus.SENT);
            String failedCode = HohenheimMcpTest.code(DeliveryModel.MODEL_ID, failed.get(DeliveryModel.ID), operator);
            String sentCode = HohenheimMcpTest.code(DeliveryModel.MODEL_ID, sent.get(DeliveryModel.ID), operator);
            assertThat(failedCode).as("step 1: a delivery's code").matches("ND\\d+");
            McpHttpSession.Answer listed = admin.call("list", Map.of("kind", "delivery", "search", tag));
            assertThat(listed.error()).as("step 1: " + listed.text()).isFalse();
            assertThat(listed.text()).as("step 1: the failed delivery listed by its code").contains(failedCode);

            // 2. The failed one goes again; the delivered one is refused in the delivery log's words.
            McpHttpSession.Answer retried = admin.call("delivery_retry", Map.of("code", failedCode,
                "request", UUID.randomUUID().toString()));
            assertThat(retried.error()).as("step 2: " + retried.text()).isFalse();
            assertThat(retried.text()).as("step 2: whether it went").containsAnyOf("Delivery retried and sent.",
                "Delivery re-queued; the sweeper will retry it.");
            Row again = Objects.requireNonNull(deliveries.find().noCache().where(DeliveryModel.ID.eq(
                failed.get(DeliveryModel.ID))).first(), "step 2: the delivery");
            assertThat((Integer) again.get(DeliveryModel.ATTEMPT_COUNT)).as("step 2: attempted once more")
                .isGreaterThanOrEqualTo(1);
            McpHttpSession.Answer refused = admin.call("delivery_retry", Map.of("code", sentCode));
            assertThat(refused.error()).as("step 2: a delivered one: " + refused.text()).isTrue();
            assertThat(refused.text()).as("step 2: in its words").contains("Only failed deliveries can be retried.");
            assertThat(admin.call("delivery_retry", Map.of()).text()).as("step 2: the code is asked for")
                .contains("code");

            // 3. An item of the operator's own inbox: listed by code, read in its words.
            String owner = NotificationInboxOwners.userKey(operatorId);
            Row about = item(owner, "mcp about " + tag, null);
            String aboutCode = HohenheimMcpTest.code(NotificationInboxModel.MODEL_ID, about.get(NotificationInboxModel.ID),
                operator);
            McpHttpSession.Answer items = admin.call("list", Map.of("kind", "notification_inbox_item"));
            assertThat(items.error()).as("step 3: " + items.text()).isFalse();
            assertThat(items.text()).as("step 3: the items listed by code").containsPattern("N\\d+");
            McpHttpSession.Answer item = admin.call("read", Map.of("codes", List.of(aboutCode)));
            assertThat(item.error()).as("step 3: " + item.text()).isFalse();
            assertThat(item.text()).as("step 3: its words").contains("mcp about " + tag);

            // 3b. One item marked read by its code; the same request again answers the first result, a new one says
            //     it was read already.
            Row one = item(owner, "mcp one " + tag, null);
            String oneCode = HohenheimMcpTest.code(NotificationInboxModel.MODEL_ID, one.get(NotificationInboxModel.ID),
                operator);
            String request = UUID.randomUUID().toString();
            McpHttpSession.Answer marked = admin.call("notification_mark_read", Map.of("code", oneCode,
                "request", request));
            assertThat(marked.error()).as("step 3b: " + marked.text()).isFalse();
            assertThat(marked.text()).as("step 3b: one item").contains("marked read: 1 item");
            assertThat(admin.call("notification_mark_read", Map.of("code", oneCode, "request", request)).text())
                .as("step 3b: the resent request answers the first result").contains("marked read: 1 item");
            assertThat(admin.call("notification_mark_read", Map.of("code", oneCode)).text())
                .as("step 3b: a new call finds it read").contains("marked read: none, it was read already");
            assertThat(inbox.find().noCache().where(NotificationInboxModel.ID.eq(one.get(NotificationInboxModel.ID)))
                .first().get(NotificationInboxModel.READ_AT)).as("step 3b: stored read").isNotNull();

            // 3c. Every item about one record, by that record's code: the code says its kind.
            item(owner, "mcp delivery " + tag, failed);
            item(owner, "mcp delivery " + tag, failed);
            McpHttpSession.Answer aboutRead = admin.call("notification_mark_read", Map.of("about", failedCode,
                "request", UUID.randomUUID().toString()));
            assertThat(aboutRead.error()).as("step 3c: " + aboutRead.text()).isFalse();
            assertThat(aboutRead.text()).as("step 3c: both items about it").contains("marked read: 2 items");

            // 3d. Both record arguments, or the kind the code already says, are refused by name; the kind is offered
            //     nowhere.
            McpHttpSession.Answer both = admin.call("notification_mark_read", Map.of("code", oneCode,
                "about", failedCode));
            assertThat(both.error()).as("step 3d: both").isTrue();
            assertThat(both.text()).as("step 3d: in words").contains("Give only one of code, about.");
            McpHttpSession.Answer kind = admin.call("notification_mark_read", Map.of("about", failedCode,
                "model", DeliveryModel.MODEL_ID.toString()));
            assertThat(kind.error()).as("step 3d: model refused: " + kind.text()).isTrue();
            assertThat(kind.text()).as("step 3d: by its name").contains("model");
            Map<?, ?> marks = (Map<?, ?>) ((Map<?, ?>) admin.tools().stream()
                .filter(tool -> "notification_mark_read".equals(tool.get("name"))).findFirst().orElseThrow()
                .get("inputSchema")).get("properties");
            assertThat(marks.keySet().stream().map(String::valueOf).toList()).as("step 3d: the arguments")
                .containsExactlyInAnyOrder("code", "about", "request");

            // 3e. Neither marks every item of the caller read.
            McpHttpSession.Answer all = admin.call("notification_mark_read", Map.of("request",
                UUID.randomUUID().toString()));
            assertThat(all.error()).as("step 3e: " + all.text()).isFalse();
            assertThat(all.text()).as("step 3e: every item").contains("marked read: every item of yours");
            assertThat(inbox.find().noCache().where(NotificationInboxModel.RECIPIENT.eq(owner))
                .and(NotificationInboxModel.TITLE.in(List.of("mcp about " + tag, "mcp delivery " + tag)))
                .and(NotificationInboxModel.READ_AT.isNull()).count()).as("step 3e: none of them unread").isZero();

            // 4. Someone without the delivery log's right retries nothing, and reads no one else's inbox item.
            McpHttpSession person = HohenheimMcpTest.presetSession(server, endpoint,
                user("mcp-delivery-" + tag + "@surface.test", "Mcp Delivery"), "Mcp Delivery");
            assertThat(person.call("delivery_retry", Map.of("code", failedCode)).error())
                .as("step 4: no retry").isTrue();
            assertThat(person.call("read", Map.of("codes", List.of(aboutCode))).text())
                .as("step 4: not his item").doesNotContain("mcp about " + tag);
            Row unread = item(owner, "mcp one " + tag, null);
            McpHttpSession.Answer foreign = person.call("notification_mark_read", Map.of("code",
                HohenheimMcpTest.code(NotificationInboxModel.MODEL_ID, unread.get(NotificationInboxModel.ID),
                    operator)));
            assertThat(foreign.error()).as("step 4: not his to mark: " + foreign.text()).isTrue();
            assertThat(inbox.find().noCache().where(NotificationInboxModel.ID.eq(unread.get(NotificationInboxModel.ID)))
                .first().get(NotificationInboxModel.READ_AT)).as("step 4: still unread").isNull();
        } finally {
            inbox.find().where(NotificationInboxModel.NOTIFICATION_KEY.eq("mcp.inbox"))
                .and(NotificationInboxModel.TITLE.in(List.of("mcp about " + tag, "mcp one " + tag,
                    "mcp delivery " + tag))).delete();
            deliveries.find().where(DeliveryModel.NOTIFICATION_KEY.in(List.of("mcp.failed." + tag, "mcp.sent." + tag)))
                .delete();
        }
    }

    /** @return a stored chat delivery of this key and status, as the outbox leaves one */
    private static Row delivery(String key, DeliveryStatus status) {
        DeliveryModel deliveries = Models.get(DeliveryModel.class);
        Row row = deliveries.createEmptyRow();
        row.set(DeliveryModel.NOTIFICATION_KEY, key);
        row.set(DeliveryModel.CHANNEL, DeliveryChannel.CHAT.getDbValue());
        row.set(DeliveryModel.STATUS, status.getDbValue());
        row.set(DeliveryModel.PAYLOAD, "{}");
        row.set(DeliveryModel.CREATED_AT, Now.instant());
        deliveries.save(row);
        return row;
    }

    /** @return an unread inbox item of the owner, about the delivery when one is given */
    private static Row item(String owner, String title, Row about) {
        NotificationInboxModel inbox = Models.get(NotificationInboxModel.class);
        Row item = inbox.createEmptyRow();
        item.set(NotificationInboxModel.RECIPIENT, owner);
        item.set(NotificationInboxModel.NOTIFICATION_KEY, "mcp.inbox");
        item.set(NotificationInboxModel.TITLE, title);
        if (about != null) {
            item.set(NotificationInboxModel.SUBJECT_MODEL, DeliveryModel.MODEL_ID.toString());
            item.set(NotificationInboxModel.SUBJECT_ID, String.valueOf((Object) about.get(DeliveryModel.ID)));
        }
        item.set(NotificationInboxModel.CREATED_AT, Now.instant());
        item.set(NotificationInboxModel.UPDATED_AT, Now.instant());
        inbox.save(item);
        return item;
    }
}
