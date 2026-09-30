package be.elevenways.hohenheim;

import be.elevenways.protoblast.common.annotation.BlastAutoLoad;
import be.elevenways.protoblast.common.registry.StoredIdChains;
import be.elevenways.protoblast.common.registry.StoredTypeMigrations;

/**
 * The hohenheim stored-id rename chain: every id this repo once stored under another spelling resolves through it.
 *
 * AIDEV-NOTE: CHAIN is the forcing field the autoload scanner picks (the first non-constant static field), so it
 * needs no LOADED field; the static block runs in the same initializer, before any stored id is read. The zenit-dev
 * rename tool appends to the marked region; a line there is never removed while a stored value may still spell it.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
@BlastAutoLoad
public final class HohenheimStoredIds {

    public static final StoredTypeMigrations CHAIN = StoredIdChains.declare(HohenheimIds.id("hohenheim_stored_ids"));

    static {
        StoredTypeMigrations migrations = CHAIN;
        // zenit-dev rename: begin
        migrations.renameType("hohenheim:instance_backups_page", "hohenheim:instance_backups");
        migrations.renameType("hohenheim:instance_snapshots_page", "hohenheim:instance_snapshots");
        migrations.renameType("hohenheim:instance_stats_page", "hohenheim:instance_stats");
        migrations.renameType("hohenheim:spamservice_client_keys_page", "hohenheim:spamservice_client_keys");
        migrations.renameType("hohenheim:backup_target_kinds", "hohenheim:backup_target_kind");
        migrations.renameType("hohenheim:git_provider_kinds", "hohenheim:git_provider_kind");
        migrations.renameType("hohenheim:instance_kinds", "hohenheim:instance_kind");
        migrations.renameType("hohenheim:servers", "hohenheim:server");
        migrations.renameType("hohenheim:site_auth_provider_types", "hohenheim:site_auth_provider_type");
        migrations.renameType("hohenheim:upstream_kinds", "hohenheim:upstream_kind");
        migrations.renameType("hohenheim:variable_types", "hohenheim:variable_type");
        migrations.renameType("hohenheim:project-roles", "hohenheim:project_role");
        // zenit-dev rename: end
    }

    private HohenheimStoredIds() {
    }
}
