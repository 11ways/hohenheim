package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimActivityAction;
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
     * @param withDetail whether the cause's stored detail (a failure's own message) may be shown; a delegated reader
     *                   gets the cause's sentence only, since a daemon's message can name the host's internals
     * @return what stopped this workload with an error, or a neutral sentence when no cause is recorded
     */
    static @NonNull Microcopy detailOf(@NonNull Row instance, boolean withDetail) {
        Integer id = instance.get(InstanceModel.ID);
        Row recorded = id == null ? null : newestCause(id);
        HohenheimActivityAction cause = recorded == null ? null : causeOf(recorded.get(ActivityModel.ACTION));
        if (cause == null) {
            return copy("error_cause_unknown");
        }
        Microcopy sentence = cause.happened().withArg("subject", instance.get(InstanceModel.NAME));
        String detail = withDetail ? recorded.get(ActivityModel.DETAIL) : null;
        if (detail == null || detail.isBlank()) {
            return sentence;
        }
        return switch (cause.errorCause()) {
            case EXIT_CODE -> copy("error_cause_exit_code").withArg("cause", sentence).withArg("code", detail);
            case MESSAGE -> copy("error_cause_reason").withArg("cause", sentence).withArg("reason", detail);
            case PLAIN, NONE -> sentence;
        };
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

    private static @NonNull Microcopy copy(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "attention_detail");
    }
}
