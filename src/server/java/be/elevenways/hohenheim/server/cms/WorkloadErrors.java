package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionSubject;
import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.instance.InstanceStatusReconciler;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.orm.query.SortOrder;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Why a workload holds the ERROR status, in words: the cause {@code InstanceOperationGuard.stampError} recorded on
 * its activity beside the status, read by the dashboard's "stopped after an error" item and the workload's verdict.
 *
 * AIDEV-NOTE: a failed start, a failed restore, a lost migration and a crash all stamp ERROR, so the status alone says
 * nothing about what happened; the old detail ("The workload exited unexpectedly and was not restarted") claimed a
 * crash for every one of them. Every ERROR stamp records its cause, so the newest cause row is the current one; where
 * none is found (an activity log switched off or pruned, a status written by hand) the words say so, never a guess.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
final class WorkloadErrors {

    /** The stored action ids of every verb that is a cause, read once. */
    private static final List<String> CAUSE_IDS = causeIds();

    /** The day a stop was recorded on, as the overview names it. */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    private WorkloadErrors() {
    }

    /**
     * What stopped this workload with an error, worded, and the failure's own message as a separate technical line.
     * A stop of a running workload also says when it stopped, why nothing restarted it and what to do
     * ({@link HohenheimActivityAction#restartWithheld()}).
     *
     * AIDEV-NOTE: a recorded message is stored text in the daemon's or a gate's English ("Conflict. The container name
     * ... is already in use"), never localized copy, so it is never the sentence a reader is given first: failed
     * starts once read "What refused it: " and then that text. It follows the worded cause as "Technically: ...".
     *
     * AIDEV-NOTE: a record stamped ERROR before causes were recorded (2026-10-08) has only the reconciler's correction
     * row; it is read as the stop it records ({@link InstanceStatusReconciler#correctionOf}), never guessed past it.
     *
     * @param withDetail whether the cause's stored detail (a failure's own message) may be shown; a delegated reader
     *                   gets the cause's sentence only, since a daemon's message can name the host's internals
     */
    static @NonNull Reading readingOf(@NonNull Row instance, boolean withDetail) {
        Integer id = instance.get(InstanceModel.ID);
        Row recorded = id == null ? null : newestCause(id);
        HohenheimActivityAction cause = recorded == null ? null : causeOf(recorded.get(ActivityModel.ACTION));
        Object name = instance.get(InstanceModel.NAME);
        if (cause == null) {
            // No cause recorded: the reconciler's correction to ERROR, when there is one, is the stop. It corrected to
            // ERROR only when no restart followed, by crash-loop protection (its note says so) or the crash policy.
            Row corrected = id == null ? null : newestCorrectionToError(id);
            if (corrected == null) {
                return new Reading(HohenheimMicrocopy.ATTENTION_DETAIL.of("error_cause_unknown"), null);
            }
            boolean crashLoop = Objects.requireNonNull(
                InstanceStatusReconciler.correctionOf(corrected.get(ActivityModel.DETAIL))).crashLoop();
            Microcopy found = HohenheimMicrocopy.ATTENTION_DETAIL.of("error_found_stopped").withArg("subject", name);
            return new Reading(stoppedOn(found, corrected, Objects.requireNonNull(withheldWhy(crashLoop
                ? HohenheimActivityAction.RestartWithheld.BY_CRASH_LOOP
                : HohenheimActivityAction.RestartWithheld.BY_POLICY, instance))), null);
        }
        Microcopy sentence = cause.happened().withArg("subject", name);
        String detail = withDetail ? recorded.get(ActivityModel.DETAIL) : null;
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
        Microcopy why = withheldWhy(cause.restartWithheld(), instance);
        return why == null ? reading : new Reading(stoppedOn(reading.worded(), recorded, why), reading.technical());
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

    /** @return what stopped it, on the day its row was recorded, then why nothing restarted it */
    private static @NonNull Microcopy stoppedOn(@NonNull Microcopy cause, @NonNull Row recorded,
                                                @NonNull Microcopy why) {
        Instant at = recorded.get(ActivityModel.CREATED_AT);
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
     *         recorded cause's {@link HohenheimActivityAction#errorPhase()}; a stop after an error when none is
     *         recorded
     */
    static AppHealth.@NonNull Stoppage stoppageOf(@NonNull Row instance) {
        HohenheimActivityAction cause = causeOf(instance);
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
        HohenheimActivityAction cause = causeOf(instance);
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

    /** @return the cause verb recorded for this workload's ERROR, null when none is */
    private static @Nullable HohenheimActivityAction causeOf(@NonNull Row instance) {
        Integer id = instance.get(InstanceModel.ID);
        Row recorded = id == null ? null : newestCause(id);
        return recorded == null ? null : causeOf(recorded.get(ActivityModel.ACTION));
    }

    /** @return the newest cause row recorded on this workload, null when none is */
    private static @Nullable Row newestCause(int instanceId) {
        if (Models.get(ActivityModel.MODEL_ID) == null) {
            return null;
        }
        return activityOf(instanceId).where(ActivityModel.ACTION.in(CAUSE_IDS)).first();
    }

    /** @return the newest reconciler correction of this workload, when it settled it to ERROR; null otherwise */
    private static @Nullable Row newestCorrectionToError(int instanceId) {
        if (Models.get(ActivityModel.MODEL_ID) == null) {
            return null;
        }
        Row newest = activityOf(instanceId)
            .where(ActivityModel.ACTION.eq(HohenheimActivityAction.RECONCILED.id().toString())).first();
        InstanceStatusReconciler.Correction correction = newest == null ? null
            : InstanceStatusReconciler.correctionOf(newest.get(ActivityModel.DETAIL));
        return correction != null && InstanceModel.STATUS_ERROR.equals(correction.settled()) ? newest : null;
    }

    /** @return this workload's activity rows, newest first */
    private static @NonNull QueryBuilder<Row> activityOf(int instanceId) {
        return Models.get(ActivityModel.class).find()
            .where(ActivityModel.MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(ActivityModel.RECORD_ID.eq(String.valueOf(instanceId)))
            .orderBy(ActivityModel.ID, SortOrder.DESC);
    }

    /** @return the cause verb a stored action id names, null for anything else */
    private static @Nullable HohenheimActivityAction causeOf(@Nullable String stored) {
        for (HohenheimActivityAction action : HohenheimActivityAction.values()) {
            if (action.errorCause().isCause() && action.id().toString().equals(stored)) {
                return action;
            }
        }
        return null;
    }

    private static @NonNull List<String> causeIds() {
        List<String> ids = new ArrayList<>();
        for (HohenheimActivityAction action : HohenheimActivityAction.values()) {
            if (action.errorCause().isCause()) {
                ids.add(action.id().toString());
            }
        }
        return List.copyOf(ids);
    }
}
