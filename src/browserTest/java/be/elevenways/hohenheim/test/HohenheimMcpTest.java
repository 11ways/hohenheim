package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.instance.InstanceLogOperations;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.instance.InstanceTemplateOperations;
import be.elevenways.hohenheim.instance.InstanceVariableOperations;
import be.elevenways.hohenheim.model.*;
import be.elevenways.hohenheim.site.SiteOperations;
import be.elevenways.hohenheim.instance.InstanceBackupOperations;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.host.VolumeBackend;
import be.elevenways.hohenheim.server.HandlerSupport;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.application.ReleaseEngine;
import be.elevenways.hohenheim.server.cms.DatabaseDumpParts;
import be.elevenways.hohenheim.server.cms.HostParts;
import be.elevenways.hohenheim.server.cms.PutOnline;
import be.elevenways.hohenheim.server.dns.GeneratedDnsRecords;
import be.elevenways.hohenheim.server.host.VolumeBackends;
import be.elevenways.hohenheim.server.instance.InstanceOperationHandlers;
import be.elevenways.hohenheim.server.instance.DockerContainerKind;
import be.elevenways.hohenheim.server.instance.InstanceCapacity;
import be.elevenways.hohenheim.server.instance.InstanceVariables;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.upstream.kinds.InstanceUpstreamKind;
import be.elevenways.hohenheim.test.application.FakeApps;
import be.elevenways.hohenheim.test.docker.FakeDockerDaemon;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.hohenheim.test.tls.FakeAcmeServer;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.testsupport.time.Poll;
import be.elevenways.zenit.ai.server.mcp.host.McpCoverage;
import be.elevenways.zenit.ai.server.mcp.host.McpDescribeTool;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHostTool;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.ai.server.mcp.host.McpListTool;
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
import be.elevenways.zenit.common.edit.FormEntry;
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
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.CredentialPresets;
import be.elevenways.zenit.common.security.KnownValueRedactor;
import be.elevenways.zenit.server.microcopy.ShippedCatalogs;
import be.elevenways.zenit.server.operation.OperationRunOperations;
import be.elevenways.zenit.server.operation.RecordCodes;
import be.elevenways.zenit.test.support.DatasourceProbe;
import be.elevenways.zenit.test.support.TestAccessContexts;
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
import java.util.TreeSet;
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
        "hohenheim:linked_server_remove", "hohenheim:manage_update_site",
        "hohenheim:open_framebuffer", "hohenheim:open_shell",
        "hohenheim:protect_path", "hohenheim:put_address_online", "hohenheim:put_machine_online",
        "hohenheim:put_online", "hohenheim:put_service_online", "hohenheim:put_stream_online",
        "hohenheim:put_volume_file", "hohenheim:put_workload_online", "hohenheim:put_workload_service_online",
        "hohenheim:redeclare_volume", "hohenheim:repin_host_key", "hohenheim:repin_incus_cert",
        "hohenheim:route_notification_events", "hohenheim:run_schedule",
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
     * Coverage: every operation an assistant may run is reached by exactly ONE tool and argument value; what is still
     * neither placed nor kept out is pinned by id, so the list only shrinks.
     *
     * AIDEV-NOTE: the three one-key variable operations and the per-app variable list are left for the panel's history
     * of past calls; variable_set runs their batch (set_instance_variables) and list kind=variable of=AP12 lists an
     * app's variables. The rest is what zenit core declares in Hohenheim's namespace with no verb, as before.
     */
    @Test
    void everyPlacedOperationIsReachedByOneToolAndValue() {
        McpHostServer server = Objects.requireNonNull(McpHostServers.get(HohenheimMcpModule.SERVER_NAME));
        assertThat(HohenheimMcpModule.tools()).as("step 1: the boot module built the tools").isNotNull();
        List<String> uncovered = McpCoverage.uncovered("hohenheim", HohenheimMcpModule.tools(),
                Objects.requireNonNull(HohenheimMcpModule.declared())).stream()
            .map(operation -> operation.id().toString()).sorted().toList();
        assertThat(uncovered).as("step 1: operations no tool places and none keeps from assistants")
            .containsExactly("hohenheim:access_rule_move_down", "hohenheim:access_rule_move_up",
                "hohenheim:delete_auth_provider",
                "hohenheim:generate_instance_secret", "hohenheim:list_instance_variables",
                "hohenheim:set_instance_variable", "hohenheim:site_hold_place",
                "hohenheim:site_hold_remove", "hohenheim:site_label_place", "hohenheim:site_label_remove",
                "hohenheim:site_pinned_place", "hohenheim:site_pinned_remove", "hohenheim:site_recover",
                "hohenheim:site_restore_revision", "hohenheim:unset_instance_variable");
        List<String> unlisted = McpCoverage.unlisted("hohenheim", HohenheimMcpModule.tools()).stream()
            .map(source -> source.id().toString()).toList();
        assertThat(unlisted).as("step 1: sources no tool lists and none keeps from assistants").isEmpty();

        // 2. Each placed operation is reached once: by one tool, and by one route of it (one kind and value).
        assertThat(McpCoverage.placedTwice(server)).as("step 2: operations placed by two tools").isEmpty();
        Map<Operation<?, ?, ?>, String> reached = new LinkedHashMap<>();
        for (McpHostTool tool : Objects.requireNonNull(HohenheimMcpModule.declared()).tools()) {
            assertThat(tool.operations()).as("step 2: " + tool.name() + " places each operation once")
                .doesNotHaveDuplicates();
            for (Operation<?, ?, ?> operation : tool.operations()) {
                assertThat(reached.put(operation, tool.name())).as("step 2: " + operation.id() + " once").isNull();
            }
        }

        // 3. No tool takes an object of undeclared members; a kind's map setting is a typed list of its entries.
        AccessContext operator = TenantConduits.operator();
        List<String> maps = List.of("app_create_from_template.variables", "certificate_order.dns_publisher");
        List<String> loose = McpCoverage.looseParameters(server, operator);
        System.out.println("MCP arguments of no declared members: " + loose);
        assertThat(loose).as("step 3: arguments of no declared shape").isSubsetOf(maps);
        // A hand-declared tool takes no object it does not declare as an entry (an importer's provenance envelope).
        List<String> handNames = Objects.requireNonNull(HohenheimMcpModule.declared()).tools().stream()
            .map(McpHostTool::name).toList();
        assertThat(McpCoverage.undeclaredObjects(server, handNames, operator))
            .as("step 3: objects a hand-declared tool takes that are no entry of its operations").isEmpty();
        // Every argument a create needs says so first, from the create's own declaration.
        Map<?, ?> docker = (Map<?, ?>) McpCoverage.offered(server, operator).stream().filter(tool -> tool.name()
            .equals("app_docker_container_set")).findFirst().orElseThrow().inputSchema(operator).get("properties");
        assertThat(String.valueOf(((Map<?, ?>) docker.get("name")).get("description")))
            .as("step 3: name is required to create").startsWith("Required to create.");
        assertThat(String.valueOf(((Map<?, ?>) docker.get("image")).get("description")))
            .as("step 3: a kind that never builds says what its image is")
            .doesNotContain("Dockerfile");

        // 4. Every hand-declared tool and each of its arguments is described, in English and in Dutch.
        Set<String> declared = new HashSet<>();
        for (McpHostTool tool : Objects.requireNonNull(HohenheimMcpModule.declared()).tools()) {
            declared.add(tool.name());
        }
        declared.add(McpDescribeTool.NAME);
        Map<String, String> english = new LinkedHashMap<>();
        for (String language : List.of("en", "nl")) {
            AccessContext reader = AccessContext.of(TestAccessContexts.attributeConduit(LocaleChain.ofTags(language),
                new ShippedCatalogs()), operator.principal(), operator.permissionChecker());
            for (McpHostTool tool : McpCoverage.offered(server, reader)) {
                if (!declared.contains(tool.name())) continue;
                String said = tool.description(reader);
                assertThat(said).as("step 4: " + tool.name() + " in " + language).isNotBlank()
                    .doesNotStartWith("mcp_").isNotEqualTo(tool.name());
                if ("en".equals(language)) english.put(tool.name(), said);
                else assertThat(said).as("step 4: " + tool.name() + " says it in Dutch")
                    .isNotEqualTo(english.get(tool.name()));
                Map<?, ?> properties = (Map<?, ?>) tool.inputSchema(reader).get("properties");
                properties.forEach((argument, shape) -> assertThat(((Map<?, ?>) shape).get("description"))
                    .as("step 4: " + tool.name() + "." + argument + " in " + language).isInstanceOf(String.class)
                    .asString().isNotBlank());
            }
        }
        assertThat(english.keySet()).as("step 4: the operator is offered every declared tool")
            .containsExactlyInAnyOrderElementsOf(declared);
        System.out.println("MCP live tools of an operator: " + McpCoverage.offered(server, operator).size());
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
        for (McpHostTool tool : Objects.requireNonNull(HohenheimMcpModule.declared()).tools()) {
            if (tool.operations().stream().anyMatch(operation -> operation.subjectType() != null
                    && HostModel.MODEL_ID.equals(operation.subjectType().codeModel()))) {
                names.add(tool.name());
            }
        }
        assertThat(names).as("step 2: every tool acting on a host is named after the host").containsExactlyInAnyOrder(
            "host_set", "host_admission_set", "host_trust", "host_client_key_reset", "host_leftover_cleanup",
            "host_media_set", "delete", "test");
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
        System.out.println("MCP arguments without words: " + McpCoverage.undescribedParameters(server,
            TenantConduits.operator()));
    }

    /**
     * Words: an argument goes by the name its field declares for people, never by a stored column name that differs
     * from it (nice_name is name), and no argument says a word the tool rules forbid (domain, ssl, letsencrypt).
     */
    @Test
    void everyArgumentGoesByItsNameForPeople() {
        McpHostServer server = Objects.requireNonNull(McpHostServers.get(HohenheimMcpModule.SERVER_NAME));
        AccessContext operator = TenantConduits.operator();
        Set<String> stored = new TreeSet<>();
        Set<String> declared = new HashSet<>();
        for (McpHostTool tool : Objects.requireNonNull(HohenheimMcpModule.declared()).tools()) {
            declared.add(tool.name());
            for (Operation<?, ?, ?> operation : tool.operations()) {
                for (FormEntry entry : operation.input().form().entries()) {
                    Field<?, ?> field = entry.field();
                    if (field != null && !field.getName().equals(field.getInputName())) stored.add(field.getName());
                }
            }
        }
        assertThat(stored).as("step 1: the stored names that go by another name")
            .contains("nice_name", "force_ssl", "custom_headers", "more_backends", "port_protocol", "delay");
        // Whole words of a name: "hsts_subdomains" names subdomains, never a domain for an address.
        List<String> forbidden = List.of("domain", "domains", "ssl", "letsencrypt", "server");
        int arguments = 0;
        for (McpHostTool tool : McpCoverage.offered(server, operator)) {
            if (!declared.contains(tool.name())) continue;
            Map<?, ?> properties = (Map<?, ?>) tool.inputSchema(operator).get("properties");
            for (Object argument : properties.keySet()) {
                String name = tool.name() + "." + argument;
                assertThat(stored).as("step 2: " + name + " is a stored name").doesNotContain(String.valueOf(argument));
                for (String word : String.valueOf(argument).split("_")) {
                    assertThat(forbidden).as("step 2: " + name + " says " + word).doesNotContain(word);
                }
                for (String prefix : List.of("nice_", "custom_", "more_")) {
                    assertThat(String.valueOf(argument)).as("step 2: " + name).doesNotStartWith(prefix);
                }
                arguments++;
            }
        }
        assertThat(arguments).as("step 2: the hand-declared tools' arguments were read").isGreaterThan(100);
    }

    /** The one list knows every kind the tools take or answer a code of, each by the spec's word for it. */
    @Test
    void theListKnowsEveryKindTheToolsName() {
        McpHostServer server = Objects.requireNonNull(McpHostServers.get(HohenheimMcpModule.SERVER_NAME));
        AccessContext operator = TenantConduits.operator();
        McpHostTool list = McpCoverage.offered(server, operator).stream()
            .filter(tool -> tool.name().equals(McpListTool.NAME)).findFirst().orElseThrow();
        Map<?, ?> kind = (Map<?, ?>) ((Map<?, ?>) list.inputSchema(operator).get("properties")).get(McpListTool.KIND);
        List<String> kinds = ((List<?>) kind.get("enum")).stream().map(String::valueOf).toList();
        assertThat(kinds).as("the kinds an operator lists").contains("dns_zone", "dns_peer",
            "backup", "snapshot", "stack", "database_engine", "database_dump",
            "template", "volume", "preview", "path_redirect", "host_account", "variable")
            .doesNotContain("app_template", "app_volume", "preview_deployment", "site_redirect", "system_user",
                // An app's deploys and builds are listed by its deploy list, both merged, newest first.
                "deploy", "build");
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
        assertThat(HohenheimMcpModule.declared()).as("the boot module built the tools").isNotNull();
        Set<Operation<?, ?, ?>> placed = new HashSet<>();
        for (McpHostTool tool : Objects.requireNonNull(HohenheimMcpModule.declared()).tools()) {
            placed.addAll(tool.operations());
        }
        assertThat(placed).as("the start, the restart, the deploy and the rollback are routes of the tools")
            .contains(InstanceOperations.START, InstanceOperations.RESTART, InstanceOperations.DEPLOY,
                InstanceOperations.ROLLBACK);
        assertThat(InstanceOperationHandlers.triggerOf(McpToolSurface.MCP_TOOL)).as("they deploy under the API trigger")
            .isEqualTo(DeployTrigger.API);
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
            .contains("read", "list", "describe", "site_pause", "site_resume", "power", "deploy", "run_read",
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
            .call("site_pause", Map.of("code", code));
        assertThat(refused.error()).as("step 4: refused: " + refused.text()).isTrue();
        assertThat(enabled(siteId)).as("step 4: still enabled").isTrue();

        // 5. The operator's key switches the site off through the same operation the panel places.
        McpHttpSession.Answer paused = session.call("site_pause", Map.of("code", code));
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
                .contains(workerName).contains("image (Container image): fake/worker").contains("tag (Image tag): 1.4")
                .contains("command (Container command): worker --queue mail").contains("container_port: 8080")
                .contains("(default)").contains("Environment variables: set").contains("status: ")
                .doesNotContain(SECRET);

            // 2. The operator's read of the application says its image, port and health check, its secret as set.
            McpHttpSession.Answer readShop = operatorSession.call("read", Map.of("ids", List.of(shopCode)));
            assertThat(readShop.error()).as("step 2: " + readShop.text()).isFalse();
            assertThat(readShop.text()).as("step 2: the application in full")
                .contains("image (Container image): fake/app")
                .contains("container_port: 8080").contains("health_path: /up").contains("Environment variables: set")
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
            assertThat(tools).as("step 4: his tools").contains("read", "list", "describe", "deploy", "roll_back",
                OperationRunOperations.READ.assistantName(), "power", "logs",
                InstanceLogOperations.LIST_DEPLOYS.assistantName(), "variable_set")
                .doesNotContain(InstanceVariableOperations.LIST_VARIABLES.assistantName());
            assertThat(tools).as("step 4: no tool is named after an instance")
                .noneMatch(tool -> tool.startsWith("instance_"));
            for (Operation<?, ?, ?> operatorOnly : List.of(InstanceOperations.INSTALL, InstanceOperations.REINSTALL,
                    InstanceOperations.CAPTURE_TEMPLATE, InstanceOperations.ATTACH_INSTALL_MEDIA,
                    InstanceOperations.DETACH_INSTALL_MEDIA, InstanceOperations.CONNECT_WEBHOOK,
                    InstanceTemplateOperations.APPROVE_TEMPLATE, InstanceTemplateOperations.UNAPPROVE_TEMPLATE)) {
                assertThat(operatorOnly.assistantName()).as("step 4: " + operatorOnly.id() + " is a tool").isNotNull();
                assertThat(tools).as("step 4: " + operatorOnly.id() + " is not offered to him")
                    .doesNotContain(operatorOnly.assistantName());
            }
            assertThat(session.call("delete", Map.of("code", appCode, "with_data", true)).error())
                .as("step 4: an operator's delete with data is refused him").isTrue();

            // 5. He starts his app, then restarts it: both deploy under the API trigger, said and stored.
            for (String action : List.of("start", "restart")) {
                McpHttpSession.Answer powered = session.call("power", Map.of("code", appCode, "action", action));
                assertThat(powered.error()).as("step 5: " + powered.text()).isFalse();
                assertThat(powered.text()).as("step 5: power " + action + " answers the trigger")
                    .contains("(" + DeployTrigger.API.word() + ")");
                assertThat(newestRelease(appId).get(ReleaseOperationModel.TRIGGER))
                    .as("step 5: power " + action + " recorded the API trigger")
                    .isEqualTo(DeployTrigger.API.word());
            }

            // 6. His app's log, the secret it printed masked.
            Row serving = Objects.requireNonNull(ApplicationReleases.ownedServing(appId), "step 6: a release serves");
            daemon.print(FakeDockerDaemon.handleOf(serving.get(InstanceModel.ID)),
                "booting\ntoken=" + SECRET + " ready\nlistening\n");
            McpHttpSession.Answer log = session.call("logs", Map.of("code", appCode, "lines", 50));
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
                Map.of("code", appCode));
            List<Integer> fewer = List.of(probe.queriesAgainst(ReleaseOperationModel.MODEL_ID),
                probe.queriesAgainst(BuildOperationModel.MODEL_ID));
            assertThat(deploys.error()).as("step 7: " + deploys.text()).isFalse();
            assertThat(deploys.text()).as("step 7: the deploys, by their codes").contains(failedCode)
                .contains(buildCode).doesNotContain(reason).doesNotContain(steps)
                .doesNotContain("exit 1 in /tmp/build-ws");
            failedBuild(appId, "npm ERR! again", "exit 2");
            failedBuild(appId, "npm ERR! and again", "exit 3");
            probe.reset();
            session.call(InstanceLogOperations.LIST_DEPLOYS.assistantName(), Map.of("code", appCode));
            assertThat(List.of(probe.queriesAgainst(ReleaseOperationModel.MODEL_ID),
                probe.queriesAgainst(BuildOperationModel.MODEL_ID)))
                .as("step 7: the list reads as much with two more builds").isEqualTo(fewer);
            McpHttpSession.Answer buildLog = session.call("logs", Map.of("code", buildCode));
            assertThat(buildLog.text()).as("step 7: his build's own output").contains("npm ERR! the build of the shop");
            McpHttpSession.Answer failed = session.call("read", Map.of("ids", List.of(failedCode, buildCode)));
            assertThat(failed.error()).as("step 7: " + failed.text()).isFalse();
            assertThat(failed.text()).as("step 7: the reads name the app and say that they failed")
                .contains("app: " + appCode).contains("failure: ").doesNotContain(reason).doesNotContain(steps)
                .doesNotContain("exit 1 in /tmp/build-ws").doesNotContain("sha256:");

            // 8. A plain variable set, listed and removed; the secret listed as set alone, and never replaced.
            McpHttpSession.Answer set = session.call("variable_set", Map.of("owner", appCode,
                "set", List.of(Map.of("key", "GREETING", "value", "hello"))));
            assertThat(set.error()).as("step 8: " + set.text()).isFalse();
            assertThat(variables(session, appCode)).as("step 8: the list by the app's code").contains("GREETING")
                .contains("hello").contains("API_TOKEN").doesNotContain(SECRET);
            McpHttpSession.Answer overSecret = session.call("variable_set", Map.of("owner", appCode,
                "set", List.of(Map.of("key", "API_TOKEN", "value", "plain"))));
            assertThat(overSecret.error()).as("step 8: a secret is never turned plain").isTrue();
            assertThat(new InstanceVariables().valuesFor(appId)).as("step 8: the secret stays")
                .containsEntry("API_TOKEN", SECRET);
            McpHttpSession.Answer unset = session.call("variable_set", Map.of("owner", appCode,
                "unset", List.of("GREETING")));
            assertThat(unset.error()).as("step 8: " + unset.text()).isFalse();
            assertThat(variables(session, appCode)).as("step 8: removed")
                .doesNotContain("GREETING");
            UUID viewerId = user(PREFIX + letters() + "@surface.test", "Mcp Viewer");
            RecordGrants.grant(GrantSubjectType.USER, viewerId, InstanceModel.MODEL_ID, appId,
                HohenheimCapabilities.VIEW, true);
            McpHttpSession viewer = presetSession(server, endpoint, viewerId, "Mcp Viewer");
            assertThat(variables(viewer, appCode)).as("step 8: a viewer lists")
                .contains("API_TOKEN").doesNotContain(SECRET);
            assertThat(viewer.call("variable_set", Map.of("owner", appCode,
                "set", List.of(Map.of("key", "GREETING", "value", "hello")))).error())
                .as("step 8: and is refused the set").isTrue();

            // 8b. The server makes a secret for a key holding none: nobody is sent it, and it never replaces a value.
            McpHttpSession.Answer generated = session.call("variable_set", Map.of("owner", appCode,
                "generate", List.of("SESSION_KEY")));
            assertThat(generated.error()).as("step 8b: " + generated.text()).isFalse();
            String made = new InstanceVariables().valuesFor(appId).get("SESSION_KEY");
            assertThat(made).as("step 8b: a value was made").isNotBlank();
            assertThat(generated.text()).as("step 8b: the answer names the key, never the value")
                .contains("SESSION_KEY").doesNotContain(made);
            assertThat(variables(session, appCode))
                .as("step 8b: listed as a set secret")
                .contains("SESSION_KEY").doesNotContain(made);
            assertThat(session.call("variable_set", Map.of("owner", appCode, "generate", List.of("API_TOKEN"))).error())
                .as("step 8b: a held value is never replaced").isTrue();
            assertThat(new InstanceVariables().valuesFor(appId)).as("step 8b: the secret stays")
                .containsEntry("API_TOKEN", SECRET).containsEntry("SESSION_KEY", made);
            assertThat(viewer.call("variable_set", Map.of("owner", appCode, "generate", List.of("VIEWER_KEY"))).error())
                .as("step 8b: a viewer is refused").isTrue();

            // 9. Another tenant's app answers as an app that does not exist.
            String missing = "AP999999";
            Map<String, Map<String, Object>> asked = new LinkedHashMap<>();
            asked.put("power", Map.of("action", "start"));
            asked.put("logs", Map.of());
            for (Map.Entry<String, Map<String, Object>> call : asked.entrySet()) {
                Map<String, Object> foreignArgs = new LinkedHashMap<>(call.getValue());
                foreignArgs.put("code", otherCode);
                Map<String, Object> noneArgs = new LinkedHashMap<>(call.getValue());
                noneArgs.put("code", missing);
                McpHttpSession.Answer foreign = session.call(call.getKey(), foreignArgs);
                McpHttpSession.Answer none = session.call(call.getKey(), noneArgs);
                assertThat(foreign.error()).as("step 9: " + call.getKey() + " refused").isTrue();
                assertThat(foreign.text().replace(otherCode, "<code>"))
                    .as("step 9: " + call.getKey() + " says what it says of none")
                    .isEqualTo(none.text().replace(missing, "<code>"));
            }
            McpHttpSession.Answer foreignDeploys = session.call(InstanceLogOperations.LIST_DEPLOYS.assistantName(),
                Map.of("code", otherCode));
            McpHttpSession.Answer noDeploys = session.call(InstanceLogOperations.LIST_DEPLOYS.assistantName(),
                Map.of("code", missing));
            McpHttpSession.Answer foreignVariables = session.call("list", Map.of("kind", "variable", "of", otherCode));
            McpHttpSession.Answer noVariables = session.call("list", Map.of("kind", "variable", "of", missing));
            for (McpHttpSession.Answer[] pair : List.of(new McpHttpSession.Answer[] {foreignDeploys, noDeploys},
                    new McpHttpSession.Answer[] {foreignVariables, noVariables})) {
                assertThat(pair[0].error()).as("step 9: refused: " + pair[0].text()).isTrue();
                assertThat(pair[0].text().replace(otherCode, "<code>"))
                    .as("step 9: says what it says of none")
                    .isEqualTo(pair[1].text().replace(missing, "<code>"));
            }

            // 10. A backup and a snapshot are refused, without naming what he lacks.
            for (Map.Entry<String, String> withheld : Map.of("back_up", "code", "snapshot_set", "code").entrySet()) {
                McpHttpSession.Answer refused = session.call(withheld.getKey(), Map.of(withheld.getValue(), appCode));
                assertThat(refused.error()).as("step 10: " + withheld.getKey() + " refused").isTrue();
                assertThat(refused.text()).as("step 10: without naming the capability")
                    .doesNotContain(HohenheimCapabilities.BACKUPS).doesNotContain(HohenheimCapabilities.SNAPSHOTS);
            }

            // 11. The operator reads the failed deploy's step log, which no customer is offered.
            McpHttpSession operatorSession = presetSession(server, endpoint,
                TenantConduits.operatorUser().get(UserModel.ID), "Test Admin");
            McpHttpSession.Answer stepLog = operatorSession.call("logs", Map.of("code", failedCode));
            assertThat(stepLog.error()).as("step 11: " + stepLog.text()).isFalse();
            assertThat(stepLog.text()).as("step 11: the step log").contains(steps);
            List<String> operatorTools = operatorSession.toolNames();
            System.out.println("MCP tools of an operator (" + operatorTools.size() + "): "
                + String.join(", ", operatorTools));
            assertThat(operatorTools).as("step 11: the operator's tools hold the runs and no instance name")
                .contains("deploy", "roll_back", OperationRunOperations.READ.assistantName())
                .noneMatch(tool -> tool.startsWith("instance_"));

            // 12. He deploys his app's next version by its tool: the call answers its run's code at once, he follows
            //     the run to its end, and his deploy list holds the new deploy, recorded under the API trigger.
            FakeApps.setSettings(appId, "v2");
            settle("step 12");
            String deployRun = startedRun(session, "deploy", appCode, "step 12");
            assertThat(followed(session, deployRun, "step 12").text()).as("step 12: the run ended well")
                .contains("status: " + OperationRunStatus.SUCCEEDED.storageKey()).contains("subject: " + appCode);
            Row deployed = newestRelease(appId);
            assertThat(deployed.get(ReleaseOperationModel.TRIGGER)).as("step 12: recorded under the API trigger")
                .isEqualTo(DeployTrigger.API.word());
            String deployedCode = code(ReleaseOperationModel.MODEL_ID, deployed.get(ReleaseOperationModel.ID),
                operator);
            assertThat(session.call(InstanceLogOperations.LIST_DEPLOYS.assistantName(), Map.of("code", appCode))
                .text())
                .as("step 12: his deploy list holds the new deploy").contains(deployedCode);

            // 13. He rolls it back the same way: a run he follows to its end, a rollback in his deploy list.
            settle("step 13");
            String rollbackRun = startedRun(session, "roll_back", appCode, "step 13");
            assertThat(followed(session, rollbackRun, "step 13").text()).as("step 13: the run ended well")
                .contains("status: " + OperationRunStatus.SUCCEEDED.storageKey()).contains("subject: " + appCode);
            Row rolledBack = newestRelease(appId);
            assertThat(rolledBack.get(ReleaseOperationModel.KIND)).as("step 13: a rollback")
                .isEqualTo(ReleaseOperationModel.KIND_ROLLBACK);
            assertThat(rolledBack.get(ReleaseOperationModel.TRIGGER)).as("step 13: recorded under the API trigger")
                .isEqualTo(DeployTrigger.API.word());
            String rolledBackCode = code(ReleaseOperationModel.MODEL_ID, rolledBack.get(ReleaseOperationModel.ID),
                operator);
            assertThat(session.call(InstanceLogOperations.LIST_DEPLOYS.assistantName(), Map.of("code", appCode))
                .text())
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
     * Roel's job by tools alone, as an administrator's key: describe what an app takes, create a Docker container from
     * an image with a command and no port, set six variables (three secret) in one call, read it, change its command,
     * merge its volumes and clear a setting; an application deployed and followed by its run, its log read, stopped
     * and started, whose next version's failing pre-deploy command does not take over from the release serving;
     * delete with its data; and the same create refused to a person who is no administrator, in the words the spec
     * quotes.
     *
     * AIDEV-NOTE: the runs are the application's: a Docker container deploys on its own private network, whose
     * kernel policy the fake daemon does not model (WorkloadNetworkPolicy refuses it with nftables off), while a
     * release runs on the shared bridge the fake serves.
     */
    @Test
    void anAdministratorRunsAnAppByToolsAlone() throws Exception {
        URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
        McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
        Pattern appCodes = Pattern.compile("AP\\d+");
        // The panel's own create places the app: the local host needs a fresh memory reading to be chosen, and a
        // data root that can hold a quota for the application of step 6 (a kind that mounts volumes).
        HostFixtures.LocalHostState local = HostFixtures.captureLocal();
        try (FakeApps apps = FakeApps.install((SqlDatasource) Models.get(InstanceModel.class)
                .getResolvedDatasource())) {
            FakeDockerDaemon daemon = apps.daemon();
            // The application's log is read from what it printed; it asks no console (crash policy none).
            daemon.keepConsoleLogs();
            HostFixtures.makeLocalPlaceable(16L * 1024);
            HostFixtures.giveLocalVolumeQuota();
            McpHttpSession admin = presetSession(server, endpoint, TenantConduits.operatorUser().get(UserModel.ID),
                "Test Admin");

            // 1. describe kind=app names the tool that creates one and what creating needs.
            McpHttpSession.Answer kind = admin.call("describe", Map.of("kind", "app"));
            assertThat(kind.error()).as("step 1: " + kind.text()).isFalse();
            assertThat(kind.text()).as("step 1: the create tools and the calls on one")
                .contains("app_docker_container_set").contains("name").contains("power code=AP12 action=stop");

            // 2. A Docker container app from an image, with a command and no port; nothing is deployed.
            String name = PREFIX + "worker-" + letters();
            Map<String, Object> create = Map.of("name", name, "image", "fake/app", "tag", "v1", "command",
                "worker --queue mail", "volumes", List.of(Map.of("name", "data", "container_path", "/data")),
                "request", UUID.randomUUID().toString());
            McpHttpSession.Answer created = admin.call("app_docker_container_set", create);
            assertThat(created.error()).as("step 2: " + created.text()).isFalse();
            Matcher made = appCodes.matcher(created.text());
            assertThat(made.find()).as("step 2: the answer names the app: " + created.text()).isTrue();
            String code = made.group();
            int appId = Integer.parseInt(Objects.requireNonNull(RecordCodes.keyOfCode(InstanceModel.MODEL_ID, code)));
            assertThat(Models.get(InstanceModel.class).findById(appId).get(InstanceModel.STATUS))
                .as("step 2: created, not deployed").isEqualTo(InstanceModel.STATUS_CREATED);
            assertThat(admin.call("app_docker_container_set", create).text())
                .as("step 2: the same request answers the first result, creating nothing").contains(code);
            assertThat(Models.get(InstanceModel.class).find().where(InstanceModel.NAME.eq(name)).count())
                .as("step 2: one app").isEqualTo(1L);
            McpHttpSession.Answer described = admin.call("describe", Map.of("code", code));
            assertThat(described.text()).as("step 2: describe names the change tool of its own kind only")
                .contains("app_docker_container_set code=" + code).doesNotContain("app_static_site_set")
                .doesNotContain("app_application_set");

            // 3. Six variables in one call, three of them secret; the answer names keys, never a value.
            List<String> secrets = List.of("s3cret-one-" + letters(), "s3cret-two-" + letters(),
                "s3cret-three-" + letters());
            McpHttpSession.Answer variables = admin.call("variable_set", Map.of("owner", code,
                "set", List.of(Map.of("key", "MODE", "value", "worker"), Map.of("key", "QUEUE", "value", "mail"),
                    Map.of("key", "REGION", "value", "eu")),
                "secret", List.of(Map.of("key", "API_TOKEN", "value", secrets.get(0)),
                    Map.of("key", "DB_PASSWORD", "value", secrets.get(1)),
                    Map.of("key", "SMTP_PASSWORD", "value", secrets.get(2)))));
            assertThat(variables.error()).as("step 3: " + variables.text()).isFalse();
            assertThat(variables.text()).as("step 3: the keys, never a value").contains("SMTP_PASSWORD")
                .doesNotContain(secrets.get(0)).doesNotContain(secrets.get(1)).doesNotContain(secrets.get(2));
            assertThat(new InstanceVariables().valuesFor(appId)).as("step 3: all six stored")
                .containsEntry("MODE", "worker").containsEntry("API_TOKEN", secrets.get(0)).hasSize(6);

            // 4. Its read shows every setting, its variables plain and the secrets as set.
            McpHttpSession.Answer read = admin.call("read", Map.of("ids", List.of(code)));
            assertThat(read.error()).as("step 4: " + read.text()).isFalse();
            assertThat(read.text()).as("step 4: the settings").contains("image (Container image): fake/app")
                .contains("command (Container command): worker --queue mail");
            String listed = variables(admin, code);
            assertThat(listed).as("step 4: the variables, plain ones with their values").contains("MODE")
                .contains("worker").contains("API_TOKEN").contains("SMTP_PASSWORD");
            for (String secret : secrets) {
                assertThat(read.text() + listed).as("step 4: no secret comes out").doesNotContain(secret);
            }

            // 5. Its command changes alone; the rest stays as it was.
            McpHttpSession.Answer changed = admin.call("app_docker_container_set", Map.of("code", code,
                "command", "worker --queue jobs", "console_mode", "plain", "protocol", "tcp"));
            assertThat(changed.error()).as("step 5: " + changed.text()).isFalse();
            assertThat(changed.text()).as("step 5: what changed").contains("command");
            assertThat(admin.call("read", Map.of("ids", List.of(code))).text()).as("step 5: the new command")
                .contains("command (Container command): worker --queue jobs")
                .contains("image (Container image): fake/app");
            assertThat(admin.call("app_docker_container_set", Map.of("code", code, "host", "SR1")).text())
                .as("step 5: a create-only argument is refused on a change").contains("host")
                .contains("creating");
            // A map setting merges by its entries: one volume added by name, another removed.
            assertThat(admin.call("app_docker_container_set", Map.of("code", code, "volumes", List.of(
                    Map.of("name", "cache", "container_path", "/cache"), Map.of("name", "data", "remove", true))))
                .error()).as("step 5: the volumes changed").isFalse();
            assertThat(RawValues.map(RawValues.map(Models.get(InstanceModel.class).findById(appId)
                    .get(InstanceModel.SETTINGS)).get("volumes")))
                .as("step 5: merged by name").containsExactly(Map.entry("cache", "/cache"));
            assertThat(admin.call("app_static_site_set", Map.of("code", code, "name", name)).text())
                .as("step 5: a code of another kind names its own tool").contains("app_docker_container_set")
                .contains("change_kind");
            // A setting the form lets be empty is cleared by null; an argument that is not "or null" refuses null.
            Map<String, Object> cleared = new LinkedHashMap<>();
            cleared.put("code", code);
            cleared.put("command", null);
            McpHttpSession.Answer clearing = admin.call("app_docker_container_set", cleared);
            assertThat(clearing.error()).as("step 5: null clears " + clearing.text()).isFalse();
            assertThat(RawValues.map(Models.get(InstanceModel.class).findById(appId).get(InstanceModel.SETTINGS))
                .get("command")).as("step 5: the command is cleared").isNull();
            Map<String, Object> unasked = new LinkedHashMap<>();
            unasked.put("code", code);
            unasked.put("request", null);
            McpHttpSession.Answer refusedNull = admin.call("app_docker_container_set", unasked);
            assertThat(refusedNull.error()).as("step 5: null refused " + refusedNull.text()).isTrue();
            assertThat(refusedNull.text()).as("step 5: in one sentence")
                .contains("request takes no null; leave it out instead.");

            // 6. An application, created, deployed and followed by its run; its log; power stop, then start.
            McpHttpSession.Answer shop = admin.call("app_application_set", Map.of("name", PREFIX + "shop-"
                + letters(), "image", "fake/app", "tag", "v1", "container_port", 8080,
                "pre_deploy_command", "migrate", "crash_policy", InstanceModel.CRASH_NONE));
            assertThat(shop.error()).as("step 6: " + shop.text()).isFalse();
            Matcher shopMade = appCodes.matcher(shop.text());
            assertThat(shopMade.find()).as("step 6: the shop's code").isTrue();
            String shopCode = shopMade.group();
            int shopId = Integer.parseInt(Objects.requireNonNull(RecordCodes.keyOfCode(InstanceModel.MODEL_ID,
                shopCode)));
            settle("step 6");
            daemon.answerNextOneShot(0, "migrated\n");
            assertThat(followed(admin, startedRun(admin, "deploy", shopCode, "step 6"), "step 6").text())
                .as("step 6: the first release serves").contains("status: " + OperationRunStatus.SUCCEEDED
                    .storageKey());
            Row first = Objects.requireNonNull(ApplicationReleases.ownedServing(shopId), "step 6: one serves");
            // Printed behind a terminal: its escape sequences never reach the answer, which is text.
            daemon.print(FakeDockerDaemon.handleOf(first.get(InstanceModel.ID)),
                "\u001b[?1049h\u001b[?1h\u001b=\u001b[32mlistening on 8080\u001b[0m\n");
            McpHttpSession.Answer log = admin.call("logs", Map.of("code", shopCode));
            assertThat(log.error()).as("step 6: " + log.text()).isFalse();
            assertThat(log.text()).as("step 6: the log it printed, as plain words").contains("listening on 8080")
                .doesNotContain("\u001b").doesNotContain("[?1049h");
            settle("step 6");
            for (String action : List.of("stop", "start")) {
                McpHttpSession.Answer powered = admin.call("power", Map.of("code", shopCode, "action", action));
                assertThat(powered.error()).as("step 6: power " + action + ": " + powered.text()).isFalse();
            }
            settle("step 6");

            // 7. Its next version's pre-deploy command fails: it does not take over from the release serving.
            Row serving = Objects.requireNonNull(ApplicationReleases.ownedServing(shopId), "step 7: one serves");
            assertThat(admin.call("app_application_set", Map.of("code", shopCode, "tag", "v2")).error())
                .as("step 7: the next version").isFalse();
            daemon.answerNextOneShot(1, "column legacy_token does not exist\n");
            String failing = startedRun(admin, "deploy", shopCode, "step 7");
            assertThat(followed(admin, failing, "step 7").text()).as("step 7: the deploy failed")
                .doesNotContain("status: " + OperationRunStatus.SUCCEEDED.storageKey());
            assertThat(Objects.requireNonNull(ApplicationReleases.ownedServing(shopId)).get(InstanceModel.ID))
                .as("step 7: the old release still serves").isEqualTo(serving.get(InstanceModel.ID));
            settle("step 7");

            // 8. delete with its data.
            for (String deleted : List.of(code, shopCode)) {
                McpHttpSession.Answer gone = admin.call("delete", Map.of("code", deleted, "with_data", true));
                assertThat(gone.error()).as("step 8: " + gone.text()).isFalse();
            }
            assertThat(admin.call("delete", Map.of("code", code, "force", true)).text())
                .as("step 8: force is not an app's").contains("force");

            // 9. The same create, for a person who is no administrator, is refused in the spec's words.
            UUID customerId = user(PREFIX + letters() + "@surface.test", "Mcp Creator");
            McpHttpSession customer = presetSession(server, endpoint, customerId, "Mcp Creator");
            McpHttpSession.Answer refused = customer.call("app_docker_container_set", Map.of("name", name,
                "image", "fake/app"));
            assertThat(refused.error()).as("step 9: refused").isTrue();
            assertThat(refused.text()).as("step 9: in the spec's words")
                .contains("Only an administrator creates an app without a template");
            assertThat(customer.call("app_docker_container_set", Map.of("image", "fake/app")).text())
                .as("step 9: a create without its name").contains("To create a Docker container app, give name.");
        } finally {
            local.restore();
        }
    }

    /**
     * What a create asks is what it is judged by, a refusal names the argument that was sent, and a change writes only
     * what it was sent: a create's memory limit decides whether its host has room, the refusal names {@code host}, a
     * site setting set and then cleared leaves its settings as empty as they were, its defaults still in force, and a
     * stored key the app's kind does not declare survives a change of another setting.
     */
    @Test
    void aCreateIsJudgedByWhatItAsksAndAChangeWritesOnlyWhatItSends() throws Exception {
        URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
        McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
        Pattern appCodes = Pattern.compile("AP\\d+");
        int hostId = HostFixtures.admittedDockerHost(PREFIX + "room-" + letters(), 4096);
        // An application mounts volumes: its host needs a data root that can hold a quota.
        VolumeBackends.store(Models.get(HostModel.class).findById(hostId),
            new VolumeBackends.Detection(VolumeBackend.BTRFS, "/fixture/volumes", "fixture: btrfs"));
        long filled = Objects.requireNonNull(InstanceCapacity.budgetMbOf(Models.get(HostModel.class)
            .findById(hostId))) - 200;
        InstanceCapacity.reserve(hostId, filled);
        Integer appId = null;
        try (FakeApps ignored = FakeApps.install((SqlDatasource) Models.get(InstanceModel.class)
                .getResolvedDatasource())) {
            McpHttpSession admin = presetSession(server, endpoint, TenantConduits.operatorUser().get(UserModel.ID),
                "Test Admin");
            String host = code(HostModel.MODEL_ID, hostId, TenantConduits.operator());

            // 1. A host with 200 MB free: a create asking 256 MB is refused for 256 MB, naming the host argument and
            //    never a stored column, a server or a workload.
            for (String tool : List.of("app_docker_container_set", "app_application_set")) {
                McpHttpSession.Answer refused = admin.call(tool, Map.of("name", PREFIX + "big-" + letters(),
                    "host", host, "image", "fake/app", "tag", "v1", "container_port", 80, "memory_limit_mb", 256));
                assertThat(refused.error()).as("step 1: " + tool + " refused: " + refused.text()).isTrue();
                assertThat(refused.text()).as("step 1: " + tool + " names the argument and what it asked")
                    .contains("host").contains("256").doesNotContain("server_id").doesNotContain("workload")
                    .doesNotContain("server");
            }

            // 2. The same create asking 128 MB fits: the limit it sends decides, never its kind's default.
            McpHttpSession.Answer created = admin.call("app_docker_container_set", Map.of("name", PREFIX + "small-"
                + letters(), "host", host, "image", "fake/app", "tag", "v1", "container_port", 80,
                "memory_limit_mb", 128));
            assertThat(created.error()).as("step 2: " + created.text()).isFalse();
            Matcher made = appCodes.matcher(created.text());
            assertThat(made.find()).as("step 2: the answer names the app: " + created.text()).isTrue();
            String app = made.group();
            appId = Integer.parseInt(Objects.requireNonNull(RecordCodes.keyOfCode(InstanceModel.MODEL_ID, app)));
            assertThat(InstanceCapacity.bookedMbOn(hostId)).as("step 2: booked at what it asked")
                .isEqualTo(filled + 128);

            // 3. A site of the app kind whose settings are empty: one setting set is the one key stored.
            String siteName = PREFIX + "walk-" + letters();
            Row siteRow = Models.get(SiteModel.class).createEmptyRow();
            siteRow.set(SiteModel.NAME, siteName);
            siteRow.set(SiteModel.SLUG, siteName);
            siteRow.set(SiteModel.UPSTREAM_KIND, InstanceUpstreamKind.ID.toString());
            siteRow.set(SiteModel.INSTANCE_ID, appId);
            siteRow.set(SiteModel.ENABLED, true);
            siteRow.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
            siteRow.set(SiteModel.SETTINGS, new LinkedHashMap<>());
            Models.get(SiteModel.class).save(siteRow);
            int siteId = siteRow.get(SiteModel.ID);
            String site = code(SiteModel.MODEL_ID, siteId, TenantConduits.operator());
            McpHttpSession.Answer set = admin.call("site_app_set", Map.of("code", site, "idle_timeout", 900));
            assertThat(set.error()).as("step 3: " + set.text()).isFalse();
            assertThat(siteSettings(siteId)).as("step 3: only the setting sent is stored")
                .containsExactly(Map.entry("idle_timeout", 900));

            // 4. Cleared with null, its key goes: the settings are empty again and the defaults still apply.
            Map<String, Object> cleared = new LinkedHashMap<>();
            cleared.put("code", site);
            cleared.put("idle_timeout", null);
            McpHttpSession.Answer clearing = admin.call("site_app_set", cleared);
            assertThat(clearing.error()).as("step 4: " + clearing.text()).isFalse();
            Map<String, Object> settings = siteSettings(siteId);
            assertThat(settings).as("step 4: nothing is stored, not even a null").isEmpty();
            assertThat(RawValues.isOn(settings, InstanceUpstreamKind.WEBSOCKET_UPGRADE))
                .as("step 4: the proxy still allows WebSockets by default").isTrue();
            Map<String, Object> nulled = new LinkedHashMap<>();
            nulled.put(InstanceUpstreamKind.WEBSOCKET_UPGRADE.getName(), null);
            assertThat(RawValues.isOn(nulled, InstanceUpstreamKind.WEBSOCKET_UPGRADE))
                .as("step 4: a stored null still reads as the default at the proxy").isTrue();
            assertThat(InstanceUpstreamKind.schemeOf(settings)).as("step 4: and forwards over http").isEqualTo("http");

            // 5. An app's setting set and then cleared with null leaves no key behind either.
            assertThat(admin.call("app_docker_container_set", Map.of("code", app, "command", "serve --quiet"))
                .error()).as("step 5: set").isFalse();
            Map<String, Object> clearedCommand = new LinkedHashMap<>();
            clearedCommand.put("code", app);
            clearedCommand.put("command", null);
            McpHttpSession.Answer clearedApp = admin.call("app_docker_container_set", clearedCommand);
            assertThat(clearedApp.error()).as("step 5: cleared: " + clearedApp.text()).isFalse();
            assertThat(RawValues.map(Models.get(InstanceModel.class).find().noCache()
                    .where(InstanceModel.ID.eq(appId)).first().get(InstanceModel.SETTINGS)))
                .as("step 5: the cleared command is no key at all").doesNotContainKey("command")
                .containsEntry("memory_limit_mb", 128);

            // 6. A stored key the kind does not declare survives a change of another setting: only a kind change
            //    leaves behind what the new kind lacks.
            Row stored = Models.get(InstanceModel.class).find().noCache().where(InstanceModel.ID.eq(appId)).first();
            Map<String, Object> withExtra = new LinkedHashMap<>(RawValues.map(stored.get(InstanceModel.SETTINGS)));
            withExtra.put("undeclared_note", "kept");
            stored.set(InstanceModel.SETTINGS, withExtra);
            Models.get(InstanceModel.class).save(stored);
            McpHttpSession.Answer changedTag = admin.call("app_docker_container_set", Map.of("code", app, "tag", "v2"));
            assertThat(changedTag.error()).as("step 6: " + changedTag.text()).isFalse();
            assertThat(RawValues.map(Models.get(InstanceModel.class).find().noCache()
                    .where(InstanceModel.ID.eq(appId)).first().get(InstanceModel.SETTINGS)))
                .as("step 6: the undeclared key is still stored beside the change")
                .containsEntry("undeclared_note", "kept").containsEntry("tag", "v2")
                .containsEntry("memory_limit_mb", 128);
        } finally {
            if (appId != null) {
                for (Row site : Models.get(SiteModel.class).find().withTrashed()
                        .where(SiteModel.INSTANCE_ID.eq(appId)).all()) {
                    HardDeletes.byId(Models.get(SiteModel.class), site.get(SiteModel.ID));
                }
                HardDeletes.byId(Models.get(InstanceModel.class), appId);
            }
            InstanceCapacity.release(hostId, filled);
            HardDeletes.byId(Models.get(HostModel.class), hostId);
        }
    }

    /**
     * A worker that runs another app's image, by tools alone: a Docker container created with image_app and no image
     * of its own reads back the app it follows, is moved to another app, and is cleared again.
     */
    @Test
    void aContainerRunsTheImageOfAnotherApp() throws Exception {
        URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
        McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
        HostFixtures.LocalHostState local = HostFixtures.captureLocal();
        List<Integer> made = new ArrayList<>();
        try (FakeApps ignored = FakeApps.install((SqlDatasource) Models.get(InstanceModel.class)
                .getResolvedDatasource())) {
            HostFixtures.makeLocalPlaceable(16L * 1024);
            McpHttpSession admin = presetSession(server, endpoint, TenantConduits.operatorUser().get(UserModel.ID),
                "Test Admin");
            AccessContext operator = TenantConduits.operator();
            int api = application(PREFIX + "api-" + letters());
            int other = application(PREFIX + "api-" + letters());
            made.add(api);
            made.add(other);
            String apiCode = code(InstanceModel.MODEL_ID, api, operator);
            String otherCode = code(InstanceModel.MODEL_ID, other, operator);
            String host = code(HostModel.MODEL_ID, Models.get(InstanceModel.class).findById(api)
                .get(InstanceModel.HOST_ID), operator);

            // 1. A worker created with image_app and no image: it stores the app it follows and no image of its own.
            McpHttpSession.Answer created = admin.call("app_docker_container_set", Map.of("name", PREFIX + "worker-"
                + letters(), "host", host, "image_app", apiCode, "command", "worker"));
            assertThat(created.error()).as("step 1: " + created.text()).isFalse();
            String worker = found(Pattern.compile("AP\\d+"), created.text(), "step 1");
            int workerId = Integer.parseInt(Objects.requireNonNull(RecordCodes.keyOfCode(InstanceModel.MODEL_ID,
                worker)));
            made.add(0, workerId);
            assertThat((Integer) Models.get(InstanceModel.class).findById(workerId).get(InstanceModel.IMAGE_APP_ID))
                .as("step 1: it follows the app").isEqualTo(api);
            assertThat(RawValues.map(Models.get(InstanceModel.class).findById(workerId).get(InstanceModel.SETTINGS))
                .get("image")).as("step 1: no image of its own").isNull();
            assertThat(admin.call("read", Map.of("ids", List.of(worker))).text())
                .as("step 1: its read names the app by the argument that set it").contains("image_app (")
                .contains(apiCode);

            // 2. Changed to another app.
            McpHttpSession.Answer moved = admin.call("app_docker_container_set", Map.of("code", worker,
                "image_app", otherCode));
            assertThat(moved.error()).as("step 2: " + moved.text()).isFalse();
            assertThat(moved.text()).as("step 2: what changed").contains("image_app");
            assertThat((Integer) Models.get(InstanceModel.class).find().noCache().where(InstanceModel.ID.eq(workerId))
                .first().get(InstanceModel.IMAGE_APP_ID)).as("step 2: it follows the other app").isEqualTo(other);

            // 3. Cleared with null.
            Map<String, Object> cleared = new LinkedHashMap<>();
            cleared.put("code", worker);
            cleared.put("image_app", null);
            McpHttpSession.Answer clearing = admin.call("app_docker_container_set", cleared);
            assertThat(clearing.error()).as("step 3: " + clearing.text()).isFalse();
            assertThat((Object) Models.get(InstanceModel.class).find().noCache().where(InstanceModel.ID.eq(workerId))
                .first().get(InstanceModel.IMAGE_APP_ID)).as("step 3: it follows nothing").isNull();
            // An Application takes no image_app: the argument is a Docker container's alone.
            assertThat(admin.call("app_application_set", Map.of("code", apiCode, "image_app", otherCode)).error())
                .as("step 3: an Application refuses image_app").isTrue();
        } finally {
            for (int id : made) HardDeletes.byId(Models.get(InstanceModel.class), id);
            local.restore();
        }
    }

    /**
     * A git connection by tools alone: an SSH connection created without a key answers the public key of the pair the
     * server made, its read shows it and never the private key, a new pair replaces it, an argument of another kind is
     * refused, an app is created on it, and a token connection keeps its token write-only.
     */
    @Test
    void aGitConnectionMakesItsOwnKeyAndAnAppBuildsFromIt() throws Exception {
        URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
        McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
        Pattern connections = Pattern.compile("G\\d+");
        Pattern publicKey = Pattern.compile("public_key: (ssh-ed25519 [A-Za-z0-9+/=]+)");
        HostFixtures.LocalHostState local = HostFixtures.captureLocal();
        List<Integer> providers = new ArrayList<>();
        Integer appId = null;
        try (FakeApps ignored = FakeApps.install((SqlDatasource) Models.get(InstanceModel.class)
                .getResolvedDatasource())) {
            HostFixtures.makeLocalPlaceable(16L * 1024);
            HostFixtures.giveLocalVolumeQuota();
            McpHttpSession admin = presetSession(server, endpoint, TenantConduits.operatorUser().get(UserModel.ID),
                "Test Admin");

            // 1. An SSH connection without a key: the answer names its code and the public key of a new pair.
            String repository = "git@git.example.test:acme/site.git";
            McpHttpSession.Answer created = admin.call("git_connection_set", Map.of("name", PREFIX + "deploy-key-"
                + letters(), "kind", "ssh", "repository", repository, "request", UUID.randomUUID().toString()));
            assertThat(created.error()).as("step 1: " + created.text()).isFalse();
            String connection = found(connections, created.text(), "step 1");
            int connectionId = Integer.parseInt(Objects.requireNonNull(RecordCodes.keyOfCode(GitProviderModel.MODEL_ID,
                connection)));
            providers.add(connectionId);
            String key = found(publicKey, created.text(), "step 1");
            Row stored = Models.get(GitProviderModel.class).findById(connectionId);
            assertThat((String) stored.get(GitProviderModel.SSH_PRIVATE_KEY)).as("step 1: the private key is stored")
                .startsWith("-----BEGIN OPENSSH PRIVATE KEY-----");
            assertThat(created.text()).as("step 1: never the private key").doesNotContain("PRIVATE KEY");

            // 2. Its read shows the same public key, the private key only as set.
            McpHttpSession.Answer read = admin.call("read", Map.of("ids", List.of(connection)));
            assertThat(read.error()).as("step 2: " + read.text()).isFalse();
            assertThat(read.text()).as("step 2: the public key").contains(key.substring("public_key: ".length()))
                .doesNotContain("PRIVATE KEY").contains(repository);

            // 3. regenerate_key makes a new pair; an argument only a token connection reads is refused.
            McpHttpSession.Answer regenerated = admin.call("git_connection_set", Map.of("code", connection,
                "regenerate_key", true));
            assertThat(regenerated.error()).as("step 3: " + regenerated.text()).isFalse();
            assertThat(found(publicKey, regenerated.text(), "step 3")).as("step 3: a new public key")
                .isNotEqualTo(key);
            McpHttpSession.Answer unread = admin.call("git_connection_set", Map.of("code", connection,
                "access_token", "ghp-not-read"));
            assertThat(unread.error()).as("step 3: refused").isTrue();
            assertThat(unread.text()).as("step 3: in one sentence").contains("access_token").contains("apply to");

            // 4. An app created on the connection and a repository builds from it.
            McpHttpSession.Answer app = admin.call("app_application_set", Map.of("name", PREFIX + "site-" + letters(),
                "git_connection", connection, "repository", repository, "container_port", 8080,
                "crash_policy", InstanceModel.CRASH_NONE));
            assertThat(app.error()).as("step 4: " + app.text()).isFalse();
            String appCode = found(Pattern.compile("AP\\d+"), app.text(), "step 4");
            appId = Integer.parseInt(Objects.requireNonNull(RecordCodes.keyOfCode(InstanceModel.MODEL_ID, appCode)));
            Map<String, Object> settings = RawValues.map(Models.get(InstanceModel.class).findById(appId)
                .get(InstanceModel.SETTINGS));
            assertThat(String.valueOf(settings.get("provider_id"))).as("step 4: the app uses the connection")
                .isEqualTo(String.valueOf(connectionId));
            assertThat(settings.get("repository")).as("step 4: and its repository").isEqualTo(repository);
            assertThat(admin.call("read", Map.of("ids", List.of(appCode))).text()).as("step 4: read back by its code")
                .contains("git_connection: " + connection);

            // 5. A token connection: its token goes in and never comes out.
            String token = "glpat-" + letters() + letters();
            McpHttpSession.Answer tokened = admin.call("git_connection_set", Map.of("name", PREFIX + "gitlab-"
                + letters(), "kind", "gitlab", "access_token", token));
            assertThat(tokened.error()).as("step 5: " + tokened.text()).isFalse();
            String tokenConnection = found(connections, tokened.text(), "step 5");
            providers.add(Integer.parseInt(Objects.requireNonNull(RecordCodes.keyOfCode(GitProviderModel.MODEL_ID,
                tokenConnection))));
            assertThat((String) Models.get(GitProviderModel.class).findById(providers.get(1))
                .get(GitProviderModel.ACCESS_TOKEN)).as("step 5: the token is stored").isEqualTo(token);
            assertThat(tokened.text() + admin.call("read", Map.of("ids", List.of(tokenConnection))).text())
                .as("step 5: never shown").doesNotContain(token).doesNotContain("public_key");
        } finally {
            if (appId != null) HardDeletes.byId(Models.get(InstanceModel.class), appId);
            for (int id : providers) HardDeletes.byId(Models.get(GitProviderModel.class), id);
            local.restore();
        }
    }

    /** @return the site's stored settings, read past the cache */
    private static Map<String, Object> siteSettings(int siteId) {
        return RawValues.map(Models.get(SiteModel.class).find().noCache().where(SiteModel.ID.eq(siteId)).first()
            .get(SiteModel.SETTINGS));
    }

    /**
     * Roel's job, second half, by tools alone as an administrator's key: an application put on a site, an address
     * added to it, a certificate ordered for that address from the test's certificate authority, an A and a TXT
     * record set in one call, listed, changed and removed, a record Hohenheim made refused, the site and the address
     * read in full, the site described, deleted to the trash; then a site serving files.
     */
    @Test
    void anAdministratorSetsUpASiteByToolsAlone() throws Exception {
        URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
        McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
        AccessContext operator = TenantConduits.operator();
        HostFixtures.LocalHostState local = HostFixtures.captureLocal();
        ProxyServer adopted = ServerMain.getProxyServer() == null ? new ProxyServer() : null;
        if (adopted != null) ServerMain.adoptProxyServer(adopted);
        String savedDirectory = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Ssl.ACME_DIRECTORY_URL);
        String savedEmail = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Ssl.LETSENCRYPT_EMAIL);
        Integer zoneId = null;
        try (FakeApps apps = FakeApps.install((SqlDatasource) Models.get(InstanceModel.class)
                .getResolvedDatasource()); FakeAcmeServer ca = new FakeAcmeServer()) {
            HostFixtures.makeLocalPlaceable(16L * 1024);
            HostFixtures.giveLocalVolumeQuota();
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED, true);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.ACME_DIRECTORY_URL, ca.directoryUrl());
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_EMAIL, "acme-test@example.com");
            McpHttpSession admin = presetSession(server, endpoint, TenantConduits.operatorUser().get(UserModel.ID),
                "Test Admin");

            // 1. An application (landing 1's tool), then a site serving it, without an address yet.
            McpHttpSession.Answer made = admin.call("app_application_set", Map.of("name", PREFIX + "web-"
                + letters(), "image", "fake/app", "tag", "v1", "container_port", 8080));
            assertThat(made.error()).as("step 1: " + made.text()).isFalse();
            String app = found(Pattern.compile("AP\\d+"), made.text(), "step 1: the app's code");
            String siteName = PREFIX + "shop-" + letters();
            McpHttpSession.Answer site = admin.call("site_app_set", Map.of("name", siteName, "app", app,
                "request_timeout", 120, "request", UUID.randomUUID().toString()));
            assertThat(site.error()).as("step 1: " + site.text()).isFalse();
            String siteCode = found(Pattern.compile("S\\d+"), site.text(), "step 1: the site's code");
            int siteId = Integer.parseInt(Objects.requireNonNull(RecordCodes.keyOfCode(SiteModel.MODEL_ID,
                siteCode)));
            Row stored = Models.get(SiteModel.class).findById(siteId);
            assertThat(stored.get(SiteModel.UPSTREAM_KIND)).as("step 1: an app site")
                .isEqualTo(InstanceUpstreamKind.ID.toString());
            assertThat(String.valueOf(RawValues.map(stored.get(SiteModel.SETTINGS)).get("request_timeout")))
                .as("step 1: its setting").isEqualTo("120");
            assertThat(admin.call("site_app_set", Map.of("name", siteName)).text())
                .as("step 1: a create without its app").contains("give name and app");
            assertThat(admin.call("site_static_files_set", Map.of("code", siteCode, "root_path", "/tmp")).text())
                .as("step 1: a code of another kind names its own tool").contains("site_app_set")
                .contains("change_kind");
            // A change sends one setting; every other stays.
            McpHttpSession.Answer timed = admin.call("site_app_set", Map.of("code", siteCode, "idle_timeout", 900));
            assertThat(timed.error()).as("step 1: one setting changed " + timed.text()).isFalse();
            Map<String, Object> settings = RawValues.map(Models.get(SiteModel.class).findById(siteId)
                .get(SiteModel.SETTINGS));
            assertThat(String.valueOf(settings.get("idle_timeout")) + " " + settings.get("request_timeout"))
                .as("step 1: merged").isEqualTo("900 120");

            // 2. An address on the site.
            String hostname = letters() + "-shop.mcp-journey.test";
            McpHttpSession.Answer address = admin.call("address_set", Map.of("site", siteCode, "hostname",
                hostname, "response_headers", List.of(Map.of("name", "X-Frame-Options", "value", "DENY"))));
            assertThat(address.error()).as("step 2: " + address.text()).isFalse();
            String addressCode = found(Pattern.compile("D\\d+"), address.text(), "step 2: the address's code");
            assertThat(admin.call("address_set", Map.of("code", addressCode, "site", siteCode)).text())
                .as("step 2: the site is given only when creating").contains("site").contains("creating");
            McpHttpSession.Answer secured = admin.call("address_set", Map.of("code", addressCode, "hsts", true,
                "force_https", false, "match", "exact", "exclude_from_automatic_certificates", false,
                "request_headers", List.of(Map.of("name", "X-Shop", "value", "1"))));
            assertThat(secured.error()).as("step 2: a change " + secured.text()).isFalse();
            assertThat(secured.text()).as("step 2: what changed, by its names for people").contains("hsts")
                .contains("request_headers").doesNotContain("hsts_enabled").doesNotContain("custom_headers");
            Row secure = Models.get(SiteDomainModel.class).find().where(SiteDomainModel.HOSTNAME.eq(hostname)).first();
            assertThat(secure.get(SiteDomainModel.HSTS_ENABLED)).as("step 2: stored under its column").isEqualTo(true);
            assertThat(RawValues.map(secure.get(SiteDomainModel.CUSTOM_HEADERS))).as("step 2: the request header")
                .containsEntry("X-Shop", "1");
            // Every change a tool makes is in the record's history, as the panel's own save is.
            McpHttpSession.Answer past = admin.call("history", Map.of("id", addressCode));
            assertThat(past.error()).as("step 2: the address's history " + past.text()).isFalse();
            assertThat(past.text()).as("step 2: the change is in it").contains("changed " + hostname);
            // The one list lists a site's addresses by the site's code, and refuses a code naming nothing.
            McpHttpSession.Answer ofSite = admin.call("list", Map.of("kind", "address", "of", siteCode));
            assertThat(ofSite.error()).as("step 2: list kind=address of=" + siteCode + ": " + ofSite.text()).isFalse();
            int siteKey = Integer.parseInt(Objects.requireNonNull(RecordCodes.keyOfCode(SiteModel.MODEL_ID, siteCode)));
            long ofThatSite = Models.get(SiteDomainModel.class).find().where(SiteDomainModel.SITE_ID.eq(siteKey))
                .count();
            assertThat(ofSite.text()).as("step 2: the site's addresses, and no other site's").contains(addressCode)
                .containsPattern(": " + ofThatSite + "(?![0-9])");
            McpHttpSession.Answer ofNothing = admin.call("list", Map.of("kind", "address", "of", "S999999"));
            assertThat(ofNothing.error()).as("step 2: of a code naming nothing is refused").isTrue();
            assertThat(ofNothing.text()).as("step 2: naming the argument and the code").contains("of")
                .contains("S999999");
            assertThat(admin.call("address_set", Map.of("code", addressCode, "hsts_enabled", true)).text())
                .as("step 2: the stored name is no argument").contains("hsts_enabled");
            // The app site's own settings under their names for people.
            McpHttpSession.Answer forwarding = admin.call("site_app_set", Map.of("code", siteCode,
                "forward_scheme", "http"));
            assertThat(forwarding.error()).as("step 2: the app site's forwarding " + forwarding.text()).isFalse();
            assertThat(forwarding.text()).as("step 2: the site's code and what changed").contains(siteCode)
                .contains("forward_scheme");
            assertThat(RawValues.map(Models.get(SiteModel.class).findById(siteId).get(SiteModel.SETTINGS)))
                .as("step 2: stored under its key").containsEntry("scheme", "http");
            assertThat(admin.call("site_app_set", Map.of("code", siteCode, "port", "web")).error())
                .as("step 2: the port nothing honours is no argument").isTrue();

            // 3. A certificate for the address, ordered with a manual DNS challenge, so nothing must reach this host.
            McpHttpSession.Answer ordered = admin.call("certificate_order", Map.of("address", addressCode,
                "challenge", CertificateModel.CHALLENGE_DNS, "name", "journey shop", "dns_publisher",
                CertificateModel.DNS_PUBLISHER_MANUAL));
            assertThat(ordered.error()).as("step 3: " + ordered.text()).isFalse();
            found(Pattern.compile("C\\d+"), ordered.text(), "step 3: the certificate's code");
            assertThat(ordered.text()).as("step 3: the TXT records the order waits for, and what to call next")
                .contains("_acme-challenge").contains(" TXT ").contains("certificate_renew");
            assertThat(Models.get(CertificateModel.class).find()
                    .where(CertificateModel.DOMAIN_NAMES_TEXT.eq(hostname)).count())
                .as("step 3: ordered for the address's hostname").isEqualTo(1L);
            assertThat(admin.call("certificate_order", Map.of("address", addressCode, "code", "C1")).text())
                .as("step 3: one record at a time").contains("Give only one of code, address.");

            // 4. An A and a TXT record in one call, in an existing zone.
            zoneId = zone(letters() + ".mcp-journey.test");
            String zone = code(DnsZoneModel.MODEL_ID, zoneId, operator);
            McpHttpSession.Answer zones = admin.call("list", Map.of("kind", "dns_zone", "search", "mcp-journey"));
            assertThat(zones.error()).as("step 4: list kind=dns_zone: " + zones.text()).isFalse();
            assertThat(zones.text()).as("step 4: the zone listed by its code").contains(zone);
            McpHttpSession.Answer set = admin.call("dns_record_set", Map.of("dns_zone", zone, "records", List.of(
                Map.of("name", "shop", "type", "A", "values", List.of(Map.of("value", "203.0.113.10"))),
                Map.of("name", "@", "type", "TXT", "ttl", 300, "values", List.of(Map.of("value", "hello"))))));
            assertThat(set.error()).as("step 4: " + set.text()).isFalse();
            assertThat(set.text()).as("step 4: what was added").contains("shop A: 1 added");
            String listed = admin.call("dns_record_list", Map.of("dns_zone", zone)).text();
            assertThat(listed).as("step 4: both listed").contains("shop A").contains("203.0.113.10")
                .contains("@ TXT 300").contains("hello");

            // 5. The A record changed, the TXT record removed.
            assertThat(admin.call("dns_record_set", Map.of("dns_zone", zone, "records", List.of(
                Map.of("name", "shop", "type", "A", "values", List.of(Map.of("value", "203.0.113.11"))),
                Map.of("name", "@", "type", "TXT", "remove", true)))).error()).as("step 5: set").isFalse();
            String after = admin.call("dns_record_list", Map.of("dns_zone", zone, "type", "A")).text();
            assertThat(after).as("step 5: the new value only").contains("203.0.113.11").doesNotContain("203.0.113.10");
            assertThat(admin.call("dns_record_list", Map.of("dns_zone", zone)).text()).as("step 5: no TXT left")
                .doesNotContain("hello");

            // 6. A record Hohenheim made itself (a certificate proof) is refused.
            Row proof = Models.get(DnsRecordModel.class).createEmptyRow();
            proof.set(DnsRecordModel.ZONE_ID, zoneId);
            proof.set(DnsRecordModel.NAME, "_acme-challenge");
            proof.set(DnsRecordModel.TYPE, DnsRecordModel.TYPE_TXT);
            proof.set(DnsRecordModel.VALUE, "proof");
            proof.set(DnsRecordModel.ENABLED, true);
            GeneratedDnsRecords.as(new GeneratedDnsRecords.Attribution(GeneratedDnsRecords.SOURCE_ACME,
                SiteDomainModel.MODEL_ID.toString(), 1), () -> Models.get(DnsRecordModel.class).save(proof));
            McpHttpSession.Answer generated = admin.call("dns_record_set", Map.of("dns_zone", zone, "records",
                List.of(Map.of("name", "_acme-challenge", "type", "TXT", "values", List.of(Map.of("value",
                    "forged"))))));
            assertThat(generated.error()).as("step 6: refused " + generated.text()).isTrue();
            assertThat(Models.get(DnsRecordModel.class).findById(proof.get(DnsRecordModel.ID)))
                .as("step 6: the proof stays").isNotNull();

            // 7. The site and the address read in full; describe names the exact calls.
            String read = admin.call("read", Map.of("ids", List.of(siteCode, addressCode))).text();
            assertThat(read).as("step 7: the reads").contains(siteName).contains(app).contains(hostname);
            assertThat(RawValues.map(Models.get(SiteDomainModel.class).find().where(SiteDomainModel.HOSTNAME
                .eq(hostname)).first().get(SiteDomainModel.RESPONSE_HEADERS)))
                .as("step 7: the header stored").containsEntry("X-Frame-Options", "DENY");
            assertThat(admin.call("describe", Map.of("code", siteCode)).text()).as("step 7: describe")
                .contains("site_app_set code=" + siteCode).contains("delete code=" + siteCode)
                .doesNotContain("site_proxy_set")
                .as("step 7: and what one makes on it next, the site as an argument")
                .contains("address_set site=" + siteCode + " hostname=...");

            // 8. roll_back takes a site (its release engine then answers in its own words); delete sends the site to
            //    the trash; site_restore brings it back; trashed again, permanent removes it for good.
            assertThat(admin.call("roll_back", Map.of("code", siteCode)).text()).as("step 8: a site is rolled back")
                .doesNotContain("is none of them");
            assertThat(admin.call("delete", Map.of("code", siteCode, "permanent", true)).error())
                .as("step 8: permanent takes only a site in the trash").isTrue();
            McpHttpSession.Answer deleted = admin.call("delete", Map.of("code", siteCode));
            assertThat(deleted.error()).as("step 8: " + deleted.text()).isFalse();
            assertThat(Models.get(SiteModel.class).find().where(SiteModel.ID.eq(siteId)).first())
                .as("step 8: in the trash").isNull();
            McpHttpSession.Answer restored = admin.call("site_restore", Map.of("code", siteCode));
            assertThat(restored.error()).as("step 8: restored " + restored.text()).isFalse();
            assertThat(Models.get(SiteModel.class).find().where(SiteModel.ID.eq(siteId)).first())
                .as("step 8: live again").isNotNull();
            assertThat(admin.call("delete", Map.of("code", siteCode)).error()).as("step 8: trashed again").isFalse();
            McpHttpSession.Answer purged = admin.call("delete", Map.of("code", siteCode, "permanent", true));
            assertThat(purged.error()).as("step 8: deleted for good " + purged.text()).isFalse();
            assertThat(Models.get(SiteModel.class).find().withTrashed().where(SiteModel.ID.eq(siteId)).first())
                .as("step 8: gone, the trash too").isNull();

            // 9. A site serving a folder, with its missing page.
            McpHttpSession.Answer files = admin.call("site_static_files_set", Map.of("name", PREFIX + "files-"
                + letters(), "root_path", "/tmp", "autoindex", false, "missing_page", "404.html"));
            assertThat(files.error()).as("step 9: " + files.text()).isFalse();
            String filesCode = found(Pattern.compile("S\\d+"), files.text(), "step 9: the site's code");
            assertThat(admin.call("read", Map.of("ids", List.of(filesCode))).text()).as("step 9: its folder")
                .contains("/tmp");
            assertThat(admin.call("delete", Map.of("code", app, "with_data", true)).error())
                .as("step 9: the app deleted").isFalse();

            // 10. A proxy site to two backends, and a redirect site; both changed by their names for people.
            McpHttpSession.Answer proxy = admin.call("site_proxy_set", Map.of("name", PREFIX + "proxy-" + letters(),
                "forward_host", "203.0.113.20", "forward_port", 8080, "backends", List.of("203.0.113.21:8080"),
                "forward_scheme", "http", "delay_ms", 0));
            assertThat(proxy.error()).as("step 10: " + proxy.text()).isFalse();
            String proxyCode = found(Pattern.compile("S\\d+"), proxy.text(), "step 10: the proxy's code");
            McpHttpSession.Answer added = admin.call("site_proxy_set", Map.of("code", proxyCode, "backends",
                List.of("203.0.113.21:8080", "203.0.113.22:8080")));
            assertThat(added.error()).as("step 10: a backend added " + added.text()).isFalse();
            Row proxied = Models.get(SiteModel.class).findById(Integer.parseInt(Objects.requireNonNull(
                RecordCodes.keyOfCode(SiteModel.MODEL_ID, proxyCode))));
            assertThat(RawValues.map(proxied.get(SiteModel.SETTINGS)).get("more_backends"))
                .as("step 10: one backend per line").isEqualTo("203.0.113.21:8080\n203.0.113.22:8080");
            McpHttpSession.Answer redirect = admin.call("site_redirect_set", Map.of("name", PREFIX + "go-"
                + letters(), "target_url", "https://example.com/", "delay_ms", 10));
            assertThat(redirect.error()).as("step 10: " + redirect.text()).isFalse();
            String redirectCode = found(Pattern.compile("S\\d+"), redirect.text(), "step 10: the redirect's code");
            assertThat(admin.call("site_redirect_set", Map.of("code", redirectCode, "target_url",
                "https://example.org/")).error()).as("step 10: its target changed").isFalse();
            for (String other : List.of(proxyCode, redirectCode)) {
                assertThat(admin.call("delete", Map.of("code", other)).error()).as("step 10: " + other + " trashed")
                    .isFalse();
            }
        } finally {
            if (zoneId != null) {
                // The proof is Hohenheim's own record: only its system scope takes it away.
                int zone = zoneId;
                GeneratedDnsRecords.as(new GeneratedDnsRecords.Attribution(GeneratedDnsRecords.SOURCE_ACME,
                    SiteDomainModel.MODEL_ID.toString(), 1), () -> Models.get(DnsRecordModel.class).find()
                        .where(DnsRecordModel.ZONE_ID.eq(zone)).delete());
                HardDeletes.byId(Models.get(DnsZoneModel.class), zoneId);
            }
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.ACME_DIRECTORY_URL, savedDirectory);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_EMAIL, savedEmail);
            if (adopted != null) ServerMain.adoptProxyServer(null);
            local.restore();
        }
    }

    /** @return the first match of the pattern in an answer, asserted present */
    private static String found(Pattern pattern, String text, String step) {
        Matcher matcher = pattern.matcher(text);
        assertThat(matcher.find()).as(step + ": " + text).isTrue();
        return matcher.group();
    }

    /** @return the list of an app's variables, by the app's code */
    private static String variables(McpHttpSession session, String appCode) {
        McpHttpSession.Answer listed = session.call("list", Map.of("kind", "variable", "of", appCode));
        assertThat(listed.error()).as("the variables of " + appCode + ": " + listed.text()).isFalse();
        return listed.text();
    }

    /** @return a new primary zone of this origin */
    private static int zone(String origin) {
        Row zone = Models.get(DnsZoneModel.class).createEmptyRow();
        zone.set(DnsZoneModel.ORIGIN, origin);
        zone.set(DnsZoneModel.ENABLED, true);
        zone.set(DnsZoneModel.DEFAULT_TTL, 3600);
        zone.set(DnsZoneModel.NEGATIVE_TTL, 300);
        zone.set(DnsZoneModel.SOA_REFRESH, 7200);
        zone.set(DnsZoneModel.SOA_RETRY, 3600);
        zone.set(DnsZoneModel.SOA_EXPIRE, 1209600);
        Models.get(DnsZoneModel.class).save(zone);
        return zone.get(DnsZoneModel.ID);
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
    static McpHttpSession presetSession(McpHostServer server, URI endpoint, UUID accountId, String name) {
        AccessContext account = AccessContext.of(TenantConduits.stubFor(new UserPrincipal(accountId, name)));
        CredentialPresets.Preset preset = server.credentialPreset(account);
        assertThat(preset).as(name + " is offered the preset").isNotNull();
        List<String> scopes = ApiKeyService.presetScopes(account, preset);
        assertThat(scopes).as("the preset admits the server").contains(McpScopes.server("hohenheim"));
        String key = ApiKeyService.create(account, accountId, PREFIX + letters(), scopes, null).plaintext();
        return McpHttpSession.open(endpoint, bearer(key));
    }

    /** @return the code of the run a background tool's call started on the app, as its answer names it */
    private static String startedRun(McpHttpSession session, String tool, String appCode, String step) {
        McpHttpSession.Answer started = session.call(tool, Map.of("code", appCode));
        assertThat(started.error()).as(step + ": " + started.text()).isFalse();
        Matcher run = RUN_CODE.matcher(started.text());
        assertThat(run.find()).as(step + ": the answer names its run: " + started.text()).isTrue();
        assertThat(started.text()).as(step + ": started on his app").contains(appCode + ": " + run.group());
        return run.group();
    }

    /** @return what the run's read tool tells of the run once it is no longer running */
    static McpHttpSession.Answer followed(McpHttpSession session, String run, String step) {
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

    static String code(Identifier model, int id, AccessContext reader) {
        String code = RecordCodes.codes(model, List.of(String.valueOf(id)), reader).get(String.valueOf(id));
        assertThat(code).as(model + " " + id + " has a code").isNotNull();
        return code;
    }

    /** @return letters alone, so a name says no number an answer could be suspected of */
    static String letters() {
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
