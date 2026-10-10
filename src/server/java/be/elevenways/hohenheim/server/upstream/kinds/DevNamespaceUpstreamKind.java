package be.elevenways.hohenheim.server.upstream.kinds;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.net.Hostnames;
import be.elevenways.hohenheim.server.devtunnel.DevLease;
import be.elevenways.hohenheim.server.devtunnel.DevLeases;
import be.elevenways.hohenheim.server.proxy.ErrorPages;
import be.elevenways.hohenheim.server.proxy.SiteDispatcher;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.upstream.UpstreamKindHandler;
import be.elevenways.hohenheim.server.sitetype.UpstreamForwarder;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.BadgeColor;
import be.elevenways.zenit.common.ui.ColorHue;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.http.HostPattern;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.util.Map;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * A dev namespace: one wildcard domain (e.g. *.dev.example.com) whose
 * subdomains are served by remote dev servers that register themselves over
 * the outbound dev tunnel. The claimed first label picks the live lease; a
 * name without a lease renders the dev-offline page.
 */
public class DevNamespaceUpstreamKind implements UpstreamKindHandler {

    public static final Identifier ID = HohenheimIds.id("dev_namespace");
    public static final String REGISTRATION_TOKEN_KEY = "registration_token";
    public static final Schema SETTINGS_SCHEMA = new Schema();

    public static final StringField REGISTRATION_TOKEN = SETTINGS_SCHEMA.addField(
        StringField.builder().name(REGISTRATION_TOKEN_KEY)
            .secret()
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("registration_token"))
            .help(HohenheimMicrocopy.HELP.of("registration_token"))
            .build());

    @Override
    public Identifier typeId() { return ID; }

    @Override
    public String getDisplayName() { return "Dev namespace"; }

    @Override
    public Icon getIcon() { return Icon.of("flask"); }

    /** A dev session creates its own namespace site; nobody puts one online by hand. */
    @Override
    public boolean offeredAsApp() { return false; }

    @Override
    public BadgeColor color() { return ColorHue.TEAL; }

    @Override
    public Schema getSchema() { return SETTINGS_SCHEMA; }

    @Override
    public SiteRequestHandler createHandler(Row site, Map<String, Object> settings) {
        Integer siteId = site.get(SiteModel.ID);
        return new DevNamespaceRequestHandler(siteId != null ? siteId : -1);
    }

    /** Routes each request to the live lease claimed by the hostname's first label. */
    private record DevNamespaceRequestHandler(int siteId) implements SiteRequestHandler {

        @Override
        public int getSiteId() {
            return siteId;
        }

        @Override
        public void handleRequest(HttpServerExchange exchange, UpstreamForwarder forwarder) {
            String hostname = hostnameOf(exchange);
            String name = claimedName(exchange, hostname);

            if (name == null) {
                ErrorPages.sendDevOffline(exchange, hostname);
                return;
            }

            DevLease lease = DevLeases.find(siteId, name);
            if (lease == null) {
                ErrorPages.sendDevOffline(exchange, name);
                return;
            }

            exchange.putAttachment(SiteDispatcher.REWRITE_LOCATION, Boolean.TRUE);
            forwarder.forwardTo(lease.getTarget());
        }

        /**
         * @return the single wildcard label the request claims, or null when the host
         *         does not sit directly under this site's matched wildcard domain
         */
        private static String claimedName(HttpServerExchange exchange, String hostname) {
            HostPattern pattern = HostPattern.tryParse(
                exchange.getAttachment(SiteDispatcher.MATCHED_HOST_PATTERN));
            if (pattern == null || pattern.base() == null || hostname.isEmpty()) {
                return null;
            }
            HostPattern.Match match = pattern.match(hostname, null);
            String name = match != null ? match.label() : null;
            return name == null || name.contains(".") ? null : name;
        }

        private static String hostnameOf(HttpServerExchange exchange) {
            return Hostnames.fromHostHeader(exchange.getRequestHeaders().getFirst(Headers.HOST));
        }
    }
}
