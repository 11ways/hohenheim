package be.elevenways.hohenheim.server.sitetype.types;

import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.SiteTypeHandler;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Schema;
import io.undertow.util.Headers;

import java.util.Map;

/**
 * Returns a 404 response for all requests. Useful for parking domains.
 */
public class DeadSiteType implements SiteTypeHandler {

    public static final Identifier ID = Identifier.of("hohenheim", "dead");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    private static final String DEAD_PAGE = "<!DOCTYPE html><html><head><title>404</title>"
        + "<style>body{font-family:system-ui;display:flex;align-items:center;justify-content:center;"
        + "min-height:100vh;margin:0;background:#111;color:#888}"
        + "h1{font-size:6rem;font-weight:200;margin:0}</style></head>"
        + "<body><h1>404</h1></body></html>";

    @Override
    public String getDisplayName() { return "Dead"; }

    @Override
    public String getDescription() { return "Return 404 for all requests (park domain)"; }

    @Override
    public String getIcon() { return "x-circle"; }

    @Override
    public Schema getSchema() { return SETTINGS_SCHEMA; }

    @Override
    public SiteRequestHandler createHandler(Row site, Map<String, Object> settings) {
        return (exchange, forwarder) -> {
            exchange.setStatusCode(404);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html; charset=UTF-8");
            exchange.getResponseSender().send(DEAD_PAGE);
        };
    }
}
