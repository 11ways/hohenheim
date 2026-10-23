package be.elevenways.hohenheim.server.build;

import be.elevenways.hohenheim.server.util.KeyedConsumers;
import be.elevenways.protoblast.common.registry.Identifier;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * The builds running NOW, by the record they build for: what a live viewer of that record taps.
 *
 * AIDEV-NOTE: keyed by OWNER, not by build id, because a page follows a record and cannot know the id of a build that
 * has not started yet. A watcher hears every build of the owner start and end. It is told nothing about the build
 * running when it attaches: it asks {@link #of} AFTER {@link #watch} returns, so a build registered in between is
 * heard twice (start and {@link #of}) and never missed -- a watcher treats a second start of the same log as none. A
 * build's start is announced after it is live and its end after it no longer is, both outside every lock here, so a
 * watcher may block in its callback (KeyedConsumers' rule).
 *
 * @author Jelle De Loecker
 * @since 0.10.0
 */
public final class LiveBuildLogs {

    private record Owner(@NonNull String model, int id) {}

    /** What hears an owner's builds come and go; called outside every lock of this class. */
    public interface Watcher {

        /** A build of the owner is live: its log stores and taps from now on. */
        void started(@NonNull BuildLog log);

        /** The build ended: its log holds its final text, the text its operation row stores. */
        void ended(@NonNull BuildLog log);
    }

    private static final Map<Owner, BuildLog> LIVE = new HashMap<>();
    private static final KeyedConsumers<Owner, Watcher> WATCHERS = new KeyedConsumers<>();

    private LiveBuildLogs() {
    }

    /**
     * Mark a build's log live for its owner until the returned handle closes, announcing its start and its end to the
     * owner's watchers.
     *
     * @return the handle that ends the registration (in the build's own finally)
     * @throws IllegalStateException when a build is already live for the owner: the owner's operation lock lets one
     *                               build run at a time, so an overlap is a bug, never a build to replace
     */
    public static KeyedConsumers.@NonNull Registration register(@NonNull Identifier forModel, int forId,
                                                                @NonNull BuildLog log) {
        Owner owner = new Owner(forModel.toString(), forId);
        synchronized (LIVE) {
            if (LIVE.containsKey(owner)) {
                throw new IllegalStateException("A build is already live for " + owner.model() + " #" + owner.id());
            }
            LIVE.put(owner, log);
        }
        WATCHERS.forEach(owner, watcher -> watcher.started(log));
        return () -> {
            synchronized (LIVE) {
                if (!LIVE.remove(owner, log)) {
                    return;
                }
            }
            WATCHERS.forEach(owner, watcher -> watcher.ended(log));
        };
    }

    /** @return the owner's running build's log, or null when none runs */
    public static @Nullable BuildLog of(@NonNull Identifier forModel, int forId) {
        synchronized (LIVE) {
            return LIVE.get(new Owner(forModel.toString(), forId));
        }
    }

    /**
     * Hear every build of the owner start and end from now on; ask {@link #of} afterwards for the one running now.
     *
     * @return the handle that stops watching
     */
    public static KeyedConsumers.@NonNull Registration watch(@NonNull Identifier forModel, int forId,
                                                             @NonNull Watcher watcher) {
        return WATCHERS.add(new Owner(forModel.toString(), forId), watcher);
    }
}
