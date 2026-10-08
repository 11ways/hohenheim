package be.elevenways.hohenheim;

import be.elevenways.protoblast.common.annotation.BlastAutoLoad;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.activity.ActivityAction;
import be.elevenways.zenit.common.orm.activity.ActivityActions;
import be.elevenways.zenit.common.orm.activity.ActivitySeverity;
import be.elevenways.zenit.common.orm.activity.ZenitActivityAction;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The activity verbs Hohenheim declares; its created, updated and deleted rows are core's verbs.
 *
 * AIDEV-NOTE: every verb heads its rows with a sentence of its own ({@link #happened()}), so a row written outside an
 * operation (a deploy from a git push, a crash restart, an old row from before operations told what happened) reads as
 * a sentence too, never as "Jelle: Deployed Instance #3". DeclaredMicrocopyKeysTest requires each one in en and nl.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
@BlastAutoLoad
public enum HohenheimActivityAction implements ActivityAction {

    IMPORTED("imported"),
    REQUESTED("requested"),
    REISSUED("reissued"),
    CONSOLE_COMMAND("console_command"),
    MEDIA_UPLOADED("media_uploaded"),
    MEDIA_DELETED("media_deleted"),
    MEDIA_FETCHED("media_fetched"),
    MOVE_SHARED("move_shared"),
    DYNDNS_UPDATE("dyndns_update"),
    DYNDNS_TOKEN_MINTED("dyndns_token_minted"),
    DYNDNS_TOKEN_REVOKED("dyndns_token_revoked"),
    RELEASED_HOSTNAME_DISABLED("released_hostname_disabled"),
    DEPLOYED("deployed"),
    STOPPED("stopped"),
    ROLLED_BACK("rolled_back"),
    VOLUMES_PURGED("volumes_purged"),
    VARIABLE_SET("variable_set"),
    VARIABLE_DELETED("variable_deleted"),
    DOMAIN_ADDED("domain_added"),
    DOMAIN_REMOVED("domain_removed"),
    TESTED("tested"),
    APPROVED("approved"),
    UNAPPROVED("unapproved"),
    DEVICE_ATTACHED("device_attached"),
    DEVICE_RESIZED("device_resized"),
    DEVICE_DETACHED("device_detached"),
    BACKUP_DOWNLOADED("backup_downloaded"),
    REMOVED_ORPHAN("removed_orphan"),
    REAPED_CONTROLLER_OBJECTS("reaped_controller_objects"),
    TEMPLATE_CAPTURED("template_captured"),
    VOLUME_DECLARED("volume_declared"),
    VOLUME_REDECLARED("volume_redeclared"),
    APP_UPDATED("app_updated"),
    APP_UPDATE_FAILED("app_update_failed"),
    PREVIEW_TRIGGERED("preview_triggered"),
    RESTORED_BACKUP("restored_backup"),
    RESTORED_SNAPSHOT("restored_snapshot"),
    EXEC("exec"),
    DRAINED("drained"),
    MIGRATED("migrated"),
    FILES_SAVE("files_save"),
    FILES_UPLOAD("files_upload"),
    FILES_MKDIR("files_mkdir"),
    FILES_RENAME("files_rename"),
    FILES_DELETE("files_delete"),
    FILES_WRITE("files_write"),
    DELETED_DATA("deleted_data"),
    SETTLED_INTERRUPTED("settled_interrupted"),
    RECONCILED("reconciled"),
    BACKUP("backup"),
    SNAPSHOT("snapshot"),
    SHELL_OPEN("shell_open"),
    SHELL_CLOSE("shell_close"),
    ENABLED("enabled"),
    DISABLED("disabled"),
    CLONED("cloned"),
    QUARANTINE_LIFTED("quarantine_lifted"),
    HTTPS_FORCED("https_forced");

    static {
        ActivityActions.register(HohenheimMicrocopy.SCOPE, values());
        // Each member's spelling before ids, a legacy key of the activity registry alone.
        for (HohenheimActivityAction action : values()) {
            ActivityActions.legacyKey(action.value, action);
        }
        ActivityActions.legacyKey("created", ZenitActivityAction.CREATE);
        ActivityActions.legacyKey("updated", ZenitActivityAction.UPDATE);
        ActivityActions.legacyKey("deleted", ZenitActivityAction.DELETE);
    }

    private final @NonNull String value;
    private final @NonNull Identifier id;

    HohenheimActivityAction(@NonNull String value) {
        this.value = value;
        this.id = HohenheimIds.id(value);
    }

    @Override
    public @NonNull Identifier id() {
        return this.id;
    }

    @Override
    public @NonNull ActivitySeverity severity() {
        return ActivitySeverity.NEUTRAL;
    }

    @Override
    public @NonNull Icon icon() {
        return Icon.of("circle-info");
    }

    @Override
    public boolean acceptedChange() {
        return false;
    }

    @Override
    public @NonNull Microcopy happened() {
        return ActivityActions.sentence(this);
    }
}
