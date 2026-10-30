package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.server.security.IpLiterals;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.protoblast.common.time.Backoff;
import be.elevenways.protoblast.common.util.Closeables;
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
import be.elevenways.protoblast.common.time.Pace;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    // AIDEV-NOTE: accept() errors are almost always TRANSIENT resource pressure (EMFILE/ENFILE
    // fd exhaustion, ECONNABORTED). Java surfaces them as message text on a plain IOException
    // with no errno, so classifying by message is fragile and locale/JDK-dependent; the robust
    // primary mechanism is consecutive-failure counting: back off briefly, retry, and escalate
    // to the failure handler only once the pressure has provably persisted (or the server
    // socket itself is closed, where retrying is pointless). This is the Aug 04 2026 incident
    // fix: one "Too many open files in system" burst used to kill the HTTPS listener
    // permanently while port 80 kept serving, and nobody noticed for six days.
    private static final int DEFAULT_MAX_CONSECUTIVE_ACCEPT_FAILURES = 100;
    private static final long DEFAULT_ACCEPT_BACKOFF_INITIAL_MILLIS = 50;
    private static final long DEFAULT_ACCEPT_BACKOFF_MAX_MILLIS = 1_000;

    /** Creates the server socket {@link #start()} binds; replaceable for tests. */
    public interface ServerSocketFactory {
        ServerSocket create() throws IOException;
    }

    private final String bindAddress;
    private final int port;
    private final int prologueTimeoutMillis;
    private final ConnectionRouter router;
    private final Supplier<List<String>> trustedProxySources;
    private final ConnectionIdentities connectionIdentities;
    private final Predicate<String> bannedIpCheck;
    private final Consumer<IOException> listenerFailureHandler;
    private final Semaphore connectionSlots;
    /* False for a stream's port: nothing is read before the relay, since a server-first protocol sends nothing. */
    private final boolean readsPrologue;
    private final Semaphore pendingPrologues;
    private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();
    private final AtomicLong nextFailureLogNanos = new AtomicLong();
    private final AtomicInteger suppressedFailures = new AtomicInteger();

    private volatile int maxConsecutiveAcceptFailures = DEFAULT_MAX_CONSECUTIVE_ACCEPT_FAILURES;
    private volatile Backoff acceptBackoff = Backoff.exponential(
            Duration.ofMillis(DEFAULT_ACCEPT_BACKOFF_INITIAL_MILLIS), 2,
            Duration.ofMillis(DEFAULT_ACCEPT_BACKOFF_MAX_MILLIS));
    private volatile ServerSocketFactory serverSocketFactory = ServerSocket::new;

    private volatile boolean running;
    private volatile ServerSocket listener;
    /* The accept loop, every connection and its relay: started detached in start(), stopped by close(). */
    private volatile @Nullable JobRunner threads;
    /* Wraps accept(): close() waits until the accept loop left it, since the port is free only then. */
    private final BlockingSocketCall acceptCall = new BlockingSocketCall();

    public PublicTcpListener(String bindAddress, int port, int prologueTimeoutMillis,
                             ConnectionRouter router, Supplier<List<String>> trustedProxySources,
                             ConnectionIdentities connectionIdentities, Predicate<String> bannedIpCheck,
                             int maxConnections, int maxPendingPrologues,
                             Consumer<IOException> listenerFailureHandler) {
        this(bindAddress, port, prologueTimeoutMillis, router, trustedProxySources, connectionIdentities,
            bannedIpCheck, maxConnections, maxPendingPrologues, listenerFailureHandler, true);
    }

    /**
     * A TCP/UDP stream's public port: every connection goes to the router at once, without waiting for a PROXY v2
     * prologue (SSH, SMTP and databases speak first from the server, so their clients send nothing to wait for).
     */
    static PublicTcpListener forStream(String bindAddress, int port, ConnectionRouter router, int maxConnections,
                                       Consumer<IOException> listenerFailureHandler) {
        // The router reads nothing; the deadline only bounds a router that would (DNS is the connect's own budget).
        return new PublicTcpListener(bindAddress, port, 10_000, router, List::of, new ConnectionIdentities(),
            ip -> false, maxConnections, maxConnections, listenerFailureHandler, false);
    }

    private PublicTcpListener(String bindAddress, int port, int prologueTimeoutMillis,
                              ConnectionRouter router, Supplier<List<String>> trustedProxySources,
                              ConnectionIdentities connectionIdentities, Predicate<String> bannedIpCheck,
                              int maxConnections, int maxPendingPrologues,
                              Consumer<IOException> listenerFailureHandler, boolean readsPrologue) {
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
        this.readsPrologue = readsPrologue;
    }

    /** Test seam: replaces the server socket the next {@link #start()} binds. */
    public void setServerSocketFactoryForTesting(ServerSocketFactory factory) {
        this.serverSocketFactory = factory;
    }

    /** Test seam: shrinks the accept-failure escalation threshold and backoff so tests finish fast. */
    public void setAcceptFailurePolicyForTesting(int maxConsecutive, long initialMillis, long maxMillis) {
        if (maxConsecutive < 1 || initialMillis < 1 || maxMillis < initialMillis) {
            throw new IllegalArgumentException("Invalid accept failure policy");
        }
        this.maxConsecutiveAcceptFailures = maxConsecutive;
        this.acceptBackoff = Backoff.exponential(Duration.ofMillis(initialMillis), 2, Duration.ofMillis(maxMillis));
    }

    public synchronized void start() throws IOException {
        if (running) return;
        ServerSocket created = serverSocketFactory.create();
        try {
            created.setReuseAddress(true);
            created.bind(new InetSocketAddress(InetAddress.getByName(bindAddress), port));
        } catch (Throwable failed) {
            Closeables.closeQuietly(created);
            throw failed;
        }
        listener = created;
        running = true;
        // AIDEV-NOTE: DETACHED, so no connection ever runs as whoever (re)started the listener (boot runs as system).
        JobRunner runner = JobRunner.create("public-tcp-" + created.getLocalPort(), JobRunner.CallerContext.DETACHED);
        threads = runner;
        runner.startBlockingThread(() -> acceptLoop(runner));
    }

    public @Nullable InetSocketAddress getLocalAddress() {
        ServerSocket current = listener;
        return current != null ? (InetSocketAddress) current.getLocalSocketAddress() : null;
    }

    private void acceptLoop(JobRunner runner) {
        int consecutiveAcceptFailures = 0;
        while (running) {
            Socket client;
            ServerSocket current = listener;
            if (current == null) return;
            try {
                client = acceptCall.during(current::accept);
                consecutiveAcceptFailures = 0;
            } catch (IOException e) {
                if (!running) return;
                consecutiveAcceptFailures++;
                if (current.isClosed() || consecutiveAcceptFailures >= maxConsecutiveAcceptFailures) {
                    // Terminal: the socket is gone, or the pressure provably persisted.
                    // Escalation is safe now that the ProxyServer restart path is bounded
                    // and supervised -- a FAILED listener heals instead of staying dead.
                    listenerFailureHandler.accept(e);
                    close();
                    return;
                }
                logAcceptFailure(e, consecutiveAcceptFailures);
                if (!sleepAcceptBackoff(consecutiveAcceptFailures)) return;
                continue;
            }
            boolean connectionAdmitted = false;
            boolean admitted = false;
            try {
                configure(client);
                connectionAdmitted = connectionSlots.tryAcquire();
                if (!connectionAdmitted) {
                    Closeables.closeQuietly(client);
                    continue;
                }
                admitted = pendingPrologues.tryAcquire();
                if (!admitted) {
                    Closeables.closeQuietly(client);
                    connectionSlots.release();
                    continue;
                }
                activeSockets.add(client);
                Socket accepted = client;
                runner.startThread(() -> handle(accepted, runner));
                client = null;
                admitted = false;
            } catch (IOException | IllegalStateException e) {
                if (client != null) activeSockets.remove(client);
                Closeables.closeQuietly(client);
                if (admitted) pendingPrologues.release();
                if (connectionAdmitted) connectionSlots.release();
            }
        }
    }

    private void handle(Socket client, JobRunner runner) {
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
            Optional<ProxyProtocolV2.Header> ingress = this.readsPrologue
                ? ProxyProtocolV2.readOptional(input) : Optional.empty();
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
                    choice.connectTimeoutMillis(), listener.getLocalPort(), choice.publicOnly());
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
            relay(client, backend, input, runner);
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
            Closeables.closeQuietly(client);
            Closeables.closeQuietly(backend);
        }
    }

    /** Logs the first failure of a burst and every 25th after, so a storm cannot flood the log. */
    private void logAcceptFailure(IOException failure, int count) {
        if (count == 1 || count % 25 == 0) {
            Blast.log("Public TCP listener: accept failed on port", port, "-", failure.getMessage(),
                "(consecutive failure", count, "of", maxConsecutiveAcceptFailures,
                "before escalation)");
        }
    }

    /**
     * Capped exponential backoff between accept retries.
     *
     * @return false when the loop must exit (interrupted or no longer running)
     */
    private boolean sleepAcceptBackoff(int failureCount) {
        try {
            Pace.sleep(acceptBackoff.delayAfter(failureCount));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return running;
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

    private static void relay(Socket client, Socket backend, InputStream clientInput, JobRunner runner) {
        Thread upstream = runner.startThread(() -> copy(clientInput, backend));
        Thread downstream = runner.startThread(() -> copy(backend, client));
        try {
            upstream.join();
            downstream.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Closeables.closeQuietly(client);
            Closeables.closeQuietly(backend);
        }
    }

    private static void copy(Socket source, Socket destination) {
        try {
            copy(source.getInputStream(), destination);
        } catch (IOException e) {
            Closeables.closeQuietly(source);
            Closeables.closeQuietly(destination);
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
            Closeables.closeQuietly(destination);
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

    /** Closes the port, every connection and their threads; the port is free once this returns, or it logs why not. */
    @Override
    public synchronized void close() {
        if (!running && listener == null) return;
        running = false;
        if (listener != null) {
            Closeables.closeQuietly(listener);
            listener = null;
        }
        for (Socket socket : activeSockets) Closeables.closeQuietly(socket);
        activeSockets.clear();
        JobRunner runner = threads;
        threads = null;
        if (runner != null) runner.shutdownNow();
        if (!acceptCall.awaitLeft()) {
            Blast.log("Public TCP listener: port", port, "is closed but not confirmed free - the accept call did not",
                "leave within", BlockingSocketCall.LEFT_WAIT, "or the wait was interrupted");
        }
    }
}
