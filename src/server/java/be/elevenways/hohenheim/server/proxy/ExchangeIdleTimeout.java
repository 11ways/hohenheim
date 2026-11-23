package be.elevenways.hohenheim.server.proxy;

import be.elevenways.protoblast.common.time.Now;
import io.undertow.client.ClientConnection;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;
import io.undertow.util.StatusCodes;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.xnio.IoUtils;
import org.xnio.XnioExecutor;
import org.xnio.channels.StreamSinkChannel;
import org.xnio.channels.StreamSourceChannel;
import org.xnio.conduits.AbstractStreamSinkConduit;
import org.xnio.conduits.AbstractStreamSourceConduit;
import org.xnio.conduits.StreamSinkConduit;
import org.xnio.conduits.StreamSourceConduit;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ends a proxied exchange on which no byte moved in either direction for a site's idle time.
 *
 * AIDEV-NOTE: per exchange, not Undertow's per-listener IDLE_TIMEOUT: that one is per connection, so on HTTP/2 a
 * silent stream would live while a sibling moves and a cut would take the siblings with it. Unset installs nothing
 * (no state, no wrapper, no timer); the only cost left on the request path is the attachment lookups in
 * {@link UpstreamProxyClient}. The cut follows Undertow's own request-timeout cancel: close the upstream, then close
 * the downstream connection when the response started (one stream on HTTP/2) or answer 504. Closing the upstream runs
 * ProxyHandler's failure callback, which answers 503 for a non-idempotent request (as on its own timeout) and REDIALS
 * an idempotent one; the cut flag is set before that close so the redial never dials (see
 * {@link #refusesDial}). An upgraded exchange completes at its 101 and so leaves the tunnel uncovered, as
 * request_timeout does. The upstream close is Undertow's clean close: with a request in flight it sends no FIN and
 * drains for io.undertow.max-drain-time (10 s) before it closes for good, exactly as on Undertow's own timeout.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class ExchangeIdleTimeout {

    private static final AttachmentKey<State> STATE = AttachmentKey.create(State.class);

    /** Exchanges carrying an idle state that has not been released yet. */
    private static final AtomicInteger LIVE = new AtomicInteger();

    /** Idle states ever installed, so a test can prove an unset site installs none. */
    private static final AtomicLong INSTALLED = new AtomicLong();

    private ExchangeIdleTimeout() {
    }

    /**
     * Arms the idle timer on an exchange about to be proxied; a no-op when the site sets no idle time.
     *
     * Must run BEFORE {@link EagerResponseCommit#install}, so this response wrapper sits innermost and sees the
     * early commit's flush.
     *
     * @param idleMillis the site's idle time; 0 or less installs nothing
     */
    static void install(@NonNull HttpServerExchange exchange, int idleMillis) {
        if (idleMillis <= 0) {
            return;
        }
        State state = new State(exchange, TimeUnit.MILLISECONDS.toNanos(idleMillis));
        exchange.putAttachment(STATE, state);
        exchange.addRequestWrapper((factory, ex) -> new CountingSource(factory.create(), state));
        exchange.addResponseWrapper((factory, ex) -> new CountingSink(factory.create(), state));
        exchange.addExchangeCompleteListener((ex, next) -> {
            state.release();
            next.proceed();
        });
        INSTALLED.incrementAndGet();
        LIVE.incrementAndGet();
        state.arm(state.idleNanos);
    }

    /**
     * Remembers the upstream connection of an exchange so a cut can close it.
     *
     * @return false when the exchange was already cut while the dial was in flight: the caller closes the connection
     */
    static boolean upstreamOpened(@NonNull HttpServerExchange exchange, @NonNull ClientConnection connection) {
        State state = exchange.getAttachment(STATE);
        if (state == null) {
            return true;
        }
        return state.remember(connection);
    }

    /** @return whether the exchange was cut, so no dial (a retry included) may start or report for it */
    static boolean refusesDial(@NonNull HttpServerExchange exchange) {
        State state = exchange.getAttachment(STATE);
        return state != null && state.cut;
    }

    /** @return how many exchanges carry an idle state not yet released */
    public static int liveStates() {
        return LIVE.get();
    }

    /** @return how many idle states were ever installed in this process */
    public static long installedStates() {
        return INSTALLED.get();
    }

    /** One exchange's idle bookkeeping: the last byte moved, the armed check and the upstream to close. */
    private static final class State {

        private final HttpServerExchange exchange;
        private final long idleNanos;
        private volatile long lastActivity = Now.monotonicNanos();
        private volatile @Nullable ClientConnection upstream;
        private volatile XnioExecutor.@Nullable Key key;
        private volatile boolean cut;
        private volatile boolean released;

        State(HttpServerExchange exchange, long idleNanos) {
            this.exchange = exchange;
            this.idleNanos = idleNanos;
        }

        void moved() {
            this.lastActivity = Now.monotonicNanos();
        }

        int count(int moved) {
            if (moved > 0) {
                moved();
            }
            return moved;
        }

        long count(long moved) {
            if (moved > 0) {
                moved();
            }
            return moved;
        }

        synchronized boolean remember(ClientConnection connection) {
            if (this.cut) {
                return false;
            }
            if (!this.released) {
                this.upstream = connection;
            }
            return true;
        }

        /** Arms the next check, unless the state was released meanwhile: nothing re-arms after a release. */
        synchronized void arm(long nanos) {
            if (this.released) {
                return;
            }
            this.key = this.exchange.getIoThread().executeAfter(this::check,
                Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos) + 1), TimeUnit.MILLISECONDS);
        }

        /** Runs on the exchange's IO thread: re-arms for the remaining time while bytes move, else cuts. */
        private void check() {
            this.key = null;
            if (this.released) {
                return;
            }
            if (this.exchange.isComplete() || !this.exchange.getConnection().isOpen()) {
                release();
                return;
            }
            long remaining = this.idleNanos - (Now.monotonicNanos() - this.lastActivity);
            if (remaining > 0) {
                arm(remaining);
                return;
            }
            cutNow();
        }

        private void cutNow() {
            ClientConnection connection;
            synchronized (this) {
                this.cut = true;
                connection = this.upstream;
            }
            release();
            IoUtils.safeClose(connection);
            if (this.exchange.isResponseStarted()) {
                IoUtils.safeClose(this.exchange.getConnection());
            } else {
                this.exchange.setStatusCode(StatusCodes.GATEWAY_TIME_OUT);
                this.exchange.endExchange();
            }
        }

        /** Cancels the armed check and drops the upstream reference; never re-arms afterwards. */
        void release() {
            XnioExecutor.Key armed;
            synchronized (this) {
                if (this.released) {
                    return;
                }
                this.released = true;
                this.upstream = null;
                armed = this.key;
                this.key = null;
            }
            if (armed != null) {
                armed.remove();
            }
            LIVE.decrementAndGet();
        }
    }

    /** Counts request-body bytes the proxy reads from the client. */
    private static final class CountingSource extends AbstractStreamSourceConduit<StreamSourceConduit> {

        private final State state;

        CountingSource(StreamSourceConduit next, State state) {
            super(next);
            this.state = state;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            return this.state.count(next.read(dst));
        }

        @Override
        public long read(ByteBuffer[] dsts, int offs, int len) throws IOException {
            return this.state.count(next.read(dsts, offs, len));
        }

        @Override
        public long transferTo(long position, long count, FileChannel target) throws IOException {
            return this.state.count(next.transferTo(position, count, target));
        }

        @Override
        public long transferTo(long count, ByteBuffer throughBuffer, StreamSinkChannel target) throws IOException {
            return this.state.count(next.transferTo(count, throughBuffer, target));
        }
    }

    /** Counts response bytes the proxy writes to the client; a flush (the early header commit) counts too. */
    private static final class CountingSink extends AbstractStreamSinkConduit<StreamSinkConduit> {

        private final State state;

        CountingSink(StreamSinkConduit next, State state) {
            super(next);
            this.state = state;
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            return this.state.count(next.write(src));
        }

        @Override
        public long write(ByteBuffer[] srcs, int offs, int len) throws IOException {
            return this.state.count(next.write(srcs, offs, len));
        }

        @Override
        public int writeFinal(ByteBuffer src) throws IOException {
            return this.state.count(next.writeFinal(src));
        }

        @Override
        public long writeFinal(ByteBuffer[] srcs, int offs, int len) throws IOException {
            return this.state.count(next.writeFinal(srcs, offs, len));
        }

        @Override
        public long transferFrom(FileChannel src, long position, long count) throws IOException {
            return this.state.count(next.transferFrom(src, position, count));
        }

        @Override
        public long transferFrom(StreamSourceChannel source, long count, ByteBuffer throughBuffer)
                throws IOException {
            return this.state.count(next.transferFrom(source, count, throughBuffer));
        }

        /**
         * A flush counts as a write.
         *
         * AIDEV-NOTE: the early header commit (EagerResponseCommit) writes no body byte through this conduit; the
         * headers are serialized below it, on this flush. Undertow's transfer flushes only after it wrote, so a flush
         * never stands for a silence.
         */
        @Override
        public boolean flush() throws IOException {
            boolean flushed = next.flush();
            if (flushed) {
                this.state.moved();
            }
            return flushed;
        }
    }
}
