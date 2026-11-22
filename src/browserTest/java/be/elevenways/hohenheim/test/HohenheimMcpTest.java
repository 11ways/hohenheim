package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.zenit.ai.server.mcp.host.McpCoverage;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.ai.server.mcp.host.McpScopes;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.CredentialPresets;
import be.elevenways.zenit.server.operation.RecordCodes;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static be.elevenways.hohenheim.test.ApiSupport.user;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hohenheim's one assistant connection: every operation is a tool or says why not, every source is listed or says why
 * not, and a real API key holding the "Hohenheim assistant" preset lists, reads and writes through the operations'
 * own gates, while a key without the server's scope never gets in.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class HohenheimMcpTest extends HohenheimHttpTestBase {

    private static final String PREFIX = "mcp-journey-";

    @AfterAll
    static void cleanUp() {
        SiteModel sites = Models.get(SiteModel.class);
        for (Row site : sites.find().withTrashed().where(SiteModel.NAME.startsWith(PREFIX)).all()) {
            HardDeletes.byId(sites, site.get(SiteModel.ID));
        }
    }

    /**
     * Coverage: what is still neither placed nor kept out is exactly what zenit core declares in Hohenheim's namespace
     * with no way to give it a verb: a row delete keeping its published id, TreeOrderOperations' two moves, and the
     * site's history operations (recover, restore a revision, place and remove a revision mark).
     */
    @Test
    void everyOperationIsAToolOrSaysWhyNot() {
        assertThat(HohenheimMcpModule.tools()).as("the boot module built the tools").isNotNull();
        List<String> uncovered = McpCoverage.uncovered("hohenheim", HohenheimMcpModule.tools()).stream()
            .map(operation -> operation.id().toString()).sorted().toList();
        assertThat(uncovered).as("operations no tool places and none keeps from assistants")
            .containsExactly("hohenheim:access_rule_move_down", "hohenheim:access_rule_move_up",
                "hohenheim:delete_auth_provider", "hohenheim:delete_environment", "hohenheim:site_hold_place",
                "hohenheim:site_hold_remove", "hohenheim:site_label_place", "hohenheim:site_label_remove",
                "hohenheim:site_pinned_place", "hohenheim:site_pinned_remove", "hohenheim:site_recover",
                "hohenheim:site_restore_revision");
        List<String> unlisted = McpCoverage.unlisted("hohenheim", HohenheimMcpModule.tools()).stream()
            .map(source -> source.id().toString()).toList();
        assertThat(unlisted).as("sources no tool lists and none keeps from assistants").isEmpty();
    }

    @Test
    void anAssistantKeyListsReadsAndWritesThroughTheOperations() {
        McpHostServer server = McpHostServers.routed(HohenheimMcpModule.SERVER_NAME);
        assertThat(server).as("the one connection is routed").isNotNull();
        URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
        String name = PREFIX + UUID.randomUUID();
        int siteId = site(name);
        AccessContext operator = TenantConduits.operator();
        UUID operatorId = TenantConduits.operatorUser().get(UserModel.ID);

        // 1. The API keys page offers the "Hohenheim assistant" preset; a key minted from it is admitted.
        CredentialPresets.Preset preset = server.credentialPreset(operator);
        assertThat(preset).as("step 1: the preset is offered").isNotNull();
        List<String> scopes = ApiKeyService.presetScopes(operator, preset);
        assertThat(scopes).as("step 1: the preset admits the server").contains(McpScopes.server("hohenheim"));
        String key = ApiKeyService.create(operator, operatorId, PREFIX + "assistant", scopes, null).plaintext();
        McpHttpSession session = McpHttpSession.open(endpoint, bearer(key));

        // 2. The tools derive from the sources, the operations and zenit-auth's accounts.
        assertThat(session.toolNames()).as("step 2: the tools")
            .contains("read", "site_list", "site_pause", "site_resume", "instance_start", "certificate_renew",
                "user_whoami");

        // 3. Listing sites by a word names the site by its code; reading that code reads the site.
        String code = RecordCodes.codes(SiteModel.MODEL_ID, List.of(String.valueOf(siteId)), operator)
            .get(String.valueOf(siteId));
        assertThat(code).as("step 3: the new site has a code").matches("S\\d+");
        McpHttpSession.Answer listed = session.call("site_list", Map.of("search", name));
        assertThat(listed.error()).as("step 3: " + listed.text()).isFalse();
        assertThat(listed.text()).as("step 3: the list").contains(code).contains(name);
        McpHttpSession.Answer read = session.call("read", Map.of("ids", List.of(code)));
        assertThat(read.error()).as("step 3: " + read.text()).isFalse();
        assertThat(read.text()).as("step 3: the read").contains(code + " (site)").contains(name);

        // 4. Someone holding no grant on the site is refused the same write, and the site stays on.
        UUID strangerId = user(PREFIX + UUID.randomUUID() + "@surface.test", "Mcp Stranger");
        String strangerKey = ApiKeyService.create(strangerId, PREFIX + "stranger",
            List.of(McpScopes.server("hohenheim"), "*"), null).plaintext();
        McpHttpSession.Answer refused = McpHttpSession.open(endpoint, bearer(strangerKey))
            .call("site_pause", Map.of("id", code));
        assertThat(refused.error()).as("step 4: refused: " + refused.text()).isTrue();
        assertThat(enabled(siteId)).as("step 4: still enabled").isTrue();

        // 5. The operator's key switches the site off through the same operation the panel places.
        McpHttpSession.Answer paused = session.call("site_pause", Map.of("id", code));
        assertThat(paused.error()).as("step 5: " + paused.text()).isFalse();
        assertThat(enabled(siteId)).as("step 5: disabled").isFalse();

        // 6. A key of the same operator without the server's scope cannot connect.
        String plainKey = ApiKeyService.create(operator, operatorId, PREFIX + "plain", List.of("*"), null)
            .plaintext();
        assertThat(McpHttpSession.initializeStatus(endpoint, bearer(plainKey))).as("step 6: no admission")
            .isNotEqualTo(200);
    }

    private static String[] bearer(String key) {
        return new String[] {"Authorization", "Bearer " + key};
    }

    private static boolean enabled(int siteId) {
        return Boolean.TRUE.equals(Models.get(SiteModel.class).find().noCache().where(SiteModel.ID.eq(siteId))
            .first().get(SiteModel.ENABLED));
    }

    private static int site(String name) {
        Row row = Models.get(SiteModel.class).createEmptyRow();
        row.set(SiteModel.NAME, name);
        row.set(SiteModel.SLUG, name);
        row.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        row.set(SiteModel.ENABLED, true);
        row.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        row.set(SiteModel.SETTINGS, new LinkedHashMap<>(Map.of("root_path", "/tmp/" + name)));
        Models.get(SiteModel.class).save(row);
        return row.get(SiteModel.ID);
    }
}
