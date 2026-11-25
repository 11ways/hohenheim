package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.BuildHookModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.GameDomainModel;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceQuotaModel;
import be.elevenways.hohenheim.model.InstanceTemplateDatabaseModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceTemplateVariableModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.BuildHookParts;
import be.elevenways.hohenheim.server.game.GameDomains;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.auth.cms.SubjectTag;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.server.operation.RecordCodes;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.security.KeyPair;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static be.elevenways.hohenheim.test.ApiSupport.user;
import static be.elevenways.hohenheim.test.HohenheimAppPartToolsTest.codeIn;
import static be.elevenways.hohenheim.test.HohenheimAppPartToolsTest.keyOf;
import static be.elevenways.hohenheim.test.HohenheimAppPartToolsTest.row;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What apps are made from and reached by, through the real MCP endpoint as an administrator's key: a template's
 * variables and databases declared, changed, renamed and removed; a game address routed, changed and deleted; a build
 * hook made and given a new URL that wait for the caller on the app's tab; an owner's limits set, changed and
 * removed; a certificate uploaded and changed; a zone file imported and read back; a DNS peer set, and a record change
 * on its secondary zone refused naming it. Each wrong call is refused by the argument it got wrong.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class HohenheimSetupToolsTest extends HohenheimHttpTestBase {

    private static final String PREFIX = "mcp-setup-";

    @Test
    void anAdministratorSetsUpTemplatesHooksLimitsCertificatesAndDnsByToolsAlone() throws Exception {
        URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
        McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
        String tag = HohenheimMcpTest.letters();
        Deque<Runnable> cleanUp = new ArrayDeque<>();
        UUID adminId = TenantConduits.operatorUser().get(UserModel.ID);
        McpHttpSession admin = HohenheimMcpTest.presetSession(server, endpoint, adminId, "Test Admin");
        try {
            // 0. The panel's one-button key negotiation is an argument of dns_peer_set now, no tool of its own.
            assertThat(admin.toolNames()).as("step 0: the new tools are offered")
                .contains("template_variable_set", "template_database_set", "game_address_set", "build_hook_set",
                    "quota_set", "certificate_set", "certificate_sign", "dns_zone_import", "dns_peer_set")
                .doesNotContain("dns_peer_negotiate_key");

            int templateId = HohenheimDataToolsTest.record(InstanceTemplateModel.class, cleanUp, row -> {
                row.set(InstanceTemplateModel.NAME, PREFIX + "template-" + tag);
                row.set(InstanceTemplateModel.KIND, "hohenheim:docker_container");
                row.set(InstanceTemplateModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine", "tag",
                    "latest")));
            });
            String templateCode = HohenheimMcpTest.code(InstanceTemplateModel.MODEL_ID, templateId,
                TenantConduits.operator());

            // 1. A template's variables: a generated secret and a bounded number declared; a change keeps what it
            //    does not name; a setting of another type, a bad key and a rename of nothing refused by name; a new
            //    type empties the old one's settings; a rename; a removal.
            McpHttpSession.Answer secret = admin.call("template_variable_set", Map.of("template", templateCode,
                "key", "DB_PASSWORD", "type", "secret", "generated", false, "generate_bytes", 24,
                "label", "Database password"));
            assertThat(secret.error()).as("step 1: " + secret.text()).isFalse();
            assertThat(secret.text()).as("step 1: declared").contains("created");
            // The variable stays declared through the journey; it names the template, so it goes first.
            cleanUp.push(() -> HardDeletes.where(Models.get(InstanceTemplateVariableModel.class),
                InstanceTemplateVariableModel.TEMPLATE_ID.eq(templateId)));
            Row password = variable(templateId, "DB_PASSWORD");
            assertThat((String) password.get(InstanceTemplateVariableModel.TYPE)).as("step 1: its type")
                .isEqualTo("hohenheim:secret");
            assertThat(String.valueOf(password.get(InstanceTemplateVariableModel.SETTINGS)))
                .as("step 1: generated is the stored generate").contains("generate=false").contains("24");
            McpHttpSession.Answer port = admin.call("template_variable_set", Map.of("template", templateCode,
                "key", "PORT", "type", "integer", "min", 1, "max", 65535));
            assertThat(port.error()).as("step 1: " + port.text()).isFalse();
            McpHttpSession.Answer narrowed = admin.call("template_variable_set", Map.of("template", templateCode,
                "key", "PORT", "max", 9000));
            assertThat(narrowed.error()).as("step 1: " + narrowed.text()).isFalse();
            Map<?, ?> bounds = (Map<?, ?>) variable(templateId, "PORT").get(InstanceTemplateVariableModel.SETTINGS);
            assertThat(((Number) bounds.get("max")).intValue()).as("step 1: the new maximum").isEqualTo(9000);
            assertThat(((Number) bounds.get("min")).intValue()).as("step 1: what was not sent stays").isEqualTo(1);
            McpHttpSession.Answer stray = admin.call("template_variable_set", Map.of("template", templateCode,
                "key", "PORT", "pattern", "^[0-9]+$"));
            assertThat(stray.error()).as("step 1: a string's setting on a number: " + stray.text()).isTrue();
            assertThat(stray.text()).as("step 1: the refusal names it").contains("pattern");
            McpHttpSession.Answer lower = admin.call("template_variable_set", Map.of("template", templateCode,
                "key", "lower", "type", "string"));
            assertThat(lower.error()).as("step 1: " + lower.text()).isTrue();
            assertThat(lower.text()).as("step 1: the refusal names it").contains("key");
            McpHttpSession.Answer nothing = admin.call("template_variable_set", Map.of("template", templateCode,
                "key", "ABSENT", "new_key", "OTHER"));
            assertThat(nothing.error()).as("step 1: " + nothing.text()).isTrue();
            assertThat(nothing.text()).as("step 1: the refusal names it").contains("new_key");
            McpHttpSession.Answer retyped = admin.call("template_variable_set", Map.of("template", templateCode,
                "key", "PORT", "type", "string", "max_length", 5));
            assertThat(retyped.error()).as("step 1: " + retyped.text()).isFalse();
            assertThat(((Map<?, ?>) variable(templateId, "PORT").get(InstanceTemplateVariableModel.SETTINGS))
                .keySet().stream().map(String::valueOf).toList())
                .as("step 1: the number's bounds went with its type").containsExactly("max_length");
            McpHttpSession.Answer renamed = admin.call("template_variable_set", Map.of("template", templateCode,
                "key", "PORT", "new_key", "APP_PORT"));
            assertThat(renamed.error()).as("step 1: " + renamed.text()).isFalse();
            assertThat(variable(templateId, "APP_PORT")).as("step 1: renamed").isNotNull();
            McpHttpSession.Answer dropped = admin.call("template_variable_set", Map.of("template", templateCode,
                "key", "APP_PORT", "remove", true));
            assertThat(dropped.error()).as("step 1: " + dropped.text()).isFalse();
            assertThat(Models.get(InstanceTemplateVariableModel.class).find()
                .where(InstanceTemplateVariableModel.TEMPLATE_ID.eq(templateId))
                .where(InstanceTemplateVariableModel.KEY.eq("APP_PORT")).first()).as("step 1: removed").isNull();

            // 2. A template's database: declared under DB, its image changed, an unknown engine refused by name,
            //    renamed and removed.
            McpHttpSession.Answer database = admin.call("template_database_set", Map.of("template", templateCode,
                "env_prefix", "DB", "engine", "postgres"));
            assertThat(database.error()).as("step 2: " + database.text()).isFalse();
            McpHttpSession.Answer imaged = admin.call("template_database_set", Map.of("template", templateCode,
                "env_prefix", "DB", "image", "postgres:17-alpine"));
            assertThat(imaged.error()).as("step 2: " + imaged.text()).isFalse();
            assertThat((String) templateDatabase(templateId, "DB").get(InstanceTemplateDatabaseModel.IMAGE))
                .as("step 2: the new image").isEqualTo("postgres:17-alpine");
            McpHttpSession.Answer oracle = admin.call("template_database_set", Map.of("template", templateCode,
                "env_prefix", "DB", "engine", "oracle"));
            assertThat(oracle.error()).as("step 2: " + oracle.text()).isTrue();
            assertThat(oracle.text()).as("step 2: the refusal names it").contains("engine");
            McpHttpSession.Answer moved = admin.call("template_database_set", Map.of("template", templateCode,
                "env_prefix", "DB", "new_env_prefix", "MAIN_DB"));
            assertThat(moved.error()).as("step 2: " + moved.text()).isFalse();
            assertThat((String) templateDatabase(templateId, "MAIN_DB").get(InstanceTemplateDatabaseModel.ENGINE))
                .as("step 2: renamed, its engine kept").isEqualTo("postgres");
            assertThat(admin.call("template_database_set", Map.of("template", templateCode, "env_prefix", "MAIN_DB",
                "remove", true)).error()).as("step 2: removed").isFalse();
            assertThat(Models.get(InstanceTemplateDatabaseModel.class).findByTemplateId(templateId))
                .as("step 2: none left").isEmpty();

            // 3. A game address: routed from an address to a backend through a proxy on the same host, its port
            //    changed, a proxy that is the backend refused, deleted.
            int siteId = HohenheimDataToolsTest.record(SiteModel.class, cleanUp, row -> {
                row.set(SiteModel.NAME, PREFIX + "site-" + tag);
                row.set(SiteModel.SLUG, PREFIX + "site-" + tag);
                row.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
                row.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
                row.set(SiteModel.STATUS, "active");
                row.set(SiteModel.ENABLED, true);
            });
            int addressId = HohenheimDataToolsTest.record(SiteDomainModel.class, cleanUp, row -> {
                row.set(SiteDomainModel.SITE_ID, siteId);
                row.set(SiteDomainModel.HOSTNAME, "play-" + tag + ".setup.test");
                row.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
            });
            int backendId = container(PREFIX + "backend-" + tag, cleanUp);
            int proxyId = container(PREFIX + "proxy-" + tag, cleanUp);
            HohenheimDataToolsTest.record(InstanceVariableModel.class, cleanUp, row -> {
                row.set(InstanceVariableModel.INSTANCE_ID, proxyId);
                row.set(InstanceVariableModel.KEY, GameDomains.PROXY_SECRET_KEY);
                row.set(InstanceVariableModel.KIND, InstanceVariableModel.KIND_SECRET);
                row.set(InstanceVariableModel.SECRET_VALUE, "setup-forwarding-secret");
            });
            // A game address hands its proxy's forwarding secret to the backend as a variable of that app.
            cleanUp.push(() -> HardDeletes.where(Models.get(InstanceVariableModel.class),
                InstanceVariableModel.INSTANCE_ID.eq(backendId)));
            String address = HohenheimMcpTest.code(SiteDomainModel.MODEL_ID, addressId, TenantConduits.operator());
            String backend = HohenheimMcpTest.code(InstanceModel.MODEL_ID, backendId, TenantConduits.operator());
            String proxy = HohenheimMcpTest.code(InstanceModel.MODEL_ID, proxyId, TenantConduits.operator());
            McpHttpSession.Answer itself = admin.call("game_address_set", Map.of("address", address,
                "backend_app", backend, "proxy_app", backend));
            assertThat(itself.error()).as("step 3: the proxy is the backend: " + itself.text()).isTrue();
            McpHttpSession.Answer routed = admin.call("game_address_set", Map.of("address", address,
                "backend_app", backend, "proxy_app", proxy, "backend_port", 25565));
            assertThat(routed.error()).as("step 3: " + routed.text()).isFalse();
            String mapping = codeIn(routed.text(), "GD");
            int mappingId = keyOf(GameDomainModel.MODEL_ID, mapping);
            McpHttpSession.Answer reported = admin.call("game_address_set", Map.of("code", mapping,
                "backend_port", 25570));
            assertThat(reported.error()).as("step 3: " + reported.text()).isFalse();
            assertThat((Integer) row(GameDomainModel.MODEL_ID, mappingId).get(GameDomainModel.BACKEND_PORT))
                .as("step 3: the new port").isEqualTo(25570);
            assertThat((Integer) row(GameDomainModel.MODEL_ID, mappingId).get(GameDomainModel.PROXY_INSTANCE_ID))
                .as("step 3: the proxy stays").isEqualTo(proxyId);
            assertThat(admin.call("delete", Map.of("code", mapping)).error()).as("step 3: deleted").isFalse();
            assertThat(Models.get(GameDomainModel.class).findById(mappingId)).as("step 3: gone").isNull();

            // 4. A build hook: made on an app that deploys releases, its URL never answered but waiting once on the
            //    app's tab for the key's owner; a new URL replaces it; a name on a change and an app that deploys no
            //    releases refused by name; revoked.
            int appId = HohenheimDataToolsTest.record(InstanceModel.class, cleanUp, row -> {
                row.set(InstanceModel.NAME, PREFIX + "app-" + tag);
                row.set(InstanceModel.KIND, ApplicationKind.ID.toString());
                row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("repository_url",
                    "https://example.invalid/app.git", "branch", "main")));
                row.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
                row.set(InstanceModel.HOST_ID, HostModel.localHostId());
            });
            String appCode = HohenheimMcpTest.code(InstanceModel.MODEL_ID, appId, TenantConduits.operator());
            AccessContext owner = AccessContext.of(TenantConduits.stubFor(new UserPrincipal(adminId, "Test Admin")));
            McpHttpSession.Answer refusedHook = admin.call("build_hook_set", Map.of("app", backend, "name", "CMS"));
            assertThat(refusedHook.error()).as("step 4: " + refusedHook.text()).isTrue();
            assertThat(refusedHook.text()).as("step 4: the refusal names it").contains("app");
            McpHttpSession.Answer hook = admin.call("build_hook_set", Map.of("app", appCode, "name", "Headless CMS"));
            assertThat(hook.error()).as("step 4: " + hook.text()).isFalse();
            String hookCode = codeIn(hook.text(), "BH");
            // A revoked hook keeps its row, which names the app: it goes before the app does.
            cleanUp.push(() -> HardDeletes.byId(Models.get(BuildHookModel.class), UUID.fromString(
                Objects.requireNonNull(RecordCodes.keyOfCode(BuildHookModel.MODEL_ID, hookCode)))));
            String waiting = BuildHookParts.claimWaiting(appId, owner);
            assertThat(waiting).as("step 4: the URL waits on the app's tab").isNotNull().contains(hookCode)
                .contains("Headless CMS");
            String url = waiting.substring(waiting.indexOf(": ") + 2).trim();
            assertThat(hook.text()).as("step 4: never in the answer").doesNotContain(url);
            assertThat(BuildHookParts.claimWaiting(appId, owner)).as("step 4: shown once").isNull();
            McpHttpSession.Answer renewed = admin.call("build_hook_set", Map.of("code", hookCode));
            assertThat(renewed.error()).as("step 4: " + renewed.text()).isFalse();
            String again = BuildHookParts.claimWaiting(appId, owner);
            assertThat(again).as("step 4: the new URL waits").isNotNull().contains(hookCode).doesNotContain(url);
            McpHttpSession.Answer named = admin.call("build_hook_set", Map.of("code", hookCode, "name", "Other"));
            assertThat(named.error()).as("step 4: " + named.text()).isTrue();
            assertThat(named.text()).as("step 4: the refusal names it").contains("name");
            assertThat(admin.call("delete", Map.of("code", hookCode)).error()).as("step 4: revoked").isFalse();
            assertThat((Object) Objects.requireNonNull(Models.get(BuildHookModel.class)
                    .findById(UUID.fromString(Objects.requireNonNull(RecordCodes.keyOfCode(BuildHookModel.MODEL_ID,
                        hookCode))))).get(BuildHookModel.REVOKED_AT)).as("step 4: its URL is dead").isNotNull();

            // 5. An owner's limits: two people sharing them, set; the same owners in the other order change them;
            //    null falls back to the default; a negative limit refused by name; removed.
            UUID ann = user(PREFIX + "ann-" + tag + "@setup.test", "Ann Setup");
            UUID bob = user(PREFIX + "bob-" + tag + "@setup.test", "Bob Setup");
            String annCode = personCode(ann);
            String bobCode = personCode(bob);
            String pack = HohenheimAccess.packSubjects(new LinkedHashSet<>(List.of(
                new SubjectTag(GrantSubjectType.USER, ann).token(),
                new SubjectTag(GrantSubjectType.USER, bob).token())));
            McpHttpSession.Answer limited = admin.call("quota_set", Map.of("owners", List.of(annCode, bobCode),
                "max_apps", 3, "max_sites", 2));
            assertThat(limited.error()).as("step 5: " + limited.text()).isFalse();
            assertThat(limited.text()).as("step 5: the owners by name").contains("Ann Setup").contains("Bob Setup");
            Row quota = quota(pack);
            Integer quotaId = quota.get(InstanceQuotaModel.ID);
            cleanUp.push(() -> HardDeletes.byId(Models.get(InstanceQuotaModel.class), quotaId));
            assertThat((Integer) quota.get(InstanceQuotaModel.MAX_INSTANCES)).as("step 5: set").isEqualTo(3);
            McpHttpSession.Answer ownerless = admin.call("quota_set", Map.of("max_apps", 9));
            assertThat(ownerless.error()).as("step 5: a call naming no owners is refused: " + ownerless.text())
                .isTrue();
            assertThat(ownerless.text()).as("step 5: the refusal names it").contains("owners");
            assertThat((Integer) quota(pack).get(InstanceQuotaModel.MAX_INSTANCES)).as("step 5: nothing changed")
                .isEqualTo(3);
            Map<String, Object> raised = new HashMap<>(Map.of("owners", List.of(bobCode, annCode), "max_apps", 5));
            raised.put("max_sites", null);
            McpHttpSession.Answer changed = admin.call("quota_set", raised);
            assertThat(changed.error()).as("step 5: " + changed.text()).isFalse();
            assertThat((Integer) quota(pack).get(InstanceQuotaModel.MAX_INSTANCES)).as("step 5: changed")
                .isEqualTo(5);
            assertThat((Integer) quota(pack).get(InstanceQuotaModel.MAX_SITES)).as("step 5: back to the default")
                .isNull();
            McpHttpSession.Answer negative = admin.call("quota_set", Map.of("owners", List.of(annCode),
                "max_apps", -1));
            assertThat(negative.error()).as("step 5: " + negative.text()).isTrue();
            assertThat(negative.text()).as("step 5: the refusal names it").contains("max_apps");
            McpHttpSession.Answer unlimited = admin.call("quota_set", Map.of("owners", List.of(annCode, bobCode),
                "remove", true));
            assertThat(unlimited.error()).as("step 5: " + unlimited.text()).isFalse();
            assertThat(Models.get(InstanceQuotaModel.class).find().where(InstanceQuotaModel.SUBJECTS.eq(pack))
                .first()).as("step 5: removed").isNull();

            // 6. A certificate: uploaded with its key, renamed, a key of another certificate refused, a renewal
            //    switch on an upload refused by name, read without its key, deleted.
            KeyPair keys = TlsCertificateTest.generateKeyPair();
            String chain = TlsCertificateTest.certToPem(TlsCertificateTest.generateSelfSignedCert(keys,
                "upload-" + tag + ".setup.test"));
            String key = TlsCertificateTest.keyToPem(keys);
            McpHttpSession.Answer renews = admin.call("certificate_set", Map.of("name", PREFIX + tag,
                "certificate_pem", chain, "private_key_pem", key, "auto_renew", true));
            assertThat(renews.error()).as("step 6: " + renews.text()).isTrue();
            assertThat(renews.text()).as("step 6: the refusal names it").contains("auto_renew");
            McpHttpSession.Answer uploaded = admin.call("certificate_set", Map.of("name", PREFIX + tag,
                "certificate_pem", chain, "private_key_pem", key));
            assertThat(uploaded.error()).as("step 6: " + uploaded.text()).isFalse();
            String certificate = codeIn(uploaded.text(), "C");
            int certificateId = keyOf(CertificateModel.MODEL_ID, certificate);
            cleanUp.push(() -> HardDeletes.byId(Models.get(CertificateModel.class), certificateId));
            assertThat((String) row(CertificateModel.MODEL_ID, certificateId).get(CertificateModel.PROVIDER))
                .as("step 6: an upload").isEqualTo(CertificateModel.PROVIDER_CUSTOM);
            McpHttpSession.Answer relabelled = admin.call("certificate_set", Map.of("code", certificate,
                "name", PREFIX + "renamed-" + tag));
            assertThat(relabelled.error()).as("step 6: " + relabelled.text()).isFalse();
            assertThat((String) row(CertificateModel.MODEL_ID, certificateId).get(CertificateModel.NICE_NAME))
                .as("step 6: renamed").isEqualTo(PREFIX + "renamed-" + tag);
            McpHttpSession.Answer foreign = admin.call("certificate_set", Map.of("code", certificate,
                "private_key_pem", TlsCertificateTest.keyToPem(TlsCertificateTest.generateKeyPair())));
            assertThat(foreign.error()).as("step 6: another certificate's key: " + foreign.text()).isTrue();
            McpHttpSession.Answer read = admin.call("read", Map.of("codes", List.of(certificate)));
            assertThat(read.error()).as("step 6: " + read.text()).isFalse();
            assertThat(read.text()).as("step 6: read without its key").contains(certificate)
                .doesNotContain("PRIVATE KEY");
            assertThat(admin.call("delete", Map.of("code", certificate)).error()).as("step 6: deleted").isFalse();

            // 7. DNS: a zone file imported into a primary zone and read back; a peer set and changed, an
            //    incomplete Hohenheim peer refused by name and a key negotiation refused on a plain nameserver; a
            //    record change on the peer's secondary zone refused naming the peer; an import there refused.
            String origin = "import-" + tag + ".setup.test";
            McpHttpSession.Answer zone = admin.call("dns_zone_set", Map.of("origin", origin));
            assertThat(zone.error()).as("step 7: " + zone.text()).isFalse();
            String zoneCode = codeIn(zone.text(), "Z");
            int zoneId = keyOf(DnsZoneModel.MODEL_ID, zoneCode);
            cleanUp.push(() -> HardDeletes.byId(Models.get(DnsZoneModel.class), zoneId));
            McpHttpSession.Answer imported = admin.call("dns_zone_import", Map.of("code", origin,
                "zone_text", "$ORIGIN " + origin + ".\nwww 300 IN A 192.0.2.10\n@ 300 IN MX 10 mail." + origin
                    + ".\nmail 300 IN A 192.0.2.11\n"));
            assertThat(imported.error()).as("step 7: " + imported.text()).isFalse();
            assertThat(imported.text()).as("step 7: says what came in").isNotBlank();
            McpHttpSession.Answer records = admin.call("dns_record_list", Map.of("dns_zone", zoneCode));
            assertThat(records.error()).as("step 7: " + records.text()).isFalse();
            assertThat(records.text()).as("step 7: the records read back").contains("www").contains("192.0.2.10")
                .contains("MX");
            McpHttpSession.Answer blank = admin.call("dns_zone_import", Map.of("code", zoneCode, "zone_text", ""));
            assertThat(blank.error()).as("step 7: " + blank.text()).isTrue();
            assertThat(blank.text()).as("step 7: the refusal names it").contains("zone_text");
            McpHttpSession.Answer peer = admin.call("dns_peer_set", Map.of("name", PREFIX + "peer-" + tag,
                "transfer_host", "192.0.2.53"));
            assertThat(peer.error()).as("step 7: " + peer.text()).isFalse();
            String peerCode = codeIn(peer.text(), "DP");
            int peerId = keyOf(DnsPeerModel.MODEL_ID, peerCode);
            cleanUp.push(() -> HardDeletes.byId(Models.get(DnsPeerModel.class), peerId));
            McpHttpSession.Answer ported = admin.call("dns_peer_set", Map.of("code", peerCode,
                "transfer_port", 5353));
            assertThat(ported.error()).as("step 7: " + ported.text()).isFalse();
            assertThat((Integer) row(DnsPeerModel.MODEL_ID, peerId).get(DnsPeerModel.TRANSFER_PORT))
                .as("step 7: the new port").isEqualTo(5353);
            McpHttpSession.Answer half = admin.call("dns_peer_set", Map.of("code", peerCode, "kind", "hohenheim"));
            assertThat(half.error()).as("step 7: " + half.text()).isTrue();
            assertThat(half.text()).as("step 7: the refusal names it").contains("base_url");
            McpHttpSession.Answer negotiated = admin.call("dns_peer_set", Map.of("code", peerCode,
                "negotiate_key", true));
            assertThat(negotiated.error()).as("step 7: a nameserver has no channel: " + negotiated.text()).isTrue();
            String secondary = "replica-" + tag + ".setup.test";
            McpHttpSession.Answer replica = admin.call("dns_zone_set", Map.of("origin", secondary,
                "role", DnsZoneModel.ROLE_SECONDARY, "primary_peer", peerCode));
            assertThat(replica.error()).as("step 7: " + replica.text()).isFalse();
            int replicaId = keyOf(DnsZoneModel.MODEL_ID, codeIn(replica.text(), "Z"));
            cleanUp.push(() -> HardDeletes.byId(Models.get(DnsZoneModel.class), replicaId));
            McpHttpSession.Answer edited = admin.call("dns_record_set", Map.of("dns_zone", secondary,
                "records", List.of(Map.of("name", "www", "type", "A", "values", List.of(Map.of("value",
                    "192.0.2.12"))))));
            assertThat(edited.error()).as("step 7: " + edited.text()).isTrue();
            assertThat(edited.text()).as("step 7: the refusal names the primary's peer")
                .contains(PREFIX + "peer-" + tag);
            McpHttpSession.Answer onReplica = admin.call("dns_zone_import", Map.of("code", secondary,
                "zone_text", "www 300 IN A 192.0.2.13\n"));
            assertThat(onReplica.error()).as("step 7: " + onReplica.text()).isTrue();
            assertThat(onReplica.text()).as("step 7: import on the primary").contains("primary");
            for (String gone : List.of(codeIn(replica.text(), "Z"), zoneCode, peerCode)) {
                McpHttpSession.Answer deleted = admin.call("delete", Map.of("code", gone));
                assertThat(deleted.error()).as("step 7: " + gone + " deleted: " + deleted.text()).isFalse();
            }
        } finally {
            while (!cleanUp.isEmpty()) cleanUp.pop().run();
        }
    }

    /** @return a stopped container app on this host, removed after the test */
    private static int container(String name, Deque<Runnable> cleanUp) {
        return HohenheimDataToolsTest.record(InstanceModel.class, cleanUp, row -> {
            row.set(InstanceModel.NAME, name);
            row.set(InstanceModel.KIND, "hohenheim:docker_container");
            row.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
            row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine", "tag", "latest")));
            row.set(InstanceModel.HOST_ID, HostModel.localHostId());
        });
    }

    private static Row variable(int templateId, String key) {
        return Objects.requireNonNull(Models.get(InstanceTemplateVariableModel.class).find()
            .where(InstanceTemplateVariableModel.TEMPLATE_ID.eq(templateId))
            .where(InstanceTemplateVariableModel.KEY.eq(key)).first(), "variable " + key);
    }

    private static Row templateDatabase(int templateId, String prefix) {
        return Objects.requireNonNull(Models.get(InstanceTemplateDatabaseModel.class).find()
            .where(InstanceTemplateDatabaseModel.TEMPLATE_ID.eq(templateId))
            .where(InstanceTemplateDatabaseModel.ENV_PREFIX.eq(prefix)).first(), "database " + prefix);
    }

    private static Row quota(String pack) {
        return Objects.requireNonNull(Models.get(InstanceQuotaModel.class).find()
            .where(InstanceQuotaModel.SUBJECTS.eq(pack)).first(), "limits of " + pack);
    }

    private static String personCode(UUID person) {
        String code = RecordCodes.codes(UserModel.MODEL_ID, List.of(person.toString()), TenantConduits.operator())
            .get(person.toString());
        assertThat(code).as("person " + person + " has a code").isNotNull();
        return code;
    }
}
