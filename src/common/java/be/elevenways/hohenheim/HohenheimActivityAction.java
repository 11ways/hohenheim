package be.elevenways.hohenheim;

import be.elevenways.protoblast.common.annotation.BlastAutoLoad;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.activity.ActivityAction;
import be.elevenways.zenit.common.orm.activity.ActivityActions;
import be.elevenways.zenit.common.orm.activity.ActivitySeverity;
import be.elevenways.zenit.common.orm.activity.ActivityVisibility;
import be.elevenways.zenit.common.orm.activity.ZenitActivityAction;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

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
    FILES_SAVE("files_save", Grouping.BATCHED),
    FILES_UPLOAD("files_upload", Grouping.BATCHED),
    FILES_MKDIR("files_mkdir", Grouping.BATCHED),
    FILES_RENAME("files_rename", Grouping.BATCHED),
    FILES_DELETE("files_delete", Grouping.BATCHED),
    FILES_WRITE("files_write", Grouping.BATCHED),
    DELETED_DATA("deleted_data"),
    SETTLED_INTERRUPTED("settled_interrupted"),
    // A correction of a record's stored status to what its host answered is bookkeeping: what followed it (the restart
    // it queued, the cause it recorded) is the row a person reads, and the full log lists it through the internal filter.
    RECONCILED("reconciled", ActivityVisibility.INTERNAL),
    BACKUP("backup"),
    SNAPSHOT("snapshot"),
    SHELL_OPEN("shell_open"),
    SHELL_CLOSE("shell_close"),
    ENABLED("enabled"),
    DISABLED("disabled"),
    CLONED("cloned"),
    QUARANTINE_LIFTED("quarantine_lifted"),
    HTTPS_FORCED("https_forced"),

    // What stamped a workload ERROR, each recorded beside the status by InstanceOperationGuard.stampError; its
    // sentence is the dashboard's crash detail, so the item says what happened instead of guessing a crash.
    WORKLOAD_EXITED("workload_exited", ErrorCause.EXIT_CODE),
    WORKLOAD_CRASH_LOOPED("workload_crash_looped", ErrorCause.PLAIN),
    WORKLOAD_START_FAILED("workload_start_failed", ErrorCause.MESSAGE, ErrorPhase.START),
    WORKLOAD_ISOLATION_REFUSED("workload_isolation_refused", ErrorCause.HOST_ISOLATION, ErrorPhase.START),
    WORKLOAD_NEVER_READY("workload_never_ready", ErrorCause.PLAIN),
    WORKLOAD_STOP_FAILED("workload_stop_failed", ErrorCause.MESSAGE),
    WORKLOAD_REMOVE_FAILED("workload_remove_failed", ErrorCause.MESSAGE),
    WORKLOAD_RESTORE_FAILED("workload_restore_failed", ErrorCause.MESSAGE),
    WORKLOAD_RESTORE_INTERRUPTED("workload_restore_interrupted", ErrorCause.PLAIN),
    WORKLOAD_COPY_LOST("workload_copy_lost", ErrorCause.PLAIN);

    /** Whether a verb is the cause of a workload's ERROR status, and what its row's detail then holds. */
    public enum ErrorCause {

        /** Not a cause: the verb records something else. */
        NONE,

        /** A cause whose row carries no detail. */
        PLAIN,

        /** A cause whose row's detail is the exit code the workload ended with, when one was seen. */
        EXIT_CODE,

        /** A cause whose row's detail is the failure's own message, verbatim. */
        MESSAGE,

        /**
         * A cause whose root is its host: per-workload firewall rules are switched off there, so the workload was refused
         * before it could run; its row carries no detail, the host's own item says why.
         */
        HOST_ISOLATION;

        /** @return whether a verb with this fact is the cause of an ERROR status */
        public boolean isCause() {
            return this != NONE;
        }
    }

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

    /** When a cause verb's error came: while the workload ran, or before it ever started. */
    public enum ErrorPhase {

        /** It ran, then stopped (an exit, a crash loop, a failed restore); also every verb that is no cause. */
        RUNNING,

        /** It never started: the start itself failed or was refused. */
        START
    }

    /** Whether one session writes many rows of a verb, so a batch of it reads in its own words. */
    public enum Grouping {

        /** One row at a time; a batch of it, if any, reads as core's "{action} ({count} times)". */
        SINGLE,

        /** Written many at a time (an SFTP session's files), so its batch reads "Uploaded 312 files". */
        BATCHED
    }

    private final @NonNull String value;
    private final @NonNull Identifier id;
    private final @NonNull ErrorCause errorCause;
    private final @NonNull Grouping grouping;
    private final @NonNull ErrorPhase errorPhase;
    private final @NonNull ActivityVisibility visibility;

    HohenheimActivityAction(@NonNull String value) {
        this(value, ErrorCause.NONE, ErrorPhase.RUNNING, Grouping.SINGLE, ActivityVisibility.LISTED);
    }

    HohenheimActivityAction(@NonNull String value, @NonNull ErrorCause errorCause) {
        this(value, errorCause, ErrorPhase.RUNNING, Grouping.SINGLE, ActivityVisibility.LISTED);
    }

    HohenheimActivityAction(@NonNull String value, @NonNull ErrorCause errorCause, @NonNull ErrorPhase errorPhase) {
        this(value, errorCause, errorPhase, Grouping.SINGLE, ActivityVisibility.LISTED);
    }

    HohenheimActivityAction(@NonNull String value, @NonNull Grouping grouping) {
        this(value, ErrorCause.NONE, ErrorPhase.RUNNING, grouping, ActivityVisibility.LISTED);
    }

    HohenheimActivityAction(@NonNull String value, @NonNull ActivityVisibility visibility) {
        this(value, ErrorCause.NONE, ErrorPhase.RUNNING, Grouping.SINGLE, visibility);
    }

    HohenheimActivityAction(@NonNull String value, @NonNull ErrorCause errorCause, @NonNull ErrorPhase errorPhase,
                            @NonNull Grouping grouping, @NonNull ActivityVisibility visibility) {
        this.value = value;
        this.id = HohenheimIds.id(value);
        this.errorCause = errorCause;
        this.errorPhase = errorPhase;
        this.grouping = grouping;
        this.visibility = visibility;
    }

    /** @return when this cause verb's error came; RUNNING for a verb that is no cause */
    public @NonNull ErrorPhase errorPhase() {
        return this.errorPhase;
    }

    @Override
    public @NonNull ActivityVisibility visibility() {
        return this.visibility;
    }

    /** @return whether many rows of this verb are written at a time */
    public @NonNull Grouping grouping() {
        return this.grouping;
    }

    /** @return whether this verb is the recorded cause of a workload's ERROR status, and what its detail holds */
    public @NonNull ErrorCause errorCause() {
        return this.errorCause;
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

    /**
     * The words of a whole batch of this verb ("Uploaded 312 files"), shipped under {@code target=activity_batch}
     * with the argument {@code count}; null reads core's "{action} ({count} times)".
     */
    @Override
    public @Nullable Microcopy batchLabel() {
        return switch (this.grouping) {
            case BATCHED -> ActivityActions.batchLabel(this);
            case SINGLE -> null;
        };
    }
}
