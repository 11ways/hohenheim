package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.server.security.IpLiterals;
import be.elevenways.hohenheim.server.sitetype.TlsPassthroughTarget;
import be.elevenways.protoblast.common.Blast;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.function.Predicate;
import java.util.function.Consumer;

/** Public TLS listener that either terminates through Undertow or preserves the stream for SNI passthrough. */
public final class TlsMultiplexer implements AutoCloseable {

    @FunctionalInterface
    public interface Resolver {
        TlsPassthroughRoutes.Decision resolve(String serverName, String listenerIp);
    }

    private static final int COPY_BUFFER_SIZE = 32 * 1024;
    private static final int TERMINATION_CONNECT_TIMEOUT_MS = 5_000;

    private final String bindAddress;
    private final int port;
    private final int clientHelloTimeoutMillis;
    private final Resolver resolver;
    private final Supplier<List<String>> trustedProxySources;
    private final TlsConnectionIdentities connectionIdentities;
    private final Predicate<String> bannedIpCheck;
    private final Consumer<IOException> listenerFailureHandler;
    private final Semaphore connectionSlots;
    private final Semaphore pendingHandshakes;
    private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();
    private final ExecutorService connections = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("tls-multiplexer-", 0).factory());
    private final AtomicLong nextFailureLogNanos = new AtomicLong();
    private final AtomicInteger suppressedFailures = new AtomicInteger();

    private volatile @Nullable InetSocketAddress terminationAddress;
    private volatile boolean running;
    private ServerSocket listener;
    private Thread acceptThread;

    public TlsMultiplexer(String bindAddress, int port, int clientHelloTimeoutMillis,
                          @Nullable InetSocketAddress terminationAddress, Resolver resolver,
                           Supplier<List<String>> trustedProxySources,
                           TlsConnectionIdentities connectionIdentities,
                           Predicate<String> bannedIpCheck, int maxConnections,
                           int maxPendingHandshakes, Consumer<IOException> listenerFailureHandler) {
        if (clientHelloTimeoutMillis < 1) {
            throw new IllegalArgumentException("ClientHello timeout must be positive");
        }
        this.bindAddress = bindAddress;
        this.port = port;
        this.clientHelloTimeoutMillis = clientHelloTimeoutMillis;
        this.terminationAddress = terminationAddress;
        this.resolver = resolver;
        this.trustedProxySources = trustedProxySources;
        this.connectionIdentities = connectionIdentities;
        this.bannedIpCheck = bannedIpCheck;
        this.listenerFailureHandler = listenerFailureHandler;
        if (maxConnections < 1) {
            throw new IllegalArgumentException("Maximum TLS connections must be positive");
        }
        this.connectionSlots = new Semaphore(maxConnections);
        if (maxPendingHandshakes < 1) {
            throw new IllegalArgumentException("Maximum pending TLS handshakes must be positive");
        }
        this.pendingHandshakes = new Semaphore(maxPendingHandshakes);
    }

    public synchronized void start() throws IOException {
        if (running) return;
        ServerSocket created = new ServerSocket();
        created.setReuseAddress(true);
        created.bind(new InetSocketAddress(InetAddress.getByName(bindAddress), port));
        listener = created;
        running = true;
        acceptThread = Thread.ofPlatform().daemon(true)
            .name("tls-multiplexer-accept-" + created.getLocalPort())
            .start(this::acceptLoop);
    }

    public InetSocketAddress getLocalAddress() {
        ServerSocket current = listener;
        return current != null ? (InetSocketAddress) current.getLocalSocketAddress() : null;
    }

    public void setTerminationAddress(@Nullable InetSocketAddress address) {
        this.terminationAddress = address;
    }

    private void acceptLoop() {
        while (running) {
            Socket client;
            try {
                client = listener.accept();
            } catch (IOException e) {
                if (running) {
                    listenerFailureHandler.accept(e);
                    close();
                }
                return;
            }
            boolean connectionAdmitted = false;
            boolean admitted = false;
            try {
                configure(client);
                connectionAdmitted = connectionSlots.tryAcquire();
                if (!connectionAdmitted) {
                    closeQuietly(client);
                    continue;
                }
                admitted = pendingHandshakes.tryAcquire();
                if (!admitted) {
                    closeQuietly(client);
                    connectionSlots.release();
                    continue;
                }
                activeSockets.add(client);
                Socket accepted = client;
                connections.submit(() -> handle(accepted));
                client = null;
                admitted = false;
            } catch (IOException | RejectedExecutionException e) {
                if (client != null) activeSockets.remove(client);
                closeQuietly(client);
                if (admitted) pendingHandshakes.release();
                if (connectionAdmitted) connectionSlots.release();
            }
        }
    }

    private void handle(Socket client) {
        Socket backend = null;
        InetSocketAddress registeredInternalSource = null;
        boolean pendingHandshake = true;
        try {
            DeadlineInputStream deadlineInput = new DeadlineInputStream(
                client.getInputStream(), client, clientHelloTimeoutMillis);
            BufferedInputStream input = new BufferedInputStream(deadlineInput, 8 * 1024);

            InetSocketAddress peer = internetAddress(client.getRemoteSocketAddress(), "client source");
            InetSocketAddress originalSource = peer;
            InetSocketAddress originalDestination = internetAddress(
                client.getLocalSocketAddress(), "listener destination");
            Optional<ProxyProtocolV2.Header> ingress = ProxyProtocolV2.readOptional(input);
            if (ingress.isPresent()) {
                if (!IpLiterals.matchesList(peer.getAddress().getAddress(), trustedProxySources.get())) {
                    throw new IOException("untrusted source sent a PROXY protocol header");
                }
                ProxyProtocolV2.Header header = ingress.get();
                if (header.command() == ProxyProtocolV2.Command.PROXY) {
                    originalSource = internetAddress(header.sourceAddress(), "PROXY source");
                    originalDestination = internetAddress(header.destinationAddress(), "PROXY destination");
                }
            }

            TlsClientHelloReader.Result hello = TlsClientHelloReader.read(input);
            deadlineInput.disableDeadline();
            pendingHandshakes.release();
            pendingHandshake = false;
            if (bannedIpCheck.test(originalSource.getAddress().getHostAddress())) {
                return;
            }
            String serverName = hello.serverName().orElse(null);
            String listenerIp = originalDestination.getAddress().getHostAddress();
            TlsPassthroughRoutes.Decision decision = resolver.resolve(serverName, listenerIp);
            if (decision.action() == TlsPassthroughRoutes.Action.REJECT) return;
            TlsPassthroughTarget passthrough = decision.target();
            InetSocketAddress destination;
            boolean sendProxyProtocol;
            boolean terminating;
            int connectTimeout;
            if (passthrough != null) {
                destination = null;
                sendProxyProtocol = passthrough.proxyProtocolV2();
                connectTimeout = passthrough.connectTimeoutMillis();
                terminating = false;
            } else {
                destination = terminationAddress;
                if (destination == null) return;
                // The internal listener learns the public identity from the identity table,
                // not from a PROXY header: Undertow owns that socket's protocol from byte one.
                sendProxyProtocol = false;
                connectTimeout = TERMINATION_CONNECT_TIMEOUT_MS;
                terminating = true;
            }

            if (passthrough != null) {
                backend = BackendConnector.connect(passthrough.host(), passthrough.port(),
                    connectTimeout, listener.getLocalPort());
            } else {
                backend = new Socket();
                backend.connect(destination, connectTimeout);
            }
            configure(backend);
            activeSockets.add(backend);
            if (terminating) {
                registeredInternalSource = internetAddress(
                    backend.getLocalSocketAddress(), "internal TLS source");
                connectionIdentities.register(registeredInternalSource,
                    originalSource, originalDestination);
            }
            OutputStream backendOutput = backend.getOutputStream();
            if (sendProxyProtocol) {
                backendOutput.write(ProxyProtocolV2.encode(originalSource, originalDestination));
            }
            backendOutput.write(hello.replayBytes());
            backendOutput.flush();
            relay(client, backend, input);
        } catch (IOException | RuntimeException failure) {
            // AIDEV-NOTE: never close the backend here -- the finally block must unregister the
            // loopback identity BEFORE the socket dies, or a new connection reusing that ephemeral
            // port would have its restored public identity dropped by this unregister.
            logFailure(client, failure);
        } finally {
            if (pendingHandshake) pendingHandshakes.release();
            if (registeredInternalSource != null) {
                connectionIdentities.unregister(registeredInternalSource);
            }
            activeSockets.remove(client);
            if (backend != null) activeSockets.remove(backend);
            connectionSlots.release();
            closeQuietly(client);
            closeQuietly(backend);
        }
    }

    private void logFailure(Socket client, Exception failure) {
        long now = System.nanoTime();
        long next = nextFailureLogNanos.get();
        if (now < next || !nextFailureLogNanos.compareAndSet(next, now + TimeUnit.SECONDS.toNanos(1))) {
            suppressedFailures.incrementAndGet();
            return;
        }
        int suppressed = suppressedFailures.getAndSet(0);
        String source = String.valueOf(client.getRemoteSocketAddress());
        if (suppressed == 0) {
            Blast.log("TLS multiplexer: connection failed from", source, "-", failure.getMessage());
        } else {
            Blast.log("TLS multiplexer: connection failed from", source, "-", failure.getMessage(),
                "(" + suppressed + " similar failures suppressed)");
        }
    }

    private static void relay(Socket client, Socket backend, InputStream clientInput) {
        Thread upstream = Thread.ofVirtual().start(() -> copy(clientInput, backend));
        Thread downstream = Thread.ofVirtual().start(() -> copy(backend, client));
        try {
            upstream.join();
            downstream.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeQuietly(client);
            closeQuietly(backend);
        }
    }

    private static void copy(Socket source, Socket destination) {
        try {
            copy(source.getInputStream(), destination);
        } catch (IOException e) {
            closeQuietly(source);
            closeQuietly(destination);
        }
    }

    private static void copy(InputStream input, Socket destination) {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        try {
            OutputStream output = destination.getOutputStream();
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) output.write(buffer, 0, count);
            }
            output.flush();
            destination.shutdownOutput();
        } catch (IOException e) {
            closeQuietly(destination);
        }
    }

    private static void configure(Socket socket) throws SocketException {
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
    }

    private static InetSocketAddress internetAddress(SocketAddress address, String role) throws IOException {
        if (!(address instanceof InetSocketAddress result) || result.isUnresolved()) {
            throw new IOException(role + " is not a resolved internet address");
        }
        return result;
    }

    @Override
    public synchronized void close() {
        if (!running && listener == null) return;
        running = false;
        if (listener != null) {
            try {
                listener.close();
            } catch (IOException ignored) {
            }
            listener = null;
        }
        for (Socket socket : activeSockets) closeQuietly(socket);
        activeSockets.clear();
        connections.shutdownNow();
    }

    private static void closeQuietly(@Nullable Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
