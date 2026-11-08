package be.elevenways.hohenheim.server.upstream.kinds;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimFormSections;
import be.elevenways.hohenheim.HohenheimPaths;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.server.proxy.SiteDispatcher;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.sitetype.FaultedSiteHandler;
import be.elevenways.hohenheim.server.sitetype.ProbeableUpstream;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.WebSocketUpgrades;
import be.elevenways.hohenheim.server.upstream.TenantUpstreams;
import be.elevenways.hohenheim.server.upstream.UpstreamKindHandler;
import be.elevenways.hohenheim.server.sitetype.UnixSocketBridgeConnection;
import be.elevenways.hohenheim.server.sitetype.UpstreamForwarder;
import be.elevenways.hohenheim.server.sitetype.UpstreamProtocol;
import be.elevenways.hohenheim.server.sitetype.UpstreamTarget;
import be.elevenways.protoblast.common.http.DefaultPorts;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.BadgeColor;
import be.elevenways.zenit.common.ui.ColorHue;
import be.elevenways.zenit.server.net.OutboundUrlGuard;
import be.elevenways.zenit.server.net.PinnedUpstreamDial;
import io.undertow.server.HttpServerExchange;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.PathKind;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.xnio.OptionMap;

/**
 * Forwards requests to an upstream HTTP/HTTPS server.
 */
public class AddressUpstreamKind implements UpstreamKindHandler {

