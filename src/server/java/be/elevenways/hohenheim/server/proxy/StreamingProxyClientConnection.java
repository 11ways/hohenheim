package be.elevenways.hohenheim.server.proxy;

import io.undertow.client.ClientCallback;
import io.undertow.client.ClientConnection;
import io.undertow.client.ClientExchange;
import io.undertow.client.ClientRequest;
import io.undertow.client.ClientResponse;
import io.undertow.client.ClientStatistics;
import io.undertow.client.ContinueNotification;
import io.undertow.client.PushCallback;
import io.undertow.connector.ByteBufferPool;
import io.undertow.protocols.http2.Http2StreamSourceChannel;
import io.undertow.server.protocol.http.HttpAttachments;
import io.undertow.util.AttachmentKey;
import io.undertow.util.AttachmentList;
import org.xnio.ChannelListener;
import org.xnio.Option;
import org.xnio.StreamConnection;
import org.xnio.XnioIoThread;
import org.xnio.XnioWorker;
import org.xnio.channels.StreamSinkChannel;
import org.xnio.channels.StreamSourceChannel;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Adapts Undertow's client for streaming upstreams, which stock Undertow proxying cannot
 * carry. Two defects, both fatal to gRPC:
 *
 * <p>Response trailers: Undertow's h2 client DROPS them (nothing in stock Undertow calls
 * setTrailersHandler), so gRPC's grpc-status never reaches the downstream client. The
 * captured map is stored under REQUEST_TRAILERS on the client exchange because
 * ProxyHandler's trailer-copy listener reads that key from its source attachable when
 * emitting response trailers downstream.
 *
 * <p>Request commit: the upstream request is never put on the wire until a request BODY
 * byte shows up -- see {@link #commitRequestHeaders}.
 */
final class StreamingProxyClientConnection implements ClientConnection {

    private final ClientConnection delegate;

    StreamingProxyClientConnection(ClientConnection delegate) {
        this.delegate = delegate;
    }

    @Override
    public void sendRequest(ClientRequest request, ClientCallback<ClientExchange> callback) {
        delegate.sendRequest(request, new ClientCallback<>() {
            @Override
            public void completed(ClientExchange exchange) {
                callback.completed(new TrailerCapturingExchange(exchange));
                // The callback above is ProxyHandler wiring up the request-body relay, so
                // the request is fully described by now and safe to put on the wire.
                commitRequestHeaders(exchange);
            }

            @Override
            public void failed(IOException e) {
                callback.failed(e);
            }
        });
    }

    /**
     * Sends the upstream request headers without waiting for a request body.
     *
     * <p>AIDEV-NOTE: Undertow writes a request's HEADERS frame lazily, on the first write to
     * the request channel, and for an incomplete downstream request ProxyHandler only calls
     * {@code Transfer.initiateTransfer}, which writes NOTHING while no body bytes are
     * buffered (it reads 0 bytes and breaks out). A bidirectional gRPC client opens its
     * stream with headers alone and sends no message until it has one, so the upstream never
     * saw the request at all: NetBird's signal stream hung for 50s and gave up, and the
     * upstream connection was observed on the wire carrying SETTINGS and GOAWAY but never a
     * HEADERS frame. Only the requiresContinueResponse path in ProxyHandler flushes, which
     * is why ordinary unary gRPC and normal requests (complete on arrival, so ProxyHandler
     * shuts down writes and flushes) always worked.
     *
     * <p>Deliberately a flush and NOT shutdownWrites: the request half must stay open for a
     * client that will send messages later. A flush is a no-op once anything else has
     * written, so complete requests are unaffected.
     */
    private static void commitRequestHeaders(ClientExchange exchange) {
        XnioIoThread ioThread = exchange.getConnection().getIoThread();
        ioThread.execute(() -> {
            try {
                exchange.getRequestChannel().flush();
            } catch (IOException | RuntimeException ignored) {
                // Already failing or finished; ProxyHandler owns the error path and a failed
                // courtesy flush must never surface as a new error.
            }
        });
    }

    private static final class TrailerCapturingExchange implements ClientExchange {

        private final ClientExchange delegate;

        TrailerCapturingExchange(ClientExchange delegate) {
            this.delegate = delegate;
        }

        @Override
        public void setResponseListener(ClientCallback<ClientExchange> listener) {
            delegate.setResponseListener(new ClientCallback<>() {
                @Override
                public void completed(ClientExchange response) {
                    StreamSourceChannel channel = response.getResponseChannel();
                    if (channel instanceof Http2StreamSourceChannel h2) {
                        h2.setTrailersHandler(trailers ->
                            response.putAttachment(HttpAttachments.REQUEST_TRAILERS, trailers));
                    }
                    listener.completed(TrailerCapturingExchange.this);
                }

                @Override
                public void failed(IOException e) {
                    listener.failed(e);
                }
            });
        }

        @Override
        public void setContinueHandler(ContinueNotification continueHandler) {
            delegate.setContinueHandler(continueHandler);
        }

        @Override
        public void setPushHandler(PushCallback pushCallback) {
            delegate.setPushHandler(pushCallback);
        }

        @Override
        public StreamSinkChannel getRequestChannel() {
            return delegate.getRequestChannel();
        }

        @Override
        public StreamSourceChannel getResponseChannel() {
            return delegate.getResponseChannel();
        }

        @Override
        public ClientRequest getRequest() {
            return delegate.getRequest();
        }

        @Override
        public ClientResponse getResponse() {
            return delegate.getResponse();
        }

        @Override
        public ClientResponse getContinueResponse() {
            return delegate.getContinueResponse();
        }

        @Override
        public ClientConnection getConnection() {
            return delegate.getConnection();
        }

        @Override
        public <T> T getAttachment(AttachmentKey<T> key) {
            return delegate.getAttachment(key);
        }

        @Override
        public <T> List<T> getAttachmentList(AttachmentKey<? extends List<T>> key) {
            return delegate.getAttachmentList(key);
        }

        @Override
        public <T> T putAttachment(AttachmentKey<T> key, T value) {
            return delegate.putAttachment(key, value);
        }

        @Override
        public <T> T removeAttachment(AttachmentKey<T> key) {
            return delegate.removeAttachment(key);
        }

        @Override
        public <T> void addToAttachmentList(AttachmentKey<AttachmentList<T>> key, T value) {
            delegate.addToAttachmentList(key, value);
        }
    }

    // ------------------------------------------------------------------
    // Plain delegation
    // ------------------------------------------------------------------

    @Override
    public StreamConnection performUpgrade() throws IOException {
        return delegate.performUpgrade();
    }

    @Override
    public ByteBufferPool getBufferPool() {
        return delegate.getBufferPool();
    }

    @Override
    public SocketAddress getPeerAddress() {
        return delegate.getPeerAddress();
    }

    @Override
    public <A extends SocketAddress> A getPeerAddress(Class<A> type) {
        return delegate.getPeerAddress(type);
    }

    @Override
    public ChannelListener.Setter<? extends ClientConnection> getCloseSetter() {
        return delegate.getCloseSetter();
    }

    @Override
    public SocketAddress getLocalAddress() {
        return delegate.getLocalAddress();
    }

    @Override
    public <A extends SocketAddress> A getLocalAddress(Class<A> type) {
        return delegate.getLocalAddress(type);
    }

    @Override
    public XnioWorker getWorker() {
        return delegate.getWorker();
    }

    @Override
    public XnioIoThread getIoThread() {
        return delegate.getIoThread();
    }

    @Override
    public boolean isOpen() {
        return delegate.isOpen();
    }

    @Override
    public boolean supportsOption(Option<?> option) {
        return delegate.supportsOption(option);
    }

    @Override
    public <T> T getOption(Option<T> option) throws IOException {
        return delegate.getOption(option);
    }

    @Override
    public <T> T setOption(Option<T> option, T value) throws IllegalArgumentException, IOException {
        return delegate.setOption(option, value);
    }

    @Override
    public boolean isUpgraded() {
        return delegate.isUpgraded();
    }

    @Override
    public boolean isPushSupported() {
        return delegate.isPushSupported();
    }

    @Override
    public boolean isMultiplexingSupported() {
        return delegate.isMultiplexingSupported();
    }

    @Override
    public ClientStatistics getStatistics() {
        return delegate.getStatistics();
    }

    @Override
    public boolean isUpgradeSupported() {
        return delegate.isUpgradeSupported();
    }

    @Override
    public void addCloseListener(ChannelListener<ClientConnection> listener) {
        delegate.addCloseListener(listener);
    }

    @Override
    public boolean isPingSupported() {
        return delegate.isPingSupported();
    }

    @Override
    public void sendPing(PingListener listener, long timeout, TimeUnit timeUnit) {
        delegate.sendPing(listener, timeout, timeUnit);
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
