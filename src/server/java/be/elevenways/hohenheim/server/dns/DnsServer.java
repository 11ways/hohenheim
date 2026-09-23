package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.protoblast.common.Blast;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Opcode;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.Type;

/**
 * Authoritative-only DNS listeners on public UDP and TCP (default port 53).
 * Serves whatever {@link DnsZoneStore} currently holds; zone edits swap the
 * snapshot and need no listener restart.
 */
public final class DnsServer {

    private static final int UDP_RECEIVE_SIZE = 4096;
    private static final int TCP_MAX_QUERY = 16384;
    private static final int TCP_IDLE_TIMEOUT_MS = 30_000;
    private static final int TCP_MAX_CONNECTIONS = 128;

    /**
     * Concurrent TCP connections one client address may hold.
     *
     * AIDEV-NOTE: without it one host could take all {@link #TCP_MAX_CONNECTIONS} slots and
     * starve every other TCP client -- including the resolvers a UDP SLIP verdict just told
     * to retry over TCP, which turned the rate limiter's escape hatch into its own outage.
     */
    public static final int TCP_MAX_CONNECTIONS_PER_CLIENT = 8;

    /**
     * The ABSOLUTE lifetime of one TCP connection, however busy it is.
     *
     * AIDEV-NOTE: the idle timeout alone re-arms on every read (it is a per-read socket
     * timeout, and readFully issues one read per arriving segment), so a client dribbling
     * one byte every 29 seconds held a slot forever. This deadline closes the socket from
     * outside the reading thread. Generous on purpose: a large AXFR to a slow secondary is
     * the longest legitimate exchange.
     */
    private static final long TCP_CONNECTION_DEADLINE_MS = 120_000;

    private final DnsResponder responder;
    private final AxfrResponder axfrResponder;
    private final Semaphore tcpConnections = new Semaphore(TCP_MAX_CONNECTIONS);
    private final ConcurrentHashMap<InetAddress, Integer> tcpPerClient = new ConcurrentHashMap<>();
    private volatile long tcpConnectionDeadlineMs = TCP_CONNECTION_DEADLINE_MS;
    private final DnsRateLimiter rateLimiter = new DnsRateLimiter(() -> {
        Integer limit = HohenheimSettings.VALUES.getValue(HohenheimSettings.Dns.RATE_LIMIT_PER_SECOND);
        return limit != null ? limit : 0;
    });
    private volatile @Nullable SecondaryZoneService secondaryService;

    public DnsServer() {
        this(new DnsResponder(DnsZoneStore.INSTANCE), new AxfrResponder(DnsZoneStore.INSTANCE));
    }

    /** For tests: serve an arbitrary store with a custom AXFR authorizer. */
    public DnsServer(@NonNull DnsResponder responder, @NonNull AxfrResponder axfrResponder) {
        this.responder = responder;
        this.axfrResponder = axfrResponder;
    }

    private volatile boolean running;
    private volatile @Nullable String startupError;
    private @Nullable DatagramSocket udpSocket;
    private @Nullable ServerSocket tcpSocket;
    private @Nullable ExecutorService workers;
    private @Nullable ScheduledExecutorService deadlines;

    /** @return true when the server is enabled in settings and both listeners bound */
    public boolean startIfEnabled() {
        Boolean enabled = HohenheimSettings.VALUES.getValue(HohenheimSettings.Dns.ENABLED);
        if (!Boolean.TRUE.equals(enabled)) {
            return false;
        }

        String bindAddress = HohenheimSettings.VALUES.getValue(HohenheimSettings.Dns.BIND_ADDRESS);
        Integer port = HohenheimSettings.VALUES.getValue(HohenheimSettings.Dns.PORT);

        try {
            this.start(bindAddress != null ? bindAddress : "0.0.0.0", port != null ? port : 53);
            return true;
        }
        catch (IOException e) {
            this.startupError = e.getMessage();
            Blast.log("DNS: failed to bind listeners:", e.getMessage());
            return false;
        }
    }

