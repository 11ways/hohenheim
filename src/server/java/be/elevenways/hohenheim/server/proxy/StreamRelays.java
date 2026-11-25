package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.ports.StreamProtocol;
import be.elevenways.hohenheim.server.auth.SiteAuthGate;
import be.elevenways.hohenheim.server.sitetype.StreamProvider;
import be.elevenways.hohenheim.server.sitetype.StreamTarget;
import be.elevenways.hohenheim.server.upstream.UpstreamKindHandler;
import be.elevenways.hohenheim.server.upstream.UpstreamKindHandlers;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.util.Closeables;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.session.SessionStore;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The proxy's TCP/UDP streams: one bound public port per port of every enabled stream site, relayed to its upstream,
 * kept in step with the stored sites at every route reload.
 *
 * AIDEV-NOTE: a relay is keyed by its PUBLIC PORT, never by its site: a reload swaps a port's ROUTE (its site, its
 * upstream and its compiled access list) in place and rebinds only when the port's protocol changed, so an unrelated
 * site edit never cuts every player off a game server, and a port moved from one site into another
 * (SitePortMoves) keeps its listener and every open connection: only the route new connections take changes. The
 * ledger claim of a port is the site's own (SitePortClaims, in the save that changed it); this class only binds what
 * the stored sites say. A port it cannot bind is kept as a {@link StreamBindFailure} (the attention item, the site's
 * verdict and the alert read it) and tried again at every reconcile, which the listener supervisor runs each minute
 * while one is failing.
 *
 * AIDEV-NOTE: "0.0.0.0" is NOT an IPv4-only bind here. Measured 2026-11-19 on OpenJDK 25: on a host with IPv6, Java
 * opens AF_INET6 sockets with IPV6_V6ONLY off and binds the IPv4 wildcard as {@code ::}, so a stream's TCP and UDP
 * ports take IPv6 clients too (ServerSocket still reports the 0.0.0.0 it was asked for; a DatagramChannel reports
 * {@code ::}). An IPv4 client is reported as an IPv4 address, so access lists and the PROXY v2 header see TCP4 for it
 * and TCP6 only for a real IPv6 client. StreamClientAddressJourneyTest pins both families.
 */
final class StreamRelays {

    /** Who a new connection or client on one public port is let through to: the upstream, behind the access list. */
    private record Route(int siteId, @NonNull StreamTarget target, @Nullable AccessRuleTree access) {

        boolean admits(@NonNull InetSocketAddress client) {
            return this.access == null || this.access.admitsConnection(client.getAddress().getAddress());
        }
    }

    /** One bound public port. */
    private static final class Relay {
        final int port;
        final StreamProtocol protocol;
        final AtomicReference<Route> route;
        @Nullable PublicTcpListener tcp;
        @Nullable PublicUdpRelay udp;

        Relay(int port, StreamProtocol protocol, Route route) {
            this.port = port;
            this.protocol = protocol;
            this.route = new AtomicReference<>(route);
        }

        void close() {
            Closeables.closeQuietly(this.tcp);
            Closeables.closeQuietly(this.udp);
        }
    }

    /* The wildcard of both families on an IPv6 host: see the class note. */
    private static final String BIND_ADDRESS = "0.0.0.0";
    private static final int MAX_CONNECTIONS = 1_024;

    private final SessionStore sessionStore;
    /* Both keyed by public port: the ledger lets one site at a time hold a port. */
    private final Map<Integer, Relay> relays = new HashMap<>();
    private final Map<Integer, StreamBindFailure> failures = new HashMap<>();

