package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.server.security.IpLiterals;
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
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A public TCP listener that resolves connection identity before any protocol is decoded.
 *
 * It accepts an optional PROXY protocol v2 header from a configured peer, hands the remaining
 * stream to a {@link ConnectionRouter}, and relays to the chosen backend. Connections routed to
 * an internal listener keep their public identity through {@link ConnectionIdentities}; external
 * backends may instead receive a re-emitted PROXY header.
 */
public final class PublicTcpListener implements AutoCloseable {

    private static final int COPY_BUFFER_SIZE = 32 * 1024;

    private final String bindAddress;
    private final int port;
    private final int prologueTimeoutMillis;
    private final ConnectionRouter router;
    private final Supplier<List<String>> trustedProxySources;
    private final ConnectionIdentities connectionIdentities;
    private final Predicate<String> bannedIpCheck;
    private final Consumer<IOException> listenerFailureHandler;
    private final Semaphore connectionSlots;
    private final Semaphore pendingPrologues;
    private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();
    private final ExecutorService connections = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("public-tcp-listener-", 0).factory());
    private final AtomicLong nextFailureLogNanos = new AtomicLong();
    private final AtomicInteger suppressedFailures = new AtomicInteger();

    private volatile boolean running;
    private ServerSocket listener;

    public PublicTcpListener(String bindAddress, int port, int prologueTimeoutMillis,
                             ConnectionRouter router, Supplier<List<String>> trustedProxySources,
                             ConnectionIdentities connectionIdentities, Predicate<String> bannedIpCheck,
                             int maxConnections, int maxPendingPrologues,
                             Consumer<IOException> listenerFailureHandler) {
        if (prologueTimeoutMillis < 1) {
            throw new IllegalArgumentException("Prologue timeout must be positive");
        }
        if (maxConnections < 1) {
            throw new IllegalArgumentException("Maximum connections must be positive");
        }
        if (maxPendingPrologues < 1) {
            throw new IllegalArgumentException("Maximum pending prologues must be positive");
        }
        this.bindAddress = bindAddress;
        this.port = port;
        this.prologueTimeoutMillis = prologueTimeoutMillis;
        this.router = router;
        this.trustedProxySources = trustedProxySources;
        this.connectionIdentities = connectionIdentities;
        this.bannedIpCheck = bannedIpCheck;
        this.listenerFailureHandler = listenerFailureHandler;
        this.connectionSlots = new Semaphore(maxConnections);
        this.pendingPrologues = new Semaphore(maxPendingPrologues);
    }

    public synchronized void start() throws IOException {
        if (running) return;
        ServerSocket created = new ServerSocket();
        created.setReuseAddress(true);
        created.bind(new InetSocketAddress(InetAddress.getByName(bindAddress), port));
        listener = created;
        running = true;
        Thread.ofPlatform().daemon(true)
            .name("public-tcp-accept-" + created.getLocalPort())
            .start(this::acceptLoop);
    }

    public @Nullable InetSocketAddress getLocalAddress() {
        ServerSocket current = listener;
        return current != null ? (InetSocketAddress) current.getLocalSocketAddress() : null;
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
                admitted = pendingPrologues.tryAcquire();
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
                if (admitted) pendingPrologues.release();
                if (connectionAdmitted) connectionSlots.release();
            }
        }
    }

    private void handle(Socket client) {
        Socket backend = null;
        InetSocketAddress registeredInternalSource = null;
        boolean pendingPrologue = true;
        try {
            DeadlineInputStream deadlineInput = new DeadlineInputStream(
                client.getInputStream(), client, prologueTimeoutMillis);
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

            ConnectionRouter.Decision decision = router.route(input, originalSource, originalDestination);
            deadlineInput.disableDeadline();
            pendingPrologues.release();
            pendingPrologue = false;
            if (bannedIpCheck.test(originalSource.getAddress().getHostAddress())) {
                return;
            }
            BackendChoice choice = decision.backend();
            if (choice == null) return;

            if (choice.isInternal()) {
                backend = new Socket();
                backend.connect(choice.internalAddress(), choice.connectTimeoutMillis());
            } else {
                backend = BackendConnector.connect(choice.host(), choice.port(),
                    choice.connectTimeoutMillis(), listener.getLocalPort());
            }
            configure(backend);
            activeSockets.add(backend);
            if (choice.isInternal()) {
                registeredInternalSource = internetAddress(
                    backend.getLocalSocketAddress(), "internal listener source");
                connectionIdentities.register(registeredInternalSource,
                    originalSource, originalDestination);
            }

            OutputStream backendOutput = backend.getOutputStream();
            if (choice.sendProxyProtocolV2()) {
                backendOutput.write(ProxyProtocolV2.encode(originalSource, originalDestination));
            }
            byte[] replay = decision.replayBytes();
            if (replay.length > 0) backendOutput.write(replay);
            backendOutput.flush();
            relay(client, backend, input);
        } catch (IOException | RuntimeException failure) {
            // AIDEV-NOTE: never close the backend here -- the finally block must unregister the
            // loopback identity BEFORE the socket dies, or a new connection reusing that ephemeral
            // port would have its restored public identity dropped by this unregister.
            logFailure(client, failure);
        } finally {
            if (pendingPrologue) pendingPrologues.release();
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
            Blast.log("Public TCP listener: connection failed from", source, "-", failure.getMessage());
        } else {
            Blast.log("Public TCP listener: connection failed from", source, "-", failure.getMessage(),
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
