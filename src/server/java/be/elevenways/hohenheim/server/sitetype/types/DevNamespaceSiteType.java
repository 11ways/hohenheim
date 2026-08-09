package be.elevenways.hohenheim.server.sitetype.types;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.net.Hostnames;
import be.elevenways.hohenheim.server.devtunnel.DevLease;
import be.elevenways.hohenheim.server.devtunnel.DevLeases;
import be.elevenways.hohenheim.server.proxy.ErrorPages;
import be.elevenways.hohenheim.server.proxy.SiteDispatcher;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.SiteTypeHandler;
import be.elevenways.hohenheim.server.sitetype.UpstreamForwarder;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.Icon;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.util.Locale;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * A dev namespace: one wildcard domain (e.g. *.dev.example.com) whose
 * subdomains are served by remote dev servers that register themselves over
 * the outbound dev tunnel. The claimed first label picks the live lease; a
 * name without a lease renders the dev-offline page.
 */
public class DevNamespaceSiteType implements SiteTypeHandler {

    public static final Identifier ID = Identifier.of("hohenheim", "dev_namespace");
    public static final String REGISTRATION_TOKEN_KEY = "registration_token";
    public static final Schema SETTINGS_SCHEMA = new Schema();

    public static final StringField REGISTRATION_TOKEN = SETTINGS_SCHEMA.addField(
        StringField.builder().name(REGISTRATION_TOKEN_KEY)
            .secret()
            .label(HohenheimFormCopy.label("registration_token"))
            .help(HohenheimFormCopy.help("registration_token"))
            .build());

    @Override
    public Identifier typeId() { return ID; }

    @Override
    public String getDisplayName() { return "Dev namespace"; }

    @Override
    public @NonNull Microcopy getLabel() {
        return Microcopy.of("dev_namespace").withFilter("scope", "site_type");
    }

    @Override
    public String getDescription() { return "Serve registered remote dev servers on wildcard subdomains"; }

    @Override
    public Icon getIcon() { return Icon.of("flask"); }

    @Override
    public String getColor() { return "teal"; }

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
            String pattern = exchange.getAttachment(SiteDispatcher.MATCHED_HOST_PATTERN);
            if (pattern == null || !pattern.startsWith("*.") || hostname.isEmpty()) {
                return null;
            }
            String base = pattern.substring(1).toLowerCase(Locale.ROOT); // ".dev.example.com"
            if (!hostname.endsWith(base) || hostname.length() <= base.length()) {
                return null;
            }
            String name = hostname.substring(0, hostname.length() - base.length());
            if (name.isEmpty() || name.contains(".")) {
                return null;
            }
            return name;
        }

        private static String hostnameOf(HttpServerExchange exchange) {
            return Hostnames.fromHostHeader(exchange.getRequestHeaders().getFirst(Headers.HOST));
        }
    }
}
