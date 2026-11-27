package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.cms.SpamserviceClientKeysResource;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static be.elevenways.hohenheim.test.ApiSupport.user;
import static be.elevenways.hohenheim.test.HohenheimAppPartToolsTest.codeIn;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The spam service's clients, keys and samples through the real MCP endpoint as an administrator's key, each named by
 * the code the service gives it: a client made and changed by what is sent alone; a key made under it, its generated
 * key waiting once on the client's Keys tab and never in an answer, a resent request answering the first result, an
 * adopted key, the key renamed and turned off and on; a sample's verdict, its rescore refused once confirmed, another
 * rescored. Each wrong call is refused by the argument it got wrong, a code naming nothing as the tools' not-found,
 * a person who is no administrator changes nothing, and without the service every call says so.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class HohenheimSpamRecordToolsTest extends HohenheimHttpTestBase {

    @Test
    void anAdministratorRunsTheSpamServicesClientsKeysAndSamplesByToolsAlone() throws Exception {
        URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
        McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
        String tag = HohenheimMcpTest.letters();
        UUID adminId = TenantConduits.operatorUser().get(UserModel.ID);
        McpHttpSession admin = HohenheimMcpTest.presetSession(server, endpoint, adminId, "Test Admin");
        AccessContext owner = AccessContext.of(TenantConduits.stubFor(new UserPrincipal(adminId, "Test Admin")));

        // 1. The three tools are the connection's own.
        assertThat(admin.toolNames()).as("step 1: the tools")
            .contains("spam_client_set", "spam_key_set", "spam_sample_set");

        try (FakeSpamservice fake = FakeSpamservice.start(Map.of())) {
            // 2. A client: a create without its name refused in the framework's words, then made and answered by the
            //    code the service gave it.
            McpHttpSession.Answer unnamed = admin.call("spam_client_set", Map.of("trusted", true));
            assertThat(unnamed.error()).as("step 2: " + unnamed.text()).isTrue();
            assertThat(unnamed.text()).as("step 2: names what a create needs")
                .contains("To create a spam client, give name.");
            McpHttpSession.Answer made = admin.call("spam_client_set", Map.of("name", "mail " + tag,
                "allowed_languages", "nld,eng", "spam_threshold", 60));
            assertThat(made.error()).as("step 2: " + made.text()).isFalse();
            String client = codeIn(made.text(), "SPC");
            Map<String, Object> stored = record(fake.clients(), client);
            assertThat(stored).as("step 2: the service holds it")
                .containsEntry("name", "mail " + tag).containsEntry("allowed_languages", "nld,eng")
                .containsEntry("spam_threshold", 60).containsEntry("enabled", true).containsEntry("trusted", false);

            // 3. Changed by what is sent alone; a wrong value and an argument it does not take refused by their names;
            //    a code naming nothing read as the tools' not-found.
            McpHttpSession.Answer changed = admin.call("spam_client_set", Map.of("code", client, "trusted", true,
                "spam_threshold", 70));
            assertThat(changed.error()).as("step 3: " + changed.text()).isFalse();
            assertThat(changed.text()).as("step 3: what it wrote").contains(client).contains("trusted")
                .contains("spam_threshold");
            assertThat(record(fake.clients(), client)).as("step 3: the rest stays")
                .containsEntry("trusted", true).containsEntry("spam_threshold", 70)
                .containsEntry("name", "mail " + tag).containsEntry("allowed_languages", "nld,eng")
                .containsEntry("enabled", true);
            McpHttpSession.Answer wrong = admin.call("spam_client_set", Map.of("code", client,
                "spam_threshold", "high"));
            assertThat(wrong.error()).as("step 3: " + wrong.text()).isTrue();
            assertThat(wrong.text()).as("step 3: names the threshold").contains("spam_threshold");
            McpHttpSession.Answer owned = admin.call("spam_client_set", Map.of("code", client, "external_id", "x"));
            assertThat(owned.error()).as("step 3: " + owned.text()).isTrue();
            assertThat(owned.text()).as("step 3: the provisioner's id is no argument").contains("external_id");
            McpHttpSession.Answer nothing = admin.call("spam_client_set", Map.of("code", "SPC999", "trusted", true));
            assertThat(nothing.error()).as("step 3: " + nothing.text()).isTrue();
            assertThat(nothing.text()).as("step 3: the usual not-found").contains("Nothing here is named SPC999");

            // 4. A key: a create without its client refused; made with a generated key that waits, once, on the
            //    client's Keys tab for the account behind the call and never in the answer; the same request again
            //    answers the first key and makes none.
            McpHttpSession.Answer keyless = admin.call("spam_key_set", Map.of("name", "primary"));
            assertThat(keyless.error()).as("step 4: " + keyless.text()).isTrue();
            assertThat(keyless.text()).as("step 4: names what a create needs")
                .contains("To create a spam key, give spam_client and name.");
            Map<String, Object> request = Map.of("spam_client", client, "name", "primary",
                "request", UUID.randomUUID().toString());
            McpHttpSession.Answer key = admin.call("spam_key_set", request);
            assertThat(key.error()).as("step 4: " + key.text()).isFalse();
            String keyCode = codeIn(key.text(), "SPK");
            assertThat(key.text()).as("step 4: where it waits").contains("Keys tab of " + client);
            String waiting = SpamserviceClientKeysResource.claimWaiting(String.valueOf(stored.get("id")), owner);
            assertThat(waiting).as("step 4: the key waits on the client's tab").isNotNull().contains(keyCode)
                .contains("primary");
            String raw = waiting.substring(waiting.indexOf(": ") + 2).trim();
            assertThat(raw).as("step 4: the generated key").startsWith("spamkey_");
            assertThat(key.text()).as("step 4: never in the answer").doesNotContain(raw);
            assertThat(SpamserviceClientKeysResource.claimWaiting(String.valueOf(stored.get("id")), owner))
                .as("step 4: shown once").isNull();
            McpHttpSession.Answer again = admin.call("spam_key_set", request);
            assertThat(again.text()).as("step 4: the same request answers the first key").contains(keyCode)
                .doesNotContain(raw);
            assertThat(fake.keys()).as("step 4: one key made").hasSize(1);

            // 5. An adopted key goes in, made off at once, and is never answered either; a client naming nothing
            //    refused by its argument's name.
            String adopted = "spamadopted" + tag;
            McpHttpSession.Answer legacy = admin.call("spam_key_set", Map.of("spam_client", client, "name", "legacy",
                "key", adopted, "enabled", false));
            assertThat(legacy.error()).as("step 5: " + legacy.text()).isFalse();
            assertThat(legacy.text()).as("step 5: made with the given key").contains("made with the key you gave")
                .doesNotContain(adopted);
            assertThat(SpamserviceClientKeysResource.claimWaiting(String.valueOf(stored.get("id")), owner))
                .as("step 5: nothing waits for an adopted key").isNull();
            assertThat(fake.keys().values()).as("step 5: the adopted key is off")
                .anySatisfy(each -> assertThat(each).containsEntry("name", "legacy").containsEntry("active", false));
            McpHttpSession.Answer orphan = admin.call("spam_key_set", Map.of("spam_client", "SPC999", "name", "x"));
            assertThat(orphan.error()).as("step 5: " + orphan.text()).isTrue();
            assertThat(orphan.text()).as("step 5: names the client argument").contains("spam_client")
                .contains("Nothing here is named SPC999");

            // 6. The key turned off, renamed and on again by its code; its key a create's alone.
            McpHttpSession.Answer off = admin.call("spam_key_set", Map.of("code", keyCode, "enabled", false));
            assertThat(off.error()).as("step 6: " + off.text()).isFalse();
            assertThat(record(fake.keys(), keyCode)).as("step 6: off").containsEntry("active", false)
                .containsEntry("name", "primary");
            McpHttpSession.Answer renamed = admin.call("spam_key_set", Map.of("code", keyCode, "name", "renamed",
                "enabled", true));
            assertThat(renamed.error()).as("step 6: " + renamed.text()).isFalse();
            assertThat(record(fake.keys(), keyCode)).as("step 6: renamed and on").containsEntry("active", true)
                .containsEntry("name", "renamed");
            McpHttpSession.Answer rekeyed = admin.call("spam_key_set", Map.of("code", keyCode, "key", "x"));
            assertThat(rekeyed.error()).as("step 6: " + rekeyed.text()).isTrue();
            assertThat(rekeyed.text()).as("step 6: names the key").contains("key applies only when creating");

            // 7. A sample: a verdict confirms it and its rescore is then refused in the service's words; another is
            //    rescored, then judged; a call naming neither, a verdict of no allowed value and a code naming nothing
            //    refused.
            String first = fake.sample("203.0.113.7", 40, 90);
            String second = fake.sample("203.0.113.8", 30, 75);
            McpHttpSession.Answer spam = admin.call("spam_sample_set", Map.of("code", first, "verdict", "spam"));
            assertThat(spam.error()).as("step 7: " + spam.text()).isFalse();
            assertThat(spam.text()).as("step 7: the verdict").contains("Sample confirmed as spam");
            assertThat(record(fake.samples(), first)).as("step 7: confirmed as spam").containsEntry("spam", true)
                .containsEntry("confirmed", true);
            McpHttpSession.Answer frozen = admin.call("spam_sample_set", Map.of("code", first, "rescore", true));
            assertThat(frozen.error()).as("step 7: " + frozen.text()).isTrue();
            assertThat(frozen.text()).as("step 7: in the service's words")
                .contains("Confirmed samples cannot be rescored");
            McpHttpSession.Answer rescored = admin.call("spam_sample_set", Map.of("code", second, "rescore", true));
            assertThat(rescored.error()).as("step 7: " + rescored.text()).isFalse();
            assertThat(rescored.text()).as("step 7: the new score").contains("75");
            McpHttpSession.Answer ham = admin.call("spam_sample_set", Map.of("code", second, "verdict", "ham"));
            assertThat(ham.text()).as("step 7: the verdict").contains("Sample confirmed as ham");
            assertThat(record(fake.samples(), second)).as("step 7: confirmed as ham").containsEntry("spam", false)
                .containsEntry("confirmed", true);
            assertThat(admin.call("spam_sample_set", Map.of("code", second)).error())
                .as("step 7: neither a verdict nor a rescore").isTrue();
            McpHttpSession.Answer maybe = admin.call("spam_sample_set", Map.of("code", second, "verdict", "maybe"));
            assertThat(maybe.error()).as("step 7: " + maybe.text()).isTrue();
            assertThat(maybe.text()).as("step 7: names the verdict").contains("verdict");
            McpHttpSession.Answer unknown = admin.call("spam_sample_set", Map.of("code", "SPS999", "verdict", "ham"));
            assertThat(unknown.text()).as("step 7: the usual not-found").contains("Nothing here is named SPS999");

            // 7a. Read names each by its code, asking the service; the key's and the sample's client by its code; a
            //     code the service does not hold is the usual not-found, and a person who is no administrator reads
            //     none of them.
            McpHttpSession.Answer read = admin.call("read", Map.of("codes", List.of(client, keyCode, first)));
            assertThat(read.error()).as("step 7a: " + read.text()).isFalse();
            assertThat(read.text()).as("step 7a: each under its kind").contains(client + " (spam_client)")
                .contains("mail " + tag).contains(keyCode + " (spam_key)").contains("renamed")
                .contains(first + " (spam_sample)").contains("203.0.113.7").contains("spam_client: " + client)
                .doesNotContain(raw).doesNotContain("client_id");
            assertThat(admin.call("read", Map.of("codes", List.of("SPC999"))).text()).as("step 7a: held nowhere")
                .contains("Nothing here is named SPC999");
            McpHttpSession outsider = HohenheimMcpTest.presetSession(server, endpoint,
                user("mcp-spam-reader-" + tag + "@surface.test", "Mcp Spam Reader"), "Mcp Spam Reader");
            assertThat(outsider.call("read", Map.of("codes", List.of(client))).error())
                .as("step 7a: no administrator, nothing read").isTrue();

            // 7b. The one list lists each kind from the service, by code: a client's keys by of, never a key's
            //     value; describe says the calls acting on each; a person who is no administrator lists none.
            McpHttpSession.Answer clients = admin.call("list", Map.of("kind", "spam_client"));
            assertThat(clients.error()).as("step 7b: " + clients.text()).isFalse();
            assertThat(clients.text()).as("step 7b: the client by its code").contains(client).contains("mail " + tag);
            McpHttpSession.Answer keys = admin.call("list", Map.of("kind", "spam_key", "of", client));
            assertThat(keys.error()).as("step 7b: " + keys.text()).isFalse();
            assertThat(keys.text()).as("step 7b: its keys, never their values").contains(keyCode).contains("renamed")
                .contains("spam_client: " + client).doesNotContain(raw).doesNotContain(adopted);
            McpHttpSession.Answer orphaned = admin.call("list", Map.of("kind", "spam_key"));
            assertThat(orphaned.error()).as("step 7b: keys of no client " + orphaned.text()).isTrue();
            assertThat(orphaned.text()).as("step 7b: names of").contains("Give of: the code of the spam client");
            McpHttpSession.Answer samples = admin.call("list", Map.of("kind", "spam_sample"));
            assertThat(samples.text()).as("step 7b: the samples").contains(first).contains(second)
                .contains("203.0.113.8");
            assertThat(admin.call("describe", Map.of("code", client)).text()).as("step 7b: describe a client")
                .contains("spam_client_set code=" + client);
            assertThat(admin.call("describe", Map.of("code", keyCode)).text()).as("step 7b: describe a key")
                .contains("spam_key_set code=" + keyCode);
            assertThat(admin.call("describe", Map.of("code", "SPC999")).error()).as("step 7b: describe nothing")
                .isTrue();
            assertThat(outsider.call("list", Map.of("kind", "spam_client")).error())
                .as("step 7b: no administrator, nothing listed").isTrue();

            // 8. A person who is no administrator changes none of them.
            McpHttpSession person = HohenheimMcpTest.presetSession(server, endpoint,
                user("mcp-spam-records-" + tag + "@surface.test", "Mcp Spam Records"), "Mcp Spam Records");
            assertThat(person.call("spam_client_set", Map.of("code", client, "trusted", false)).error())
                .as("step 8: no client change").isTrue();
            assertThat(person.call("spam_key_set", Map.of("code", keyCode, "enabled", false)).error())
                .as("step 8: no key change").isTrue();
            assertThat(record(fake.clients(), client)).as("step 8: still trusted").containsEntry("trusted", true);
            assertThat(record(fake.keys(), keyCode)).as("step 8: still on").containsEntry("active", true);
        }

        // 9. Without the service every call is refused in the spam tools' words, never as a record naming nothing.
        McpHttpSession.Answer away = admin.call("spam_client_set", Map.of("code", "SPC1", "trusted", true));
        assertThat(away.error()).as("step 9: " + away.text()).isTrue();
        assertThat(away.text()).as("step 9: not a missing record").doesNotContain("Nothing here is named");
        assertThat(admin.call("spam_client_set", Map.of("name", "late " + tag)).error()).as("step 9: no create")
            .isTrue();
        McpHttpSession.Answer unread = admin.call("read", Map.of("codes", List.of("SPC1")));
        assertThat(unread.error()).as("step 9: " + unread.text()).isTrue();
        assertThat(unread.text()).as("step 9: the read too, never as a record naming nothing")
            .doesNotContain("Nothing here is named");
        McpHttpSession.Answer unlisted = admin.call("list", Map.of("kind", "spam_client"));
        assertThat(unlisted.error()).as("step 9: the list too " + unlisted.text()).isTrue();
        assertThat(unlisted.text()).as("step 9: in the spam tools' words").doesNotContain("Nothing here is named");
    }

    /** @return the record the fake service holds under this code */
    private static Map<String, Object> record(Map<String, Map<String, Object>> records, String code) {
        return records.values().stream().filter(record -> code.equals(record.get("code"))).findFirst()
            .orElseThrow(() -> new AssertionError("the service holds no " + code));
    }
}
