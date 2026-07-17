package be.elevenways.hohenheim.server.devtunnel;

import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.sitetype.types.DevNamespaceSiteType;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.protoblast.common.thread.ScheduledJob;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.websocket.WebSocketHandler;
import be.elevenways.zenit.common.websocket.WebSocketSession;
import be.elevenways.zenit.server.devtunnel.TunnelFrames;
import be.elevenways.zenit.server.devtunnel.TunnelMessage;
import be.elevenways.zenit.server.devtunnel.TunnelStream;
import be.elevenways.zenit.server.devtunnel.TunnelTransport;
import be.elevenways.zenit.server.security.SecureTokens;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Server half of one dev-tunnel connection: authenticates the register
 * message against a dev-namespace site's registration token, claims the name
 * lease (newest registration wins), bridges Undertow proxy connections onto
 * tunnel streams, and answers heartbeats.
 *
 * The WebSocket handshake itself is unauthenticated; a connection that has
 * not registered within the auth window is dropped. All frame handling
 * arrives on the connection's serial receive lane (zenit's WS dispatch), so
 * control handling needs no extra ordering locks.
 */
public final class DevTunnelServerHandler implements WebSocketHandler, TunnelTransport {

    private static final long AUTH_TIMEOUT_MS = 10_000;
    private static final long IDLE_TIMEOUT_MS = 90_000;
    private static final int MAX_PRE_AUTH_CONNECTIONS = 32;
    private static final Pattern NAME_PATTERN =
        Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");

    /**
     * One shared scheduler for all connections' auth/idle timers: the endpoint is
     * unauthenticated until the register frame, so per-connection scheduler
     * threads would let anonymous peers burn platform threads.
     */
    private static final JobRunner TIMERS = JobRunner.create("dev-tunnel-timers");
    private static final AtomicInteger PRE_AUTH_CONNECTIONS = new AtomicInteger();

    private final WebSocketSession session;
    private final JobRunner jobs = JobRunner.create("dev-tunnel-server");
    private final Object sendLock = new Object();
    private final Map<Integer, TunnelStream> streams = new ConcurrentHashMap<>();
    private final AtomicInteger nextStreamId = new AtomicInteger(1);
    private final java.util.List<ScheduledJob> timers = new java.util.concurrent.CopyOnWriteArrayList<>();

    private volatile @Nullable DevLease lease;
    private volatile @Nullable DevTunnelBridge bridge;
    private volatile long lastSeenAt = System.currentTimeMillis();
    private volatile boolean closed;
    private volatile boolean preAuthCounted;

    public DevTunnelServerHandler(WebSocketSession session) {
        this.session = session;
        TunnelMessage.init();
    }

    // ------------------------------------------------------------------
    // WebSocketHandler
    // ------------------------------------------------------------------

    @Override
    public void onOpen() {
        if (PRE_AUTH_CONNECTIONS.incrementAndGet() > MAX_PRE_AUTH_CONNECTIONS) {
            PRE_AUTH_CONNECTIONS.decrementAndGet();
            Blast.log("Dev tunnel: refusing connection, too many pending registrations");
            teardown("too many pending registrations", true);
            return;
        }
        preAuthCounted = true;
        timers.add(TIMERS.schedule(() -> {
            if (lease == null && !closed) {
                // Transient: a slow registration round-trip must not permanently
                // silence the client; it reconnects and retries.
                sendControlQuietly(TunnelMessage.transientError("registration timeout"));
                teardown("no registration within " + (AUTH_TIMEOUT_MS / 1000) + "s", true);
            }
        }, AUTH_TIMEOUT_MS));
        timers.add(TIMERS.scheduleRepeating(() -> {
            if (!closed && System.currentTimeMillis() - lastSeenAt > IDLE_TIMEOUT_MS) {
                teardown("idle timeout", true);
            }
        }, 30_000));
    }

    @Override
    public void onTextMessage(String message) {
        lastSeenAt = System.currentTimeMillis();
        TunnelMessage msg;
        try {
            Object parsed = Zenit.DRY.parse(message);
            if (!(parsed instanceof TunnelMessage control)) {
                return;
            }
            msg = control;
        } catch (RuntimeException e) {
            Blast.log("Dev tunnel: unparseable control message:", e.getMessage());
            return;
        }

        switch (msg.getType()) {
            case TunnelMessage.REGISTER -> handleRegister(msg);
            case TunnelMessage.PING -> sendControlQuietly(TunnelMessage.pong());
            case TunnelMessage.PONG -> { /* lastSeenAt already touched */ }
            case TunnelMessage.EOF -> withStream(msg.getStream(), TunnelStream::receiveEof);
            case TunnelMessage.RESET -> withStream(msg.getStream(), TunnelStream::receiveReset);
            case TunnelMessage.ACK -> {
                Long bytes = msg.getBytes();
                if (bytes != null) {
                    withStream(msg.getStream(), stream -> stream.receiveAck(bytes));
                }
            }
            default -> Blast.log("Dev tunnel: unexpected control message:", msg.getType());
        }
    }

