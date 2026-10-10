package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionSubject;
import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

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

    private WorkloadErrors() {
    }

    /**
     * What stopped this workload with an error as one text: the worded cause, then the technical words after it.
     *
     * @param withDetail whether the cause's stored detail (a failure's own message) may be shown; a delegated reader
     *                   gets the cause's sentence only, since a daemon's message can name the host's internals
     * @return what stopped this workload with an error, or a neutral sentence when no cause is recorded
     */
    static @NonNull Microcopy detailOf(@NonNull Row instance, boolean withDetail) {
        Reading reading = readingOf(instance, withDetail);
        return reading.technical() == null ? reading.worded()
            : Microcopy.concat(List.of(reading.worded(), Microcopy.literal(" "), reading.technical()));
    }

    /**
     * What stopped this workload with an error, worded, and the failure's own message as a separate technical line.
     *
     * AIDEV-NOTE: a recorded message is stored text in the daemon's or a gate's English ("Conflict. The container name
     * ... is already in use"), never localized copy, so it is never the sentence a reader is given first: DEP10's
     * failed starts read "What refused it: " and then that text. It follows the worded cause as "Technically: ...".
     *
     * @param withDetail whether the cause's stored detail may be shown, as {@link #detailOf}
     */
    static @NonNull Reading readingOf(@NonNull Row instance, boolean withDetail) {
        Integer id = instance.get(InstanceModel.ID);
        Row recorded = id == null ? null : newestCause(id);
        HohenheimActivityAction cause = recorded == null ? null : causeOf(recorded.get(ActivityModel.ACTION));
        if (cause == null) {
            return new Reading(HohenheimMicrocopy.ATTENTION_DETAIL.of("error_cause_unknown"), null);
        }
        Microcopy sentence = cause.happened().withArg("subject", instance.get(InstanceModel.NAME));
        String detail = withDetail ? recorded.get(ActivityModel.DETAIL) : null;
        boolean none = detail == null || detail.isBlank();
        // A start that failed is headed "could not be started" by its title (Stoppage.START_FAILED), so its detail is
        // what that meant, never that sentence again.
        return switch (cause.errorCause()) {
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
     *         recorded cause's {@link HohenheimActivityAction#errorPhase()}; a stop after an error when none is recorded
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
        return Models.get(ActivityModel.class).find()
            .where(ActivityModel.MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(ActivityModel.RECORD_ID.eq(String.valueOf(instanceId)))
            .where(ActivityModel.ACTION.in(CAUSE_IDS))
            .orderBy(ActivityModel.ID, SortOrder.DESC)
            .first();
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