    public static final Identifier ID = HohenheimIds.id("address");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    public static final EnumField FORWARD_SCHEME = SETTINGS_SCHEMA.addField(
        EnumField.builder("forward_scheme")
            .value("http", "HTTP", HohenheimMicrocopy.UPSTREAM_SCHEME.of("http"))
            .value("https", "HTTPS", HohenheimMicrocopy.UPSTREAM_SCHEME.of("https"))
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("forward_scheme"))
            .help(HohenheimMicrocopy.HELP.of("forward_scheme"))
            .build());

    public static final StringField FORWARD_HOST = SETTINGS_SCHEMA.addField(
        StringField.builder().name("forward_host").label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("forward_host"))
            .help(HohenheimMicrocopy.HELP.of("forward_host")).build());

    public static final IntegerField FORWARD_PORT = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("forward_port").label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("forward_port"))
            .help(HohenheimMicrocopy.HELP.of("forward_port")).build());

    /** Further host[:port] backends, one per line: requests take turns among them all and pass a silent one over. */
    public static final TextField MORE_BACKENDS = SETTINGS_SCHEMA.addField(
        TextField.builder("more_backends")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("more_backends"))
            .help(HohenheimMicrocopy.HELP.of("more_backends")).build());

    public static final EnumField UPSTREAM_PROTOCOL = SETTINGS_SCHEMA.addField(protocolField());

    public static final IntegerField REQUEST_TIMEOUT = SETTINGS_SCHEMA.addField(
        UpstreamSettings.requestTimeout());

    public static final BooleanField WEBSOCKET_UPGRADE = SETTINGS_SCHEMA.addField(
        BooleanField.builder("websocket_upgrade").defaultValue(true)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("websocket_upgrade"))
            .help(HohenheimMicrocopy.HELP.of("websocket_upgrade")).build());

    public static final BooleanField IGNORE_CERTIFICATES = SETTINGS_SCHEMA.addField(
        BooleanField.builder("ignore_certificates").defaultValue(false)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("ignore_certificates"))
            .help(HohenheimMicrocopy.HELP.of("ignore_certificates")).build());

    public static final BooleanField REWRITE_LOCATION = SETTINGS_SCHEMA.addField(
        BooleanField.builder("rewrite_location").defaultValue(true)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("rewrite_location"))
            .help(HohenheimMicrocopy.HELP.of("rewrite_location")).build());

    public static final StringField SOCKET = SETTINGS_SCHEMA.addField(
        PathField.builder("socket").browserSource(HohenheimPaths.SERVER_FILES, PathKind.ANY)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("upstream_socket"))
            .help(HohenheimMicrocopy.HELP.of("upstream_socket")).build());

    public static final IntegerField DELAY = SETTINGS_SCHEMA.addField(UpstreamSettings.delay());

    // The decision is WHERE to forward: scheme, host, port, or a unix socket instead.
    // How the connection behaves is the next thing an operator comes to
    // change, so it stays open, the protocol pin last. AIDEV-NOTE: never the framework's generic
    // "Advanced" section here: the site form around this sub-form has its own, and two folded
    // "Advanced" cards on one page cannot be told apart. Membership is validated eagerly, so the
    // section is declared after the fields.
    static {
        SETTINGS_SCHEMA.addSection(HohenheimFormSections.open(HohenheimFormSections.FORWARDING,
            List.of(WEBSOCKET_UPGRADE.getName(), REWRITE_LOCATION.getName(), IGNORE_CERTIFICATES.getName(),
                REQUEST_TIMEOUT.getName(), DELAY.getName(), UPSTREAM_PROTOCOL.getName())));
    }

    @Override
    public Identifier typeId() { return ID; }

    @Override
    public String getDisplayName() { return "Address"; }

    @Override
    public Icon getIcon() { return Icon.of("arrow-right"); }

    @Override
    public BadgeColor color() { return ColorHue.VIOLET; }

    @Override
    public Schema getSchema() { return SETTINGS_SCHEMA; }

    /** The named address: the unix socket when one is set, else host:port. */
    @Override
    public String upstreamSummary(Map<String, Object> settings) {
        Object socket = settings.get(SOCKET.getName());
        if (socket != null && !String.valueOf(socket).isBlank()) {
            return String.valueOf(socket);
        }
        Object host = settings.get(FORWARD_HOST.getName());
        Object port = settings.get(FORWARD_PORT.getName());
        if (host == null || String.valueOf(host).isBlank()) {
            return null;
        }
        return port != null ? host + ":" + port : String.valueOf(host);
    }

    /** The protocol choice, one option per {@link UpstreamProtocol} member, derived from its home. */
    private static EnumField protocolField() {
        EnumField.Builder builder = EnumField.builder("upstream_protocol");
        for (UpstreamProtocol protocol : UpstreamProtocol.values()) {
            builder.value(protocol.token(), protocol.title(),
                HohenheimMicrocopy.UPSTREAM_PROTOCOL.of(protocol.token()));
        }
        return builder
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("upstream_protocol"))
            .help(HohenheimMicrocopy.HELP.of("upstream_protocol"))
            .build();
    }

    @Override
    public SiteRequestHandler createHandler(Row site, Map<String, Object> settings) {
        String socketSetting = (String) settings.get("socket");
        String socket = socketSetting != null && !socketSetting.isEmpty() ? socketSetting : null;
        String host = (String) settings.get("forward_host");
        boolean websocketEnabled = RawValues.isOn(settings, WEBSOCKET_UPGRADE);
        boolean ignoreCertificates = RawValues.isOn(settings, IGNORE_CERTIFICATES);
        UpstreamProtocol protocol = UpstreamProtocol.fromSetting(settings.get("upstream_protocol"));
        // Default true when absent: sites created before the field existed keep rewriting.
        boolean rewriteLocation = RawValues.isOn(settings, REWRITE_LOCATION);

        if (socket == null && (host == null || host.isEmpty())) {
            return (exchange, forwarder) -> {
                exchange.setStatusCode(502);
                exchange.getResponseSender().send("No upstream configured");
            };
        }
        String scheme = (String) settings.getOrDefault("forward_scheme", "http");
        List<ProbeableUpstream.Target> more;
        try {
            more = moreBackends(settings.get(MORE_BACKENDS.getName()), scheme);
        } catch (IllegalArgumentException unreadable) {
            return (exchange, forwarder) -> {
                exchange.setStatusCode(502);
                exchange.getResponseSender().send("Invalid upstream: " + unreadable.getMessage());
            };
        }

        // AIDEV-NOTE: a tenant-owned site reaches the public internet only (TenantUpstreams).
        // The socket check comes FIRST because socket OVERRIDES forward_host below: a tenant
        // could pass the write-time forward_host check with a public name and still dial a
        // unix socket through this setting.
        // publicOnly: tenant-owned AND not marked trusted by the operator.
        boolean tenantOwned = TenantUpstreams.publicOnly(site);
        Integer siteId = site.get(SiteModel.ID);
        if (tenantOwned && (socket != null || !more.isEmpty())) {
            // A further backend would be dialed without the per-request public-address vetting a tenant's one host
            // gets, so a tenant-owned site faults rather than reach a private address through it.
            return new FaultedSiteHandler(siteId != null ? siteId : -1,
                HohenheimMicrocopy.SITE_FAULT.of(socket != null ? "tenant_socket" : "tenant_more_backends"));
        }

        // Socket mode takes precedence over url mode (matching the Node implementation). The unix
        // socket is reached through a loopback bridge held by the handler and reused across requests.
        if (socket != null) {
            boolean hasPlaceholders = PLACEHOLDER.matcher(socket).find();
            return new ProxySocketHandler(socket, hasPlaceholders, ignoreCertificates,
                websocketEnabled, rewriteLocation, protocol);
        }

        Object portObj = settings.get("forward_port");
        int port = portObj instanceof Integer i ? i : DefaultPorts.of(scheme);

        URI upstream;
        try {
            upstream = new URI(scheme, null, host, port, "/", null, null);
        } catch (Exception e) {
            return (exchange, forwarder) -> {
                exchange.setStatusCode(502);
                exchange.getResponseSender().send("Invalid upstream: " + e.getMessage());
            };
        }

        if (tenantOwned) {
            Boolean literalPublic = TenantUpstreams.literalIsPublic(host);
            if (Boolean.FALSE.equals(literalPublic)) {
                return new FaultedSiteHandler(siteId != null ? siteId : -1,
                    HohenheimMicrocopy.SITE_FAULT.of("tenant_private_address"));
            }
            return new TenantAddressHandler(scheme, host, port, protocol, ignoreCertificates,
                websocketEnabled, rewriteLocation);
        }

        List<Backend> backends = new ArrayList<>();
        backends.add(new Backend(new ProbeableUpstream.Target(host, port), upstream));
        for (ProbeableUpstream.Target backend : more) {
            try {
                backends.add(new Backend(backend,
                    new URI(scheme, null, backend.host(), backend.port(), "/", null, null)));
            } catch (URISyntaxException unaddressable) {
                return (exchange, forwarder) -> {
                    exchange.setStatusCode(502);
                    exchange.getResponseSender().send("Invalid upstream: " + backend.host());
                };
            }
        }
        return new FixedAddressHandler(backends, protocol, ignoreCertificates, websocketEnabled, rewriteLocation);
    }

    /**
     * The further backends a settings value names, one {@code host[:port]} per line (or comma), a missing port the
     * scheme's own.
     *
     * @param value the {@link #MORE_BACKENDS} setting as stored, null or blank for none
     * @throws IllegalArgumentException naming the first entry that is no host[:port]
     */
    public static @NonNull List<ProbeableUpstream.Target> moreBackends(@Nullable Object value, @NonNull String scheme) {
        if (value == null) {
            return List.of();
        }
        List<ProbeableUpstream.Target> backends = new ArrayList<>();
        for (String entry : String.valueOf(value).split("[\\n,]")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            URI parsed;
            try {
                parsed = new URI("tcp://" + trimmed);
            } catch (URISyntaxException unreadable) {
                throw new IllegalArgumentException(trimmed, unreadable);
            }
            if (parsed.getHost() == null || parsed.getRawPath() != null && !parsed.getRawPath().isEmpty()
                    || parsed.getUserInfo() != null || parsed.getQuery() != null || parsed.getFragment() != null) {
                throw new IllegalArgumentException(trimmed);
            }
            int port = parsed.getPort() < 0 ? DefaultPorts.of(scheme) : parsed.getPort();
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException(trimmed);
            }
            backends.add(new ProbeableUpstream.Target(parsed.getHost().replaceAll("^\\[|]$", ""), port));
        }
        return List.copyOf(backends);
    }

    /** One backend of an operator-configured site: where it is, and the URI it is dialed at. */
    private record Backend(ProbeableUpstream.Target address, URI uri) {}

    /**
     * Forwards an operator-configured site to its fixed backends, which the dispatcher also probes on its own so an
     * upstream that does not answer shows as down before any visitor finds out.
     *
     * AIDEV-NOTE: with several backends each request starts at the next one in turn, passing over one the last probe
     * found silent, and dials the others after it in order when its first dial is refused (the dial plan's own
     * retry), so one backend going down costs a visitor a refused connect at most, never an error page. A backend
     * that is down for the probe is still dialed last: a probe can be wrong, and a request with nowhere else to go
     * loses nothing by trying it. One backend dials exactly as a single address always did.
     */
    private static final class FixedAddressHandler implements SiteRequestHandler, ProbeableUpstream {

        private final List<Backend> backends;
        private final UpstreamProtocol protocol;
        private final boolean ignoreCertificates;
        private final boolean websocketEnabled;
        private final boolean rewriteLocation;
        private final AtomicInteger turn = new AtomicInteger();
        private final Set<Target> silent = ConcurrentHashMap.newKeySet();

        FixedAddressHandler(List<Backend> backends, UpstreamProtocol protocol, boolean ignoreCertificates,
                            boolean websocketEnabled, boolean rewriteLocation) {
            this.backends = List.copyOf(backends);
            this.protocol = protocol;
            this.ignoreCertificates = ignoreCertificates;
            this.websocketEnabled = websocketEnabled;
            this.rewriteLocation = rewriteLocation;
        }

        @Override
        public void handleRequest(HttpServerExchange exchange, UpstreamForwarder forwarder) {
            if (WebSocketUpgrades.refuse(exchange, this.websocketEnabled)) {
                return;
            }
            if (this.rewriteLocation) {
                exchange.putAttachment(SiteDispatcher.REWRITE_LOCATION, Boolean.TRUE);
            }
            forwarder.forwardTo(target());
        }

        /** @return this request's target: one backend as named, several as dials in this request's turn */
        UpstreamTarget target() {
            if (this.backends.size() == 1) {
                return new UpstreamTarget(this.backends.get(0).uri(), this.protocol, this.ignoreCertificates);
            }
            // The turn goes round the backends that answer, so a silent one's share is spread evenly over the rest;
            // the silent ones follow as the last dials, in case the probe was wrong.
            List<PinnedUpstreamDial> answering = new ArrayList<>();
            List<PinnedUpstreamDial> passedOver = new ArrayList<>();
            for (Backend backend : this.backends) {
                PinnedUpstreamDial dial = new PinnedUpstreamDial(backend.uri(), OptionMap.EMPTY);
                (this.silent.contains(backend.address()) ? passedOver : answering).add(dial);
            }
            List<PinnedUpstreamDial> turnOver = answering.isEmpty() ? passedOver : answering;
            int start = Math.floorMod(this.turn.getAndIncrement(), turnOver.size());
            List<PinnedUpstreamDial> dials = new ArrayList<>(turnOver.subList(start, turnOver.size()));
            dials.addAll(turnOver.subList(0, start));
            if (turnOver == answering) {
                dials.addAll(passedOver);
            }
            return UpstreamTarget.pinned(dials.get(0).uri(), dials, this.protocol, this.ignoreCertificates);
        }

        @Override
        public @NonNull List<Target> probeTargets() {
            return this.backends.stream().map(Backend::address).toList();
        }

        @Override
        public void probed(@NonNull Target target, boolean answered) {
            if (answered) {
                this.silent.remove(target);
            } else {
                this.silent.add(target);
            }
        }
    }

    /**
     * Forwards a TENANT-owned site to a named host only after the name's addresses are vetted
     * as public, per request (the verdict is cached briefly in {@link TenantUpstreams}).
     *
     * AIDEV-NOTE: every upstream, cleartext or TLS, is dialed at a VETTED address through
     * zenit's {@link PinnedUpstreamDial}, so a DNS answer that changes between the check and
     * the dial cannot redirect it; an address that refuses falls through to the next vetted one.
     * TLS still sends and verifies the configured host name, and the Host header is forwarded
     * unchanged (the proxy never rewrites it).
     */
    private static final class TenantAddressHandler implements SiteRequestHandler {

        private final String scheme;
        private final String host;
        private final int port;
        private final UpstreamProtocol protocol;
        private final boolean ignoreCertificates;
        private final boolean websocketEnabled;
        private final boolean rewriteLocation;

        TenantAddressHandler(String scheme, String host, int port, UpstreamProtocol protocol,
                             boolean ignoreCertificates, boolean websocketEnabled,
                             boolean rewriteLocation) {
            this.scheme = scheme;
            this.host = host;
            this.port = port;
            this.protocol = protocol;
            this.ignoreCertificates = ignoreCertificates;
            this.websocketEnabled = websocketEnabled;
            this.rewriteLocation = rewriteLocation;
        }

        @Override
        public void handleRequest(HttpServerExchange exchange, UpstreamForwarder forwarder) {
            if (WebSocketUpgrades.refuse(exchange, websocketEnabled)) {
                return;
            }
            // Vetting may resolve a name: never on the I/O thread.
            if (exchange.isInIoThread()) {
                exchange.dispatch(() -> handleRequest(exchange, forwarder));
                return;
            }
            OutboundUrlGuard.Verdict verdict = TenantUpstreams.vet(scheme, host, true);
            if (!(verdict instanceof OutboundUrlGuard.Allowed allowed) || allowed.addresses().isEmpty()) {
                exchange.setStatusCode(502);
                exchange.getResponseSender().send("Upstream refused: a tenant-owned site may only "
                    + "forward to a public address");
                return;
            }
            URI upstream;
            List<PinnedUpstreamDial> dials;
            try {
                upstream = new URI(scheme, null, host, port, "/", null, null);
                dials = PinnedUpstreamDial.allOf(upstream, allowed);
            } catch (URISyntaxException | IllegalArgumentException e) {
                exchange.setStatusCode(502);
                exchange.getResponseSender().send("Invalid upstream");
                return;
            }
            if (rewriteLocation) {
                exchange.putAttachment(SiteDispatcher.REWRITE_LOCATION, Boolean.TRUE);
            }
            forwarder.forwardTo(UpstreamTarget.pinned(upstream, dials, protocol, ignoreCertificates));
        }
    }

    /**
     * Handles a unix-socket proxy upstream. Each distinct resolved socket path (placeholders are
     * substituted from regex-host capture groups per request) gets its own long-lived loopback
     * bridge, cached here and closed on {@link #destroy()}.
     */
    private static final class ProxySocketHandler implements SiteRequestHandler {

        private final String socketTemplate;
        private final boolean hasPlaceholders;
        private final boolean ignoreCertificates;
        private final boolean websocketEnabled;
        private final boolean rewriteLocation;
        private final UpstreamProtocol protocol;
        private final Map<String, UnixSocketBridgeConnection> bridges = new ConcurrentHashMap<>();

        ProxySocketHandler(String socketTemplate, boolean hasPlaceholders,
                           boolean ignoreCertificates, boolean websocketEnabled,
                           boolean rewriteLocation, UpstreamProtocol protocol) {
            this.socketTemplate = socketTemplate;
            this.hasPlaceholders = hasPlaceholders;
            this.ignoreCertificates = ignoreCertificates;
            this.websocketEnabled = websocketEnabled;
            this.rewriteLocation = rewriteLocation;
            this.protocol = protocol;
        }

        @Override
        public void handleRequest(HttpServerExchange exchange, UpstreamForwarder forwarder) {
            if (WebSocketUpgrades.refuse(exchange, websocketEnabled)) {
                return;
            }

            String resolvedSocket = socketTemplate;
            if (hasPlaceholders) {
                Map<String, String> groups = exchange.getAttachment(SiteDispatcher.MATCHED_GROUPS);
                try {
                    resolvedSocket = substitutePlaceholders(socketTemplate, groups);
                } catch (IllegalArgumentException unsafe) {
                    // A Host-derived capture tried to escape the socket path (see
                    // substitutePlaceholders): refuse rather than dial an attacker-chosen path.
                    exchange.setStatusCode(502);
                    exchange.getResponseSender().send("Invalid upstream socket");
                    return;
                }
            }

            UnixSocketBridgeConnection conn;
            try {
                conn = bridgeFor(resolvedSocket);
            } catch (IOException e) {
                exchange.setStatusCode(502);
                exchange.getResponseSender().send("Cannot reach socket upstream: " + e.getMessage());
                return;
            }
            if (rewriteLocation) {
                exchange.putAttachment(SiteDispatcher.REWRITE_LOCATION, Boolean.TRUE);
            }
            forwarder.forwardTo(new UpstreamTarget(conn.connectUri(), protocol,
                conn.ignoreCertificates()));
        }

        private UnixSocketBridgeConnection bridgeFor(String socketPath) throws IOException {
            UnixSocketBridgeConnection existing = bridges.get(socketPath);
            if (existing != null) {
                return existing;
            }
            // computeIfAbsent can't propagate the checked IOException, so guard the create explicitly.
            synchronized (bridges) {
                existing = bridges.get(socketPath);
                if (existing != null) {
                    return existing;
                }
                // verifyReachable=true: this cache is keyed by a resolved (placeholder-
                // substituted, Host-derived) socket path, so an unreachable path must FAIL
                // here rather than be cached as a permanent no-op bridge (a leaked loopback
                // listener + accept thread per distinct path). Each later request retries fresh.
                UnixSocketBridgeConnection created =
                    new UnixSocketBridgeConnection(socketPath, ignoreCertificates, true);
                bridges.put(socketPath, created);
                return created;
            }
        }

        @Override
        public void destroy() {
            for (UnixSocketBridgeConnection conn : bridges.values()) {
                conn.close();
            }
            bridges.clear();
        }
    }

    /** {name} or {0} placeholders resolved against regex-host capture groups. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*|\\d+)\\}");

    /**
     * Resolve socket-path placeholders from regex-host capture groups.
     *
     * AIDEV-NOTE: capture-group values originate in the untrusted Host header
     * ({@code Hostnames.fromHostHeader} does no charset validation), so a substituted value is
     * validated at the tier it is trusted -- here, where it becomes part of a filesystem path.
     * A value carrying a path separator, {@code ..}, NUL or any control character is rejected,
     * closing the path-escape that an operator regex with a permissive capture would otherwise
     * open ({@code {project}} matching {@code ../../etc}).
     *
     * @throws IllegalArgumentException when a substituted value is unsafe for a socket path
     */
    static String substitutePlaceholders(String template, Map<String, String> groups) {
        if (template == null || groups == null || groups.isEmpty()) {
            return template;
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String value = groups.get(name);
            if (value != null) {
                if (!isSafePathSegment(value)) {
                    throw new IllegalArgumentException("unsafe socket placeholder value");
                }
                m.appendReplacement(out, Matcher.quoteReplacement(value));
            } else {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * A capture value is safe to splice into a socket path only if it names no separator, no {@code ..} and no control
     * char.
     */
    private static boolean isSafePathSegment(String value) {
        if (value.isEmpty() || value.equals(".") || value.contains("..")) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '/' || c == '\\' || c <= ' ' || c == 0x7f) {
                return false;
            }
        }
        return true;
    }
}