    @Override
    public void onBinaryMessage(byte[] frame) {
        lastSeenAt = System.currentTimeMillis();
        try {
            int streamId = TunnelFrames.streamId(frame);
            TunnelStream stream = streams.get(streamId);
            if (stream != null) {
                stream.receiveData(TunnelFrames.payload(frame));
            }
        } catch (RuntimeException e) {
            Blast.log("Dev tunnel: bad data frame:", e.getMessage());
        }
    }

    @Override
    public void onClose(int code, String reason) {
        teardown("connection closed", false);
    }

    @Override
    public void onError(Throwable error) {
        teardown("connection error: " + error.getMessage(), false);
    }

    // ------------------------------------------------------------------
    // Registration
    // ------------------------------------------------------------------

    private void handleRegister(TunnelMessage msg) {
        if (closed) {
            return; // teardown already ran; the close frame just hasn't landed yet
        }
        if (lease != null) {
            sendControlQuietly(TunnelMessage.error("already registered"));
            return;
        }

        Integer version = msg.getVersion();
        if (version == null || version != TunnelMessage.PROTOCOL_VERSION) {
            refuse("unsupported protocol version");
            return;
        }

        String name = msg.getName();
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            refuse("invalid name: use lowercase letters, digits and inner dashes");
            return;
        }

        String token = msg.getToken();
        if (token == null || token.isEmpty()) {
            refuse("missing token");
            return;
        }

        Row site = findNamespaceSite(token);
        if (site == null) {
            Blast.log("Dev tunnel: registration for '" + name + "' refused (unknown token)");
            refuse("invalid token");
            return;
        }

        Integer siteId = site.get(SiteModel.ID);
        String wildcardBase = firstWildcardBase(siteId);
        if (wildcardBase == null) {
            refuse("dev namespace has no wildcard domain configured");
            return;
        }

        String origin = "https://" + name + wildcardBase;
        DevTunnelBridge createdBridge;
        try {
            createdBridge = new DevTunnelBridge(this);
        } catch (Exception e) {
            // Transient: a bind/fd hiccup on the proxy should not brick the client.
            sendControlQuietly(TunnelMessage.transientError("could not allocate a bridge: " + e.getMessage()));
            teardown("bridge allocation failed: " + e.getMessage(), true);
            return;
        }

        DevLease newLease = new DevLease(siteId, name, origin, this, createdBridge, remoteDescription());
        DevLease replaced;
        // Publish bridge + lease + registry claim under the same lock teardown
        // uses to set `closed`: the auth timer firing mid-registration (the DB
        // scans above can straddle the window) must never leave a zombie lease
        // owned by a torn-down handler, nor leak the freshly bound bridge.
        synchronized (this) {
            if (closed) {
                createdBridge.close();
                Blast.log("Dev tunnel: registration for '" + name + "' lost the race with teardown");
                return;
            }
            this.bridge = createdBridge;
            // Lease is visible BEFORE the claim races anyone: a racing claim for
            // the same name replaces us immediately, and replacedBy() must see
            // the lease to log it (the bridge is closed via the bridge field).
            this.lease = newLease;
            replaced = DevLeases.claim(newLease);
        }
        releasePreAuthSlot();

        if (replaced != null) {
            // Off the receive lane: the old handler's send lock may be held by one
            // of its stream pumps, and this lane must not block behind it.
            DevTunnelServerHandler old = replaced.getHandler();
            JobRunner.startVirtualThread(old::replacedBy);
        }

