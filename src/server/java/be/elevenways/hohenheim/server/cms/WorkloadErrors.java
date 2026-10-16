package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionSubject;
import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Why a workload holds the ERROR status, in words: the stoppage {@code InstanceOperationGuard.stampError} stamped on
 * its record beside the status, read by the dashboard's "stopped after an error" item and the workload's verdict.
 *
 * AIDEV-NOTE: a failed start, a failed restore, a lost migration and a crash all stamp ERROR, so the status alone says
 * nothing about what happened; the old detail ("The workload exited unexpectedly and was not restarted") claimed a
 * crash for every one of them. Every ERROR stamp stores its cause on the row ({@link InstanceModel#STOPPAGE_CAUSE});
 * where none is stored (a status written by hand) the words say so, never a guess. Nothing here reads the activity
 * log: it may be pruned, and health must not change with it.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
final class WorkloadErrors {

    /** The day a stop was recorded on, as the overview names it. */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    private WorkloadErrors() {
    }

    /**
     * What stopped this workload with an error, worded, and the failure's own message as a separate technical line.
     * A stop of a running workload also says when it stopped, why nothing restarted it and what to do
     * ({@link InstanceModel#RESTART_WITHHELD}).
     *
     * AIDEV-NOTE: a recorded message is stored text in the daemon's or a gate's English ("Conflict. The container name
     * ... is already in use"), never localized copy, so it is never the sentence a reader is given first: failed
     * starts once read "What refused it: " and then that text. It follows the worded cause as "Technically: ...".
     *
     * AIDEV-NOTE: a record stamped ERROR before causes were recorded (2026-10-08) had only the reconciler's correction
     * row; M017 stored it as the stoppage {@link HohenheimActivityAction#RECONCILED}, read as the stop it records,
     * never guessed past it.
     *
     * @param withDetail whether the cause's stored detail (a failure's own message) may be shown; a delegated reader
     *                   gets the cause's sentence only, since a daemon's message can name the host's internals
     */
    static @NonNull Reading readingOf(@NonNull Row instance, boolean withDetail) {
        HohenheimActivityAction cause = InstanceModel.stoppageCauseOf(instance);
        Object name = instance.get(InstanceModel.NAME);
        if (cause == null) {
            return new Reading(HohenheimMicrocopy.ATTENTION_DETAIL.of("error_cause_unknown"), null);
        }
        Microcopy why = withheldWhy(InstanceModel.restartWithheldOf(instance), instance);
        if (cause == HohenheimActivityAction.RECONCILED) {
            // A correction to ERROR from before causes: no restart followed, by crash-loop protection or the policy.
            Microcopy found = HohenheimMicrocopy.ATTENTION_DETAIL.of("error_found_stopped").withArg("subject", name);
            return new Reading(why == null ? found : stoppedOn(found, instance, why), null);
        }
        Microcopy sentence = cause.happened().withArg("subject", name);
        String detail = withDetail ? instance.get(InstanceModel.STOPPAGE_DETAIL) : null;
        boolean none = detail == null || detail.isBlank();
        // A start that failed is headed "could not be started" by its title (Stoppage.START_FAILED), so its detail is
        // what that meant, never that sentence again.
        Reading reading = switch (cause.errorCause()) {
            case HOST_ISOLATION -> new Reading(withDetail
                ? HohenheimMicrocopy.ATTENTION_DETAIL.of("start_refused_isolation").withArg("host",
                ServerModel.canonicalNameOf(instance.get(InstanceModel.SERVER_ID))) : sentence,
                null);
            case EXIT_CODE -> new Reading(none ? sentence
                : HohenheimMicrocopy.ATTENTION_DETAIL.of("error_cause_exit_code").withArg("cause", sentence)
                    .withArg("code", detail), null);
            case MESSAGE -> new Reading(switch (cause.errorPhase()) {
                case START -> HohenheimMicrocopy.ATTENTION_DETAIL.of("start_failed_worded");
                case RUNNING -> sentence;
            }, none ? null : technically(detail));
            case PLAIN, NONE -> new Reading(sentence, null);
        };
        return why == null ? reading : new Reading(stoppedOn(reading.worded(), instance, why), reading.technical());
    }

    /**
     * @return why nothing restarted the workload and what to do about it, null when no restart was in question: its
     *         crash policy (and whether it would restart it now), or crash-loop protection
     */
    private static @Nullable Microcopy withheldWhy(HohenheimActivityAction.@NonNull RestartWithheld withheld,
                                                  @NonNull Row instance) {
        return switch (withheld) {
            case NONE -> null;
            case BY_CRASH_LOOP -> HohenheimMicrocopy.ATTENTION_DETAIL.of("restart_withheld_crash_loop");
            case BY_POLICY -> InstanceModel.CRASH_RESTART.equals(instance.get(InstanceModel.CRASH_POLICY))
                ? HohenheimMicrocopy.ATTENTION_DETAIL.of("restart_withheld_then")
                : HohenheimMicrocopy.ATTENTION_DETAIL.of("restart_withheld_policy").withArg("restart",
                    CmsSupport.enumValueLabel(InstanceModel.CRASH_POLICY, InstanceModel.CRASH_RESTART));
        };
    }

    /** @return what stopped it, on the day it was stamped, then why nothing restarted it */
    private static @NonNull Microcopy stoppedOn(@NonNull Microcopy cause, @NonNull Row instance,
                                                @NonNull Microcopy why) {
        Instant at = instance.get(InstanceModel.STOPPAGE_AT);
        return at == null
            ? HohenheimMicrocopy.ATTENTION_DETAIL.of("error_stopped_why").withArg("cause", cause).withArg("why", why)
            : HohenheimMicrocopy.ATTENTION_DETAIL.of("error_stopped_on").withArg("cause", cause)
                .withArg("date", DAY.format(at)).withArg("why", why);
    }

    /** @return stored technical text (a daemon's or a gate's own words) as the line after the worded ones */
    static @NonNull Microcopy technically(@NonNull String detail) {
        return HohenheimMicrocopy.ATTENTION_DETAIL.of("technically").withArg("detail", detail);
    }

    /**
     * @param worded    what happened, in words
     * @param technical the failure's own message as a "Technically: ..." line, null when there is none to show
     */
    record Reading(@NonNull Microcopy worded, @Nullable Microcopy technical) {
    }

    /**
     * @return how this workload's ERROR reads: a start that failed ("Could not start") or a stop after it ran, by its
     *         stored cause's {@link HohenheimActivityAction#errorPhase()}; a stop after an error when none is stored
     */
    static AppHealth.@NonNull Stoppage stoppageOf(@NonNull Row instance) {
        HohenheimActivityAction cause = InstanceModel.stoppageCauseOf(instance);
        if (cause == null) {
            return AppHealth.Stoppage.AFTER_ERROR;
        }
        return switch (cause.errorPhase()) {
            case START -> AppHealth.Stoppage.START_FAILED;
            case RUNNING -> AppHealth.Stoppage.AFTER_ERROR;
        };
    }

    /**
     * The record whose own item is this errored workload's root, so the dashboard folds the workload's item under it.
     *
     * AIDEV-NOTE: an old engine a move left behind belongs to its database ({@link DatabaseAttention#leftoverOf}):
     * removing it is the fix, never restarting it, and the database's item names it. A start its host refused because
     * per-workload firewall rules are switched off there ({@link HohenheimActivityAction.ErrorCause#HOST_ISOLATION})
     * is its host's for as long as they stay off ({@link HostAttention#isolationUnenforced}); once they are on, a
     * restart is this workload's own fix again.
     *
     * @return the root, null when the workload's item is its own root
     */
    static @Nullable AttentionSubject rootOf(@NonNull Row instance) {
        Row database = DatabaseAttention.leftoverOf(instance);
        if (database != null) {
            return AttentionSubject.database(database.get(DatabaseModel.ID));
        }
        HohenheimActivityAction cause = InstanceModel.stoppageCauseOf(instance);
        if (cause == null) {
            return null;
        }
        return switch (cause.errorCause()) {
            case HOST_ISOLATION -> {
                int host = ServerModel.canonicalServerId(instance.get(InstanceModel.SERVER_ID));
                yield HostAttention.enforcesIsolation(host) ? null : AttentionSubject.host(host);
            }
            case NONE, PLAIN, EXIT_CODE, MESSAGE -> null;
        };
    }
}
