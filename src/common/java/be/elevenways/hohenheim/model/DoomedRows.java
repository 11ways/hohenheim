package be.elevenways.hohenheim.model;

import be.elevenways.zenit.common.orm.datasource.context.RemoveFromDatasource;
import be.elevenways.zenit.common.orm.model.Schema;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * THE before-remove / after-remove handover: read what matters of the rows a delete is about
 * to remove while they still exist, and act on it once the delete succeeded.
 *
 * AIDEV-NOTE: a remove context carries CRITERIA, never rows, and the rows are gone by the
 * time an after-remove hook runs, so every consumer that must act AFTER a delete (release a
 * quota charge, park a port claim, sweep a network) needs the same pair: capture in the
 * before hook, stash on the shared context, consume in the after hook. That pair was
 * hand-written per consumer, each with its own attribute key and its own re-read of the
 * doomed rows; the rows now come from {@link RemoveFromDatasource#doomedRows()} (read once
 * per delete, trashed rows included) and the stash from here. A consumer that can act
 * BEFORE the delete (a refusal, a cascade) needs neither: it reads
 * {@code context.doomedRows()} directly.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class DoomedRows {

    private static int registrations;

    private DoomedRows() {
    }

    /**
     * Reads what matters of the doomed rows while they still exist; it blocks (it reads them).
     *
     * AIDEV-NOTE: a standalone SAM on purpose, never a java.util.function type: on the browser it suspends, and a
     * suspending JDK callback lets every call through that JDK interface in the bundle suspend (protoblast's
     * SyncFamilyVerifierPlugin fails such a bundle).
     */
    @FunctionalInterface
    public interface Capture<T> {
        @Nullable T capture(@NonNull RemoveFromDatasource context);
    }

    /** Acts on a capture once the delete ran; it blocks for the same reason, a standalone SAM like {@link Capture}. */
    @FunctionalInterface
    public interface AfterRemove<T> {
        void accept(@NonNull RemoveFromDatasource context, @NonNull T captured);
    }

    /**
     * Register the pair on {@code schema}: {@code capture} reads the pending delete (through
     * {@code doomedRows()} or {@code doomedPrimaryKeys()}) before it runs, and
     * {@code afterRemove} receives its answer once the delete ran.
     *
     * @param capture     answers null when there is nothing to do after the delete
     * @param afterRemove runs only when {@code capture} answered non-null
     */
    @SuppressWarnings("unchecked")
    public static <T> void handOver(@NonNull Schema schema,
                                    @NonNull Capture<T> capture,
                                    @NonNull AfterRemove<T> afterRemove) {
        String key = nextKey();
        schema.addBeforeRemoveHook(context -> {
            T captured = capture.capture(context);
            if (captured != null) {
                context.setAttribute(key, captured);
            }
        });
        schema.addAfterRemoveHook(context -> {
            Object captured = context.getAttribute(key);
            if (captured != null) {
                afterRemove.accept(context, (T) captured);
            }
        });
    }

    /** One attribute key per registration, so two pairs on one schema never share a stash. */
    private static synchronized @NonNull String nextKey() {
        registrations++;
        return "hohenheim.doomed-rows." + registrations;
    }
}
