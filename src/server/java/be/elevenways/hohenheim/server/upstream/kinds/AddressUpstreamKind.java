package be.elevenways.hohenheim.server.upstream.kinds;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.HohenheimFormSections;
import be.elevenways.hohenheim.HohenheimPaths;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.PathKind;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Forwards requests to an upstream HTTP/HTTPS server.
 */
public class AddressUpstreamKind implements UpstreamKindHandler {

    public static final Identifier ID = HohenheimIds.id("address");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    public static final EnumField FORWARD_SCHEME = SETTINGS_SCHEMA.addField(
        EnumField.builder("forward_scheme")
            .value("http", "HTTP", UpstreamCopy.scheme("http"))
            .value("https", "HTTPS", UpstreamCopy.scheme("https"))
            .label(HohenheimFormCopy.label("forward_scheme"))
            .help(HohenheimFormCopy.help("forward_scheme"))
            .build());

    public static final StringField FORWARD_HOST = SETTINGS_SCHEMA.addField(
        StringField.builder().name("forward_host").label(HohenheimFormCopy.label("forward_host"))
            .help(HohenheimFormCopy.help("forward_host")).build());

    public static final IntegerField FORWARD_PORT = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("forward_port").label(HohenheimFormCopy.label("forward_port"))
            .help(HohenheimFormCopy.help("forward_port")).build());

    public static final EnumField UPSTREAM_PROTOCOL = SETTINGS_SCHEMA.addField(protocolField());

    public static final IntegerField REQUEST_TIMEOUT = SETTINGS_SCHEMA.addField(
        UpstreamSettings.requestTimeout());

    public static final BooleanField WEBSOCKET_UPGRADE = SETTINGS_SCHEMA.addField(
        BooleanField.builder("websocket_upgrade").defaultValue(true)
            .label(HohenheimFormCopy.label("websocket_upgrade"))
            .help(HohenheimFormCopy.help("websocket_upgrade")).build());

    public static final BooleanField IGNORE_CERTIFICATES = SETTINGS_SCHEMA.addField(
        BooleanField.builder("ignore_certificates").defaultValue(false)
            .label(HohenheimFormCopy.label("ignore_certificates"))
            .help(HohenheimFormCopy.help("ignore_certificates")).build());

    public static final BooleanField REWRITE_LOCATION = SETTINGS_SCHEMA.addField(
        BooleanField.builder("rewrite_location").defaultValue(true)
            .label(HohenheimFormCopy.label("rewrite_location"))
            .help(HohenheimFormCopy.help("rewrite_location")).build());

    public static final StringField SOCKET = SETTINGS_SCHEMA.addField(
        PathField.builder("socket").browserSource(HohenheimPaths.SERVER_FILES, PathKind.ANY)
            .label(HohenheimFormCopy.label("upstream_socket"))
            .help(HohenheimFormCopy.help("upstream_socket")).build());

    public static final IntegerField DELAY = SETTINGS_SCHEMA.addField(UpstreamSettings.delay());

    // The decision is WHERE to forward: scheme, host, port, or a unix socket instead (board
    // App-Config-Address). How the connection behaves is the next thing an operator comes to
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
    public @NonNull Microcopy getLabel() {
        return Microcopy.of("address").withFilter("scope", "upstream_kind");
    }

    @Override
    public @NonNull Microcopy getDescription() {
        return Microcopy.of("address").withFilter("scope", "upstream_kind_description");
    }

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
            builder.value(protocol.token(), protocol.title(), UpstreamCopy.protocol(protocol.token()));
        }
        return builder
            .label(HohenheimFormCopy.label("upstream_protocol"))
            .help(HohenheimFormCopy.help("upstream_protocol"))
            .build();
    }

    @Override
    public SiteRequestHandler createHandler(Row site, Map<String, Object> settings) {
        String socketSetting = (String) settings.get("socket");
        String socket = socketSetting != null && !socketSetting.isEmpty() ? socketSetting : null;
        String host = (String) settings.get("forward_host");
        boolean websocketEnabled = !Boolean.FALSE.equals(settings.get("websocket_upgrade"));
        boolean ignoreCertificates = Boolean.TRUE.equals(settings.get("ignore_certificates"));
        UpstreamProtocol protocol = UpstreamProtocol.fromSetting(settings.get("upstream_protocol"));
        // Default true when absent: sites created before the field existed keep rewriting.
        boolean rewriteLocation = !Boolean.FALSE.equals(settings.get("rewrite_location"));

        if (socket == null && (host == null || host.isEmpty())) {
            return (exchange, forwarder) -> {
                exchange.setStatusCode(502);
                exchange.getResponseSender().send("No upstream configured");
            };
        }

        // AIDEV-NOTE: a tenant-owned site reaches the public internet only (TenantUpstreams).
        // The socket check comes FIRST because socket OVERRIDES forward_host below: a tenant
        // could pass the write-time forward_host check with a public name and still dial a
        // unix socket through this setting.
        // publicOnly: tenant-owned AND not marked trusted by the operator.
        boolean tenantOwned = TenantUpstreams.publicOnly(site);
        Integer siteId = site.get(SiteModel.ID);
        if (tenantOwned && socket != null) {
            return new FaultedSiteHandler(siteId != null ? siteId : -1,
                Microcopy.of("tenant_socket").withFilter("scope", "site_fault"));
        }

        // Socket mode takes precedence over url mode (matching the Node implementation). The unix
        // socket is reached through a loopback bridge held by the handler and reused across requests.
        if (socket != null) {
            boolean hasPlaceholders = PLACEHOLDER.matcher(socket).find();
            return new ProxySocketHandler(socket, hasPlaceholders, ignoreCertificates,
                websocketEnabled, rewriteLocation, protocol);
        }

        String scheme = (String) settings.getOrDefault("forward_scheme", "http");
        Object portObj = settings.get("forward_port");
        int defaultPort = "https".equals(scheme) ? 443 : 80;
        int port = portObj instanceof Integer i ? i : defaultPort;

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
                    Microcopy.of("tenant_private_address").withFilter("scope", "site_fault"));
            }
            return new TenantAddressHandler(scheme, host, port, protocol, ignoreCertificates,
                websocketEnabled, rewriteLocation);
        }

        return new FixedAddressHandler(new UpstreamTarget(upstream, protocol, ignoreCertificates), host, port,
            websocketEnabled, rewriteLocation);
    }

    /**
     * Forwards an operator-configured site to one fixed host and port, which the dispatcher also probes on its own
     * so an upstream that does not answer shows as down before any visitor finds out.
     */
    private record FixedAddressHandler(UpstreamTarget target, String host, int port, boolean websocketEnabled,
                                       boolean rewriteLocation) implements SiteRequestHandler, ProbeableUpstream {

        @Override
        public void handleRequest(HttpServerExchange exchange, UpstreamForwarder forwarder) {
            if (WebSocketUpgrades.refuse(exchange, this.websocketEnabled)) {
                return;
            }
            if (this.rewriteLocation) {
                exchange.putAttachment(SiteDispatcher.REWRITE_LOCATION, Boolean.TRUE);
            }
            forwarder.forwardTo(this.target);
        }

        @Override
        public String probeHost() {
            return this.host;
        }

        @Override
        public int probePort() {
            return this.port;
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

    /** A capture value is safe to splice into a socket path only if it names no separator, no {@code ..} and no control char. */
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
