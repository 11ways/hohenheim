package be.elevenways.hohenheim.test;

import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.zenit.common.channel.Channel;
import be.elevenways.zenit.common.channel.ChannelException;
import be.elevenways.zenit.common.channel.ChannelLink;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.common.websocket.WebSocketSession;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A link shaped like zenit's ServerChannelLink where a deadlock lives: its close runs the handler's onClose UNDER the
 * monitor its submit takes.
 *
 * AIDEV-NOTE: {@link #racesClose} parks the delivering thread at the door of that monitor until the closing thread
 * holds it, the exact interleaving that hung when a registry or session lock was held across a submit; latches with a
 * bound prove both threads finish.
 *
 * @author Jelle De Loecker
 * @since 0.10.0
 */
public final class MonitorLink implements ChannelLink<Object, Object> {

    /** How long either racing thread may take. */
    public static final Duration BOUND = Duration.ofSeconds(10);

    private final @NonNull Principal principal;
    private final @NonNull Channel<Object, Object> channel;
    private final List<Object> submitted = new ArrayList<>();
    private final CountDownLatch holding = new CountDownLatch(1);
    private volatile @Nullable CountDownLatch submitting;
    private volatile @Nullable Runnable onClose;
    private boolean closed;

    public MonitorLink(@NonNull Principal principal, @NonNull Channel<Object, Object> channel) {
        this.principal = principal;
        this.channel = channel;
    }

    /** Run {@code onClose} under this link's monitor when it closes, as ServerChannelLink runs its handler's. */
    public @NonNull MonitorLink onClose(@NonNull Runnable onClose) {
        this.onClose = onClose;
        return this;
    }

    /** Close {@code link} while {@code work} reaches it from another thread, both within the bound. */
    public static void racesClose(@NonNull MonitorLink link, @NonNull Runnable work, @NonNull String what)
            throws InterruptedException {
        link.submitting = new CountDownLatch(1);
        CountDownLatch workDone = new CountDownLatch(1);
        CountDownLatch closeDone = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        JobRunner.startVirtualThread(() -> {
            try {
                work.run();
            } catch (Throwable failed) {
                failure.set(failed);
            } finally {
                workDone.countDown();
            }
        });
        JobRunner.startVirtualThread(() -> {
            try {
                if (link.awaitSubmitting()) {
                    link.close();
                }
            } catch (Throwable failed) {
                failure.set(failed);
            } finally {
                closeDone.countDown();
            }
        });
        assertThat(workDone.await(BOUND.toMillis(), TimeUnit.MILLISECONDS))
            .as(what + ": the work reaching the closing link finished").isTrue();
        assertThat(closeDone.await(BOUND.toMillis(), TimeUnit.MILLISECONDS))
            .as(what + ": the close finished").isTrue();
        assertThat(failure.get()).as(what + ": neither thread failed").isNull();
        assertThat(link.reachedTheDoor()).as(what + ": the work reached the link while it closed").isTrue();
        assertThat(link.isClosed()).as(what + ": the link closed").isTrue();
    }

    private boolean awaitSubmitting() throws InterruptedException {
        CountDownLatch gate = this.submitting;
        return gate != null && gate.await(BOUND.toMillis(), TimeUnit.MILLISECONDS);
    }

    private boolean reachedTheDoor() {
        CountDownLatch gate = this.submitting;
        return gate != null && gate.getCount() == 0;
    }

    @Override
    public void submit(@NonNull Object message) {
        CountDownLatch gate = this.submitting;
        if (gate != null && gate.getCount() > 0) {
            gate.countDown();
            try {
                this.holding.await(BOUND.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this) {
            if (this.closed) {
                throw ChannelException.closedLink("monitor-link");
            }
            this.submitted.add(message);
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (this.closed) {
                return;
            }
            this.closed = true;
            this.holding.countDown();
            Runnable closing = this.onClose;
            if (closing != null) {
                closing.run();
            }
        }
    }

    @Override
    public synchronized boolean isOpen() {
        return !this.closed;
    }

    @Override
    public synchronized boolean isClosed() {
        return this.closed;
    }

    @Override
    public @NonNull Channel<Object, Object> getChannel() {
        return this.channel;
    }

    @Override
    public @NonNull String getLinkId() {
        return "monitor-link";
    }

    @Override
    public @Nullable WebSocketSession getSession() {
        return null;
    }

    @Override
    public @Nullable Principal getPrincipal() {
        return this.principal;
    }

    @Override
    public void setAttribute(@NonNull String key, @Nullable Object value) {
    }

    @Override
    public @Nullable Object getAttribute(@NonNull String key) {
        return null;
    }
}
