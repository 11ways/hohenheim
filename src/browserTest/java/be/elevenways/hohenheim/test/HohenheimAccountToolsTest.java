package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.auth.model.ApiKeyModel;
import be.elevenways.zenit.auth.model.SshKeyModel;
import be.elevenways.zenit.auth.model.TotpModel;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AccountOperations;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.CredentialDisclosures;
import be.elevenways.zenit.auth.server.PasswordService;
import be.elevenways.zenit.auth.test.TestSessions;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Secret;
import be.elevenways.zenit.server.operation.RecordCodes;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The caller's own account tools through the real MCP endpoint, as a customer who administers nothing: the default
 * assistant preset reads the account and is refused every own-account write naming what it lacks; a key carrying the
 * account access changes the name and language, adds and removes an SSH key, makes a second API key whose secret
 * waits on the API keys page, changes and revokes it, ends another session and every session, and turns two-step
 * sign-in off, each wrong call refused by the argument that made it wrong.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class HohenheimAccountToolsTest extends HohenheimHttpTestBase {

    private static final String PREFIX = "mcp-account-";

    private static final String PASSWORD = "the customer's own password";

    /** A key's code as an answer names it. */
    private static final Pattern KEY_CODE = Pattern.compile("\\bAK\\d+\\b");

    /** A session's line in the account read, and its code. */
    private static final Pattern SESSION = Pattern.compile("Session (\\S+): ");

    /** The tools only a key that may act on its own account is offered. */
    private static final List<String> WRITES = List.of("account_set", "account_reset", "api_key_set",
        "ssh_key_set", "session_end");

    @Test
    void aCustomerRunsHisOwnAccountByToolsAlone() throws Exception {
        URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
        McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
        String tag = HohenheimMcpTest.letters();
        String email = PREFIX + tag + "@example.test";
        Deque<Runnable> cleanUp = new ArrayDeque<>();
        try {
            UUID customerId = ApiSupport.user(email, "Mcp Account Customer");
            cleanUp.push(() -> HardDeletes.byId(Models.get(UserModel.class), customerId));
            PasswordService.setPassword(customerId, PASSWORD);
            McpHttpSession preset = HohenheimMcpTest.presetSession(server, endpoint, customerId,
                "Mcp Account Customer");

            // 1. The default preset reads its account, says it may not act on it, and is offered none of the writes.
            McpHttpSession.Answer read = preset.call("account_read", Map.of());
            assertThat(read.error()).as("step 1: " + read.text()).isFalse();
            assertThat(read.text()).as("step 1: the account in its words").contains("Mcp Account Customer <" + email
                + ">").contains("You are not an administrator.").contains("may not act on its own account")
                .doesNotContain(customerId.toString()).doesNotContain("Session ");
            assertThat(preset.toolNames()).as("step 1: the writes are not offered").contains("account_read")
                .doesNotContainAnyElementsOf(WRITES);

            // 2. Each write it calls anyway is refused, naming what it lacks in plain words.
            List<Map<String, Object>> calls = List.of(Map.of("name", "Not Me"),
                Map.of("what", "two_step", "current_password", PASSWORD), Map.of("name", "Not a key"),
                Map.of("public_key", publicKey("refused")), Map.of("all", true));
            for (int at = 0; at < WRITES.size(); at++) {
                McpHttpSession.Answer refused = preset.call(WRITES.get(at), calls.get(at));
                assertThat(refused.error()).as("step 2: " + WRITES.get(at) + " " + refused.text()).isTrue();
                assertThat(refused.text()).as("step 2: " + WRITES.get(at) + " names the permission")
                    .contains("Permission denied").contains("act on its own account")
                    .contains(AccountOperations.OWN_ACCOUNT_SCOPE);
            }
            assertThat(account(customerId).get(UserModel.DISPLAY_NAME)).as("step 2: nothing changed")
                .isEqualTo("Mcp Account Customer");

            // 3. A key carrying the account access is offered every write and says so.
            McpHttpSession own = HohenheimMcpTest.presetSession(server, endpoint, customerId,
                "Mcp Account Customer", AccountOperations.OWN_ACCOUNT_SCOPE);
            assertThat(own.toolNames()).as("step 3: the writes").containsAll(WRITES);
            assertThat(own.call("account_read", Map.of()).text()).as("step 3: it may")
                .contains("This key may act on its own account").contains("Key presets api_key_set offers:");

            // 4. Its name and language change in one call; a blank name is refused by its argument.
            McpHttpSession.Answer renamed = own.call("account_set", Map.of("name", "Mcp Renamed Customer",
                "language", "nl"));
            assertThat(renamed.error()).as("step 4: " + renamed.text()).isFalse();
            assertThat(account(customerId).get(UserModel.DISPLAY_NAME)).as("step 4: renamed")
                .isEqualTo("Mcp Renamed Customer");
            assertThat(account(customerId).get(UserModel.LOCALE)).as("step 4: in Dutch").isEqualTo("nl");
            McpHttpSession.Answer blank = own.call("account_set", Map.of("name", " "));
            assertThat(blank.error()).as("step 4: " + blank.text()).isTrue();
            assertThat(blank.text()).as("step 4: by its argument").contains("name");
            assertThat(own.call("account_set", Map.of("language", "en")).error()).as("step 4: back in English")
                .isFalse();

            // 5. An SSH key is added and removed by its public half; a name with a removal is refused.
            String sshKey = publicKey("mcp-" + tag);
            McpHttpSession.Answer added = own.call("ssh_key_set", Map.of("public_key", sshKey));
            assertThat(added.error()).as("step 5: " + added.text()).isFalse();
            assertThat(sshKeys(customerId)).as("step 5: added").isEqualTo(1L);
            assertThat(Models.get(SshKeyModel.class).find().noCache().where(SshKeyModel.USER_ID.eq(customerId))
                .first().get(SshKeyModel.LABEL)).as("step 5: named by its comment").isEqualTo("mcp-" + tag);
            McpHttpSession.Answer named = own.call("ssh_key_set", Map.of("public_key", sshKey, "remove", true,
                "name", "x"));
            assertThat(named.error()).as("step 5: " + named.text()).isTrue();
            assertThat(named.text()).as("step 5: name only adds").contains("name").contains("remove=false");
            McpHttpSession.Answer removed = own.call("ssh_key_set", Map.of("public_key", sshKey, "remove", true));
            assertThat(removed.error()).as("step 5: " + removed.text()).isFalse();
            assertThat(sshKeys(customerId)).as("step 5: removed").isZero();

            // 6. A second API key: its code and prefix answered, its secret never; it waits once on the API keys
            //    page of the account, and signs in.
            McpHttpSession.Answer made = own.call("api_key_set", Map.of("name", "Second key " + tag,
                "preset", "mcp:hohenheim", "expires_in_days", 30));
            assertThat(made.error()).as("step 6: " + made.text()).isFalse();
            Matcher code = KEY_CODE.matcher(made.text());
            assertThat(code.find()).as("step 6: a code in " + made.text()).isTrue();
            String keyCode = code.group();
            assertThat(made.text()).as("step 6: where it waits").contains("API keys");
            AccessContext page = AccessContext.of(TenantConduits.stubFor(new UserPrincipal(customerId,
                "Mcp Renamed Customer")));
            Secret secret = CredentialDisclosures.claimApiKey(page, customerId);
            assertThat(secret).as("step 6: parked for the account").isNotNull();
            assertThat(made.text()).as("step 6: never answered").doesNotContain(secret.reveal());
            assertThat(CredentialDisclosures.claimApiKey(page, customerId)).as("step 6: shown once").isNull();
            assertThat(ApiKeyService.authenticate(secret.reveal())).as("step 6: it signs in").isNotNull();
            assertThat(own.call("account_read", Map.of()).text()).as("step 6: the read lists it by its code")
                .contains(keyCode + " Second key " + tag);
            McpHttpSession.Answer refusedName = own.call("api_key_set", Map.of("code", keyCode, "name", "x"));
            assertThat(refusedName.error()).as("step 6: " + refusedName.text()).isTrue();
            assertThat(refusedName.text()).as("step 6: a name is a create's only").contains("name");
            McpHttpSession.Answer rescoped = own.call("api_key_set", Map.of("code", keyCode, "scopes",
                List.of("mcp:hohenheim", AccountOperations.OWN_ACCOUNT_SCOPE)));
            assertThat(rescoped.error()).as("step 6: " + rescoped.text()).isFalse();
            assertThat(keyScopes(keyCode, customerId)).as("step 6: its access replaced")
                .containsExactlyInAnyOrder("mcp:hohenheim", AccountOperations.OWN_ACCOUNT_SCOPE);
            McpHttpSession.Answer revoked = own.call("delete", Map.of("code", keyCode));
            assertThat(revoked.error()).as("step 6: " + revoked.text()).isFalse();
            assertThat(ApiKeyService.authenticate(secret.reveal())).as("step 6: revoked").isNull();

            // 7. Another browser session ends by its code, then every one; a code beside all is refused.
            TestSessions.signedIn(customerId);
            TestSessions.signedIn(customerId);
            List<String> sessions = sessionCodes(own.call("account_read", Map.of()).text());
            assertThat(sessions).as("step 7: both browsers read").hasSize(2);
            McpHttpSession.Answer both = own.call("session_end", Map.of("code", sessions.getFirst(), "all", true));
            assertThat(both.error()).as("step 7: " + both.text()).isTrue();
            assertThat(both.text()).as("step 7: code goes without all").contains("code");
            McpHttpSession.Answer one = own.call("session_end", Map.of("code", sessions.getFirst()));
            assertThat(one.error()).as("step 7: " + one.text()).isFalse();
            assertThat(sessionCodes(own.call("account_read", Map.of()).text())).as("step 7: the other stays")
                .containsExactly(sessions.get(1));
            assertThat(own.call("session_end", Map.of("all", true)).text()).as("step 7: every one")
                .contains("Sessions ended: 1.");
            assertThat(sessionCodes(own.call("account_read", Map.of()).text())).as("step 7: none left").isEmpty();
            assertThat(own.call("account_read", Map.of()).error()).as("step 7: the key keeps working").isFalse();

            // 8. Two-step sign-in is turned off with the password; a wrong one and an argument of another reset are
            //    refused by their names.
            twoStepOn(customerId);
            McpHttpSession.Answer wrong = own.call("account_reset", Map.of("what", "two_step",
                "current_password", "not the password"));
            assertThat(wrong.error()).as("step 8: " + wrong.text()).isTrue();
            assertThat(wrong.text()).as("step 8: by its argument").contains("current_password");
            McpHttpSession.Answer stray = own.call("account_reset", Map.of("what", "two_step",
                "current_password", PASSWORD, "connected_app", "x"));
            assertThat(stray.error()).as("step 8: " + stray.text()).isTrue();
            assertThat(stray.text()).as("step 8: the app goes with its what").contains("connected_app");
            McpHttpSession.Answer off = own.call("account_reset", Map.of("what", "two_step",
                "current_password", PASSWORD));
            assertThat(off.error()).as("step 8: " + off.text()).isFalse();
            assertThat(off.text()).as("step 8: said").contains("Two-step sign-in is off.");
            assertThat(Models.get(TotpModel.class).find().where(TotpModel.USER_ID.eq(customerId)).count())
                .as("step 8: off").isZero();

            // 9. The password changes with the current one, never without it; the key keeps working.
            McpHttpSession.Answer bare = own.call("account_set", Map.of("new_password", "a new password, long"));
            assertThat(bare.error()).as("step 9: " + bare.text()).isTrue();
            assertThat(bare.text()).as("step 9: by its argument").contains("current_password");
            McpHttpSession.Answer changed = own.call("account_set", Map.of("current_password", PASSWORD,
                "new_password", "a new password, long"));
            assertThat(changed.error()).as("step 9: " + changed.text()).isFalse();
            assertThat(PasswordService.verifyPassword(customerId, "a new password, long")).as("step 9: changed")
                .isTrue();
            assertThat(own.call("account_read", Map.of()).error()).as("step 9: the key keeps working").isFalse();
        } finally {
            while (!cleanUp.isEmpty()) cleanUp.pop().run();
        }
    }

    private static Row account(UUID accountId) {
        return Objects.requireNonNull(Models.get(UserModel.class).find().noCache()
            .where(UserModel.ID.eq(accountId)).first(), "the account");
    }

    private static long sshKeys(UUID accountId) {
        return Models.get(SshKeyModel.class).find().noCache().where(SshKeyModel.USER_ID.eq(accountId)).count();
    }

    /** @return the scopes of the account's key the code names */
    private static List<String> keyScopes(String code, UUID accountId) {
        UUID keyId = UUID.fromString(Objects.requireNonNull(RecordCodes.keyOfCode(ApiKeyModel.MODEL_ID, code), code));
        Row key = Objects.requireNonNull(Models.get(ApiKeyModel.class).find().noCache()
            .where(ApiKeyModel.ID.eq(keyId)).and(ApiKeyModel.USER_ID.eq(accountId)).first(), code);
        return key.get(ApiKeyModel.SCOPES);
    }

    /** @return the session codes an account read lists */
    private static List<String> sessionCodes(String read) {
        List<String> codes = new ArrayList<>();
        Matcher session = SESSION.matcher(read);
        while (session.find()) codes.add(session.group(1));
        return codes;
    }

    /** Sets two-step sign-in up for the account, as its confirmed enrolment stores it. */
    private static void twoStepOn(UUID accountId) {
        TotpModel model = Models.get(TotpModel.class);
        Row row = model.createEmptyRow();
        row.set(TotpModel.USER_ID, accountId);
        row.set(TotpModel.SECRET, "JBSWY3DPEHPK3PXP");
        row.set(TotpModel.BACKUP_CODES, List.of());
        row.set(TotpModel.CONFIRMED_AT, Now.instant());
        model.save(row);
    }

    /** @return a fresh Ed25519 public key as one OpenSSH line, with this comment */
    private static String publicKey(String comment) throws GeneralSecurityException, IOException {
        byte[] spki = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded();
        byte[] raw = Arrays.copyOfRange(spki, spki.length - 32, spki.length);
        byte[] type = "ssh-ed25519".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(wire);
        out.writeInt(type.length);
        out.write(type);
        out.writeInt(raw.length);
        out.write(raw);
        return "ssh-ed25519 " + Base64.getEncoder().encodeToString(wire.toByteArray()) + " " + comment;
    }
}
