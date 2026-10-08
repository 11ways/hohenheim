package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimCounts;
import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.server.cms.TaskWords;
import be.elevenways.zenit.microcopy.server.MicrocopyCatalogScope;
import org.junit.jupiter.api.Test;

/**
 * The scope contract of Hohenheim's shipped catalogs: every variant carries a scope Hohenheim owns, never another
 * module's ({@code field} and {@code nav} are plumage's, {@code forms} zenit-forms', {@code cms} zenit-cms'), and no
 * (key, filters) identity collides with a framework module's on the classpath. The mechanism is zenit-microcopy's
 * gate; this suite names the scopes Hohenheim owns, a declared value no variant uses failing it too.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class MicrocopyCatalogScopeTest {

    @Test
    void everyShippedVariantCarriesAScopeHohenheimOwns() {
        MicrocopyCatalogScope.ofOwnCatalogs()
            .owning(HohenheimFormCopy.FIELD_SCOPE, HohenheimFormCopy.NAV_SCOPE, HohenheimMicrocopy.SCOPE,
                HohenheimViolations.SCOPE, HohenheimCounts.SCOPE, TaskWords.LABEL_SCOPE)
            .owning(
                "access_list", "access_rule", "access_rule_type", "access_satisfy", "admin", "admin_inbox", "alert",
                "app", "app_health", "app_list", "app_overview", "attention_action", "attention_detail",
                "attention_title", "auth_provider", "backup_status", "backup_target", "backup_target_kind",
                "backup_target_kind_description", "ban", "ban_cause", "ban_scope", "ban_source", "ban_state",
                "build_operation", "build_status", "builder_kind", "capability", "cert_challenge", "cert_provider",
                "cert_status",
                "certificate", "certificate_request", "certificate_request_error", "console_kind", "console_mode",
                "crash_policy", "dashboard", "database", "database_engine", "database_list", "database_overview",
                "database_placement", "database_restore", "database_status", "database_tab", "db_engine", "delegated", "depends_condition", "deployments",
                "dev_sessions", "dns_delegation", "dns_freshness", "dns_peer", "dns_publisher", "dns_record",
                "dns_record_type", "dns_remote", "dns_role", "dns_secondaries", "dns_transfer", "dns_zone",
                "dns_zone_file", "dns_zone_peer", "dns_zone_records", "domain_match", "environment",
                "environment_variable", "form_section", "game_domain", "git_provider", "git_provider_kind",
                "git_provider_kind_description", "help", "host_admission", "host_check", "host_list", "host_mode", "host_posture",
                "host_probe",
                "host_runtime", "image_origin", "install_state", "instance", "instance_artifacts", "instance_backup",
                "instance_console", "instance_database", "instance_deployments", "instance_device", "instance_exec",
                "instance_file", "instance_files", "instance_framebuffer", "instance_from_template", "instance_kind",
                "instance_kind_description", "instance_migrate", "instance_overview", "instance_provisioning",
                "instance_quota", "instance_schedule", "instance_shell", "instance_snapshot", "instance_stats",
                "instance_status", "instance_template", "instance_variable", "instance_volume", "manage",
                "manage_dashboard", "nav_cluster", "nav_cluster_hint", "notification_channel", "notification_event",
                "notification_format", "onboarding", "onboarding_checklist", "permission", "port_exposure",
                "port_protocol", "power_operation", "preview_deployment", "preview_status", "project", "project_member",
                "protect_path", "protected_path", "proxy_error", "put_online", "readiness_kind", "reconcile_bucket",
                "reconcile_finding", "reconcile_kind", "redirect_status", "reinstall_policy", "release_kind",
                "release_operation", "release_status", "released_claim", "restart_policy", "role", "routing_problem",
                "runtime_image", "runtime_role", "schedule_action", "schedule_run_status", "schedule_step",
                "security_event_type", "server", "server_media", "server_option", "server_overview", "settings",
                "settings_section", "site", "site_database", "site_databases", "site_domain", "site_domains", "site_fault",
                "site_status", "site_tls", "snapshot_status", "spamservice", "spamservice_client", "spamservice_event",
                "spamservice_key", "spamservice_reputation", "spamservice_sample", "spamservice_word", "stack",
                "stack_deploy_reason", "stack_deploy_status", "stack_deployments", "stack_file", "stack_mount_type",
                "stack_service", "stack_services", "stack_state", "stack_status", "stop_kind", "template_contents",
                "template_database", "template_file", "template_import", "template_variable", "template_volume", "tenant_usage",
                "uid_mode", "upstream_kind", "upstream_kind_description", "upstream_protocol", "upstream_scheme",
                "user", "variable_kind", "variable_type", "volume_backend", "webhook_outcome")
            .requireClaimed();
    }
}
