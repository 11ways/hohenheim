package be.elevenways.hohenheim;

import be.elevenways.protoblast.common.annotation.BlastAutoLoad;
import be.elevenways.protoblast.common.registry.Identifier;
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
        migrations.renameType(Identifier.of("hohenheim", "instance_backups_page"), Identifier.of("hohenheim", "instance_backups"));
        migrations.renameType(Identifier.of("hohenheim", "instance_snapshots_page"), Identifier.of("hohenheim", "instance_snapshots"));
        migrations.renameType(Identifier.of("hohenheim", "instance_stats_page"), Identifier.of("hohenheim", "instance_stats"));
        migrations.renameType(Identifier.of("hohenheim", "spamservice_client_keys_page"), Identifier.of("hohenheim", "spamservice_client_keys"));
        migrations.renameType(Identifier.of("hohenheim", "backup_target_kinds"), Identifier.of("hohenheim", "backup_target_kind"));
        migrations.renameType(Identifier.of("hohenheim", "git_provider_kinds"), Identifier.of("hohenheim", "git_provider_kind"));
        migrations.renameType(Identifier.of("hohenheim", "instance_kinds"), Identifier.of("hohenheim", "instance_kind"));
        migrations.renameType(Identifier.of("hohenheim", "servers"), Identifier.of("hohenheim", "server"));
        migrations.renameType(Identifier.of("hohenheim", "site_auth_provider_types"), Identifier.of("hohenheim", "site_auth_provider_type"));
        migrations.renameType(Identifier.of("hohenheim", "upstream_kinds"), Identifier.of("hohenheim", "upstream_kind"));
        migrations.renameType(Identifier.of("hohenheim", "variable_types"), Identifier.of("hohenheim", "variable_type"));
        migrations.renameType(Identifier.of("hohenheim", "project-roles"), Identifier.of("hohenheim", "project_role"));
        // zenit-dev rename: end
    }

    private HohenheimStoredIds() {
    }
}
