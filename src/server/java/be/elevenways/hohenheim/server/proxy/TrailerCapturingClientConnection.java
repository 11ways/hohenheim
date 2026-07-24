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
 * Captures incoming HTTP/2 response trailers, which Undertow's h2 client otherwise DROPS
 * (nothing in stock Undertow calls setTrailersHandler): without this, gRPC's grpc-status
 * never reaches the downstream client.
 *
 * The captured map is stored under REQUEST_TRAILERS on the client exchange because
 * ProxyHandler's trailer-copy listener reads that key from its source attachable when
 * emitting response trailers downstream.
 */
final class TrailerCapturingClientConnection implements ClientConnection {

    private final ClientConnection delegate;

    TrailerCapturingClientConnection(ClientConnection delegate) {
        this.delegate = delegate;
    }

    @Override
    public void sendRequest(ClientRequest request, ClientCallback<ClientExchange> callback) {
        delegate.sendRequest(request, new ClientCallback<>() {
            @Override
            public void completed(ClientExchange exchange) {
                callback.completed(new TrailerCapturingExchange(exchange));
            }

            @Override
            public void failed(IOException e) {
                callback.failed(e);
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
