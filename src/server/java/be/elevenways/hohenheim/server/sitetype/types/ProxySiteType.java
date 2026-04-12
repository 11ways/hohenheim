package be.elevenways.hohenheim.server.sitetype.types;

import be.elevenways.hohenheim.server.proxy.SiteDispatcher;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.SiteTypeHandler;
import be.elevenways.hohenheim.server.sitetype.UpstreamTarget;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Schema;
import io.undertow.util.Headers;

import java.net.URI;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            .build());

    public static final StringField FORWARD_HOST = SETTINGS_SCHEMA.addField(
        StringField.builder().name("forward_host").build());

    public static final IntegerField FORWARD_PORT = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("forward_port").build());

    public static final BooleanField WEBSOCKET_UPGRADE = SETTINGS_SCHEMA.addField(
        BooleanField.builder("websocket_upgrade").defaultValue(true).build());

    public static final BooleanField IGNORE_CERTIFICATES = SETTINGS_SCHEMA.addField(
        BooleanField.builder("ignore_certificates").defaultValue(false).build());

    public static final StringField SOCKET = SETTINGS_SCHEMA.addField(
        StringField.builder().name("socket").build());

    public static final IntegerField DELAY = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("delay").build());

    @Override
    public String getDisplayName() { return "Proxy"; }

    @Override
    public String getDescription() { return "Forward requests to an upstream server"; }

    @Override
    public String getIcon() { return "arrow-right"; }

    @Override
    public Schema getSchema() { return SETTINGS_SCHEMA; }

    @Override
    public SiteRequestHandler createHandler(Row site, Map<String, Object> settings) {
        String socketPath = (String) settings.get("socket");
        String scheme = (String) settings.getOrDefault("forward_scheme", "http");
        String host = (String) settings.get("forward_host");
        Object portObj = settings.get("forward_port");
        int defaultPort = "https".equals(scheme) ? 443 : 80;
        int port = portObj instanceof Integer i ? i : defaultPort;

        if ((host == null || host.isEmpty()) && (socketPath == null || socketPath.isEmpty())) {
            return (exchange, forwarder) -> {
                exchange.setStatusCode(502);
                exchange.getResponseSender().send("No upstream configured");
            };
        }

        URI upstream;
        try {
            upstream = new URI(scheme, null, host, port, "/", null, null);
        } catch (Exception e) {
            return (exchange, forwarder) -> {
                exchange.setStatusCode(502);
                exchange.getResponseSender().send("Invalid upstream: " + e.getMessage());
            };
        }

        boolean websocketEnabled = Boolean.TRUE.equals(settings.get("websocket_upgrade"));
        boolean ignoreCertificates = Boolean.TRUE.equals(settings.get("ignore_certificates"));
        String socket = socketPath != null && !socketPath.isEmpty() ? socketPath : null;
        boolean socketHasPlaceholders = socket != null && PLACEHOLDER.matcher(socket).find();

        return (exchange, forwarder) -> {
            if (!websocketEnabled
                    && "websocket".equalsIgnoreCase(
                        exchange.getRequestHeaders().getFirst(Headers.UPGRADE))) {
                exchange.setStatusCode(403);
                exchange.getResponseSender().send("WebSocket upgrades disabled for this site");
                return;
            }
            String resolvedSocket = socket;
            if (socketHasPlaceholders) {
                Map<String, String> groups = exchange.getAttachment(SiteDispatcher.MATCHED_GROUPS);
                resolvedSocket = substitutePlaceholders(socket, groups);
            }
            forwarder.forwardTo(new UpstreamTarget(upstream, ignoreCertificates, resolvedSocket));
        };
    }

    /** {name} or {0} placeholders resolved against regex-host capture groups. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*|\\d+)\\}");

    static String substitutePlaceholders(String template, Map<String, String> groups) {
        if (template == null || groups == null || groups.isEmpty()) {
            return template;
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String value = groups.get(name);
            m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group(0)));
        }
        m.appendTail(out);
        return out.toString();
    }
}