        sendControlQuietly(TunnelMessage.registered(origin));
        Blast.log("Dev tunnel: '" + name + "' registered on site", siteId, "->", origin);
        Blast.slog("devtunnel.lease_claimed", Map.of(
            "site_id", siteId, "name", name, "origin", origin));
    }

    private void refuse(String reason) {
        sendControlQuietly(TunnelMessage.error(reason));
        teardown("registration refused: " + reason, true);
    }

    /** Constant-time token match over the enabled dev-namespace sites. */
    private static @Nullable Row findNamespaceSite(String token) {
        String typeId = DevNamespaceSiteType.ID.toString();
        Row match = null;
        for (Row site : Models.get(SiteModel.class).findEnabled()) {
            if (!typeId.equals(site.get(SiteModel.SITE_TYPE))) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> settings = (Map<String, Object>) site.get(SiteModel.SETTINGS);
            String siteToken = settings != null
                ? (String) settings.get(DevNamespaceSiteType.REGISTRATION_TOKEN_KEY) : null;
            // No early break: every candidate is compared so timing does not leak which
            // site (if any) matched.
            if (siteToken != null && SecureTokens.constantTimeEquals(siteToken, token) && match == null) {
                match = site;
            }
        }
        return match;
    }

    /** The ".dev.example.com" tail of the site's first wildcard domain, or null. */
    private static @Nullable String firstWildcardBase(int siteId) {
        for (Row domain : Models.get(SiteDomainModel.class).findBySiteId(siteId)) {
            String hostname = domain.get(SiteDomainModel.HOSTNAME);
            if (hostname != null && hostname.startsWith("*.") && hostname.length() > 2) {
                return hostname.substring(1).toLowerCase();
            }
        }
        return null;
    }

    private String remoteDescription() {
        // The WS handshake gives no peer info through the session API yet; keep a
        // stable placeholder rather than inventing one.
        return "tunnel client";
    }

    // ------------------------------------------------------------------
    // Streams (called by the bridge's accept loop)
    // ------------------------------------------------------------------

    void openStream(SocketChannel socket) {
        if (closed) {
            throw new IllegalStateException("tunnel connection is closed");
        }
        int id = nextStreamId.getAndIncrement();
        TunnelStream[] holder = new TunnelStream[1];
        TunnelStream stream = new TunnelStream(id, socket, this, jobs,
            sid -> streams.remove(sid, holder[0]));
        holder[0] = stream;
        streams.put(id, stream);
        if (closed) {
            // Teardown raced the accept loop and already cleared the map.
            streams.remove(id);
            stream.abort("tunnel connection closed", false);
            throw new IllegalStateException("tunnel connection is closed");
        }
        // The open control frame must precede the stream's first data frame; both go
        // through the send lock, and the pump only starts after open was sent.
        try {
            sendControl(TunnelMessage.open(id));
        } catch (RuntimeException e) {
            // A failed send means the connection is dying: do not leave a
            // never-started stream in the map until teardown finds it.
            streams.remove(id, stream);
            stream.abort("open send failed: " + e.getMessage(), false);
            throw e;
        }
        stream.startPump();
    }

    public int getActiveStreamCount() {
        return streams.size();
    }

    private void withStream(@Nullable Integer streamId, java.util.function.Consumer<TunnelStream> action) {
        if (streamId == null) {
            return;
        }
        TunnelStream stream = streams.get(streamId);
        if (stream != null) {
            action.accept(stream);
        }
    }

    // ------------------------------------------------------------------
    // TunnelTransport
    // ------------------------------------------------------------------

    @Override
    public void sendControl(TunnelMessage message) {
        String text = Zenit.DRY.stringify(message);
        synchronized (sendLock) {
            session.sendTextBlocking(text);
        }
    }

    @Override
    public void sendData(int streamId, byte[] chunk, int offset, int length) {
        byte[] frame = TunnelFrames.encode(streamId, chunk, offset, length);
        synchronized (sendLock) {
            session.sendBinaryBlocking(frame);
        }
    }

    private void sendControlQuietly(TunnelMessage message) {
        try {
            sendControl(message);
        } catch (RuntimeException ignored) {
            // Connection already unusable; teardown handles the rest.
        }
    }

    // ------------------------------------------------------------------
    // Teardown
    // ------------------------------------------------------------------

    /** A newer registration claimed this lease's name: tell the client and close. */
    void replacedBy() {
        sendControlQuietly(TunnelMessage.replaced());
        // The lease was already removed from the registry by the new claim.
        teardownConnection("replaced by a newer registration", true, false);
    }

    private void teardown(String reason, boolean closeSession) {
        teardownConnection(reason, closeSession, true);
    }

    private void teardownConnection(String reason, boolean closeSession, boolean releaseLease) {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }

        releasePreAuthSlot();
        for (ScheduledJob timer : timers) {
            timer.cancel();
        }
        timers.clear();

        // The bridge is closed via its own field: during a replace race the lease
        // may already belong to the winner while OUR bridge still needs closing.
        DevTunnelBridge currentBridge = bridge;
        if (currentBridge != null) {
            currentBridge.close();
        }

        DevLease current = lease;
        if (current != null) {
            if (releaseLease) {
                DevLeases.release(current);
            }
            Blast.log("Dev tunnel: '" + current.getName() + "' disconnected (" + reason + ")");
            Blast.slog("devtunnel.lease_released", Map.of(
                "site_id", current.getSiteId(), "name", current.getName(), "reason", reason));
        }

        for (TunnelStream stream : streams.values()) {
            stream.abort(reason, false);
        }
        streams.clear();
        jobs.shutdownNow();

        if (closeSession) {
            try {
                session.close(1000, reason);
            } catch (RuntimeException ignored) {
                // Session already gone.
            }
            // Backstop: a peer that never completes the close handshake (or keeps
            // sending while refusing to read) would otherwise hold the channel —
            // and any blocked send — open indefinitely.
            TIMERS.schedule(session::abort, 2_000);
        }
        else {
            // Transport already failed; make sure any blocked send is released.
            session.abort();
        }
    }

    private synchronized void releasePreAuthSlot() {
        if (preAuthCounted) {
            preAuthCounted = false;
            PRE_AUTH_CONNECTIONS.decrementAndGet();
        }
    }
}
