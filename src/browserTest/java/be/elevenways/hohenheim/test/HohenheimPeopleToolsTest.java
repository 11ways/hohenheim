package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.auth.AuthEndpoints;
import be.elevenways.zenit.auth.model.PermissionGroupModel;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.server.setting.ServerSettings;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The people, role and sharing tools through the real MCP endpoint, as an administrator's key: a person invited, the
 * invitation sent again and cancelled; a person made, given a role, switched off and on, their address confirmed,
 * signed out everywhere and deleted; a role made, its permissions set and deleted; a site shared with a person, its
 * shares read and the share taken away; describe naming each call; and a customer's key refused each of these.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class HohenheimPeopleToolsTest extends HohenheimHttpTestBase {

    private static final String PREFIX = "mcp-people-";

    /** A record's code as an answer names it. */
    private static final Pattern CODE = Pattern.compile("\\b(U|RO)\\d+\\b");

    @Test
    void anAdministratorRunsPeopleRolesAndSharingByToolsAlone() {
        URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
        McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
        AccessContext operator = TenantConduits.operator();
        String tag = HohenheimMcpTest.letters();
        Deque<Runnable> cleanUp = new ArrayDeque<>();
        // An invitation's link is built on the installation's main URL.
        String mainUrl = ServerSettings.VALUES.getValue(ServerSettings.Network.MAIN_URL);
        ServerSettings.VALUES.setValue(ServerSettings.Network.MAIN_URL, "https://people.example.test");
        cleanUp.push(() -> ServerSettings.VALUES.setValue(ServerSettings.Network.MAIN_URL, mainUrl));
        try {
            McpHttpSession admin = HohenheimMcpTest.presetSession(server, endpoint,
                TenantConduits.operatorUser().get(UserModel.ID), "Test Admin");
            UUID customerId = ApiSupport.user(PREFIX + tag + "@surface.test", "Mcp People Customer");
            cleanUp.push(() -> HardDeletes.byId(Models.get(UserModel.class), customerId));
            McpHttpSession customer = HohenheimMcpTest.presetSession(server, endpoint, customerId,
                "Mcp People Customer");

            // 1. A create naming neither an address nor a name is refused in the spec's words; an invitation makes
            //    the account.
            assertThat(admin.call("person_set", Map.of()).text()).as("step 1: neither")
                .contains("To create a person, give at least one of email, name.");
            String invitedEmail = PREFIX + "invited-" + tag + "@example.test";
            McpHttpSession.Answer invited = admin.call("person_set", Map.of("email", invitedEmail,
                "name", "Ivo Invited", "invite", true));
            assertThat(invited.error()).as("step 1: " + invited.text()).isFalse();
            String ivo = code(invited.text(), "step 1");
            Row ivoRow = account(invitedEmail);
            cleanUp.push(() -> HardDeletes.byId(Models.get(UserModel.class), ivoRow.get(UserModel.ID)));

            // 2. The invitation is sent again, then cancelled: an account never used goes with it.
            McpHttpSession.Answer resent = admin.call("person_set", Map.of("code", ivo, "invite", true));
            assertThat(resent.error()).as("step 2: " + resent.text()).isFalse();
            assertThat(resent.text()).as("step 2: says what became of the link").contains("invitation link");
            McpHttpSession.Answer cancelled = admin.call("person_reset", Map.of("code", ivo, "what", "invitation"));
            assertThat(cancelled.error()).as("step 2: " + cancelled.text()).isFalse();
            assertThat(Models.get(UserModel.class).findById(ivoRow.get(UserModel.ID))).as("step 2: removed").isNull();

            // 3. A person made quietly, and a role made with one permission.
            String email = PREFIX + "hana-" + tag + "@example.test";
            McpHttpSession.Answer made = admin.call("person_set", Map.of("email", email, "name", "Hana Made"));
            assertThat(made.error()).as("step 3: " + made.text()).isFalse();
            String hana = code(made.text(), "step 3");
            UUID hanaId = account(email).get(UserModel.ID);
            cleanUp.push(() -> HardDeletes.byId(Models.get(UserModel.class), hanaId));
            assertThat(admin.call("role_set", Map.of("name", "No key")).text()).as("step 3: a role needs its key")
                .contains("To create a role, give key and name.");
            String key = PREFIX + tag;
            McpHttpSession.Answer roleMade = admin.call("role_set", Map.of("key", key, "name", "Mcp Role " + tag,
                "visibility", "private", "allow", List.of(AuthEndpoints.PERM_SESSIONS_REVOKE.value())));
            assertThat(roleMade.error()).as("step 3: " + roleMade.text()).isFalse();
            String role = code(roleMade.text(), "step 3");
            Row roleRow = Objects.requireNonNull(Models.get(PermissionGroupModel.class).find()
                .where(PermissionGroupModel.SLUG.eq(key)).first());
            cleanUp.push(() -> HardDeletes.byId(Models.get(PermissionGroupModel.class),
                roleRow.get(PermissionGroupModel.ID)));
            McpHttpSession.Answer reworded = admin.call("role_set", Map.of("code", role, "description", "For tests",
                "deny", List.of(AuthEndpoints.PERM_USERS_EDIT.value())));
            assertThat(reworded.error()).as("step 3: " + reworded.text()).isFalse();
            assertThat(admin.call("role_set", Map.of("code", role, "key", "other")).text())
                .as("step 3: the key is a create's only").contains("key");

            // 4. The person is given the role; an unknown role is refused by the argument that named it.
            McpHttpSession.Answer unknown = admin.call("person_set", Map.of("code", hana, "roles", List.of("RO99999")));
            assertThat(unknown.error()).as("step 4: " + unknown.text()).isTrue();
            // AIDEV-NOTE: core's code resolution names the code it could not read, not the argument holding it.
            assertThat(unknown.text()).as("step 4: names the code sent").contains("RO99999");
            McpHttpSession.Answer given = admin.call("person_set", Map.of("code", hana, "roles", List.of(role),
                "name", "Hana Given"));
            assertThat(given.error()).as("step 4: " + given.text()).isFalse();
            assertThat(given.text()).as("step 4: what changed").contains(hana).contains("roles").contains("name");
            assertThat((String) Models.get(UserModel.class).findById(hanaId).get(UserModel.DISPLAY_NAME))
                .as("step 4: renamed").isEqualTo("Hana Given");

            // 5. Switched off and on; the address confirmed on the administrator's word, never unconfirmed.
            assertThat(admin.call("person_set", Map.of("code", hana, "enabled", false)).error()).as("step 5: off")
                .isFalse();
            assertThat(Models.get(UserModel.class).findById(hanaId).get(UserModel.ENABLED)).as("step 5: stored off")
                .isEqualTo(false);
            assertThat(admin.call("person_set", Map.of("code", hana, "enabled", true)).error()).as("step 5: on")
                .isFalse();
            assertThat(Models.get(UserModel.class).findById(hanaId).get(UserModel.ENABLED)).as("step 5: stored on")
                .isEqualTo(true);
            McpHttpSession.Answer unconfirm = admin.call("person_set", Map.of("code", hana,
                "email_confirmed", false));
            assertThat(unconfirm.error()).as("step 5: " + unconfirm.text()).isTrue();
            assertThat(unconfirm.text()).as("step 5: names the argument").contains("email_confirmed");
            assertThat(admin.call("person_set", Map.of("code", hana, "email_confirmed", true)).error())
                .as("step 5: confirmed").isFalse();
            assertThat(Models.get(UserModel.class).findById(hanaId).get(UserModel.EMAIL_VERIFIED_AT))
                .as("step 5: stamped").isNotNull();

            // 6. Signed out everywhere; a second factor that was never set up is refused as not applying.
            McpHttpSession.Answer signedOut = admin.call("person_reset", Map.of("code", hana,
                "what", "sign_out_everywhere"));
            assertThat(signedOut.error()).as("step 6: " + signedOut.text()).isFalse();
            assertThat(admin.call("person_reset", Map.of("code", hana, "what", "two_step")).error())
                .as("step 6: no two-step sign-in to remove").isTrue();

            // 7. describe names the exact calls on a person and on a role.
            assertThat(admin.call("describe", Map.of("code", hana)).text()).as("step 7: a person")
                .contains("person_set code=" + hana).contains("person_reset code=" + hana + " what=sign_out_everywhere")
                .contains("delete code=" + hana);
            assertThat(admin.call("describe", Map.of("code", role)).text()).as("step 7: a role")
                .contains("role_set code=" + role).contains("delete code=" + role);

            // 8. A site shared with the person, read with its levels, the share taken away; access with remove is
            //    refused by its argument.
            int siteId = HohenheimMcpTest.site(PREFIX + "site-" + tag);
            cleanUp.push(() -> HardDeletes.byId(Models.get(SiteModel.class), siteId));
            String site = HohenheimMcpTest.code(SiteModel.MODEL_ID, siteId, operator);
            McpHttpSession.Answer shared = admin.call("share_set", Map.of("record", site, "to", hana,
                "access", "manage"));
            assertThat(shared.error()).as("step 8: " + shared.text()).isFalse();
            McpHttpSession.Answer shares = admin.call("share_read", Map.of("code", site));
            assertThat(shares.error()).as("step 8: " + shares.text()).isFalse();
            assertThat(admin.call("read", Map.of("codes", List.of(hana))).text())
                .as("step 8: the administrator reads the person by her name").contains("Hana Given");
            assertThat(shares.text()).as("step 8: the holder and the levels").contains("Hana Given")
                .contains("manage").contains("Access levels");
            McpHttpSession.Answer both = admin.call("share_set", Map.of("record", site, "to", hana, "remove", true,
                "access", "manage"));
            assertThat(both.error()).as("step 8: " + both.text()).isTrue();
            assertThat(both.text()).as("step 8: names the argument").contains("access").contains("remove=false");
            McpHttpSession.Answer unshared = admin.call("share_set", Map.of("record", site, "to", hana,
                "remove", true));
            assertThat(unshared.error()).as("step 8: " + unshared.text()).isFalse();

            // 9. A customer's key is refused every administrator's call.
            assertThat(customer.call("person_set", Map.of("email", "x-" + email)).error()).as("step 9: create")
                .isTrue();
            assertThat(customer.call("person_set", Map.of("code", hana, "name", "Hacked")).error())
                .as("step 9: change").isTrue();
            assertThat(customer.call("person_reset", Map.of("code", hana, "what", "password")).error())
                .as("step 9: reset").isTrue();
            assertThat(customer.call("role_set", Map.of("code", role, "name", "Hacked")).error())
                .as("step 9: role").isTrue();
            assertThat(customer.call("delete", Map.of("code", hana)).error()).as("step 9: delete").isTrue();
            assertThat(customer.call("share_set", Map.of("record", site, "to", hana, "access", "manage")).error())
                .as("step 9: share").isTrue();
            assertThat((String) Models.get(UserModel.class).findById(hanaId).get(UserModel.DISPLAY_NAME))
                .as("step 9: nothing changed").isEqualTo("Hana Given");

            // 10. A role with members is not deleted; emptied, it is, and so is the person.
            assertThat(admin.call("delete", Map.of("code", role)).error()).as("step 10: a member stands in the way")
                .isTrue();
            assertThat(admin.call("person_set", Map.of("code", hana, "roles", List.of())).error())
                .as("step 10: emptied").isFalse();
            McpHttpSession.Answer roleGone = admin.call("delete", Map.of("code", role));
            assertThat(roleGone.error()).as("step 10: " + roleGone.text()).isFalse();
            McpHttpSession.Answer personGone = admin.call("delete", Map.of("code", hana));
            assertThat(personGone.error()).as("step 10: " + personGone.text()).isFalse();
            assertThat(Models.get(UserModel.class).findById(hanaId)).as("step 10: deleted").isNull();
            System.out.println("MCP people tools offered an operator: " + admin.toolNames().size());
        } finally {
            while (!cleanUp.isEmpty()) cleanUp.pop().run();
        }
    }

    /** @return the account holding the address */
    private static Row account(String email) {
        return Objects.requireNonNull(Models.get(UserModel.class).find().where(UserModel.EMAIL.eq(email)).first(),
            email);
    }

    /** @return the code a create's answer names */
    private static String code(String answer, String step) {
        Matcher matcher = CODE.matcher(answer);
        assertThat(matcher.find()).as(step + ": a code in " + answer).isTrue();
        return matcher.group();
    }
}
