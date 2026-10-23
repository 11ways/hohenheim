package be.elevenways.hohenheim.server.util;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayDeque;
import java.util.function.Consumer;

/**
 * One viewer's ordered delivery: items are queued from any thread and handed to the sink in queue order, one at a time.
 *
 * AIDEV-NOTE: {@link #offer} only queues, under this outbox's own lock, which is a LEAF (nothing is called while it is
 * held), so it is safe under any other monitor. {@link #drain} is where the sink runs and it must be called holding NO
 * lock: the sink may block (a channel submit waits on the link monitor an onClose holds). One thread drains at a time;
 * a drain that finds another one running returns at once, and the running one takes what was queued meanwhile, so
 * every item is delivered once, in offer order, as long as each offer is followed by a drain. {@link #close} never
 * waits for a drain in progress: it drops what is queued and the drainer stops after its current item.
 *
 * @param <T> the item type
 * @author Jelle De Loecker
 * @since 0.10.0
 */
public final class SerialOutbox<T> {

    private final @NonNull Consumer<T> sink;
    private final ArrayDeque<T> queue = new ArrayDeque<>();
    private boolean draining;
    private boolean closed;

    public SerialOutbox(@NonNull Consumer<T> sink) {
        this.sink = sink;
    }

    /** @return false when the outbox is closed and the item was dropped */
    public boolean offer(@NonNull T item) {
        synchronized (this.queue) {
            if (this.closed) {
                return false;
            }
            this.queue.add(item);
            return true;
        }
    }

    /** Hand every queued item to the sink, unless another thread is already doing so; call holding no lock. */
    public void drain() {
        synchronized (this.queue) {
            if (this.draining || this.closed) {
                return;
            }
            this.draining = true;
        }
        boolean released = false;
        try {
            while (true) {
                T next;
                synchronized (this.queue) {
                    next = this.closed ? null : this.queue.poll();
                    if (next == null) {
                        this.draining = false;
                        released = true;
                        return;
                    }
                }
                this.sink.accept(next);
            }
        } finally {
            if (!released) {
                synchronized (this.queue) {
                    this.draining = false;
                }
            }
        }
    }

    /** Drop what is queued and refuse what is offered after; never waits for a drain in progress. */
    public void close() {
        synchronized (this.queue) {
            this.closed = true;
            this.queue.clear();
        }
    }
}
