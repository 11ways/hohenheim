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
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
}
