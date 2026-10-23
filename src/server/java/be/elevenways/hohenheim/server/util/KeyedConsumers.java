package be.elevenways.hohenheim.server.util;

import be.elevenways.protoblast.common.Blast;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A table of consumers by key, each held until its registration closes and called outside the table's lock.
 *
 * AIDEV-NOTE: the lock guards the table and nothing else: {@link #forEach} copies a key's consumers under it and calls
 * them after letting go, so a consumer may block (a channel submit waits on the link monitor) while another thread
 * holding that monitor closes a registration here (a channel's onClose), with no lock-order cycle. The price is that a
 * consumer can be called once after its registration closed, by a delivery that copied it just before; a consumer
 * that must stay quiet then checks its own state. A consumer that throws is logged and skipped: one broken viewer
 * must not fail the build or release that is announcing.
 *
 * AIDEV-NOTE: a candidate to move to protoblast: its KeyedListenerTable takes only PropertyChangeListener and
 * ChangeListener consumers, so it could not hold these.
 *
 * @param <K> the key consumers listen under
 * @param <C> the consumer type
 * @author Jelle De Loecker
 * @since 0.10.0
 */
public final class KeyedConsumers<K, C> {

    /** A registration, ended by closing it; closing twice is harmless. */
    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    /** One added consumer; removed by identity, so the same consumer added twice is two entries. */
    private static final class Entry<C> {
        private final C consumer;

        private Entry(C consumer) {
            this.consumer = consumer;
        }
    }

    private final Map<K, List<Entry<C>>> table = new HashMap<>();

    /**
     * Hold a consumer under a key until the returned registration closes.
     *
     * @return the handle that removes it
     */
    public @NonNull Registration add(@NonNull K key, @NonNull C consumer) {
        Entry<C> entry = new Entry<>(consumer);
        synchronized (this.table) {
            this.table.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
        }
        return () -> this.remove(key, entry);
    }

    /** Call {@code action} on every consumer of the key, outside the table's lock. */
    public void forEach(@NonNull K key, @NonNull Consumer<? super C> action) {
        List<Entry<C>> held;
        synchronized (this.table) {
            List<Entry<C>> entries = this.table.get(key);
            if (entries == null) {
                return;
            }
            held = new ArrayList<>(entries);
        }
        for (Entry<C> entry : held) {
            try {
                action.accept(entry.consumer);
            } catch (RuntimeException failed) {
                Blast.log("A consumer of", key, "failed and was skipped:", failed);
            }
        }
    }

    private void remove(@NonNull K key, @NonNull Entry<C> entry) {
        synchronized (this.table) {
            List<Entry<C>> entries = this.table.get(key);
            if (entries != null && entries.remove(entry) && entries.isEmpty()) {
                this.table.remove(key);
            }
        }
    }
}
