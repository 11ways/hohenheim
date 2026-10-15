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
 * The proxy's TCP/UDP streams: one public port per enabled stream site, relayed to its upstream, kept in step with
 * the stored sites at every route reload.
 *
 * AIDEV-NOTE: a reload swaps a stream's ROUTE (its upstream and its compiled access list) in place and rebinds only
 * when its port or protocol changed: an unrelated site edit must never cut every player off a game server. The
 * ledger claim of the port is the site's own (SitePortClaims, in the save that changed it); this class only binds
 * what the stored sites say, and a port it cannot bind is logged and left for the next reload.
 */
final class StreamRelays {

    /** Who a new connection or client is let through to: the upstream, behind the access list when one is set. */
    private record Route(@NonNull StreamTarget target, @Nullable AccessRuleTree access) {

        boolean admits(@NonNull InetSocketAddress client) {
            return this.access == null || this.access.admitsConnection(client.getAddress().getAddress());
        }
    }

    /** One stream's bound public port. */
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

    private static final String BIND_ADDRESS = "0.0.0.0";
    private static final int MAX_CONNECTIONS = 1_024;

    private final SessionStore sessionStore;
    private final Map<Integer, Relay> relays = new HashMap<>();

    StreamRelays(@NonNull SessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    /** Binds, rebinds or closes every stream's port so they match the enabled stream sites. */
    synchronized void reconcile() {
        Map<Integer, Relay> wanted = new HashMap<>();
        for (Row site : Models.get(SiteModel.class).findEnabled()) {
            UpstreamKindHandler handler = UpstreamKindHandlers.getHandler(site.get(SiteModel.UPSTREAM_KIND));
            if (!(handler instanceof StreamProvider provider)) continue;
            int siteId = site.get(SiteModel.ID);
            Route route;
            try {
                Map<String, Object> settings = RawValues.mapOrNull(site.get(SiteModel.SETTINGS));
                route = new Route(provider.streamTarget(site, settings == null ? Map.of() : settings),
                    accessOf(site));
            } catch (RuntimeException refused) {
                Blast.log("Stream relays: site", site.get(SiteModel.NAME), "is not forwarded -", refused.getMessage());
                continue;
            }
            Relay relay = this.relays.remove(siteId);
            if (relay != null && relay.port == route.target().listenPort()
                    && relay.protocol == route.target().protocol()) {
                relay.route.set(route);
                wanted.put(siteId, relay);
                continue;
            }
            if (relay != null) {
                relay.close();
            }
            Relay started = start(String.valueOf((Object) site.get(SiteModel.NAME)), route);
            if (started != null) {
                wanted.put(siteId, started);
            }
        }
        // Whatever is left belongs to a stream that was switched off, trashed, deleted or changed kind.
        this.relays.values().forEach(Relay::close);
        this.relays.clear();
        this.relays.putAll(wanted);
    }

    /** Closes every stream's port. */
    synchronized void closeAll() {
        this.relays.values().forEach(Relay::close);
        this.relays.clear();
    }

    /** @return the bound TCP address of a stream site, null when it listens on none */
    synchronized @Nullable InetSocketAddress tcpAddress(int siteId) {
        Relay relay = this.relays.get(siteId);
        return relay == null || relay.tcp == null ? null : relay.tcp.getLocalAddress();
    }

    /** @return the bound UDP address of a stream site, null when it listens on none */
    synchronized @Nullable InetSocketAddress udpAddress(int siteId) {
        Relay relay = this.relays.get(siteId);
        return relay == null || relay.udp == null ? null : relay.udp.getLocalAddress();
    }

    private @Nullable Relay start(@NonNull String name, @NonNull Route route) {
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
            return null;
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
            false, target.connectTimeoutMs(), target.publicOnly()), ConnectionRouter.NOTHING_CONSUMED);
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
