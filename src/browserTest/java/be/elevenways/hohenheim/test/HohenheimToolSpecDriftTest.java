package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHostTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The assistant tools against their specification: every tool the Hohenheim connection holds is specified, and every
 * specified tool is held or named, with its reason, among the ones not built yet, so their count is read here, never
 * argued from memory.
 *
 * AIDEV-NOTE: the specification itself is the ignored scratch/2026-11-24-hohenheim-tools/spec/*.yaml of the
 * workspace (designs are never committed); its check.py reads {@link #SPECIFIED} and {@link #NOT_BUILT} from this file
 * and fails when the YAML names another set, so a tool added, renamed or removed is one edit here and one entry there,
 * or a red. Every tool counts, the ones a key's scope admits alone (own-account tools) included.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class HohenheimToolSpecDriftTest extends HohenheimHttpTestBase {

    /** Every tool the specification holds, sorted. */
    static final List<String> SPECIFIED = List.of(
        "access_list_set", "access_log_read", "access_rule_move", "access_rule_set", "account_read", "account_reset",
        "account_set", "address_set", "api_key_set", "app_application_set", "app_create_from_template",
        "app_database_set", "app_deploy_list", "app_device_set", "app_docker_container_set", "app_exec",
        "app_install_media_set", "app_move",
        "app_network_read", "app_script_run", "app_static_site_set", "app_system_container_set", "app_vm_set",
        "app_webhook_set", "app_workspace_set", "back_up", "backup_restore", "backup_target_set", "ban_set",
        "build_hook_set", "certificate_order", "certificate_renew", "certificate_set", "certificate_sign",
        "config_file_set", "database_dump_restore", "database_engine_set", "database_move_to_shared", "database_set",
        "delete", "delivery_retry", "deploy", "describe", "dns_peer_set", "dns_record_list", "dns_record_set",
        "dns_zone_import", "dns_zone_queries_read", "dns_zone_set", "dns_zone_take_over", "environment_set",
        "file_delete", "file_move", "file_read", "file_write",
        "game_address_set", "git_connection_set", "history", "host_admission_set", "host_client_key_reset",
        "host_leftover_cleanup", "host_media_set", "host_set", "host_trust", "image_cleanup", "list", "logs",
        "notification_channel_set", "notification_mark_read", "path_redirect_set", "person_picture_read",
        "person_reset", "person_set", "power", "preview_run", "project_set", "protected_path_set", "quota_set",
        "read", "request_upload_path", "role_permission_list", "role_set", "roll_back", "run_read", "runtime_image_set",
        "schedule_run", "schedule_set", "session_end", "settings_apps_set", "settings_backups_set",
        "settings_blocking_set",
        "settings_brand_set", "settings_dns_set", "settings_general_set", "settings_hosts_set", "settings_https_set",
        "settings_installation_set", "settings_notifications_set", "settings_read", "settings_signin_set",
        "settings_spam_set", "share_read", "share_set", "sign_in_provider_set", "site_app_set", "site_clone",
        "site_dev_namespace_set", "site_passthrough_set", "site_pause", "site_ports_move", "site_proxy_set",
        "site_redirect_set", "site_restore", "site_revision_mark_set", "site_static_files_set", "site_stream_set",
        "snapshot_restore", "snapshot_set", "spam_client_set", "spam_key_set", "spam_reputation_read",
        "spam_sample_set", "spam_service_power", "spam_service_set", "spam_service_test", "spam_word_set",
        "ssh_key_set", "stack_service_set", "stack_set", "stack_volumes_purge", "task_run_end", "task_run_pause",
        "template_database_set", "template_export", "template_import", "template_set", "template_variable_set",
        "test", "traffic_read", "undo", "variable_set", "volume_set");

    /** The specified tools not built yet, each with what it waits for; the list only shrinks. */
    static final Map<String, String> NOT_BUILT = Map.of(
        "settings_spam_set", "the spam service ships no setting declarations to derive arguments from"
            + " (spamservice-needs.md)");

    @Test
    void everyToolIsSpecifiedAndEverySpecifiedToolIsBuiltOrSaysWhyNot() {
        McpHostServer server = Objects.requireNonNull(McpHostServers.get(HohenheimMcpModule.SERVER_NAME));
        assertThat(SPECIFIED).as("step 1: the specified names are sorted and named once").isSorted()
            .doesNotHaveDuplicates();
        assertThat(SPECIFIED).as("step 1: a tool not built is a specified one").containsAll(NOT_BUILT.keySet());
        Set<String> held = new TreeSet<>();
        for (McpHostTool tool : server.tools()) held.add(tool.name());
        Set<String> built = new TreeSet<>(SPECIFIED);
        built.removeAll(NOT_BUILT.keySet());
        Set<String> unspecified = new TreeSet<>(held);
        unspecified.removeAll(SPECIFIED);
        Set<String> missing = new TreeSet<>(built);
        missing.removeAll(held);
        System.out.println("MCP tools: " + SPECIFIED.size() + " specified, " + held.size() + " built, "
            + NOT_BUILT.size() + " not built: " + new TreeSet<>(NOT_BUILT.keySet()));
        assertThat(unspecified).as("step 2: tools the connection holds that no spec entry names").isEmpty();
        assertThat(missing).as("step 2: specified tools neither held nor named not built").isEmpty();
        assertThat(held).as("step 2: a tool named not built is not held").doesNotContainAnyElementsOf(
            NOT_BUILT.keySet());
    }
}
