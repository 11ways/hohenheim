package be.elevenways.hohenheim;

import be.elevenways.protoblast.common.annotation.BlastAutoLoad;
import be.elevenways.protoblast.common.registry.StoredIdChains;
import be.elevenways.protoblast.common.registry.StoredTypeMigrations;
import be.elevenways.zenit.common.orm.activity.ZenitActivityAction;

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
        // No hohenheim id that is ever stored changed spelling: the sweep renamed only panel page, role owner and
        // registry ids, which are never stored, so their lines were dropped (G12).
        // zenit-dev rename: end
        // The activity verbs hohenheim stored before ids (module-fit stage 2, 4.1, F6 for the core verbs).
        migrations.legacyKey("created", ZenitActivityAction.CREATE.id());
        migrations.legacyKey("updated", ZenitActivityAction.UPDATE.id());
        migrations.legacyKey("deleted", ZenitActivityAction.DELETE.id());
        migrations.legacyKey("imported", HohenheimActivityAction.IMPORTED.id());
        migrations.legacyKey("requested", HohenheimActivityAction.REQUESTED.id());
        migrations.legacyKey("reissued", HohenheimActivityAction.REISSUED.id());
        migrations.legacyKey("console_command", HohenheimActivityAction.CONSOLE_COMMAND.id());
        migrations.legacyKey("media_uploaded", HohenheimActivityAction.MEDIA_UPLOADED.id());
        migrations.legacyKey("media_deleted", HohenheimActivityAction.MEDIA_DELETED.id());
        migrations.legacyKey("media_fetched", HohenheimActivityAction.MEDIA_FETCHED.id());
        migrations.legacyKey("move_shared", HohenheimActivityAction.MOVE_SHARED.id());
        migrations.legacyKey("dyndns_update", HohenheimActivityAction.DYNDNS_UPDATE.id());
        migrations.legacyKey("dyndns_token_minted", HohenheimActivityAction.DYNDNS_TOKEN_MINTED.id());
        migrations.legacyKey("dyndns_token_revoked", HohenheimActivityAction.DYNDNS_TOKEN_REVOKED.id());
        migrations.legacyKey("released_hostname_disabled", HohenheimActivityAction.RELEASED_HOSTNAME_DISABLED.id());
        migrations.legacyKey("deployed", HohenheimActivityAction.DEPLOYED.id());
        migrations.legacyKey("stopped", HohenheimActivityAction.STOPPED.id());
        migrations.legacyKey("rolled_back", HohenheimActivityAction.ROLLED_BACK.id());
        migrations.legacyKey("volumes_purged", HohenheimActivityAction.VOLUMES_PURGED.id());
        migrations.legacyKey("variable_set", HohenheimActivityAction.VARIABLE_SET.id());
        migrations.legacyKey("variable_deleted", HohenheimActivityAction.VARIABLE_DELETED.id());
        migrations.legacyKey("domain_added", HohenheimActivityAction.DOMAIN_ADDED.id());
        migrations.legacyKey("domain_removed", HohenheimActivityAction.DOMAIN_REMOVED.id());
        migrations.legacyKey("tested", HohenheimActivityAction.TESTED.id());
        migrations.legacyKey("approved", HohenheimActivityAction.APPROVED.id());
        migrations.legacyKey("unapproved", HohenheimActivityAction.UNAPPROVED.id());
        migrations.legacyKey("device_attached", HohenheimActivityAction.DEVICE_ATTACHED.id());
        migrations.legacyKey("device_resized", HohenheimActivityAction.DEVICE_RESIZED.id());
        migrations.legacyKey("device_detached", HohenheimActivityAction.DEVICE_DETACHED.id());
        migrations.legacyKey("backup_downloaded", HohenheimActivityAction.BACKUP_DOWNLOADED.id());
        migrations.legacyKey("removed_orphan", HohenheimActivityAction.REMOVED_ORPHAN.id());
        migrations.legacyKey("reaped_controller_objects", HohenheimActivityAction.REAPED_CONTROLLER_OBJECTS.id());
        migrations.legacyKey("template_captured", HohenheimActivityAction.TEMPLATE_CAPTURED.id());
        migrations.legacyKey("volume_declared", HohenheimActivityAction.VOLUME_DECLARED.id());
        migrations.legacyKey("volume_redeclared", HohenheimActivityAction.VOLUME_REDECLARED.id());
        migrations.legacyKey("app_updated", HohenheimActivityAction.APP_UPDATED.id());
        migrations.legacyKey("app_update_failed", HohenheimActivityAction.APP_UPDATE_FAILED.id());
        migrations.legacyKey("preview_triggered", HohenheimActivityAction.PREVIEW_TRIGGERED.id());
        migrations.legacyKey("restored_backup", HohenheimActivityAction.RESTORED_BACKUP.id());
        migrations.legacyKey("restored_snapshot", HohenheimActivityAction.RESTORED_SNAPSHOT.id());
        migrations.legacyKey("exec", HohenheimActivityAction.EXEC.id());
        migrations.legacyKey("drained", HohenheimActivityAction.DRAINED.id());
        migrations.legacyKey("migrated", HohenheimActivityAction.MIGRATED.id());
        migrations.legacyKey("files_save", HohenheimActivityAction.FILES_SAVE.id());
        migrations.legacyKey("files_upload", HohenheimActivityAction.FILES_UPLOAD.id());
        migrations.legacyKey("files_mkdir", HohenheimActivityAction.FILES_MKDIR.id());
        migrations.legacyKey("files_rename", HohenheimActivityAction.FILES_RENAME.id());
        migrations.legacyKey("files_delete", HohenheimActivityAction.FILES_DELETE.id());
        migrations.legacyKey("files_write", HohenheimActivityAction.FILES_WRITE.id());
        migrations.legacyKey("deleted_data", HohenheimActivityAction.DELETED_DATA.id());
        migrations.legacyKey("settled_interrupted", HohenheimActivityAction.SETTLED_INTERRUPTED.id());
        migrations.legacyKey("reconciled", HohenheimActivityAction.RECONCILED.id());
        migrations.legacyKey("backup", HohenheimActivityAction.BACKUP.id());
        migrations.legacyKey("snapshot", HohenheimActivityAction.SNAPSHOT.id());
        migrations.legacyKey("shell_open", HohenheimActivityAction.SHELL_OPEN.id());
        migrations.legacyKey("shell_close", HohenheimActivityAction.SHELL_CLOSE.id());
        migrations.legacyKey("enabled", HohenheimActivityAction.ENABLED.id());
        migrations.legacyKey("disabled", HohenheimActivityAction.DISABLED.id());
        migrations.legacyKey("cloned", HohenheimActivityAction.CLONED.id());
        migrations.legacyKey("quarantine_lifted", HohenheimActivityAction.QUARANTINE_LIFTED.id());
    }

    private HohenheimStoredIds() {
    }
}