    StreamRelays(@NonNull SessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    /** Binds, rebinds or closes every public port so they match the ports of the enabled stream sites. */
    synchronized void reconcile() {
        Map<Integer, Relay> wanted = new HashMap<>();
        Map<Integer, StreamBindFailure> failed = new HashMap<>();
        for (Row site : Models.get(SiteModel.class).findEnabled()) {
            UpstreamKindHandler handler = UpstreamKindHandlers.getHandler(site.get(SiteModel.UPSTREAM_KIND));
            if (!(handler instanceof StreamProvider provider)) continue;
            int siteId = site.get(SiteModel.ID);
            String name = String.valueOf((Object) site.get(SiteModel.NAME));
            List<StreamTarget> targets;
            AccessRuleTree access;
            try {
                Map<String, Object> settings = RawValues.mapOrNull(site.get(SiteModel.SETTINGS));
                targets = provider.streamTargets(site, settings == null ? Map.of() : settings);
                access = accessOf(site);
            } catch (RuntimeException refused) {
                Blast.log("Stream relays: site", name, "is not forwarded -", refused.getMessage());
                continue;
            }
            for (StreamTarget target : targets) {
                int port = target.listenPort();
                if (wanted.containsKey(port) || failed.containsKey(port)) {
                    // Only a store the ledger never judged holds one port twice: the first site keeps it.
                    Blast.log("Stream relays: port", port, "of", name, "is another stream's already");
                    continue;
                }
                Route route = new Route(siteId, target, access);
                Relay relay = this.relays.remove(port);
                if (relay != null && relay.protocol == target.protocol()) {
                    relay.route.set(route);
                    wanted.put(port, relay);
                    continue;
                }
                if (relay != null) {
                    relay.close();
                }
                try {
                    wanted.put(port, start(name, route));
                } catch (IOException | RuntimeException failure) {
                    StreamBindFailure before = this.failures.get(port);
                    int attempts = before != null && before.siteId() == siteId
                        && before.protocol() == target.protocol() ? before.attempts() + 1 : 1;
                    failed.put(port, new StreamBindFailure(siteId, name, port, target.protocol(),
                        ListenerBindFailures.explainStream(failure, name, port, target.protocol()), attempts));
                }
            }
        }
        // Whatever is left belongs to a port no enabled stream lists any more: removed, switched off, trashed.
        this.relays.values().forEach(Relay::close);
        this.relays.clear();
        this.relays.putAll(wanted);
        this.failures.clear();
        this.failures.putAll(failed);
    }

    /** @return the stream ports the last reconcile could not bind, by site */
    synchronized @NonNull List<StreamBindFailure> failures() {
        return List.copyOf(this.failures.values());
    }

    /** @return why some of a stream site's ports are not bound, empty when every one is or the site is no stream */
    synchronized @NonNull List<StreamBindFailure> failuresOf(int siteId) {
        return this.failures.values().stream().filter(failure -> failure.siteId() == siteId).toList();
    }

    /** Closes every stream's port. */
    synchronized void closeAll() {
        this.relays.values().forEach(Relay::close);
        this.relays.clear();
        this.failures.clear();
    }

    /** @return the bound TCP address of a site's public port, null when nothing listens on it for that site */
    synchronized @Nullable InetSocketAddress tcpAddress(int siteId, int port) {
        Relay relay = this.relayOf(siteId, port);
        return relay == null || relay.tcp == null ? null : relay.tcp.getLocalAddress();
    }

    /** @return the bound UDP address of a site's public port, null when nothing listens on it for that site */
    synchronized @Nullable InetSocketAddress udpAddress(int siteId, int port) {
        Relay relay = this.relayOf(siteId, port);
        return relay == null || relay.udp == null ? null : relay.udp.getLocalAddress();
    }

    /** @return the port's relay while it routes for this site, null when another site holds it or none listens */
    private @Nullable Relay relayOf(int siteId, int port) {
        Relay relay = this.relays.get(port);
        return relay == null || relay.route.get().siteId() != siteId ? null : relay;
    }

    /** @throws IOException when a transport's port cannot be bound; whatever was bound is closed again */
    private @NonNull Relay start(@NonNull String name, @NonNull Route route) throws IOException {
        StreamTarget target = route.target();
        Relay relay = new Relay(target.listenPort(), target.protocol(), route);
        try {
            if (target.protocol().forwardsTcp()) {
                relay.tcp = PublicTcpListener.forStream(BIND_ADDRESS, target.listenPort(),
                    (input, source, destination) -> tcpDecision(relay.route.get(), source),
                    MAX_CONNECTIONS, failure -> Blast.log("Stream relays: TCP port", target.listenPort(),
                        "of", name, "stopped -", failure.getMessage()));
                relay.tcp.start();
            }
            if (target.protocol().forwardsUdp()) {
                relay.udp = new PublicUdpRelay(BIND_ADDRESS, target.listenPort(),
                    client -> udpUpstream(relay.route.get(), client));
                relay.udp.start();
            }
        } catch (IOException | RuntimeException failure) {
            relay.close();
            Blast.log("Stream relays: could not listen on port", target.listenPort(), "for", name, "-",
                failure.getMessage());
            throw failure;
        }
        Blast.log("Stream relays:", name, "listens on", target.listenPort() + "/" + target.protocol().token(),
            "->", target.forwardHost() + ":" + target.forwardPort());
        return relay;
    }

    private static ConnectionRouter.@NonNull Decision tcpDecision(@NonNull Route route,
                                                                  @NonNull InetSocketAddress source) {
        if (!route.admits(source)) {
            return ConnectionRouter.Decision.reject();
        }
        StreamTarget target = route.target();
        return new ConnectionRouter.Decision(BackendChoice.external(target.forwardHost(), target.forwardPort(),
            target.sendsProxyProtocolV2(), target.connectTimeoutMs(), target.publicOnly()),
            ConnectionRouter.NOTHING_CONSUMED);
    }

    private static @Nullable InetSocketAddress udpUpstream(@NonNull Route route, @NonNull InetSocketAddress client)
            throws IOException {
        if (!route.admits(client)) {
            return null;
        }
        StreamTarget target = route.target();
        return BackendConnector.datagramTarget(target.forwardHost(), target.forwardPort(), target.connectTimeoutMs(),
            target.listenPort(), target.publicOnly());
    }

    /**
     * @return the site's access list compiled for raw connections, null when it names none; a list that is gone
     *         denies everyone
     */
    private @Nullable AccessRuleTree accessOf(@NonNull Row site) {
        Integer listId = site.get(SiteModel.ACCESS_LIST_ID);
        if (listId == null) {
            return null;
        }
        Row list = Models.get(AccessListModel.class).findById(listId);
        if (list == null) {
            return AccessRuleTree.denyAll("access list " + listId + " (missing)");
        }
        List<Row> rules = Models.get(AccessRuleModel.class).find()
            .where(AccessRuleModel.ACCESS_LIST_ID.eq(listId))
            .orderBy(AccessRuleModel.SORT, SortOrder.ASC)
            .orderBy(AccessRuleModel.ID, SortOrder.ASC).all();
        int siteId = site.get(SiteModel.ID);
        String name = String.valueOf((Object) site.get(SiteModel.NAME));
        return AccessRuleTree.compile(list.get(AccessListModel.SATISFY), rules, new AccessRuleTree.LeafContext() {
            @Override public @NonNull String realm() { return name; }
            @Override public int siteId() { return siteId; }
            @Override public @NonNull SessionStore sessionStore() { return StreamRelays.this.sessionStore; }

            /* A stream cannot send anyone to a login: a provider leaf compiles to a refusing one. */
            @Override public @Nullable SiteAuthGate gateFor(int providerId, @Nullable String requiredPermission) {
                return null;
            }
        });
    }
}
