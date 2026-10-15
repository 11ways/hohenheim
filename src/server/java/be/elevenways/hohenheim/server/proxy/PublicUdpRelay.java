package be.elevenways.hohenheim.server.proxy;

import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.common.time.Pace;
import be.elevenways.protoblast.common.util.Closeables;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A UDP stream's public port: each client address gets a session of its own, a socket towards the upstream whose
 * answers go back to that client, closed after it has been idle.
 *
 * AIDEV-NOTE: UDP has no connection to accept or refuse, so the router is asked once per NEW client address (its
 * access list and the upstream's address); a refused client's datagrams are dropped one by one without a session.
 * A session's own socket is what tells two clients' answers apart, the shape every UDP proxy (nginx stream, npmplus)
 * uses. The upstream sees the proxy's address, never the client's.
 */
final class PublicUdpRelay implements AutoCloseable {

    /** Decides one new client: the upstream to send its datagrams to, or null to drop them. */
    interface Router {
        @Nullable InetSocketAddress upstreamFor(InetSocketAddress client) throws IOException;
    }

    private static final int MAX_DATAGRAM = 65_535;
    private static final int MAX_SESSIONS = 4_096;
    private static final Duration IDLE = Duration.ofMinutes(2);
    private static final Duration SWEEP = Duration.ofSeconds(10);

    private record Session(DatagramChannel upstream, AtomicLong lastSeen) {
    }

    private final String bindAddress;
    private final int port;
    private final Router router;
    private final Map<SocketAddress, Session> sessions = new ConcurrentHashMap<>();
    private volatile @Nullable DatagramChannel channel;
    private volatile boolean running;

    PublicUdpRelay(String bindAddress, int port, Router router) {
        this.bindAddress = bindAddress;
        this.port = port;
        this.router = router;
    }

    /** Binds the public port and starts relaying. */
    synchronized void start() throws IOException {
        if (this.running) return;
        DatagramChannel created = DatagramChannel.open();
        try {
            created.bind(new InetSocketAddress(InetAddress.getByName(this.bindAddress), this.port));
        } catch (IOException refused) {
            Closeables.closeQuietly(created);
            throw refused;
        }
        this.channel = created;
        this.running = true;
        Thread.ofPlatform().daemon(true).name("stream-udp-" + this.port).start(() -> receiveLoop(created));
        Thread.ofVirtual().name("stream-udp-sweep-" + this.port).start(this::sweepLoop);
    }

    private void receiveLoop(DatagramChannel public_) {
        ByteBuffer buffer = ByteBuffer.allocate(MAX_DATAGRAM);
        while (this.running) {
            buffer.clear();
            SocketAddress from;
            try {
                from = public_.receive(buffer);
            } catch (ClosedChannelException closed) {
                return;
            } catch (IOException failure) {
                Blast.log("Stream UDP relay: receive failed on port", this.port, "-", failure.getMessage());
                continue;
            }
            if (!(from instanceof InetSocketAddress client)) continue;
            buffer.flip();
            Session session = this.sessions.get(client);
            try {
                if (session == null) {
                    session = open(public_, client);
                    if (session == null) continue;
                }
                session.lastSeen().set(Now.millis());
                session.upstream().write(buffer);
            } catch (IOException failure) {
                Blast.log("Stream UDP relay: datagram from", client, "on port", this.port, "not forwarded -",
                    failure.getMessage());
            }
        }
    }

    /** @return the new client's session, or null when it is refused or the relay is full */
    private @Nullable Session open(DatagramChannel public_, InetSocketAddress client) throws IOException {
        if (this.sessions.size() >= MAX_SESSIONS) return null;
        InetSocketAddress upstream = this.router.upstreamFor(client);
        if (upstream == null) return null;
        DatagramChannel towards = DatagramChannel.open();
        try {
            towards.connect(upstream);
        } catch (IOException failure) {
            Closeables.closeQuietly(towards);
            throw failure;
        }
        Session session = new Session(towards, new AtomicLong(Now.millis()));
        this.sessions.put(client, session);
        Thread.ofVirtual().name("stream-udp-back-" + this.port).start(() -> answerLoop(public_, client, session));
        return session;
    }

    /** Sends every answer of the upstream back to the client the session belongs to, until the session closes. */
    private void answerLoop(DatagramChannel public_, InetSocketAddress client, Session session) {
        ByteBuffer buffer = ByteBuffer.allocate(MAX_DATAGRAM);
        try {
            while (this.running && session.upstream().isOpen()) {
                buffer.clear();
                session.upstream().read(buffer);
                buffer.flip();
                session.lastSeen().set(Now.millis());
                public_.send(buffer, client);
            }
        } catch (IOException ended) {
            // Closed by the sweep or the relay, or the upstream refused (ICMP unreachable): the session ends.
        } finally {
            this.sessions.remove(client, session);
            Closeables.closeQuietly(session.upstream());
        }
    }

    private void sweepLoop() {
        while (this.running) {
            try {
                Pace.sleep(SWEEP);
            } catch (InterruptedException interrupted) {
                return;
            }
            long oldest = Now.millis() - IDLE.toMillis();
            this.sessions.forEach((client, session) -> {
                if (session.lastSeen().get() < oldest) {
                    this.sessions.remove(client, session);
                    Closeables.closeQuietly(session.upstream());
                }
            });
        }
    }

    /** @return the bound public address, null before start or after close */
    @Nullable InetSocketAddress getLocalAddress() {
        DatagramChannel current = this.channel;
        try {
            return current == null ? null : (InetSocketAddress) current.getLocalAddress();
        } catch (IOException closed) {
            return null;
        }
    }

    /** @return how many clients have a session right now */
    int sessionCount() {
        return this.sessions.size();
    }

    @Override
    public synchronized void close() {
        this.running = false;
        Closeables.closeQuietly(this.channel);
        this.channel = null;
        this.sessions.values().forEach(session -> Closeables.closeQuietly(session.upstream()));
        this.sessions.clear();
    }
}
