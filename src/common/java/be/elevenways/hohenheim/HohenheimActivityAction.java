package be.elevenways.hohenheim;

import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceStatus;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.model.ReleasedRouteClaimModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.protoblast.common.annotation.BlastAutoLoad;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.typed.CoreTypes;
import be.elevenways.protoblast.common.util.BlastString;
import be.elevenways.zenit.common.orm.activity.ActivityAction;
import be.elevenways.zenit.common.orm.activity.ActivityActions;
import be.elevenways.zenit.common.orm.activity.ActivitySeverity;
import be.elevenways.zenit.common.orm.history.HistoryVisibility;
import be.elevenways.zenit.common.orm.activity.FactShape;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * The history fact kinds Hohenheim declares; its created, updated and deleted rows are the ORM's own changes.
 *
 * AIDEV-NOTE: every verb heads its rows with a sentence of its own ({@link #happened()}), so a row written outside an
 * operation (a deploy from a git push, a crash restart, an old row from before operations told what happened) reads as
 * a sentence too, never as "Jelle: Deployed Instance #3". DeclaredMicrocopyKeysTest requires each one in en and nl.
 * A verb's body is typed ({@link #shape()}) and it is permitted only on the models {@link #subjects()} names.
 * The ids are stored ({@code InstanceModel.STOPPAGE_CAUSE} holds a cause's path), so a member is never renamed.
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
    // it queued, the cause it recorded) is the row a person reads, and the full log lists it through the internal
    // filter.
    RECONCILED("reconciled", HistoryVisibility.INTERNAL),
    BACKUP("backup"),
    /** A backup attempt that stored nothing; the detail is the failure's own reason. */
    BACKUP_FAILED("backup_failed"),
    /** A certificate issuance or renewal that failed; the detail is the failure's own reason. */
    RENEWAL_FAILED("renewal_failed"),
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
    WORKLOAD_EXITED("workload_exited", ErrorCause.EXIT_CODE, RestartWithheld.BY_POLICY),
    WORKLOAD_CRASH_LOOPED("workload_crash_looped", ErrorCause.PLAIN, RestartWithheld.BY_CRASH_LOOP),
    WORKLOAD_START_FAILED("workload_start_failed", ErrorCause.MESSAGE, ErrorPhase.START),
    WORKLOAD_ISOLATION_REFUSED("workload_isolation_refused", ErrorCause.HOST_ISOLATION, ErrorPhase.START),
    WORKLOAD_NEVER_READY("workload_never_ready", ErrorCause.PLAIN),
    WORKLOAD_STOP_FAILED("workload_stop_failed", ErrorCause.MESSAGE),
    WORKLOAD_REMOVE_FAILED("workload_remove_failed", ErrorCause.MESSAGE),
    WORKLOAD_RESTORE_FAILED("workload_restore_failed", ErrorCause.MESSAGE),
    WORKLOAD_RESTORE_INTERRUPTED("workload_restore_interrupted", ErrorCause.PLAIN),
    WORKLOAD_COPY_LOST("workload_copy_lost", ErrorCause.PLAIN),
    /** It stopped and the crash policy's automatic restart was refused; the detail is the refusal's own message. */
    WORKLOAD_RESTART_REFUSED("workload_restart_refused", ErrorCause.MESSAGE),
    /**
     * A destroy's end (stopped, trashed) failed too often, so its record is left destroying, not ERROR, until an
     * operator's Delete retries it; the detail is the failure's own message.
     */
    DESTROY_UNFINISHED("destroy_unfinished", ErrorCause.MESSAGE),
    /** A changed host key quarantined the host: it takes no new workload until an operator re-pins it. */
    QUARANTINED("quarantined"),

    // The host trust acts: security audit facts, each its own typed event, because the trust columns it changed do
    // not say which act changed them (a first-scan pin and an operator's re-pin write the same columns).
    /** A first scan pinned the identity a trust lane offered, unverified. */
    HOST_KEY_PINNED("host_key_pinned"),
    /** An operator confirmed a lane's pinned fingerprint out of band. */
    HOST_KEY_VERIFIED("host_key_verified"),
    /** An operator re-pinned a lane to the identity the host now offers, lifting its quarantine. */
    HOST_KEY_REPINNED("host_key_repinned"),
    /** A fresh client identity was minted for a lane. */
    HOST_IDENTITY_ROTATED("host_identity_rotated"),
    /** An operator accepted the host's shared-container posture risk at a warning version. */
    POSTURE_RISK_ACCEPTED("posture_risk_accepted"),
    /** An operator cordoned the host: it takes no new apps. */
    ADMISSION_CORDONED("admission_cordoned"),
    /** An operator lifted the cordon: the host takes new apps again. */
    ADMISSION_UNCORDONED("admission_uncordoned"),
    /** A check passed and admitted the host for the first time. */
    ADMISSION_ADMITTED("admission_admitted"),
    /** A delete that was forced past a failed teardown: the record went, the host may keep leftovers. */
    FORCED("forced");

    /** A text key: a name, path, hostname or command as the act named it. */
    public static final String NAME_KEY = "name";
    public static final String SOURCE_KEY = "source";
    public static final String MANUAL_KEY = "manual";
    public static final String HOSTNAMES_KEY = "hostnames";
    public static final String PREVIOUS_KEY = "previous";
    public static final String COMMAND_KEY = "command";
    public static final String ADDRESS_KEY = "address";
    public static final String HOSTNAME_KEY = "hostname";
    public static final String TRIGGER_KEY = "trigger";
    public static final String HANDLE_KEY = "handle";
    public static final String VARIABLE_KEY = "key";
    public static final String DEVICE_KEY = "device";
    public static final String TYPE_KEY = "type";
    public static final String KIND_KEY = "kind";
    public static final String OBJECTS_KEY = "objects";
    public static final String TEMPLATE_KEY = "template";
    public static final String VOLUME_KEY = "volume";
    /** The failure's own words, an opaque literal a source wrote. */
    public static final String REASON_KEY = "reason";
    public static final String VIA_KEY = "via";
    public static final String REF_KEY = "ref";
    public static final String BACKUP_KEY = "backup";
    public static final String LOSSES_KEY = "losses";
    public static final String MOVED_PORTS_KEY = "moved_ports";
    public static final String SNAPSHOT_KEY = "snapshot";
    public static final String NATIVE_SNAPSHOT_KEY = "native_snapshot";
    public static final String MOVED_KEY = "moved";
    public static final String REFUSED_KEY = "refused";
    public static final String COMPLETE_KEY = "complete";
    public static final String FROM_KEY = "from";
    public static final String TO_KEY = "to";
    public static final String PATH_KEY = "path";
    public static final String REMOVED_KEY = "removed";
    /** What a correction's host reported of the workload, an {@link ObservedWorkload} token. */
    public static final String DAEMON_STATE_KEY = "daemon_state";
    public static final String CRASH_LOOP_KEY = "crash_loop";
    public static final String SHELL_KEY = "shell";
    public static final String RUN_USER_KEY = "run_user";
    public static final String CLOSED_BY_KEY = "closed_by";
    public static final String STRANDED_KEY = "stranded";
    public static final String ORIGINAL_KEY = "original";
    /** A cause's exit code as the daemon reported it. */
    public static final String EXIT_CODE_KEY = "exit_code";
    /** A cause's failure message, verbatim. */
    public static final String MESSAGE_KEY = "message";
    /** The trust lane an act was on ({@code HostTrustSlot.name()}). */
    public static final String LANE_KEY = "lane";
    public static final String FINGERPRINT_KEY = "fingerprint";
    public static final String POSTURE_KEY = "posture";
    /** The posture warning version an acceptance was given at. */
    public static final String WARNING_VERSION_KEY = "warning_version";

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
         * A cause whose root is its host: per-workload firewall rules are switched off there, so the workload was
         * refused before it could run; its row carries no detail, the host's own item says why.
         */
        HOST_ISOLATION;

        /** @return whether a verb with this fact is the cause of an ERROR status */
        public boolean isCause() {
            return this != NONE;
        }
    }

    static {
        ActivityActions.register(HohenheimMicrocopy.HOHENHEIM.copy(), values());
        // Each member's spelling before ids, a legacy key of the activity registry alone.
        for (HohenheimActivityAction action : values()) {
            ActivityActions.legacyKey(action.value, action);
        }
    }

    /** When a cause verb's error came: while the workload ran, or before it ever started. */
    public enum ErrorPhase {

        /** It ran, then stopped (an exit, a crash loop, a failed restore); also every verb that is no cause. */
        RUNNING,

        /** It never started: the start itself failed or was refused. */
        START
    }

    /** What a status correction's host reported of the workload, stored under {@link #DAEMON_STATE_KEY}. */
    public enum ObservedWorkload {

        /** The host holds no workload under the record's handle. */
        ABSENT,

        /** The host holds the workload, stopped. */
        STOPPED;

        /** @return the stored token */
        public @NonNull String token() {
            return BlastString.lower(this.name());
        }

        /** @return the report in the reader's words */
        public @NonNull Microcopy label() {
            return HohenheimMicrocopy.HOHENHEIM.of("workload_" + this.token(), DAEMON_STATE_KEY);
        }

        /** @return the words of a stored token, null for one no member stores */
        static @Nullable Microcopy labelOf(@NonNull String token) {
            for (ObservedWorkload observed : values()) {
                if (observed.token().equals(token)) {
                    return observed.label();
                }
            }
            return null;
        }
    }

    /** Why no automatic restart followed the stop a cause verb records. */
    public enum RestartWithheld {

        /** No restart was in question (a start or a restore failed), or one was tried and its refusal is the cause. */
        NONE,

        /**
         * Its crash policy asked for none: both restart lanes (the console watch, the reconciler) record the verb only
         * under a policy other than restart.
         */
        BY_POLICY,

        /** Crash-loop protection held further restarts back. */
        BY_CRASH_LOOP
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
    private final @NonNull HistoryVisibility visibility;
    private final @NonNull RestartWithheld restartWithheld;

    HohenheimActivityAction(@NonNull String value) {
        this(value, ErrorCause.NONE, ErrorPhase.RUNNING, Grouping.SINGLE, HistoryVisibility.LISTED,
            RestartWithheld.NONE);
    }

    HohenheimActivityAction(@NonNull String value, @NonNull ErrorCause errorCause) {
        this(value, errorCause, ErrorPhase.RUNNING, Grouping.SINGLE, HistoryVisibility.LISTED, RestartWithheld.NONE);
    }

    HohenheimActivityAction(@NonNull String value, @NonNull ErrorCause errorCause,
                            @NonNull RestartWithheld restartWithheld) {
        this(value, errorCause, ErrorPhase.RUNNING, Grouping.SINGLE, HistoryVisibility.LISTED, restartWithheld);
    }

    HohenheimActivityAction(@NonNull String value, @NonNull ErrorCause errorCause, @NonNull ErrorPhase errorPhase) {
        this(value, errorCause, errorPhase, Grouping.SINGLE, HistoryVisibility.LISTED, RestartWithheld.NONE);
    }

    HohenheimActivityAction(@NonNull String value, @NonNull Grouping grouping) {
        this(value, ErrorCause.NONE, ErrorPhase.RUNNING, grouping, HistoryVisibility.LISTED, RestartWithheld.NONE);
    }

    HohenheimActivityAction(@NonNull String value, @NonNull HistoryVisibility visibility) {
        this(value, ErrorCause.NONE, ErrorPhase.RUNNING, Grouping.SINGLE, visibility, RestartWithheld.NONE);
    }

    HohenheimActivityAction(@NonNull String value, @NonNull ErrorCause errorCause, @NonNull ErrorPhase errorPhase,
                            @NonNull Grouping grouping, @NonNull HistoryVisibility visibility,
                            @NonNull RestartWithheld restartWithheld) {
        this.value = value;
        this.id = HohenheimIds.id(value);
        this.errorCause = errorCause;
        this.errorPhase = errorPhase;
        this.grouping = grouping;
        this.visibility = visibility;
        this.restartWithheld = restartWithheld;
    }

    /** @return why no automatic restart followed the stop this cause verb records */
    public @NonNull RestartWithheld restartWithheld() {
        return this.restartWithheld;
    }

    /** @return when this cause verb's error came; RUNNING for a verb that is no cause */
    public @NonNull ErrorPhase errorPhase() {
        return this.errorPhase;
    }

    @Override
    public @NonNull HistoryVisibility visibility() {
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

    /**
     * @return the typed keys this verb's fact holds; a cause verb's body is what its {@link #errorCause()} keeps
     */
    @Override
    public @NonNull FactShape shape() {
        return switch (this) {
            case IMPORTED -> FactShape.of(FactShape.optionalKey(SOURCE_KEY, CoreTypes.TEXT));
            case REQUESTED -> FactShape.of(FactShape.optionalKey(MANUAL_KEY, CoreTypes.BOOLEAN));
            case REISSUED -> FactShape.of(FactShape.key(HOSTNAMES_KEY, CoreTypes.LIST),
                FactShape.optionalKey(PREVIOUS_KEY, CoreTypes.TEXT));
            case CONSOLE_COMMAND, EXEC -> FactShape.of(FactShape.key(COMMAND_KEY, CoreTypes.TEXT));
            case MEDIA_UPLOADED, MEDIA_DELETED, MEDIA_FETCHED -> FactShape.of(FactShape.key(NAME_KEY, CoreTypes.TEXT));
            case DYNDNS_UPDATE -> FactShape.of(FactShape.key(ADDRESS_KEY, CoreTypes.TEXT));
            case RELEASED_HOSTNAME_DISABLED, DOMAIN_ADDED, DOMAIN_REMOVED, QUARANTINE_LIFTED ->
                FactShape.of(FactShape.key(HOSTNAME_KEY, CoreTypes.TEXT));
            case DEPLOYED -> FactShape.of(FactShape.optionalKey(TRIGGER_KEY, CoreTypes.TEXT),
                FactShape.optionalKey(HANDLE_KEY, CoreTypes.TEXT));
            case STOPPED -> FactShape.of(FactShape.optionalKey(HANDLE_KEY, CoreTypes.TEXT));
            case VARIABLE_SET, VARIABLE_DELETED -> FactShape.of(FactShape.key(VARIABLE_KEY, CoreTypes.TEXT));
            case DEVICE_ATTACHED -> FactShape.of(FactShape.optionalKey(DEVICE_KEY, CoreTypes.TEXT),
                FactShape.optionalKey(TYPE_KEY, CoreTypes.TEXT));
            case DEVICE_RESIZED, DEVICE_DETACHED -> FactShape.of(FactShape.optionalKey(DEVICE_KEY, CoreTypes.TEXT));
            case REMOVED_ORPHAN -> FactShape.of(FactShape.key(KIND_KEY, CoreTypes.TEXT),
                FactShape.key(NAME_KEY, CoreTypes.TEXT));
            case REAPED_CONTROLLER_OBJECTS -> FactShape.of(FactShape.key(OBJECTS_KEY, CoreTypes.LIST));
            case TEMPLATE_CAPTURED -> FactShape.of(FactShape.key(TEMPLATE_KEY, FactShape.RECORD));
            case VOLUME_DECLARED, VOLUME_REDECLARED -> FactShape.of(FactShape.key(VOLUME_KEY, CoreTypes.TEXT));
            case APP_UPDATE_FAILED, BACKUP_FAILED, RENEWAL_FAILED ->
                FactShape.of(FactShape.optionalKey(REASON_KEY, CoreTypes.TEXT));
            case QUARANTINED -> FactShape.of(FactShape.optionalKey(REASON_KEY, CoreTypes.TEXT));
            case HOST_KEY_PINNED, HOST_KEY_VERIFIED -> FactShape.of(FactShape.key(LANE_KEY, CoreTypes.TEXT),
                FactShape.key(FINGERPRINT_KEY, CoreTypes.TEXT));
            case HOST_KEY_REPINNED -> FactShape.of(FactShape.key(LANE_KEY, CoreTypes.TEXT),
                FactShape.key(FINGERPRINT_KEY, CoreTypes.TEXT), FactShape.optionalKey(PREVIOUS_KEY, CoreTypes.TEXT));
            case HOST_IDENTITY_ROTATED -> FactShape.of(FactShape.key(LANE_KEY, CoreTypes.TEXT));
            case POSTURE_RISK_ACCEPTED -> FactShape.of(FactShape.wordedKey(POSTURE_KEY, token -> {
                    EnumField.EnumValue posture = ServerModel.POSTURE.getValues().get(token);
                    return posture == null ? null : posture.getLabel();
                }), FactShape.key(WARNING_VERSION_KEY, CoreTypes.INTEGER));
            case PREVIEW_TRIGGERED -> FactShape.of(FactShape.key(VIA_KEY, CoreTypes.TEXT),
                FactShape.key(REF_KEY, CoreTypes.TEXT));
            case RESTORED_BACKUP -> FactShape.of(FactShape.optionalKey(BACKUP_KEY, CoreTypes.INTEGER),
                FactShape.optionalKey(LOSSES_KEY, CoreTypes.TEXT), FactShape.optionalKey(MOVED_PORTS_KEY,
                    CoreTypes.LIST));
            case RESTORED_SNAPSHOT -> FactShape.of(FactShape.optionalKey(SNAPSHOT_KEY, CoreTypes.INTEGER),
                FactShape.optionalKey(NATIVE_SNAPSHOT_KEY, CoreTypes.TEXT));
            case DRAINED -> FactShape.of(FactShape.key(MOVED_KEY, CoreTypes.INTEGER),
                FactShape.key(REFUSED_KEY, CoreTypes.INTEGER), FactShape.key(COMPLETE_KEY, CoreTypes.BOOLEAN));
            case MIGRATED -> FactShape.of(FactShape.key(FROM_KEY, CoreTypes.TEXT),
                FactShape.key(TO_KEY, CoreTypes.TEXT));
            case SETTLED_INTERRUPTED -> FactShape.of(statusKey(FROM_KEY), statusKey(TO_KEY));
            case FILES_SAVE, FILES_UPLOAD, FILES_MKDIR, FILES_RENAME, FILES_DELETE, FILES_WRITE ->
                FactShape.of(FactShape.key(PATH_KEY, CoreTypes.TEXT));
            case DELETED_DATA -> FactShape.of(FactShape.key(REMOVED_KEY, CoreTypes.LIST));
            case RECONCILED -> FactShape.of(statusKey(FROM_KEY), statusKey(TO_KEY),
                FactShape.wordedKey(DAEMON_STATE_KEY, ObservedWorkload::labelOf),
                FactShape.key(CRASH_LOOP_KEY, CoreTypes.BOOLEAN));
            case BACKUP -> FactShape.of(FactShape.key(BACKUP_KEY, CoreTypes.INTEGER));
            case SNAPSHOT -> FactShape.of(FactShape.key(SNAPSHOT_KEY, CoreTypes.INTEGER));
            case SHELL_OPEN -> FactShape.of(FactShape.key(SHELL_KEY, CoreTypes.TEXT),
                FactShape.key(RUN_USER_KEY, CoreTypes.TEXT));
            case SHELL_CLOSE -> FactShape.of(FactShape.key(SHELL_KEY, CoreTypes.TEXT),
                FactShape.key(RUN_USER_KEY, CoreTypes.TEXT), FactShape.key(CLOSED_BY_KEY, CoreTypes.TEXT));
            case DISABLED -> FactShape.of(FactShape.optionalKey(STRANDED_KEY, CoreTypes.BOOLEAN));
            case CLONED -> FactShape.of(FactShape.key(ORIGINAL_KEY, FactShape.RECORD));
            case MOVE_SHARED, DYNDNS_TOKEN_MINTED, DYNDNS_TOKEN_REVOKED, ROLLED_BACK, VOLUMES_PURGED, TESTED, APPROVED,
                 UNAPPROVED, BACKUP_DOWNLOADED, APP_UPDATED, ENABLED, HTTPS_FORCED, ADMISSION_CORDONED,
                 ADMISSION_UNCORDONED, ADMISSION_ADMITTED, FORCED -> FactShape.NONE;
            case WORKLOAD_EXITED, WORKLOAD_CRASH_LOOPED, WORKLOAD_START_FAILED, WORKLOAD_ISOLATION_REFUSED,
                 WORKLOAD_NEVER_READY, WORKLOAD_STOP_FAILED, WORKLOAD_REMOVE_FAILED, WORKLOAD_RESTORE_FAILED,
                 WORKLOAD_RESTORE_INTERRUPTED, WORKLOAD_COPY_LOST, WORKLOAD_RESTART_REFUSED, DESTROY_UNFINISHED ->
                switch (this.errorCause) {
                    case EXIT_CODE -> FactShape.of(FactShape.optionalKey(EXIT_CODE_KEY, CoreTypes.TEXT));
                    case MESSAGE -> FactShape.of(FactShape.optionalKey(MESSAGE_KEY, CoreTypes.TEXT));
                    case NONE, PLAIN, HOST_ISOLATION -> FactShape.NONE;
                };
        };
    }

    /** @return a required key holding an instance status token, read as the status badge's label */
    private static FactShape.@NonNull Key statusKey(@NonNull String name) {
        return FactShape.wordedKey(name, token -> {
            InstanceStatus status = InstanceStatus.forToken(token);
            return status == null ? null : status.label();
        });
    }

    /**
     * @return the one body key a cause verb keeps its detail under, null for a cause keeping none (and any other
     *         verb)
     */
    public @Nullable String causeDetailKey() {
        return switch (this.errorCause) {
            case EXIT_CODE -> EXIT_CODE_KEY;
            case MESSAGE -> MESSAGE_KEY;
            case NONE, PLAIN, HOST_ISOLATION -> null;
        };
    }

    /** @return the models a fact of this verb may be recorded on ({@code HistoryFacts.permit}) */
    public @NonNull List<Identifier> subjects() {
        return switch (this) {
            case IMPORTED -> List.of(DnsZoneModel.MODEL_ID, InstanceTemplateModel.MODEL_ID);
            case REQUESTED, REISSUED, RENEWAL_FAILED -> List.of(CertificateModel.MODEL_ID);
            case MEDIA_UPLOADED, MEDIA_DELETED, MEDIA_FETCHED, REMOVED_ORPHAN, REAPED_CONTROLLER_OBJECTS, DRAINED,
                 QUARANTINED, HOST_KEY_PINNED, HOST_KEY_VERIFIED, HOST_KEY_REPINNED, HOST_IDENTITY_ROTATED,
                 POSTURE_RISK_ACCEPTED, ADMISSION_CORDONED, ADMISSION_UNCORDONED, ADMISSION_ADMITTED ->
                List.of(ServerModel.MODEL_ID);
            case FORCED -> List.of(DatabaseModel.MODEL_ID, DatabaseEngineModel.MODEL_ID);
            case MOVE_SHARED, BACKUP_DOWNLOADED -> List.of(DatabaseModel.MODEL_ID);
            case RESTORED_BACKUP -> List.of(InstanceModel.MODEL_ID, DatabaseModel.MODEL_ID);
            case DYNDNS_UPDATE, DYNDNS_TOKEN_MINTED, DYNDNS_TOKEN_REVOKED, RELEASED_HOSTNAME_DISABLED ->
                List.of(DnsRecordModel.MODEL_ID);
            case DEPLOYED, STOPPED, ROLLED_BACK -> List.of(InstanceModel.MODEL_ID, StackModel.MODEL_ID);
            case VOLUMES_PURGED -> List.of(StackModel.MODEL_ID);
            case VARIABLE_SET, VARIABLE_DELETED -> List.of(InstanceModel.MODEL_ID, EnvironmentModel.MODEL_ID);
            case DOMAIN_ADDED, DOMAIN_REMOVED, ENABLED, DISABLED, CLONED -> List.of(SiteModel.MODEL_ID);
            case TESTED -> List.of(NotificationChannelModel.MODEL_ID);
            case APPROVED, UNAPPROVED -> List.of(InstanceTemplateModel.MODEL_ID);
            case QUARANTINE_LIFTED -> List.of(ReleasedRouteClaimModel.MODEL_ID);
            case HTTPS_FORCED -> List.of(SiteDomainModel.MODEL_ID);
            case CONSOLE_COMMAND, DEVICE_ATTACHED, DEVICE_RESIZED, DEVICE_DETACHED, TEMPLATE_CAPTURED, VOLUME_DECLARED,
                 VOLUME_REDECLARED, APP_UPDATED, APP_UPDATE_FAILED, PREVIEW_TRIGGERED, RESTORED_SNAPSHOT, EXEC,
                 MIGRATED, FILES_SAVE, FILES_UPLOAD, FILES_MKDIR, FILES_RENAME, FILES_DELETE, FILES_WRITE,
                 DELETED_DATA, SETTLED_INTERRUPTED, RECONCILED, BACKUP, BACKUP_FAILED, SNAPSHOT, SHELL_OPEN,
                 SHELL_CLOSE, WORKLOAD_EXITED, WORKLOAD_CRASH_LOOPED, WORKLOAD_START_FAILED,
                 WORKLOAD_ISOLATION_REFUSED, WORKLOAD_NEVER_READY, WORKLOAD_STOP_FAILED, WORKLOAD_REMOVE_FAILED,
                 WORKLOAD_RESTORE_FAILED, WORKLOAD_RESTORE_INTERRUPTED, WORKLOAD_COPY_LOST,
                 WORKLOAD_RESTART_REFUSED, DESTROY_UNFINISHED -> List.of(InstanceModel.MODEL_ID);
        };
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
