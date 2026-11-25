package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.BanModel;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.SiteRedirectModel;
import be.elevenways.hohenheim.server.auth.types.ProteusAuthProviderType;
import be.elevenways.hohenheim.server.cms.SiteRegistrationTokens;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.hohenheim.site.SiteOperations;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpResponse;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A site's passthrough and dev namespace kinds, its protected paths, path redirects and revision marks, access lists
 * and their rules, sign-in providers and bans, through the real MCP endpoint as an administrator's key: each made,
 * read back, changed, refused a wrong call by the argument it names, and deleted or lifted.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class HohenheimSiteAccessToolsTest extends HohenheimHttpTestBase {

    private static final String PREFIX = "mcp-access-";

    /** An address of the documentation range, never this installation's own. */
    private static final String BANNED = "203.0.113.77";

    @Test
    void anAdministratorGuardsSitesByToolsAlone() throws Exception {
        URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
        McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
        String tag = HohenheimMcpTest.letters();
        Deque<Runnable> cleanUp = new ArrayDeque<>();
        try {
            McpHttpSession admin = HohenheimMcpTest.presetSession(server, endpoint,
                TenantConduits.operatorUser().get(UserModel.ID), "Test Admin");

            // 1. A passthrough site: made, read, changed; an access list is refused by name; trashed and purged.
            McpHttpSession.Answer passthrough = admin.call("site_passthrough_set", Map.of("name",
                PREFIX + "tls-" + tag, "forward_host", "backend.example.test", "forward_port", 8443));
            assertThat(passthrough.error()).as("step 1: " + passthrough.text()).isFalse();
            String tlsCode = HohenheimAppPartToolsTest.codeIn(passthrough.text(), "S");
            int tlsId = HohenheimAppPartToolsTest.keyOf(SiteModel.MODEL_ID, tlsCode);
            cleanUp.push(() -> HardDeletes.byId(Models.get(SiteModel.class), tlsId));
            assertThat(admin.call("read", Map.of("codes", List.of(tlsCode))).text()).as("step 1: read back")
                .contains("backend.example.test").contains("8443");
            McpHttpSession.Answer moved = admin.call("site_passthrough_set", Map.of("code", tlsCode,
                "forward_port", 9443));
            assertThat(moved.error()).as("step 1: " + moved.text()).isFalse();
            assertThat(moved.text()).as("step 1: what changed").contains(tlsCode).contains("forward_port");
            McpHttpSession.Answer listed = admin.call("site_passthrough_set", Map.of("code", tlsCode,
                "sign_in_provider", "SP1"));
            assertThat(listed.error()).as("step 1: a passthrough takes no sign-in: " + listed.text()).isTrue();
            assertThat(listed.text()).as("step 1: the refusal names it").contains("sign_in_provider");
            assertThat(admin.call("delete", Map.of("code", tlsCode)).error()).as("step 1: trashed").isFalse();
            assertThat(admin.call("delete", Map.of("code", tlsCode, "permanent", true)).error())
                .as("step 1: purged").isFalse();

            // 2. A dev namespace, its token given; a forward_host is refused by name; renamed; trashed.
            McpHttpSession.Answer namespace = admin.call("site_dev_namespace_set", Map.of("name",
                PREFIX + "dev-" + tag, "registration_token", "zdev_" + tag));
            assertThat(namespace.error()).as("step 2: " + namespace.text()).isFalse();
            assertThat(namespace.text()).as("step 2: the token never comes back").doesNotContain("zdev_" + tag);
            String devCode = HohenheimAppPartToolsTest.codeIn(namespace.text(), "S");
            int devId = HohenheimAppPartToolsTest.keyOf(SiteModel.MODEL_ID, devCode);
            cleanUp.push(() -> HardDeletes.byId(Models.get(SiteModel.class), devId));
            assertThat(admin.call("site_dev_namespace_set", Map.of("code", devCode, "forward_host", "x")).text())
                .as("step 2: the refusal names it").contains("forward_host");
            assertThat(admin.call("site_dev_namespace_set", Map.of("code", devCode, "name",
                PREFIX + "dev2-" + tag)).error()).as("step 2: renamed").isFalse();
            assertThat(admin.call("read", Map.of("codes", List.of(devCode))).text()).as("step 2: read back")
                .contains(PREFIX + "dev2-" + tag).doesNotContain("zdev_" + tag);
            assertThat(admin.call("delete", Map.of("code", devCode)).error()).as("step 2: trashed").isFalse();

            // 2b. A dev namespace made without a token: Hohenheim makes one, which neither the answer nor the read
            //     carries; the creator's Dev sessions tab shows it once. A new one replaces it, by tool and by panel.
            McpHttpSession.Answer minted = admin.call("site_dev_namespace_set", Map.of("name",
                PREFIX + "devmade-" + tag));
            assertThat(minted.error()).as("step 2b: " + minted.text()).isFalse();
            String mintedCode = HohenheimAppPartToolsTest.codeIn(minted.text(), "S");
            int mintedId = HohenheimAppPartToolsTest.keyOf(SiteModel.MODEL_ID, mintedCode);
            cleanUp.push(() -> HardDeletes.byId(Models.get(SiteModel.class), mintedId));
            String first = EnvironmentSecretsTest.tokenOf(mintedId);
            assertThat(first).as("step 2b: a token made").startsWith("zdev_");
            assertThat(minted.text()).as("step 2b: who sees it where, never the token").contains("Dev sessions")
                .doesNotContain(first);
            assertThat(admin.call("read", Map.of("codes", List.of(mintedCode))).text())
                .as("step 2b: the read says where").contains("registration_token").contains("Dev sessions")
                .doesNotContain(first);
            String devTab = CmsRoutes.subpage(HohenheimSlugs.ADMIN, HohenheimSlugs.SITES, mintedId,
                HohenheimSlugs.Tab.DEV_SESSIONS).toUrl();
            assertThat(rendered(adminGet(devTab).body())).as("step 2b: shown on the Dev sessions tab").contains(first);
            assertThat(rendered(adminGet(devTab).body())).as("step 2b: and only once").doesNotContain(first);
            assertThat(admin.call("site_dev_namespace_set", Map.of("code", mintedCode,
                SiteRegistrationTokens.REGENERATE, true, "name", "x")).error()).as("step 2b: a new token alone")
                .isTrue();
            McpHttpSession.Answer renewed = admin.call("site_dev_namespace_set", Map.of("code", mintedCode,
                SiteRegistrationTokens.REGENERATE, true));
            assertThat(renewed.error()).as("step 2b: " + renewed.text()).isFalse();
            String second = EnvironmentSecretsTest.tokenOf(mintedId);
            assertThat(second).as("step 2b: a new token replaces it").startsWith("zdev_").isNotEqualTo(first);
            assertThat(renewed.text()).as("step 2b: never in the answer").contains("Dev sessions")
                .doesNotContain(second);
            HttpResponse<String> clicked = adminPostForm(siteInvoke(SiteOperations.REGENERATE_REGISTRATION_TOKEN,
                mintedId), confirmed(""));
            assertThat(clicked.statusCode()).as("step 2b: the panel's own").isIn(302, 303);
            assertThat(landingOf(clicked)).as("step 2b: onto the tab that shows it").isEqualTo(devTab);
            String third = EnvironmentSecretsTest.tokenOf(mintedId);
            assertThat(third).as("step 2b: and replaces it again").startsWith("zdev_").isNotEqualTo(second);
            assertThat(rendered(adminGet(devTab).body())).as("step 2b: only the newest shown").contains(third)
                .doesNotContain(second);
            String copyName = PREFIX + "devcopy-" + tag;
            assertThat(adminPostForm(siteInvoke(SiteOperations.CLONE, mintedId), confirmed("name=" + copyName))
                .statusCode()).as("step 2b: copied").isIn(302, 303);
            int copyId = Objects.requireNonNull(Models.get(SiteModel.class).find()
                .where(SiteModel.NAME.eq(copyName)).first(), "the copy").get(SiteModel.ID);
            cleanUp.push(() -> HardDeletes.byId(Models.get(SiteModel.class), copyId));
            assertThat(EnvironmentSecretsTest.tokenOf(copyId)).as("step 2b: a copy never shares the token").isNull();
            assertThat(admin.call("delete", Map.of("code", mintedCode)).error()).as("step 2b: trashed").isFalse();

            // 3. An access list: a create without a name refused by name; made, read, its satisfy changed.
            assertThat(admin.call("access_list_set", Map.of("satisfy", "all")).text())
                .as("step 3: what a create needs").contains("name");
            McpHttpSession.Answer list = admin.call("access_list_set", Map.of("name", PREFIX + "list-" + tag));
            assertThat(list.error()).as("step 3: " + list.text()).isFalse();
            String listCode = HohenheimAppPartToolsTest.codeIn(list.text(), "AL");
            int listId = HohenheimAppPartToolsTest.keyOf(AccessListModel.MODEL_ID, listCode);
            cleanUp.push(() -> HardDeletes.byId(Models.get(AccessListModel.class), listId));
            assertThat(admin.call("read", Map.of("codes", List.of(listCode))).text()).as("step 3: read back")
                .contains(PREFIX + "list-" + tag);
            assertThat(admin.call("access_list_set", Map.of("code", listCode, "satisfy", "all")).error())
                .as("step 3: satisfy changed").isFalse();
            assertThat((String) HohenheimAppPartToolsTest.row(AccessListModel.MODEL_ID, listId)
                .get(AccessListModel.SATISFY)).as("step 3: all must pass").isEqualTo(AccessListModel.SATISFY_ALL);

            // 4. Two rules on it: a network and a password; a field of another kind refused by name; a password
            //    stored hashed and kept when the rule is switched off and on; a group with a rule inside.
            McpHttpSession.Answer network = admin.call("access_rule_set", Map.of("access_list", listCode,
                "kind", "ip_allow", "network", "10.0.0.0/8"));
            assertThat(network.error()).as("step 4: " + network.text()).isFalse();
            String networkRule = HohenheimAppPartToolsTest.codeIn(network.text(), "RU");
            McpHttpSession.Answer wrong = admin.call("access_rule_set", Map.of("access_list", listCode,
                "kind", "ip_allow", "username", "ops"));
            assertThat(wrong.error()).as("step 4: " + wrong.text()).isTrue();
            assertThat(wrong.text()).as("step 4: the refusal names it").contains("username").contains("basic_auth");
            assertThat(admin.call("access_rule_set", Map.of("access_list", listCode, "kind", "ip_deny",
                "network", "not-a-network")).text()).as("step 4: a bad network refused by name").contains("network");
            McpHttpSession.Answer password = admin.call("access_rule_set", Map.of("access_list", listCode,
                "kind", "basic_auth", "username", "ops", "password", "pw-" + tag));
            assertThat(password.error()).as("step 4: " + password.text()).isFalse();
            assertThat(password.text()).as("step 4: no password back").doesNotContain("pw-" + tag);
            String passwordRule = HohenheimAppPartToolsTest.codeIn(password.text(), "RU");
            int passwordId = HohenheimAppPartToolsTest.keyOf(AccessRuleModel.MODEL_ID, passwordRule);
            String hash = String.valueOf(AccessRuleModel.dataOf(HohenheimAppPartToolsTest.row(
                AccessRuleModel.MODEL_ID, passwordId)).get("password"));
            assertThat(hash).as("step 4: stored hashed").isNotEqualTo("pw-" + tag).isNotBlank();
            assertThat(admin.call("access_rule_set", Map.of("code", passwordRule, "enabled", false)).error())
                .as("step 4: switched off").isFalse();
            assertThat(admin.call("access_rule_set", Map.of("code", passwordRule, "enabled", true)).error())
                .as("step 4: switched on again, its password kept").isFalse();
            assertThat(AccessRuleModel.dataOf(HohenheimAppPartToolsTest.row(AccessRuleModel.MODEL_ID, passwordId))
                .get("password")).as("step 4: the same hash").isEqualTo(hash);
            McpHttpSession.Answer group = admin.call("access_rule_set", Map.of("access_list", listCode,
                "kind", "group", "satisfy", "all"));
            assertThat(group.error()).as("step 4: " + group.text()).isFalse();
            String groupRule = HohenheimAppPartToolsTest.codeIn(group.text(), "RU");
            McpHttpSession.Answer inside = admin.call("access_rule_set", Map.of("group", groupRule,
                "kind", "ip_deny", "network", "192.0.2.0/24"));
            assertThat(inside.error()).as("step 4: " + inside.text()).isFalse();
            int insideId = HohenheimAppPartToolsTest.keyOf(AccessRuleModel.MODEL_ID,
                HohenheimAppPartToolsTest.codeIn(inside.text(), "RU"));
            assertThat((Integer) HohenheimAppPartToolsTest.row(AccessRuleModel.MODEL_ID, insideId)
                .get(AccessRuleModel.PARENT_ID)).as("step 4: inside the group")
                .isEqualTo(HohenheimAppPartToolsTest.keyOf(AccessRuleModel.MODEL_ID, groupRule));

            // 5. Each rule landed after the rules added before it, in the list's own order.
            assertThat(sortOf(HohenheimAppPartToolsTest.keyOf(AccessRuleModel.MODEL_ID, networkRule)))
                .as("step 5: the network rule first").isLessThan(sortOf(passwordId));

            // 6. The list guards a proxy site made with it, whose read names it; its description changed after.
            McpHttpSession.Answer guarded = admin.call("site_proxy_set", Map.of("name", PREFIX + "site-" + tag,
                "forward_host", "203.0.113.20", "forward_port", 8080, "access_list", listCode));
            assertThat(guarded.error()).as("step 6: " + guarded.text()).isFalse();
            String siteCode = HohenheimAppPartToolsTest.codeIn(guarded.text(), "S");
            int siteId = HohenheimAppPartToolsTest.keyOf(SiteModel.MODEL_ID, siteCode);
            cleanUp.push(() -> HardDeletes.byId(Models.get(SiteModel.class), siteId));
            assertThat(admin.call("site_proxy_set", Map.of("code", siteCode, "description", "guarded")).error())
                .as("step 6: changed, its list kept").isFalse();
            assertThat((Integer) HohenheimAppPartToolsTest.row(SiteModel.MODEL_ID, siteId)
                .get(SiteModel.ACCESS_LIST_ID)).as("step 6: the site's list").isEqualTo(listId);
            assertThat(admin.call("read", Map.of("codes", List.of(siteCode))).text()).as("step 6: read names it")
                .contains(listCode);

            // 7. A protected path by the list, moved; two guards at once refused; one made from networks.
            McpHttpSession.Answer path = admin.call("protected_path_set", Map.of("site", siteCode, "path", "/admin",
                "access_list", listCode));
            assertThat(path.error()).as("step 7: " + path.text()).isFalse();
            String pathCode = HohenheimAppPartToolsTest.codeIn(path.text(), "PP");
            int pathId = HohenheimAppPartToolsTest.keyOf(ProtectedPathModel.MODEL_ID, pathCode);
            cleanUp.push(() -> HardDeletes.byId(Models.get(ProtectedPathModel.class), pathId));
            assertThat(admin.call("protected_path_set", Map.of("code", pathCode, "path", "/wp-admin")).error())
                .as("step 7: moved").isFalse();
            assertThat((String) HohenheimAppPartToolsTest.row(ProtectedPathModel.MODEL_ID, pathId)
                .get(ProtectedPathModel.PATH)).as("step 7: the new path").isEqualTo("/wp-admin");
            McpHttpSession.Answer both = admin.call("protected_path_set", Map.of("site", siteCode, "path", "/x",
                "access_list", listCode, "networks", List.of("10.1.0.0/16")));
            assertThat(both.error()).as("step 7: " + both.text()).isTrue();
            assertThat(both.text()).as("step 7: the refusal names them").contains("access_list").contains("networks");
            McpHttpSession.Answer made = admin.call("protected_path_set", Map.of("site", siteCode, "path",
                "/private", "networks", List.of("10.1.0.0/16")));
            assertThat(made.error()).as("step 7: " + made.text()).isFalse();
            int madeId = HohenheimAppPartToolsTest.keyOf(ProtectedPathModel.MODEL_ID,
                HohenheimAppPartToolsTest.codeIn(made.text(), "PP"));
            Integer madeList = HohenheimAppPartToolsTest.row(ProtectedPathModel.MODEL_ID, madeId)
                .get(ProtectedPathModel.ACCESS_LIST_ID);
            cleanUp.push(() -> HardDeletes.byId(Models.get(AccessListModel.class), madeList));
            cleanUp.push(() -> HardDeletes.byId(Models.get(ProtectedPathModel.class), madeId));
            assertThat(Models.get(AccessRuleModel.class).findForAccessList(Objects.requireNonNull(madeList)))
                .as("step 7: the list made of the network").hasSize(1);
            assertThat(admin.call("delete", Map.of("code", pathCode)).error()).as("step 7: deleted").isFalse();

            // 8. A path redirect: its target needed to create; made, changed, deleted.
            assertThat(admin.call("path_redirect_set", Map.of("site", siteCode, "from_path", "/old")).text())
                .as("step 8: what a create needs").contains("target_url");
            McpHttpSession.Answer redirect = admin.call("path_redirect_set", Map.of("site", siteCode,
                "from_path", "/old", "target_url", "/new"));
            assertThat(redirect.error()).as("step 8: " + redirect.text()).isFalse();
            String redirectCode = HohenheimAppPartToolsTest.codeIn(redirect.text(), "RD");
            int redirectId = HohenheimAppPartToolsTest.keyOf(SiteRedirectModel.MODEL_ID, redirectCode);
            cleanUp.push(() -> HardDeletes.byId(Models.get(SiteRedirectModel.class), redirectId));
            assertThat(admin.call("path_redirect_set", Map.of("code", redirectCode, "status", "302",
                "path_match", "prefix")).error()).as("step 8: changed").isFalse();
            Row redirected = HohenheimAppPartToolsTest.row(SiteRedirectModel.MODEL_ID, redirectId);
            assertThat((String) redirected.get(SiteRedirectModel.HTTP_STATUS)).as("step 8: temporary").isEqualTo("302");
            assertThat((String) redirected.get(SiteRedirectModel.TARGET)).as("step 8: kept").isEqualTo("/new");
            assertThat(admin.call("delete", Map.of("code", redirectCode)).error()).as("step 8: deleted").isFalse();

            // 9. Marks on the site's latest revision: pinned, a label replaced, words on a pin refused by name, the
            //    pin lifted, a lift of what is not there refused; a hold on the whole site placed and lifted.
            int revision = SiteModel.REVISIONABLE.latestRevisionOf(Models.get(SiteModel.class), siteId);
            assertThat(revision).as("step 9: the site has a revision").isPositive();
            McpHttpSession.Answer pinned = admin.call("site_revision_mark_set", Map.of("site", siteCode,
                "revision", revision, "mark", "pinned"));
            assertThat(pinned.error()).as("step 9: " + pinned.text()).isFalse();
            assertThat(pinned.text()).as("step 9: in force").contains("pinned");
            admin.call("site_revision_mark_set", Map.of("site", siteCode, "revision", revision, "mark", "label",
                "text", "release one"));
            McpHttpSession.Answer relabelled = admin.call("site_revision_mark_set", Map.of("site", siteCode,
                "revision", revision, "mark", "label", "text", "release two"));
            assertThat(relabelled.text()).as("step 9: the words replaced").contains("release two")
                .doesNotContain("release one");
            assertThat(admin.call("site_revision_mark_set", Map.of("site", siteCode, "revision", revision,
                "mark", "pinned", "text", "x")).text()).as("step 9: the refusal names it").contains("text");
            assertThat(admin.call("site_revision_mark_set", Map.of("site", siteCode, "revision", revision,
                "mark", "pinned", "remove", true)).text()).as("step 9: lifted").doesNotContain("pinned");
            assertThat(admin.call("site_revision_mark_set", Map.of("site", siteCode, "revision", revision,
                "mark", "pinned", "remove", true)).error()).as("step 9: nothing to lift").isTrue();
            assertThat(admin.call("site_revision_mark_set", Map.of("site", siteCode, "mark", "hold",
                "text", "audit")).text()).as("step 9: a hold on the whole site").contains("hold");
            assertThat(admin.call("site_revision_mark_set", Map.of("site", siteCode, "mark", "hold",
                "remove", true)).error()).as("step 9: the hold lifted").isFalse();

            // 10. Sign-in providers: passwords never read back, kept when left empty; a proteus setting refused for a
            //     basic one by name; a proteus one's key kept when another setting changes; one used by a rule.
            McpHttpSession.Answer basic = admin.call("sign_in_provider_set", Map.of("name", PREFIX + "basic-" + tag,
                "kind", "basic", "credentials", List.of(Map.of("username", "ann", "password", "pa-" + tag))));
            assertThat(basic.error()).as("step 10: " + basic.text()).isFalse();
            String basicCode = HohenheimAppPartToolsTest.codeIn(basic.text(), "SP");
            int basicId = HohenheimAppPartToolsTest.keyOf(SiteAuthProviderModel.MODEL_ID, basicCode);
            cleanUp.push(() -> HardDeletes.byId(Models.get(SiteAuthProviderModel.class), basicId));
            String basicRead = admin.call("read", Map.of("codes", List.of(basicCode))).text();
            assertThat(basicRead).as("step 10: read back, no password").contains(PREFIX + "basic-" + tag)
                .doesNotContain("pa-" + tag).doesNotContain("$argon");
            String annHash = credentials(basicId).get("ann");
            assertThat(annHash).as("step 10: stored hashed").isNotBlank().isNotEqualTo("pa-" + tag);
            McpHttpSession.Answer people = admin.call("sign_in_provider_set", Map.of("code", basicCode,
                "credentials", List.of(Map.of("username", "ann", "password", ""),
                    Map.of("username", "bob", "password", "pb-" + tag))));
            assertThat(people.error()).as("step 10: " + people.text()).isFalse();
            assertThat(credentials(basicId)).as("step 10: ann kept, bob added").containsEntry("ann", annHash)
                .containsKey("bob");
            McpHttpSession.Answer endpointless = admin.call("sign_in_provider_set", Map.of("code", basicCode,
                "endpoint", "https://proteus.example.test"));
            assertThat(endpointless.error()).as("step 10: " + endpointless.text()).isTrue();
            assertThat(endpointless.text()).as("step 10: the refusal names it").contains("endpoint")
                .contains("proteus");
            McpHttpSession.Answer proteus = admin.call("sign_in_provider_set", Map.of("name",
                PREFIX + "proteus-" + tag, "kind", "proteus", "endpoint", "https://proteus.example.test",
                "realm_client", "client-" + tag, "access_key", "key-" + tag));
            assertThat(proteus.error()).as("step 10: " + proteus.text()).isFalse();
            String proteusCode = HohenheimAppPartToolsTest.codeIn(proteus.text(), "SP");
            int proteusId = HohenheimAppPartToolsTest.keyOf(SiteAuthProviderModel.MODEL_ID, proteusCode);
            cleanUp.push(() -> HardDeletes.byId(Models.get(SiteAuthProviderModel.class), proteusId));
            assertThat(admin.call("sign_in_provider_set", Map.of("code", proteusCode, "authenticator", "password"))
                .error()).as("step 10: changed").isFalse();
            Map<?, ?> config = (Map<?, ?>) Models.get(SiteAuthProviderModel.class).find().noCache()
                .where(SiteAuthProviderModel.ID.eq(proteusId)).first().get(SiteAuthProviderModel.CONFIG);
            assertThat(String.valueOf(config.get(ProteusAuthProviderType.ACCESS_KEY))).as("step 10: its key kept")
                .isNotBlank().isNotEqualTo("null");
            assertThat(String.valueOf(config.get(ProteusAuthProviderType.REALM_CLIENT))).as("step 10: its client")
                .isEqualTo("client-" + tag);
            McpHttpSession.Answer signIn = admin.call("access_rule_set", Map.of("access_list", listCode,
                "kind", "auth_provider", "sign_in_provider", proteusCode));
            assertThat(signIn.error()).as("step 10: " + signIn.text()).isFalse();
            String signInRule = HohenheimAppPartToolsTest.codeIn(signIn.text(), "RU");
            assertThat(admin.call("delete", Map.of("code", proteusCode)).error()).as("step 10: in use").isTrue();
            assertThat(admin.call("delete", Map.of("code", signInRule)).error()).as("step 10: rule gone").isFalse();
            assertThat(admin.call("delete", Map.of("code", proteusCode)).error()).as("step 10: deleted").isFalse();

            // 11. A ban set and set again for good, lifted, lifted again; an address of this installation refused.
            cleanUp.push(() -> HardDeletes.where(Models.get(BanModel.class), BanModel.IP.eq(BANNED)));
            McpHttpSession.Answer banned = admin.call("ban_set", Map.of("ip", BANNED, "duration", "1h",
                "reason", "mcp journey"));
            assertThat(banned.error()).as("step 11: " + banned.text()).isFalse();
            assertThat(banned.text()).as("step 11: until when").contains("until");
            McpHttpSession.Answer forGood = admin.call("ban_set", Map.of("ip", BANNED, "duration", "permanent"));
            assertThat(forGood.text()).as("step 11: for good").contains("for good");
            assertThat(Models.get(BanModel.class).find().where(BanModel.IP.eq(BANNED)).where(BanModel.ACTIVE.eq(true))
                .count()).as("step 11: one ban in force").isEqualTo(1);
            assertThat(admin.call("ban_set", Map.of("ip", BANNED, "remove", true)).text()).as("step 11: lifted")
                .contains("lifted");
            assertThat(admin.call("ban_set", Map.of("ip", BANNED, "remove", true)).text()).as("step 11: nothing")
                .contains("Nothing bans");
            McpHttpSession.Answer own = admin.call("ban_set", Map.of("ip", "127.0.0.1"));
            assertThat(own.error()).as("step 11: " + own.text()).isTrue();
            assertThat(own.text()).as("step 11: the refusal names it").contains("ip");
        } finally {
            while (!cleanUp.isEmpty()) cleanUp.pop().run();
        }
    }

    /** @return the rule's place among its siblings, as stored now */
    private static int sortOf(int ruleId) {
        return Objects.requireNonNull(Models.get(AccessRuleModel.class).find().noCache()
            .where(AccessRuleModel.ID.eq(ruleId)).first().get(AccessRuleModel.SORT));
    }

    /** @return a basic provider's usernames to their stored hashes, as stored now */
    @SuppressWarnings("unchecked")
    private static Map<String, String> credentials(int providerId) {
        Map<?, ?> config = (Map<?, ?>) Models.get(SiteAuthProviderModel.class).find().noCache()
            .where(SiteAuthProviderModel.ID.eq(providerId)).first().get(SiteAuthProviderModel.CONFIG);
        return (Map<String, String>) config.get("credentials");
    }
}
