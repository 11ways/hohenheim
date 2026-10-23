package be.elevenways.hohenheim.server.application;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.protoblast.common.holder.KeyedListenerTable;
import be.elevenways.protoblast.common.holder.Subscription;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;
import java.util.function.Consumer;

/**
 * The release step log of each application as the engine writes it: what a live viewer of the application hears.
 *
 * AIDEV-NOTE: a step frame carries the WHOLE step log (a few dozen lines), never a delta, so a viewer can only ever
 * replace what it holds. The engine publishes each step log after its write is stored, in write order, so a watcher
 * that attaches and THEN reads {@link #newestStepLog} reads at least what every publish it missed carried; it keeps
 * that read only when no publish reached it meanwhile, which would be as new or newer. Nothing here reads the database
 * or calls a watcher under a lock (KeyedListenerTable.deliver's rule).
 *
 * @author Jelle De Loecker
 * @since 0.10.0
 */
public final class DeployProgress {

    private static final KeyedListenerTable<Integer, Consumer<String>> WATCHERS = KeyedListenerTable.delivering();

    private DeployProgress() {
    }

    /**
     * Hear every step log of the application written from now on; read {@link #newestStepLog} afterwards for the one
     * written before.
     *
     * @return the handle that stops watching
     */
    public static @NonNull Subscription watch(int applicationId, @NonNull Consumer<String> watcher) {
        return WATCHERS.register(applicationId, watcher);
    }

    /** Hand a stored step log to the application's watchers; called by the engine after the write. */
    static void published(int applicationId, @NonNull String stepLog) {
        WATCHERS.deliver(applicationId, watcher -> watcher.accept(stepLog));
    }

    /** @return the step log of the application's newest release operation, or "" */
    static @NonNull String newestStepLog(int applicationId) {
        List<Row> newest = Models.get(ReleaseOperationModel.class)
            .findForOwner(InstanceModel.MODEL_ID.toString(), applicationId, 1);
        String log = newest.isEmpty() ? null : newest.get(0).get(ReleaseOperationModel.STEP_LOG);
        return log == null ? "" : log;
    }
}