    public synchronized void start(@NonNull String bindAddress, int port) throws IOException {
        if (this.running) {
            return;
        }

        InetAddress address = InetAddress.getByName(bindAddress);

        // Both transports must share ONE port. With an ephemeral request
        // (port 0) the UDP bind picks the number, and the TCP bind on that
        // same number can lose the race, so retry with a fresh pick.
        DatagramSocket udp = null;
        ServerSocket tcp = null;
        IOException lastFailure = null;
        for (int attempt = 0; attempt < 5 && tcp == null; attempt++) {
            udp = new DatagramSocket(new InetSocketAddress(address, port));
            try {
                tcp = new ServerSocket();
                tcp.bind(new InetSocketAddress(address, udp.getLocalPort()));
            }
            catch (IOException e) {
                lastFailure = e;
                udp.close();
                udp = null;
                tcp = null;
                if (port != 0) {
                    break;
                }
            }
        }
        if (udp == null || tcp == null) {
            throw lastFailure != null ? lastFailure : new IOException("DNS listeners could not bind");
        }
        final DatagramSocket udpBound = udp;
        final ServerSocket tcpBound = tcp;

        this.udpSocket = udpBound;
        this.tcpSocket = tcpBound;
        this.workers = Executors.newVirtualThreadPerTaskExecutor();
        this.deadlines = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "hohenheim-dns-tcp-deadline");
            thread.setDaemon(true);
            return thread;
        });
        this.running = true;
        this.startupError = null;

        Thread udpThread = new Thread(() -> this.udpLoop(udpBound), "hohenheim-dns-udp");
        udpThread.setDaemon(true);
        udpThread.start();

        Thread tcpThread = new Thread(() -> this.tcpLoop(tcpBound), "hohenheim-dns-tcp");
        tcpThread.setDaemon(true);
        tcpThread.start();

        Blast.log("DNS: authoritative listeners on", bindAddress + ":" + udpBound.getLocalPort(), "(udp+tcp)");
    }

    public synchronized void stop() {
        if (!this.running) {
            return;
        }
        this.running = false;
        closeQuietly(this.udpSocket);
        closeSocketQuietly(this.tcpSocket);
        this.udpSocket = null;
        this.tcpSocket = null;
        if (this.workers != null) {
            this.workers.shutdown();
            this.workers = null;
        }
        if (this.deadlines != null) {
            this.deadlines.shutdownNow();
            this.deadlines = null;
        }
    }

    public boolean isRunning() {
        return this.running;
    }

    /** For tests: shorten the absolute TCP connection lifetime so it can be observed. */
    public void setTcpConnectionDeadlineMillis(long deadlineMs) {
        this.tcpConnectionDeadlineMs = deadlineMs;
    }

    /** Wires the secondary service so inbound NOTIFY triggers an immediate refresh. */
    public void setSecondaryService(@Nullable SecondaryZoneService secondaryService) {
        this.secondaryService = secondaryService;
    }

    /** @return the replication service wired at boot, or null when this server runs without one */
    public @Nullable SecondaryZoneService getSecondaryService() {
        return this.secondaryService;
    }

    /** @return the bind failure message from the last enabled start attempt, or null */
    public @Nullable String getStartupError() {
        return this.startupError;
    }

    public int getUdpPort() {
        DatagramSocket socket = this.udpSocket;
        return socket != null ? socket.getLocalPort() : -1;
    }

    private void udpLoop(@NonNull DatagramSocket socket) {
        while (this.running) {
            byte[] buffer = new byte[UDP_RECEIVE_SIZE];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            }
            catch (IOException e) {
                if (this.running) {
                    Blast.log("DNS: udp receive failed:", e.getMessage());
                }
                continue;
            }

            byte[] wire = new byte[packet.getLength()];
            System.arraycopy(packet.getData(), packet.getOffset(), wire, 0, packet.getLength());
            InetSocketAddress client = (InetSocketAddress) packet.getSocketAddress();

            ExecutorService pool = this.workers;
            if (pool == null) {
                return;
            }
            pool.execute(() -> {
                Message parsed = tryParse(wire);
                byte[] reply;
                if (parsed != null && parsed.getHeader().getOpcode() == Opcode.NOTIFY) {
                    // A NOTIFY is a spoofable UDP packet that costs a zone lookup and a TSIG
                    // verify: over-limit ones from one prefix are dropped (the refresh timer
                    // covers a genuinely lost NOTIFY), keyed per zone like NXDOMAIN.
                    if (this.rateLimiter.check(client.getAddress(), notifyBucket(parsed))
                            != DnsRateLimiter.Verdict.ALLOW) {
                        return;
                    }
                    reply = handleNotify(parsed, wire);
                }
                else if (parsed == null) {
                    // Unparseable garbage earns at most a FORMERR (amplification
                    // <= 1); over-limit garbage is dropped from a shared bucket.
                    if (this.rateLimiter.check(client.getAddress(), "err|formerr") != DnsRateLimiter.Verdict.ALLOW) {
                        return;
                    }
                    reply = this.responder.respondToWire(wire, false);
                }
                else {
                    // RRL applies to UDP queries only: TCP is spoof-resistant, and a
                    // SLIP verdict answers truncated so real clients retry over TCP.
                    // The verdict keys on the COMPUTED response so an NXDOMAIN flood
                    // with random subdomains shares one per-zone bucket.
                    DnsResponder.Answer computed = this.responder.answer(parsed);
                    if (computed == null) {
                        return;
                    }
                    Message answer = computed.response();
                    switch (this.rateLimiter.check(client.getAddress(),
                            DnsRateLimiter.keyFor(parsed, answer, computed.source()))) {
                        case DROP -> {
                            return;
                        }
                        case SLIP -> {
                            sendUdp(socket, truncatedWire(parsed), client);
                            return;
                        }
                        case ALLOW -> { }
                    }
                    reply = this.responder.wireFor(parsed, answer, false);
                }
                if (reply == null) {
                    return;
                }
                try {
                    socket.send(new DatagramPacket(reply, reply.length, client));
                }
                catch (IOException e) {
                    if (this.running) {
                        Blast.log("DNS: udp send failed:", e.getMessage());
                    }
                }
            });
        }
    }

    private void tcpLoop(@NonNull ServerSocket serverSocket) {
        while (this.running) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            }
            catch (IOException e) {
                if (this.running) {
                    Blast.log("DNS: tcp accept failed:", e.getMessage());
                }
                continue;
            }

            InetAddress peer = socket.getInetAddress();
            if (!this.acquireTcpSlot(peer)) {
                closeSocketQuietly(socket);
                continue;
            }

            ExecutorService pool = this.workers;
            ScheduledExecutorService reaper = this.deadlines;
            if (pool == null || reaper == null) {
                this.releaseTcpSlot(peer);
                closeSocketQuietly(socket);
                return;
            }
            try {
                ScheduledFuture<?> deadline = reaper.schedule(() -> closeSocketQuietly(socket),
                    this.tcpConnectionDeadlineMs, TimeUnit.MILLISECONDS);
                pool.execute(() -> {
                    try {
                        this.handleTcpConnection(socket);
                    }
                    finally {
                        deadline.cancel(false);
                        this.releaseTcpSlot(peer);
                        closeSocketQuietly(socket);
                    }
                });
            }
            catch (RejectedExecutionException stopping) {
                // stop() shut the pools down between the accept and here.
                this.releaseTcpSlot(peer);
                closeSocketQuietly(socket);
                return;
            }
        }
    }

    /**
     * Take one of the server-wide TCP slots AND one of the client's own.
     *
     * AIDEV-NOTE: the per-client count moves only inside {@code compute}, which is atomic per
     * key, so a racing release can never strand a count on a detached holder.
     *
     * @return false when either is exhausted; nothing is held then
     */
    private boolean acquireTcpSlot(@NonNull InetAddress client) {
        boolean[] admitted = {false};
        this.tcpPerClient.compute(client, (key, held) -> {
            int current = held == null ? 0 : held;
            if (current >= TCP_MAX_CONNECTIONS_PER_CLIENT) {
                return held;
            }
            admitted[0] = true;
            return current + 1;
        });
        if (!admitted[0]) {
            return false;
        }
        if (!this.tcpConnections.tryAcquire()) {
            this.releaseClientSlot(client);
            return false;
        }
        return true;
    }

    /** Give back what {@link #acquireTcpSlot} took. */
    private void releaseTcpSlot(@NonNull InetAddress client) {
        this.tcpConnections.release();
        this.releaseClientSlot(client);
    }

    private void releaseClientSlot(@NonNull InetAddress client) {
        this.tcpPerClient.compute(client, (key, held) -> held == null || held <= 1 ? null : held - 1);
    }

    /** The RRL bucket of a NOTIFY: per claimed zone, so a flood of one zone shares one bucket. */
    private static @NonNull String notifyBucket(@NonNull Message query) {
        Record question = query.getQuestion();
        return "notify|" + (question != null
            ? question.getName().toString(true).toLowerCase(Locale.ROOT) : "-");
    }

    private void handleTcpConnection(@NonNull Socket socket) {
        try {
            socket.setSoTimeout(TCP_IDLE_TIMEOUT_MS);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            while (this.running) {
                int length;
                try {
                    length = in.readUnsignedShort();
                }
                catch (EOFException | SocketTimeoutException e) {
                    return;
                }

                if (length == 0 || length > TCP_MAX_QUERY) {
                    return;
                }

                byte[] wire = new byte[length];
                in.readFully(wire);

                Message parsed = tryParse(wire);
                if (parsed != null) {
                    Record question = parsed.getQuestion();
                    if (question != null && (question.getType() == Type.AXFR || question.getType() == Type.IXFR)) {
                        writeAxfr(out, parsed, wire);
                        continue;
                    }
                    if (parsed.getHeader().getOpcode() == Opcode.NOTIFY) {
                        byte[] ack = handleNotify(parsed, wire);
                        out.writeShort(ack.length);
                        out.write(ack);
                        out.flush();
                        continue;
                    }
                }

                byte[] reply = this.responder.respondToWire(wire, true);
                if (reply == null) {
                    return;
                }

                out.writeShort(reply.length);
                out.write(reply);
                out.flush();
            }
        }
        catch (IOException e) {
            // Peer went away mid-exchange; nothing to salvage.
        }
    }

    private static @Nullable Message tryParse(byte @NonNull [] wire) {
        try {
            return new Message(wire);
        }
        catch (IOException e) {
            return null;
        }
    }

    /** Streams an AXFR (IXFR falls back to a full AXFR) or a single REFUSED when unauthorized. */
    private void writeAxfr(@NonNull DataOutputStream out, @NonNull Message query, byte @NonNull [] wire)
            throws IOException {
        List<byte[]> stream = this.axfrResponder.respond(query, wire);
        if (stream == null) {
            byte[] refused = refusedWire(query);
            out.writeShort(refused.length);
            out.write(refused);
            out.flush();
            return;
        }
        for (byte[] message : stream) {
            out.writeShort(message.length);
            out.write(message);
        }
        out.flush();
    }

    /**
     * Acks a NOTIFY; the secondary service decides whether it is authentic
     * (TSIG when the primary peer has a key) and worth a serial-checked pull.
     */
    private byte @NonNull [] handleNotify(@NonNull Message query, byte @NonNull [] wire) {
        Record question = query.getQuestion();
        SecondaryZoneService service = this.secondaryService;
        if (question != null && service != null) {
            service.onNotify(query, wire);
        }
        Message ack = new Message(query.getHeader().getID());
        ack.getHeader().setOpcode(Opcode.NOTIFY);
        ack.getHeader().setFlag(Flags.QR);
        ack.getHeader().setFlag(Flags.AA);
        if (question != null) {
            ack.addRecord(question, Section.QUESTION);
        }
        return ack.toWire(512);
    }

    /** An answerless TC response: "ask again over TCP". */
    private static byte @NonNull [] truncatedWire(@NonNull Message query) {
        Message response = new Message(query.getHeader().getID());
        response.getHeader().setFlag(Flags.QR);
        response.getHeader().setFlag(Flags.AA);
        response.getHeader().setFlag(Flags.TC);
        Record question = query.getQuestion();
        if (question != null) {
            response.addRecord(question, Section.QUESTION);
        }
        return response.toWire(512);
    }

    private void sendUdp(@NonNull DatagramSocket socket, byte @NonNull [] reply,
                         @NonNull InetSocketAddress client) {
        try {
            socket.send(new DatagramPacket(reply, reply.length, client));
        }
        catch (IOException e) {
            if (this.running) {
                Blast.log("DNS: udp send failed:", e.getMessage());
            }
        }
    }

    private static byte @NonNull [] refusedWire(@NonNull Message query) {
        Message response = new Message(query.getHeader().getID());
        response.getHeader().setFlag(Flags.QR);
        Record question = query.getQuestion();
        if (question != null) {
            response.addRecord(question, Section.QUESTION);
        }
        response.getHeader().setRcode(Rcode.REFUSED);
        return response.toWire(65535);
    }

    private static void closeQuietly(@Nullable DatagramSocket socket) {
        if (socket != null) {
            socket.close();
        }
    }

    private static void closeSocketQuietly(@Nullable ServerSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            }
            catch (IOException ignored) {
            }
        }
    }

    private static void closeSocketQuietly(@Nullable Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            }
            catch (IOException ignored) {
            }
        }
    }
}
