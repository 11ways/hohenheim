package be.elevenways.hohenheim.server.sitetype.types;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.HohenheimPaths;
import be.elevenways.hohenheim.server.proxy.SiteDispatcher;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.SiteTypeHandler;
import be.elevenways.hohenheim.server.sitetype.UnixSocketBridgeConnection;
import be.elevenways.hohenheim.server.sitetype.UpstreamForwarder;
import be.elevenways.hohenheim.server.sitetype.UpstreamProtocol;
import be.elevenways.hohenheim.server.sitetype.UpstreamTarget;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Schema;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.io.IOException;
import java.net.URI;
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
public class ProxySiteType implements SiteTypeHandler {

    public static final Identifier ID = Identifier.of("hohenheim", "proxy");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    public static final EnumField FORWARD_SCHEME = SETTINGS_SCHEMA.addField(
        EnumField.builder("forward_scheme")
            .value("http", "HTTP")
            .value("https", "HTTPS")
            .label(HohenheimFormCopy.label("forward_scheme"))
            .help(HohenheimFormCopy.help("forward_scheme"))
            .build());

    public static final StringField FORWARD_HOST = SETTINGS_SCHEMA.addField(
        StringField.builder().name("forward_host").label(HohenheimFormCopy.label("forward_host"))
            .help(HohenheimFormCopy.help("forward_host")).build());

    public static final IntegerField FORWARD_PORT = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("forward_port").label(HohenheimFormCopy.label("forward_port"))
            .help(HohenheimFormCopy.help("forward_port")).build());

    public static final EnumField UPSTREAM_PROTOCOL = SETTINGS_SCHEMA.addField(
        EnumField.builder("upstream_protocol")
            .value("http1", "HTTP/1.1")
            .value("h2", "HTTP/2 (gRPC)")
            .label(HohenheimFormCopy.label("upstream_protocol"))
            .help(HohenheimFormCopy.help("upstream_protocol"))
            .build());

    public static final IntegerField REQUEST_TIMEOUT = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("request_timeout").suffix("s")
            .label(HohenheimFormCopy.label("request_timeout"))
            .help(HohenheimFormCopy.help("request_timeout"))
            .build());

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

    public static final IntegerField DELAY = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("delay").suffix("ms").label(HohenheimFormCopy.label("delay"))
            .help(HohenheimFormCopy.help("delay")).build());

    @Override
    public Identifier typeId() { return ID; }

    @Override
    public String getDisplayName() { return "Proxy"; }

    @Override
    public @NonNull Microcopy getLabel() {
        return Microcopy.of("proxy").withFilter("scope", "site_type");
    }

    @Override
    public String getDescription() { return "Forward requests to an upstream server"; }

    @Override
    public Icon getIcon() { return Icon.of("arrow-right"); }

    @Override
    public String getColor() { return "violet"; }

    @Override
    public Schema getSchema() { return SETTINGS_SCHEMA; }

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

        return (exchange, forwarder) -> {
            if (refusesUpgrade(exchange, websocketEnabled)) {
                exchange.setStatusCode(403);
                exchange.getResponseSender().send("WebSocket upgrades disabled for this site");
                return;
            }
            if (rewriteLocation) {
                exchange.putAttachment(SiteDispatcher.REWRITE_LOCATION, Boolean.TRUE);
            }
            forwarder.forwardTo(new UpstreamTarget(upstream, protocol, ignoreCertificates));
        };
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
            if (refusesUpgrade(exchange, websocketEnabled)) {
                exchange.setStatusCode(403);
                exchange.getResponseSender().send("WebSocket upgrades disabled for this site");
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

    /**
     * Whether a {@code websocket_upgrade=false} site must refuse this exchange. AIDEV-NOTE: this
     * fires on ANY upgrade attempt (the mere presence of an Upgrade header), not just an exact
     * {@code Upgrade: websocket} spelling -- a comma-list ({@code h2c, websocket}) or a duplicate
     * whose first value is not "websocket" would otherwise slip past and let a 101 tunnel
     * regardless of the setting, since the actual tunnel is established by Undertow on the 101,
     * not by our string match.
     */
    static boolean refusesUpgrade(HttpServerExchange exchange, boolean websocketEnabled) {
        return !websocketEnabled && exchange.getRequestHeaders().contains(Headers.UPGRADE);
    }

    /** {name} or {0} placeholders resolved against regex-host capture groups. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*|\\d+)\\}");

    /**
     * Resolve socket-path placeholders from regex-host capture groups.
     *
     * <p>AIDEV-NOTE: capture-group values originate in the untrusted Host header
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
