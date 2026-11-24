package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.instance.InstanceLogOperations;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.instance.InstanceTemplateOperations;
import be.elevenways.hohenheim.instance.InstanceVariableOperations;
import be.elevenways.hohenheim.model.*;
import be.elevenways.hohenheim.site.SiteOperations;
import be.elevenways.hohenheim.instance.InstanceBackupOperations;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.server.HandlerSupport;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.application.ReleaseEngine;
import be.elevenways.hohenheim.server.cms.DatabaseDumpParts;
import be.elevenways.hohenheim.server.cms.HostParts;
import be.elevenways.hohenheim.server.cms.PutOnline;
import be.elevenways.hohenheim.server.cms.StackOperations;
import be.elevenways.hohenheim.server.instance.InstanceOperationHandlers;
import be.elevenways.hohenheim.server.instance.DockerContainerKind;
import be.elevenways.hohenheim.server.instance.InstanceVariables;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.hohenheim.test.application.FakeApps;
import be.elevenways.hohenheim.test.docker.FakeDockerDaemon;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.testsupport.time.Poll;
import be.elevenways.zenit.ai.server.mcp.host.McpCoverage;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHostTool;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.ai.server.mcp.host.McpScopes;
import be.elevenways.zenit.ai.server.mcp.host.McpToolSurface;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.data.RecordSource;
import be.elevenways.zenit.common.data.RecordSourceRegistry;
import be.elevenways.zenit.common.operation.AssistantNoun;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationRunStatus;
import be.elevenways.zenit.common.operation.OperationRunModel;
import be.elevenways.zenit.common.operation.Operations;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.security.PrincipalRef;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.CredentialPresets;
import be.elevenways.zenit.common.security.KnownValueRedactor;
import be.elevenways.zenit.server.operation.OperationRunOperations;
import be.elevenways.zenit.server.operation.RecordCodes;
import be.elevenways.zenit.test.support.DatasourceProbe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static be.elevenways.hohenheim.test.ApiSupport.user;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hohenheim's one assistant connection: every operation is a tool or says why not, every source is listed or says why
 * not, a real API key holding the "Hohenheim assistant" preset lists, reads and writes through the operations' own
 * gates, a key without the server's scope never gets in, and a customer who is no operator runs, deploys and rolls back
 * his own app with it.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class HohenheimMcpTest extends HohenheimHttpTestBase {

    private static final String PREFIX = "mcp-journey-";

    /** A secret of the customer's app, long enough to be masked wherever the app prints it. */
    private static final String SECRET = "hh-mcp-secret-7c1e9a5b";

    /** A run's code, as a background tool's answer names it. */
    private static final Pattern RUN_CODE = Pattern.compile("RUN\\d+");

    /** The Hohenheim operations and sources kept from assistants, sorted; a later landing removes lines, never adds. */
    private static final String[] KEPT = {
        "hohenheim:access_rule_add", "hohenheim:access_rule_add_inside", "hohenheim:acknowledge_posture",
        "hohenheim:build_hook_create", "hohenheim:build_hook_regenerate", "hohenheim:change_database_password",
        "hohenheim:confirm_host_key", "hohenheim:confirm_incus_cert", "hohenheim:create_game_domain",
        "hohenheim:create_site", "hohenheim:declare_volume", "hohenheim:delete_dns_record",
        "hohenheim:delete_schedule", "hohenheim:delete_schedule_step", "hohenheim:dyndns_revoke",
        "hohenheim:dyndns_token", "hohenheim:edit_remote_dns_record", "hohenheim:lift_ban",
        "hohenheim:linked_server_add", "hohenheim:linked_server_check", "hohenheim:linked_server_offer_join",
        "hohenheim:linked_server_remove",
        "hohenheim:migrate_instance", "hohenheim:open_framebuffer", "hohenheim:open_shell",
        "hohenheim:protect_path", "hohenheim:put_address_online", "hohenheim:put_machine_online",
        "hohenheim:put_online", "hohenheim:put_service_online", "hohenheim:put_stream_online",
        "hohenheim:put_volume_file", "hohenheim:put_workload_online", "hohenheim:put_workload_service_online",
        "hohenheim:redeclare_volume", "hohenheim:repin_host_key", "hohenheim:repin_incus_cert",
        "hohenheim:restore_backup", "hohenheim:restore_backup_in_place", "hohenheim:restore_database_dump",
        "hohenheim:restore_database_dump_in_place", "hohenheim:rollback_release", "hohenheim:rotate_host_key",
        "hohenheim:rotate_incus_cert", "hohenheim:route_notification_events", "hohenheim:run_schedule",
        "hohenheim:scan_host_key", "hohenheim:scan_incus_cert", "hohenheim:site_deploy_from_repository",
        "hohenheim:spamservice_key_create", "hohenheim:spamservice_key_enable", "hohenheim:spamservice_key_revoke",
        "hohenheim:spamservice_mark_ham", "hohenheim:spamservice_mark_spam", "hohenheim:spamservice_rescore",
        "hohenheim:update_game_domain", "hohenheim:update_site", "source hohenheim:access_list@admin",
        "source hohenheim:access_list@admin_picker", "source hohenheim:access_rule@admin",
        "source hohenheim:access_rule@admin_picker", "source hohenheim:auth_provider@admin",
        "source hohenheim:backup_target@admin", "source hohenheim:backup_target@admin_picker",
        "source hohenheim:ban", "source hohenheim:ban@admin", "source hohenheim:ban@admin_picker",
        "source hohenheim:build_hook@admin", "source hohenheim:build_operation",
        "source hohenheim:build_operation@admin", "source hohenheim:build_operation@admin_picker",
        "source hohenheim:certificate@admin", "source hohenheim:certificate@admin_picker",
        "source hohenheim:database@admin", "source hohenheim:database@admin_picker",
        "source hohenheim:database_dump@admin", "source hohenheim:database_engine@admin",
        "source hohenheim:dns_peer@admin", "source hohenheim:dns_record", "source hohenheim:dns_record@admin",
        "source hohenheim:dns_record@admin_picker", "source hohenheim:dns_zone@admin",
        "source hohenheim:dns_zone_peer@admin", "source hohenheim:environment@admin",
        "source hohenheim:environment_variable@admin", "source hohenheim:game_domain@admin",
        "source hohenheim:git_provider@admin", "source hohenheim:git_provider@admin_picker",
        "source hohenheim:instance@admin", "source hohenheim:instance@admin_picker",
        "source hohenheim:instance_backup@admin", "source hohenheim:instance_database@admin",
        "source hohenheim:instance_database@admin_picker", "source hohenheim:instance_device@admin",
        "source hohenheim:instance_device@admin_picker", "source hohenheim:instance_file@admin",
        "source hohenheim:instance_quota@admin", "source hohenheim:instance_schedule@admin",
        "source hohenheim:instance_schedule@admin_picker", "source hohenheim:instance_schedule_run@admin",
        "source hohenheim:instance_schedule_step@admin", "source hohenheim:instance_snapshot@admin",
        "source hohenheim:instance_template@admin", "source hohenheim:instance_template@admin_picker",
        "source hohenheim:instance_template_database@admin", "source hohenheim:instance_template_file@admin",
        "source hohenheim:instance_template_variable@admin", "source hohenheim:instance_template_volume@admin",
        "source hohenheim:instance_variable@admin", "source hohenheim:instance_volume@admin",
        "source hohenheim:instance_volume@admin_picker", "source hohenheim:manage_access_list@manage",
        "source hohenheim:manage_access_list@manage_picker", "source hohenheim:manage_access_rule@manage",
        "source hohenheim:manage_access_rule@manage_picker", "source hohenheim:manage_build_hook@manage",
        "source hohenheim:manage_certificate@manage", "source hohenheim:manage_certificate@manage_picker",
        "source hohenheim:manage_database@manage", "source hohenheim:manage_database@manage_picker",
        "source hohenheim:manage_dns_record@manage", "source hohenheim:manage_dns_record@manage_picker",
        "source hohenheim:manage_git_provider@manage", "source hohenheim:manage_git_provider@manage_picker",
        "source hohenheim:manage_instance@manage", "source hohenheim:manage_instance@manage_picker",
        "source hohenheim:manage_instance_backup@manage", "source hohenheim:manage_instance_database@manage",
        "source hohenheim:manage_instance_database@manage_picker", "source hohenheim:manage_instance_device@manage",
        "source hohenheim:manage_instance_device@manage_picker", "source hohenheim:manage_instance_schedule@manage",
        "source hohenheim:manage_instance_schedule@manage_picker",
        "source hohenheim:manage_instance_schedule_step@manage", "source hohenheim:manage_instance_snapshot@manage",
        "source hohenheim:manage_instance_template@manage",
        "source hohenheim:manage_instance_template@manage_picker",
        "source hohenheim:manage_instance_variable@manage", "source hohenheim:manage_instance_volume@manage",
        "source hohenheim:manage_instance_volume@manage_picker",
        "source hohenheim:manage_preview_deployment@manage",
        "source hohenheim:manage_preview_deployment@manage_picker", "source hohenheim:manage_project@manage",
        "source hohenheim:manage_project@manage_picker", "source hohenheim:manage_protected_path@manage",
        "source hohenheim:manage_protected_path@manage_picker", "source hohenheim:manage_site@manage",
        "source hohenheim:manage_site@manage_picker", "source hohenheim:manage_site_domain@manage",
        "source hohenheim:manage_site_domain@manage_picker", "source hohenheim:manage_site_redirect@manage",
        "source hohenheim:manage_site_redirect@manage_picker", "source hohenheim:notification_channel@admin",
        "source hohenheim:preview_deployment@admin", "source hohenheim:preview_deployment@admin_picker",
        "source hohenheim:project@admin", "source hohenheim:project@admin_picker",
        "source hohenheim:protected_path@admin", "source hohenheim:protected_path@admin_picker",
        "source hohenheim:reconcile_finding@admin", "source hohenheim:release_operation",
        "source hohenheim:release_operation@admin", "source hohenheim:release_operation@admin_picker",
        "source hohenheim:released_claim@admin", "source hohenheim:runtime_image@admin",
        "source hohenheim:runtime_image@admin_picker", "source hohenheim:server@admin",
        "source hohenheim:server@admin_picker", "source hohenheim:site@admin", "source hohenheim:site@admin_picker",
        "source hohenheim:site_domain@admin", "source hohenheim:site_domain@admin_picker",
        "source hohenheim:site_redirect@admin", "source hohenheim:site_redirect@admin_picker",
        "source hohenheim:spamservice_system_users", "source hohenheim:stack@admin",
        "source hohenheim:stack_file@admin", "source hohenheim:stack_service@admin"};

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

    /**
     * Words: a host (a machine apps run on) names its tools and its list kind "host"; "server" is left for a whole
     * Hohenheim, while the operation ids a history stores keep their old spelling.
     */
    @Test
    void aHostIsNamedHostNeverServer() {
        assertThat(HohenheimMcpModule.tools()).as("step 1: the boot module built the tools").isNotNull();
        assertThat(AssistantNoun.of(HostModel.MODEL_ID)).as("step 1: the list kind of a host").isEqualTo("host");
        List<String> names = new ArrayList<>();
        names.add(HostParts.DELETE.assistantName());
        names.add(StackOperations.RECLAIM_IMAGES.assistantName());
        for (Operation<Row, Void, ?> operation : HostParts.lifecycleForAssistants()) {
            names.add(operation.assistantName());
        }
        assertThat(names).as("step 2: every host tool is named after the host").containsExactlyInAnyOrder(
            "host_delete", "host_reclaim_images", "host_test", "host_admit", "host_pause", "host_drain",
            "host_resume", "host_reap");
        assertThat(HostParts.DELETE.id().toString()).as("step 3: the stored operation id keeps its old spelling")
            .isEqualTo("hohenheim:delete_server");
    }

    /**
     * Words: every operation is placed once and says more than its label, and every argument declares its shape and
     * its words; what is left is pinned by name, so the list can only shrink.
     *
     * AIDEV-NOTE: the left-overs are a template's own variables (their names are per template) and a field shown
     * only for one choice of another, whose shape core's schema puts under its condition alone.
     */
    @Test
    void everyToolSaysWhatItDoesOnce() {
        McpHostServer server = McpHostServers.get(HohenheimMcpModule.SERVER_NAME);
        assertThat(server).as("the one connection is registered").isNotNull();
        assertThat(McpCoverage.placedTwice(server)).as("operations placed by two tools").isEmpty();
        List<String> undescribed = McpCoverage.undescribed(server);
        System.out.println("MCP operations said only by their label: " + undescribed);
        assertThat(undescribed).as("no operation is said only by its label").isEmpty();
        AccessContext operator = TenantConduits.operator();
        List<String> loose = McpCoverage.looseParameters(server, operator);
        List<String> bare = McpCoverage.undescribedParameters(server, operator);
        System.out.println("MCP arguments of no declared shape: " + loose + "; without words: " + bare);
        assertThat(loose).as("arguments of no declared shape")
            .containsExactlyInAnyOrder("app_template_create_app.variables", "certificate_create.dns_publisher",
                "address_order_certificate.dns_publisher", "certificate_reissue.dns_publisher");
        assertThat(bare).as("arguments without words")
            .containsExactlyInAnyOrder("app_template_create_app.environmentId", "app_template_create_app.variables",
                "certificate_create.dns_publisher", "address_order_certificate.dns_publisher",
                "certificate_reissue.dns_publisher");
    }

    /** The operations and sources kept from assistants, by id: a new one fails here, a tool given to one shrinks it. */
    @Test
    void theExcusesOnlyShrink() {
        List<String> kept = new ArrayList<>();
        for (Operation<?, ?, ?> operation : Operations.registry()) {
            if ("hohenheim".equals(operation.id().getNamespace()) && operation.notForAssistants() != null) {
                kept.add(operation.id().toString());
            }
        }
        for (RecordSourceRegistry.Entry entry : RecordSourceRegistry.INSTANCE.registryEntries()) {
            RecordSource<?> source = entry.source();
            if ("hohenheim".equals(source.id().getNamespace()) && source.notForAssistants() != null) {
                kept.add("source " + source.id());
            }
        }
        System.out.println("MCP kept from assistants (" + kept.size() + "): " + kept);
        assertThat(kept.stream().sorted().toList()).as("what is kept from assistants").containsExactly(KEPT);
    }

    /** Every tool that deploys (a start, a restart, a deploy, a rollback) asks under the API trigger, known at boot. */
    @Test
    void everyDeployingToolHasItsTrigger() {
        assertThat(HohenheimMcpModule.tools()).as("the boot module built the tools").isNotNull();
        List<Operation<?, ?, ?>> deployingOperations = List.of(InstanceOperations.START, InstanceOperations.RESTART,
            InstanceOperations.DEPLOY, InstanceOperations.ROLLBACK);
        Set<String> deploying = new HashSet<>();
        for (Operation<?, ?, ?> operation : deployingOperations) {
            if (operation.assistantName() != null) deploying.add(operation.assistantName());
        }
        List<String> placed = new ArrayList<>();
        for (McpHostTool tool : Objects.requireNonNull(HohenheimMcpModule.tools()).tools()) {
            if (deploying.contains(tool.name())) {
                placed.add(tool.name());
                assertThat(InstanceOperationHandlers.triggerOf(McpToolSurface.MCP_TOOL))
                    .as(tool.name() + " deploys under the API trigger").isEqualTo(DeployTrigger.API);
            }
        }
        assertThat(placed).as("the start, the restart, the deploy and the rollback are tools")
            .containsExactlyInAnyOrderElementsOf(deploying).hasSize(deployingOperations.size());
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

        // 1. The API keys page offers the "Hohenheim assistant" preset, named by the product Hohenheim installs (never
        //    the framework's own name); a key minted from it is admitted.
        CredentialPresets.Preset preset = server.credentialPreset(operator);
        assertThat(preset).as("step 1: the preset is offered").isNotNull();
        CredentialPresets.Preset offered = CredentialPresets.offeredTo(operator, preset.key());
        assertThat(offered).as("step 1: the account is offered it").isNotNull();
        assertThat(offered.label().resolve(LocaleChain.ofTags("en"), Zenit.getMessageResolver()))
            .as("step 1: its name").isEqualTo("Hohenheim assistant");
        assertThat(offered.description().resolve(LocaleChain.ofTags("en"), Zenit.getMessageResolver()))
            .as("step 1: its line").contains("work in Hohenheim for you");
        List<String> scopes = ApiKeyService.presetScopes(operator, preset);
        assertThat(scopes).as("step 1: the preset admits the server").contains(McpScopes.server("hohenheim"));
        String key = ApiKeyService.create(operator, operatorId, PREFIX + "assistant", scopes, null).plaintext();
        McpHttpSession session = McpHttpSession.open(endpoint, bearer(key));

        // 2. The tools derive from the sources, the operations and zenit-auth's accounts; an app is an app.
        assertThat(session.toolNames()).as("step 2: the tools")
            .contains("read", "list", "site_pause", "site_resume", "app_start", "app_deploy", "run_read",
                "certificate_renew", "user_whoami");

        // 3. Listing sites by a word names the site by its code; reading that code reads the site.
        String code = RecordCodes.codes(SiteModel.MODEL_ID, List.of(String.valueOf(siteId)), operator)
            .get(String.valueOf(siteId));
        assertThat(code).as("step 3: the new site has a code").matches("S\\d+");
        McpHttpSession.Answer listed = session.call("list", Map.of("kind", "site", "search", name));
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

    /**
     * Roel's question: an operator reads an app in full, as the operator panel's form shows it, so an existing app
     * can be the example for a new one: what it runs (image, tag, command), its port and its health check, a setting
     * left at its default said so, and a secret setting only as set; the customer holding the same app reads only what
     * the customer panel shows him.
     */
    @Test
    void anAppReadsInFullAsItsReadersPanelShowsIt() throws Exception {
        URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
        McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
        try (FakeApps apps = FakeApps.install((SqlDatasource) Models.get(InstanceModel.class)
                .getResolvedDatasource())) {
            AccessContext operator = TenantConduits.operator();
            String workerName = PREFIX + "worker-" + letters();
            Row worker = Models.get(InstanceModel.class).createEmptyRow();
            worker.set(InstanceModel.NAME, workerName);
            worker.set(InstanceModel.KIND, DockerContainerKind.ID.toString());
            worker.set(InstanceModel.HOST_ID, HostModel.localHostId());
            Map<String, Object> workerSettings = new LinkedHashMap<>();
            workerSettings.put("image", "fake/worker");
            workerSettings.put("tag", "1.4");
            workerSettings.put("command", "worker --queue mail");
            workerSettings.put("container_port", 8080);
            workerSettings.put("environment_variables", new LinkedHashMap<>(Map.of("API_TOKEN", SECRET)));
            worker.set(InstanceModel.SETTINGS, workerSettings);
            worker.set(InstanceModel.CRASH_POLICY, InstanceModel.CRASH_RESTART);
            Models.get(InstanceModel.class).save(worker);
            int workerId = worker.get(InstanceModel.ID);
            String shopName = PREFIX + "shop-" + letters();
            int shopId = application(shopName);
            Row shop = Models.get(InstanceModel.class).findById(shopId);
            Map<String, Object> shopSettings = new LinkedHashMap<>(FakeApps.settingsFor("v1"));
            shopSettings.put("health_path", "/up");
            shopSettings.put("environment_variables", new LinkedHashMap<>(Map.of("API_TOKEN", SECRET)));
            shop.set(InstanceModel.SETTINGS, shopSettings);
            Models.get(InstanceModel.class).save(shop);
            String workerCode = code(InstanceModel.MODEL_ID, workerId, operator);
            String shopCode = code(InstanceModel.MODEL_ID, shopId, operator);
            McpHttpSession operatorSession = presetSession(server, endpoint,
                TenantConduits.operatorUser().get(UserModel.ID), "Test Admin");

            // 1. The operator's read of the docker container says what it runs, its port, a setting left at its
            //    default, and its secret environment as set, never its value.
            McpHttpSession.Answer read = operatorSession.call("read", Map.of("ids", List.of(workerCode)));
            assertThat(read.error()).as("step 1: " + read.text()).isFalse();
            System.out.println("A docker container app read in full (" + read.text().length() + " characters):\n"
                + read.text());
            assertThat(read.text()).as("step 1: the container in full").contains(workerCode + " (app)")
                .contains(workerName).contains("Container image: fake/worker").contains("Image tag: 1.4")
                .contains("Container command: worker --queue mail").contains("Container port: 8080")
                .contains("(default)").contains("Environment variables: set").contains("status: ")
                .doesNotContain(SECRET);

            // 2. The operator's read of the application says its image, port and health check, its secret as set.
            McpHttpSession.Answer readShop = operatorSession.call("read", Map.of("ids", List.of(shopCode)));
            assertThat(readShop.error()).as("step 2: " + readShop.text()).isFalse();
            assertThat(readShop.text()).as("step 2: the application in full").contains("Container image: fake/app")
                .contains("Container port: 8080").contains("Health path: /up").contains("Environment variables: set")
                .doesNotContain(SECRET);

            // 3. The customer holding the container reads what the customer panel shows him: its name and status,
            //    none of the operator form's settings.
            UUID customerId = user(PREFIX + letters() + "@surface.test", "Mcp Full Read");
            RecordGrants.grant(GrantSubjectType.USER, customerId, InstanceModel.MODEL_ID, workerId,
                HohenheimCapabilities.MANAGE, true);
            McpHttpSession customer = presetSession(server, endpoint, customerId, "Mcp Full Read");
            McpHttpSession.Answer his = customer.call("read", Map.of("ids", List.of(workerCode)));
            assertThat(his.error()).as("step 3: " + his.text()).isFalse();
            assertThat(his.text()).as("step 3: the customer panel's fields").contains(workerName).contains("status: ")
                .doesNotContain("Container image").doesNotContain("fake/worker").doesNotContain("worker --queue")
                .doesNotContain("Environment variables").doesNotContain(SECRET);
        }
    }

    /**
     * A customer who is no operator, holding manage on his own app and its site, runs the app through a key of the
     * "Hohenheim assistant" preset: he sees status, starts and restarts it under the API trigger, reads its log with
     * its secret masked, its deploys and a build's log without the daemon's words, sets and removes a plain variable,
     * has a secret generated he is never sent, is told of another tenant's app what he is told of none, is never
     * offered an operator's verb, and deploys and rolls back his app as runs he follows by their codes, which another
     * tenant reads as codes naming nothing.
     */
    @Test
    void aCustomerRunsHisOwnAppThroughTheAssistantPreset() throws Exception {
        URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
        McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
        try (FakeApps apps = FakeApps.install((SqlDatasource) Models.get(InstanceModel.class)
                .getResolvedDatasource())) {
            FakeDockerDaemon daemon = apps.daemon();
            daemon.keepConsoleLogs();
            String name = PREFIX + "shop-" + letters();
            int appId = application(name);
            new InstanceVariables().setValue(appId, null, "API_TOKEN", InstanceVariableModel.KIND_SECRET, SECRET);
            Row site = ProxyTestSupport.setupInstanceSite(name, name, appId);
            int siteId = site.get(SiteModel.ID);
            String otherName = PREFIX + "other-" + letters();
            int otherId = application(otherName);
            UUID customerId = user(PREFIX + letters() + "@surface.test", "Mcp Customer");
            RecordGrants.grant(GrantSubjectType.USER, customerId, InstanceModel.MODEL_ID, appId,
                HohenheimCapabilities.MANAGE, true);
            RecordGrants.grant(GrantSubjectType.USER, customerId, SiteModel.MODEL_ID, siteId,
                HohenheimCapabilities.MANAGE, true);
            AccessContext operator = TenantConduits.operator();
            String appCode = code(InstanceModel.MODEL_ID, appId, operator);
            String siteCode = code(SiteModel.MODEL_ID, siteId, operator);
            String otherCode = code(InstanceModel.MODEL_ID, otherId, operator);

            // 1. He connects with a key minted from the preset the API keys page offers him.
            McpHttpSession session = presetSession(server, endpoint, customerId, "Mcp Customer");

            // 2. His list holds his app and its status, and no other tenant's app; his site's list its status.
            McpHttpSession.Answer listed = session.call("list", Map.of("kind", "app", "search", PREFIX));
            assertThat(listed.error()).as("step 2: " + listed.text()).isFalse();
            assertThat(listed.text()).as("step 2: his app, with its status").contains(appCode).contains(name)
                .containsAnyOf(MicrocopyText.say(InstanceStatus.CREATED.label()), InstanceModel.STATUS_CREATED)
                .doesNotContain(otherName).doesNotContain(otherCode);
            McpHttpSession.Answer sites = session.call("list", Map.of("kind", "site", "search", name));
            assertThat(sites.text()).as("step 2: his site, with its status").contains(siteCode)
                .containsAnyOf(MicrocopyText.say(HohenheimMicrocopy.SITE_STATUS.of(SiteModel.STATUS_ACTIVE)),
                    SiteModel.STATUS_ACTIVE);

            // 3. A read of his app and of his site says its status, and no stored number.
            for (String code : List.of(appCode, siteCode)) {
                McpHttpSession.Answer read = session.call("read", Map.of("ids", List.of(code)));
                assertThat(read.error()).as("step 3: " + read.text()).isFalse();
                assertThat(read.text()).as("step 3: the read of " + code).contains("status: ")
                    .doesNotContainPattern("(?<![A-Za-z0-9])[0-9]+(?![0-9])");
            }

            // 4. His tools are his app's, each named "app", never "instance"; not one operator verb is offered to him.
            List<String> tools = session.toolNames();
            System.out.println("MCP tools of a customer: " + String.join(", ", tools));
            assertThat(tools).as("step 4: his tools").contains("read", "list", "app_deploy", "app_roll_back",
                OperationRunOperations.READ.assistantName(), InstanceOperations.START.assistantName(),
                InstanceOperations.RESTART.assistantName(), InstanceLogOperations.READ_LOG.assistantName(),
                InstanceLogOperations.LIST_DEPLOYS.assistantName(),
                InstanceLogOperations.READ_BUILD_LOG.assistantName(),
                InstanceVariableOperations.LIST_VARIABLES.assistantName(),
                InstanceVariableOperations.SET_VARIABLE.assistantName(),
                InstanceVariableOperations.UNSET_VARIABLE.assistantName(),
                InstanceVariableOperations.GENERATE_SECRET.assistantName());
            assertThat(tools).as("step 4: no tool is named after an instance")
                .noneMatch(tool -> tool.startsWith("instance_"));
            for (Operation<?, ?, ?> operatorOnly : List.of(InstanceOperations.INSTALL, InstanceOperations.REINSTALL,
                    InstanceOperations.CAPTURE_TEMPLATE, InstanceOperations.DESTROY_WITH_DATA,
                    InstanceOperations.ATTACH_INSTALL_MEDIA, InstanceOperations.DETACH_INSTALL_MEDIA,
                    InstanceOperations.CONNECT_WEBHOOK, InstanceTemplateOperations.APPROVE_TEMPLATE,
                    InstanceTemplateOperations.UNAPPROVE_TEMPLATE, DatabaseDumpParts.DELETE,
                    InstanceLogOperations.READ_DEPLOY_LOG)) {
                assertThat(operatorOnly.assistantName()).as("step 4: " + operatorOnly.id() + " is a tool").isNotNull();
                assertThat(tools).as("step 4: " + operatorOnly.id() + " is not offered to him")
                    .doesNotContain(operatorOnly.assistantName());
            }

            // 5. He starts his app, then restarts it: both deploy under the API trigger, said and stored.
            for (Operation<?, ?, ?> power : List.of(InstanceOperations.START, InstanceOperations.RESTART)) {
                McpHttpSession.Answer powered = session.call(power.assistantName(), Map.of("id", appCode));
                assertThat(powered.error()).as("step 5: " + powered.text()).isFalse();
                assertThat(powered.text()).as("step 5: " + power.assistantName() + " answers the trigger")
                    .contains("(" + DeployTrigger.API.word() + ")");
                assertThat(newestRelease(appId).get(ReleaseOperationModel.TRIGGER))
                    .as("step 5: " + power.assistantName() + " recorded the API trigger")
                    .isEqualTo(DeployTrigger.API.word());
            }

            // 6. His app's log, the secret it printed masked.
            Row serving = Objects.requireNonNull(ApplicationReleases.ownedServing(appId), "step 6: a release serves");
            daemon.print(FakeDockerDaemon.handleOf(serving.get(InstanceModel.ID)),
                "booting\ntoken=" + SECRET + " ready\nlistening\n");
            McpHttpSession.Answer log = session.call(InstanceLogOperations.READ_LOG.assistantName(),
                Map.of("id", appCode, "lines", 50));
            assertThat(log.error()).as("step 6: " + log.text()).isFalse();
            assertThat(log.text()).as("step 6: the log, its secret masked").contains("listening")
                .contains("token=" + KnownValueRedactor.PLACEHOLDER + " ready").doesNotContain(SECRET);

            // 7. His deploys, a build's log, and a failed deploy read without the daemon's words.
            String reason = "daemon refused /var/run/docker.sock on host-7";
            String steps = "pulling registry.internal/app at /srv/hoh/apps";
            int failedId = failedRelease(appId, reason, steps);
            int buildId = failedBuild(appId, "npm ERR! the build of the shop", "exit 1 in /tmp/build-ws");
            String failedCode = code(ReleaseOperationModel.MODEL_ID, failedId, operator);
            String buildCode = code(BuildOperationModel.MODEL_ID, buildId, operator);
            DatasourceProbe probe = DatasourceProbe.on((SqlDatasource) Models.get(BuildOperationModel.class)
                .getResolvedDatasource());
            probe.reset();
            McpHttpSession.Answer deploys = session.call(InstanceLogOperations.LIST_DEPLOYS.assistantName(),
                Map.of("id", appCode));
            List<Integer> fewer = List.of(probe.queriesAgainst(ReleaseOperationModel.MODEL_ID),
                probe.queriesAgainst(BuildOperationModel.MODEL_ID));
            assertThat(deploys.error()).as("step 7: " + deploys.text()).isFalse();
            assertThat(deploys.text()).as("step 7: the deploys, by their codes").contains(failedCode)
                .contains(buildCode).doesNotContain(reason).doesNotContain(steps)
                .doesNotContain("exit 1 in /tmp/build-ws");
            failedBuild(appId, "npm ERR! again", "exit 2");
            failedBuild(appId, "npm ERR! and again", "exit 3");
            probe.reset();
            session.call(InstanceLogOperations.LIST_DEPLOYS.assistantName(), Map.of("id", appCode));
            assertThat(List.of(probe.queriesAgainst(ReleaseOperationModel.MODEL_ID),
                probe.queriesAgainst(BuildOperationModel.MODEL_ID)))
                .as("step 7: the list reads as much with two more builds").isEqualTo(fewer);
            McpHttpSession.Answer buildLog = session.call(InstanceLogOperations.READ_BUILD_LOG.assistantName(),
                Map.of("id", buildCode));
            assertThat(buildLog.text()).as("step 7: his build's own output").contains("npm ERR! the build of the shop");
            McpHttpSession.Answer failed = session.call("read", Map.of("ids", List.of(failedCode, buildCode)));
            assertThat(failed.error()).as("step 7: " + failed.text()).isFalse();
            assertThat(failed.text()).as("step 7: the reads name the app and say that they failed")
                .contains("app: " + appCode).contains("failure: ").doesNotContain(reason).doesNotContain(steps)
                .doesNotContain("exit 1 in /tmp/build-ws").doesNotContain("sha256:");

            // 8. A plain variable set, listed and removed; the secret listed as set alone, and never replaced.
            McpHttpSession.Answer set = session.call(InstanceVariableOperations.SET_VARIABLE.assistantName(),
                Map.of("id", appCode, "key", "GREETING", "plain_value", "hello"));
            assertThat(set.error()).as("step 8: " + set.text()).isFalse();
            String listVariables = InstanceVariableOperations.LIST_VARIABLES.assistantName();
            assertThat(session.call(listVariables, Map.of("id", appCode)).text()).as("step 8: the list")
                .contains("GREETING = hello").contains("API_TOKEN (secret, set)").doesNotContain(SECRET);
            McpHttpSession.Answer overSecret = session.call(InstanceVariableOperations.SET_VARIABLE.assistantName(),
                Map.of("id", appCode, "key", "API_TOKEN", "plain_value", "plain"));
            assertThat(overSecret.error()).as("step 8: a secret is never turned plain").isTrue();
            assertThat(new InstanceVariables().valuesFor(appId)).as("step 8: the secret stays")
                .containsEntry("API_TOKEN", SECRET);
            McpHttpSession.Answer unset = session.call(InstanceVariableOperations.UNSET_VARIABLE.assistantName(),
                Map.of("id", appCode, "key", "GREETING"));
            assertThat(unset.error()).as("step 8: " + unset.text()).isFalse();
            assertThat(session.call(listVariables, Map.of("id", appCode)).text()).as("step 8: removed")
                .doesNotContain("GREETING");
            UUID viewerId = user(PREFIX + letters() + "@surface.test", "Mcp Viewer");
            RecordGrants.grant(GrantSubjectType.USER, viewerId, InstanceModel.MODEL_ID, appId,
                HohenheimCapabilities.VIEW, true);
            McpHttpSession viewer = presetSession(server, endpoint, viewerId, "Mcp Viewer");
            assertThat(viewer.call(listVariables, Map.of("id", appCode)).text()).as("step 8: a viewer lists")
                .contains("API_TOKEN (secret, set)");
            assertThat(viewer.call(InstanceVariableOperations.SET_VARIABLE.assistantName(),
                Map.of("id", appCode, "key", "GREETING", "plain_value", "hello")).error())
                .as("step 8: and is refused the set").isTrue();

            // 8b. The server makes a secret for a key holding none: nobody is sent it, and it never replaces a value.
            String generate = InstanceVariableOperations.GENERATE_SECRET.assistantName();
            McpHttpSession.Answer generated = session.call(generate, Map.of("id", appCode, "key", "SESSION_KEY"));
            assertThat(generated.error()).as("step 8b: " + generated.text()).isFalse();
            String made = new InstanceVariables().valuesFor(appId).get("SESSION_KEY");
            assertThat(made).as("step 8b: a value was made").isNotBlank();
            assertThat(generated.text()).as("step 8b: the answer names the key, never the value")
                .contains("SESSION_KEY").doesNotContain(made);
            assertThat(session.call(listVariables, Map.of("id", appCode)).text()).as("step 8b: listed as a set secret")
                .contains("SESSION_KEY (secret, set)").doesNotContain(made);
            assertThat(session.call(generate, Map.of("id", appCode, "key", "API_TOKEN")).error())
                .as("step 8b: a held value is never replaced").isTrue();
            assertThat(new InstanceVariables().valuesFor(appId)).as("step 8b: the secret stays")
                .containsEntry("API_TOKEN", SECRET).containsEntry("SESSION_KEY", made);
            assertThat(viewer.call(generate, Map.of("id", appCode, "key", "VIEWER_KEY")).error())
                .as("step 8b: a viewer is refused").isTrue();

            // 9. Another tenant's app answers as an app that does not exist.
            String missing = "AP999999";
            for (Operation<?, ?, ?> asked : List.of(InstanceOperations.START, InstanceLogOperations.READ_LOG,
                    InstanceLogOperations.LIST_DEPLOYS, InstanceVariableOperations.LIST_VARIABLES)) {
                McpHttpSession.Answer foreign = session.call(asked.assistantName(), Map.of("id", otherCode));
                McpHttpSession.Answer none = session.call(asked.assistantName(), Map.of("id", missing));
                assertThat(foreign.error()).as("step 9: " + asked.assistantName() + " refused").isTrue();
                assertThat(foreign.text().replace(otherCode, "<code>"))
                    .as("step 9: " + asked.assistantName() + " says what it says of none")
                    .isEqualTo(none.text().replace(missing, "<code>"));
            }

            // 10. A backup and a snapshot are refused, without naming what he lacks.
            for (Operation<?, ?, ?> withheld : List.of(InstanceOperations.BACKUP, InstanceOperations.SNAPSHOT)) {
                McpHttpSession.Answer refused = session.call(withheld.assistantName(), Map.of("id", appCode));
                assertThat(refused.error()).as("step 10: " + withheld.assistantName() + " refused").isTrue();
                assertThat(refused.text()).as("step 10: without naming the capability")
                    .doesNotContain(HohenheimCapabilities.BACKUPS).doesNotContain(HohenheimCapabilities.SNAPSHOTS);
            }

            // 11. The operator reads the failed deploy's step log, which no customer is offered.
            McpHttpSession operatorSession = presetSession(server, endpoint,
                TenantConduits.operatorUser().get(UserModel.ID), "Test Admin");
            McpHttpSession.Answer stepLog = operatorSession.call(InstanceLogOperations.READ_DEPLOY_LOG.assistantName(),
                Map.of("id", failedCode));
            assertThat(stepLog.error()).as("step 11: " + stepLog.text()).isFalse();
            assertThat(stepLog.text()).as("step 11: the step log").contains(steps);
            List<String> operatorTools = operatorSession.toolNames();
            System.out.println("MCP tools of an operator: " + String.join(", ", operatorTools));
            assertThat(operatorTools).as("step 11: the operator's tools hold the runs and no instance name")
                .contains("app_deploy", "app_roll_back", OperationRunOperations.READ.assistantName())
                .noneMatch(tool -> tool.startsWith("instance_"));

            // 12. He deploys his app's next version by its tool: the call answers its run's code at once, he follows
            //     the run to its end, and his deploy list holds the new deploy, recorded under the API trigger.
            FakeApps.setSettings(appId, "v2");
            settle("step 12");
            String deployRun = startedRun(session, InstanceOperations.DEPLOY, appCode, "step 12");
            assertThat(followed(session, deployRun, "step 12").text()).as("step 12: the run ended well")
                .contains("status: " + OperationRunStatus.SUCCEEDED.storageKey()).contains("subject: " + appCode);
            Row deployed = newestRelease(appId);
            assertThat(deployed.get(ReleaseOperationModel.TRIGGER)).as("step 12: recorded under the API trigger")
                .isEqualTo(DeployTrigger.API.word());
            String deployedCode = code(ReleaseOperationModel.MODEL_ID, deployed.get(ReleaseOperationModel.ID),
                operator);
            assertThat(session.call(InstanceLogOperations.LIST_DEPLOYS.assistantName(), Map.of("id", appCode)).text())
                .as("step 12: his deploy list holds the new deploy").contains(deployedCode);

            // 13. He rolls it back the same way: a run he follows to its end, a rollback in his deploy list.
            settle("step 13");
            String rollbackRun = startedRun(session, InstanceOperations.ROLLBACK, appCode, "step 13");
            assertThat(followed(session, rollbackRun, "step 13").text()).as("step 13: the run ended well")
                .contains("status: " + OperationRunStatus.SUCCEEDED.storageKey()).contains("subject: " + appCode);
            Row rolledBack = newestRelease(appId);
            assertThat(rolledBack.get(ReleaseOperationModel.KIND)).as("step 13: a rollback")
                .isEqualTo(ReleaseOperationModel.KIND_ROLLBACK);
            assertThat(rolledBack.get(ReleaseOperationModel.TRIGGER)).as("step 13: recorded under the API trigger")
                .isEqualTo(DeployTrigger.API.word());
            String rolledBackCode = code(ReleaseOperationModel.MODEL_ID, rolledBack.get(ReleaseOperationModel.ID),
                operator);
            assertThat(session.call(InstanceLogOperations.LIST_DEPLOYS.assistantName(), Map.of("id", appCode)).text())
                .as("step 13: his deploy list holds the rollback").contains(rolledBackCode);
            settle("step 13");

            // 14. Another tenant reads either run's code exactly as a code naming nothing, by either tool.
            UUID rivalId = user(PREFIX + letters() + "@surface.test", "Mcp Rival");
            RecordGrants.grant(GrantSubjectType.USER, rivalId, InstanceModel.MODEL_ID, otherId,
                HohenheimCapabilities.MANAGE, true);
            McpHttpSession rival = presetSession(server, endpoint, rivalId, "Mcp Rival");
            String runRead = Objects.requireNonNull(OperationRunOperations.READ.assistantName());
            String noRun = "RUN999999";
            for (String run : List.of(deployRun, rollbackRun)) {
                McpHttpSession.Answer hidden = rival.call(runRead, Map.of("id", run));
                assertThat(hidden.error()).as("step 14: " + run + " refused to another tenant").isTrue();
                assertThat(hidden.text().replace(run, "<code>")).as("step 14: " + run + " names nothing to him")
                    .isEqualTo(rival.call(runRead, Map.of("id", noRun)).text().replace(noRun, "<code>"));
                assertThat(rival.call("read", Map.of("ids", List.of(run))).text().replace(run, "<code>"))
                    .as("step 14: nor does a read of " + run)
                    .isEqualTo(rival.call("read", Map.of("ids", List.of(noRun))).text().replace(noRun, "<code>"));
            }
        }
    }

    /**
     * Runs over records that state no readers of their own (a site's rollback, a backup's and a dump's restore in
     * place, a service put online refused while its template was the subject, an address put online over the site it
     * made) are their starter's alone: another tenant learns of each exactly what he learns of a run that does not
     * exist, by the panel's run page, its live part, run_read and read.
     */
    @Test
    void anotherTenantReadsNoRunOverARecordThatStatesNoReaders() throws Exception {
        URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
        McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
        UUID ownerId = user(PREFIX + letters() + "@surface.test", "Run Owner");
        UUID rivalId = user(PREFIX + letters() + "@surface.test", "Run Rival");
        int siteId = site(PREFIX + "runs-" + letters());
        int rivalSite = site(PREFIX + "rival-" + letters());
        RecordGrants.grant(GrantSubjectType.USER, ownerId, SiteModel.MODEL_ID, siteId, HohenheimCapabilities.MANAGE,
            true);
        RecordGrants.grant(GrantSubjectType.USER, rivalId, SiteModel.MODEL_ID, rivalSite,
            HohenheimCapabilities.MANAGE, true);
        int appId = record(InstanceModel.class, row -> {
            row.set(InstanceModel.NAME, PREFIX + "app-" + letters());
            row.set(InstanceModel.KIND, "hohenheim:docker_container");
            row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine", "tag", "latest")));
            row.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        });
        int backupId = record(InstanceBackupModel.class, row -> {
            row.set(InstanceBackupModel.INSTANCE_ID, appId);
            row.set(InstanceBackupModel.STATUS, "complete");
        });
        int dumpId = record(DatabaseDumpModel.class, row -> {
            row.set(DatabaseDumpModel.DATABASE_NAME, PREFIX + "db");
            row.set(DatabaseDumpModel.ENGINE, DatabaseModel.ENGINE_POSTGRES);
            row.set(DatabaseDumpModel.STATUS, "complete");
        });
        int templateId = record(InstanceTemplateModel.class, row -> {
            row.set(InstanceTemplateModel.NAME, PREFIX + "template-" + letters());
            row.set(InstanceTemplateModel.KIND, "hohenheim:docker_container");
            row.set(InstanceTemplateModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine", "tag", "latest")));
            // Approved, so every tenant may choose it: the open read that leaked the run admitted the rival.
            row.set(InstanceTemplateModel.APPROVED_AT, Now.instant());
            row.set(InstanceTemplateModel.APPROVED_BY_USER_ID, TenantConduits.operatorUser().get(UserModel.ID));
        });
        try {
            // 1. The owner of the site started five runs, one per kind of subject that states no readers.
            Map<String, Long> runs = new LinkedHashMap<>();
            runs.put("site rollback", run(SiteOperations.ROLLBACK_RELEASE, SiteOperations.SITE, siteId, ownerId,
                OperationRunStatus.SUCCEEDED));
            runs.put("backup restore", run(InstanceBackupOperations.RESTORE_BACKUP_IN_PLACE,
                InstanceBackupOperations.BACKUP, backupId, ownerId, OperationRunStatus.FAILED));
            runs.put("dump restore", run(DatabaseDumpParts.RESTORE_IN_PLACE, DatabaseDumpParts.DUMP, dumpId, ownerId,
                OperationRunStatus.SUCCEEDED));
            runs.put("service put online", run(PutOnline.PUT_SERVICE_ONLINE,
                Objects.requireNonNull(PutOnline.PUT_SERVICE_ONLINE.subjectType()), templateId, ownerId,
                OperationRunStatus.REFUSED));
            runs.put("address put online", run(PutOnline.PUT_ADDRESS_ONLINE, SiteOperations.SITE, siteId, ownerId,
                OperationRunStatus.SUCCEEDED));
            AccessContext owner = AccessContext.of(TenantConduits.stubFor(new UserPrincipal(ownerId, "Run Owner")));
            String ownerSession = sessionFor(ownerId).token();
            String rivalSession = sessionFor(rivalId).token();
            McpHttpSession ownerTools = presetSession(server, endpoint, ownerId, "Run Owner");
            McpHttpSession rival = presetSession(server, endpoint, rivalId, "Run Rival");
            String runRead = Objects.requireNonNull(OperationRunOperations.READ.assistantName());
            String noRun = "RUN999999";
            long noRunId = 999_999_999L;
            String page = "/manage/" + HohenheimSlugs.SITES + "/run/";
            String part = "/zn/live/zenit.cms_operation_run/manage," + HohenheimSlugs.SITES + ",";
            int missingPage = httpGet(page + noRunId, rivalSession).statusCode();
            int missingPart = httpGet(part + noRunId, rivalSession).statusCode();
            assertThat(List.of(missingPage, missingPart)).as("step 1: no run is found by either route")
                .allMatch(status -> status == 404);
            for (Map.Entry<String, Long> started : runs.entrySet()) {
                String what = started.getKey();
                long id = started.getValue();
                String code = code(OperationRunModel.MODEL_ID, (int) id, owner);

                // 2. Its starter reads it by every route.
                assertThat(ownerTools.call(runRead, Map.of("id", code)).error()).as("step 2: " + what + " by its tool")
                    .isFalse();
                assertThat(httpGet(page + id, ownerSession).statusCode()).as("step 2: " + what + " by its page")
                    .isEqualTo(200);
                assertThat(httpGet(part + id, ownerSession).statusCode()).as("step 2: " + what + " by its part")
                    .isEqualTo(200);

                // 3. THE ASSERTION: another tenant admitted to the same panel entry reads it as no run at all.
                McpHttpSession.Answer hidden = rival.call(runRead, Map.of("id", code));
                assertThat(hidden.error()).as("step 3: " + what + " refused by run_read").isTrue();
                assertThat(hidden.text().replace(code, "<code>")).as("step 3: " + what + " names nothing to him")
                    .isEqualTo(rival.call(runRead, Map.of("id", noRun)).text().replace(noRun, "<code>"));
                assertThat(rival.call("read", Map.of("ids", List.of(code))).text().replace(code, "<code>"))
                    .as("step 3: nor does a read of " + what)
                    .isEqualTo(rival.call("read", Map.of("ids", List.of(noRun))).text().replace(noRun, "<code>"));
                assertThat(httpGet(page + id, rivalSession).statusCode()).as("step 3: " + what + "'s page")
                    .isEqualTo(missingPage);
                assertThat(httpGet(part + id, rivalSession).statusCode()).as("step 3: " + what + "'s live part")
                    .isEqualTo(missingPart);
            }
        } finally {
            HardDeletes.byId(Models.get(InstanceBackupModel.class), backupId);
            HardDeletes.byId(Models.get(DatabaseDumpModel.class), dumpId);
            HardDeletes.byId(Models.get(InstanceTemplateModel.class), templateId);
            HardDeletes.byId(Models.get(InstanceModel.class), appId);
        }
    }

    /** @return the id of a row of the model saved with what {@code fill} sets */
    private static int record(Class<? extends Model> model, Consumer<Row> fill) {
        Row row = Models.get(model).createEmptyRow();
        fill.accept(row);
        Models.get(model).save(row);
        return (Integer) Objects.requireNonNull(Models.get(model).getPrimaryKeyValue(row));
    }

    /** @return the id of an ended run of the operation over the subject, stored as its pipeline stores one */
    private static long run(Operation<?, ?, ?> operation, SubjectType<?> type, int key, UUID starter,
                            OperationRunStatus status) {
        OperationRunModel runs = Models.get(OperationRunModel.class);
        Row row = runs.createEmptyRow();
        row.set(OperationRunModel.OPERATION, operation.id().toString());
        row.set(OperationRunModel.INVOCATION_ID, UUID.randomUUID());
        OperationRunModel.CALLER_PRINCIPAL.write(row, PrincipalRef.account(starter));
        row.set(OperationRunModel.SUBJECT_TYPE, type.id().toString());
        row.set(OperationRunModel.SUBJECT_KEY, String.valueOf(key));
        row.set(OperationRunModel.STATUS, status.storageKey());
        row.set(OperationRunModel.PROGRESS, 1.0);
        row.set(OperationRunModel.CLAIM_FENCE, 1L);
        row.set(OperationRunModel.STARTED_AT, Now.instant());
        row.set(OperationRunModel.ENDED_AT, Now.instant());
        return runs.save(row).get(OperationRunModel.ID);
    }

    /** A session of a key the account mints from the "Hohenheim assistant" preset, as its API keys page does. */
    private static McpHttpSession presetSession(McpHostServer server, URI endpoint, UUID accountId, String name) {
        AccessContext account = AccessContext.of(TenantConduits.stubFor(new UserPrincipal(accountId, name)));
        CredentialPresets.Preset preset = server.credentialPreset(account);
        assertThat(preset).as(name + " is offered the preset").isNotNull();
        List<String> scopes = ApiKeyService.presetScopes(account, preset);
        assertThat(scopes).as("the preset admits the server").contains(McpScopes.server("hohenheim"));
        String key = ApiKeyService.create(account, accountId, PREFIX + letters(), scopes, null).plaintext();
        return McpHttpSession.open(endpoint, bearer(key));
    }

    /** @return the code of the run a background tool's call started on the app, as its answer names it */
    private static String startedRun(McpHttpSession session, Operation<?, ?, ?> operation, String appCode,
                                     String step) {
        McpHttpSession.Answer started = session.call(Objects.requireNonNull(operation.assistantName()),
            Map.of("id", appCode));
        assertThat(started.error()).as(step + ": " + started.text()).isFalse();
        Matcher run = RUN_CODE.matcher(started.text());
        assertThat(run.find()).as(step + ": the answer names its run: " + started.text()).isTrue();
        assertThat(started.text()).as(step + ": started on his app").contains(appCode + ": " + run.group());
        return run.group();
    }

    /** @return what the run's read tool tells of the run once it is no longer running */
    private static McpHttpSession.Answer followed(McpHttpSession session, String run, String step) {
        String read = Objects.requireNonNull(OperationRunOperations.READ.assistantName());
        String running = "status: " + OperationRunStatus.RUNNING.storageKey();
        McpHttpSession.Answer ended = Poll.value(step + ": " + run + " ends", Duration.ofSeconds(20),
            Duration.ofMillis(50), () -> {
                McpHttpSession.Answer answer = session.call(read, Map.of("id", run));
                return answer.text().contains(running) ? null : answer;
            });
        assertThat(ended.error()).as(step + ": " + ended.text()).isFalse();
        return ended;
    }

    /** Waits on the release engine's drains and the background pool, so a deploy is not refused as one under way. */
    private static void settle(String step) {
        assertThat(ReleaseEngine.awaitDrainsIdle(Duration.ofSeconds(20))).as(step + ": the drains settled").isTrue();
        assertThat(HandlerSupport.awaitBackgroundIdle(Duration.ofSeconds(20))).as(step + ": the background settled")
            .isTrue();
    }

    private static String code(Identifier model, int id, AccessContext reader) {
        String code = RecordCodes.codes(model, List.of(String.valueOf(id)), reader).get(String.valueOf(id));
        assertThat(code).as(model + " " + id + " has a code").isNotNull();
        return code;
    }

    /** @return letters alone, so a name says no number an answer could be suspected of */
    private static String letters() {
        return UUID.randomUUID().toString().replaceAll("[^a-f]", "");
    }

    /** An authored app of the fake image that declares no console contract, so its log is read, never streamed. */
    private static int application(String name) {
        int id = FakeApps.application(name, "v1");
        Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(id))
            .assign(InstanceModel.CRASH_POLICY, InstanceModel.CRASH_NONE).updateAll();
        return id;
    }

    /** A deploy of the app that failed, as the engine stores one: the daemon's words and its steps. */
    private static int failedRelease(int appId, String reason, String steps) {
        Row release = Models.get(ReleaseOperationModel.class).createEmptyRow();
        release.set(ReleaseOperationModel.KIND, ReleaseOperationModel.KIND_RELEASE);
        release.set(ReleaseOperationModel.FOR_MODEL, InstanceModel.MODEL_ID.toString());
        release.set(ReleaseOperationModel.FOR_ID, appId);
        release.set(ReleaseOperationModel.STATUS, ReleaseOperationModel.LIFECYCLE.stored(OperationStatus.FAILED));
        release.set(ReleaseOperationModel.FAILURE_REASON, reason);
        release.set(ReleaseOperationModel.STEP_LOG, steps);
        release.set(ReleaseOperationModel.IMAGE_ID, "sha256:0123456789abcdef");
        release.set(ReleaseOperationModel.STARTED_AT, Now.instant().minus(Duration.ofHours(1)));
        Models.get(ReleaseOperationModel.class).save(release);
        return release.get(ReleaseOperationModel.ID);
    }

    /** A build of the app that failed and so never became a release. */
    private static int failedBuild(int appId, String log, String reason) {
        Row build = Models.get(BuildOperationModel.class).createEmptyRow();
        build.set(BuildOperationModel.FOR_MODEL, InstanceModel.MODEL_ID.toString());
        build.set(BuildOperationModel.FOR_ID, appId);
        build.set(BuildOperationModel.STATUS, BuildOperationModel.LIFECYCLE.stored(OperationStatus.FAILED));
        build.set(BuildOperationModel.SOURCE_REF, "main");
        build.set(BuildOperationModel.LOG, log);
        build.set(BuildOperationModel.FAILURE_REASON, reason);
        build.set(BuildOperationModel.STARTED_AT, Now.instant().minus(Duration.ofHours(2)));
        Models.get(BuildOperationModel.class).save(build);
        return build.get(BuildOperationModel.ID);
    }

    /** @return the app's newest deploy */
    private static Row newestRelease(int appId) {
        return Models.get(ReleaseOperationModel.class).find().noCache()
            .where(ReleaseOperationModel.FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(ReleaseOperationModel.FOR_ID.eq(appId))
            .orderBy(ReleaseOperationModel.ID, SortOrder.DESC).first();
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
