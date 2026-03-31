package be.elevenways.hohenheim.server.sitetype.types;

import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.SiteTypeHandler;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Schema;
import io.undertow.util.Headers;

import java.util.Map;

/**
 * Sends an HTTP redirect to a target URL.
 */
public class RedirectSiteType implements SiteTypeHandler {

    public static final Identifier ID = Identifier.of("hohenheim", "redirect");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    public static final StringField TARGET_URL = SETTINGS_SCHEMA.addField(
        StringField.builder().name("target_url").build());

    public static final EnumField HTTP_STATUS = SETTINGS_SCHEMA.addField(
        EnumField.builder("http_status")
            .value("301", "301 Permanent")
            .value("302", "302 Found")
            .value("307", "307 Temporary Redirect")
            .value("308", "308 Permanent Redirect")
            .build());

    public static final BooleanField PRESERVE_PATH = SETTINGS_SCHEMA.addField(
        BooleanField.builder("preserve_path").defaultValue(false).build());

    @Override
    public String getDisplayName() { return "Redirect"; }

    @Override
    public String getDescription() { return "Redirect requests to another URL"; }

    @Override
    public String getIcon() { return "external-link"; }

    @Override
    public Schema getSchema() { return SETTINGS_SCHEMA; }

    @Override
    public Map<String, Object> getProperties() {
        return Map.of("description", getDescription(), "icon", getIcon());
    }

    @Override
    public SiteRequestHandler createHandler(Row site, Map<String, Object> settings) {
        String targetUrl = (String) settings.get("target_url");
        String statusStr = (String) settings.getOrDefault("http_status", "302");
        Object preserveObj = settings.get("preserve_path");
        boolean preservePath = Boolean.TRUE.equals(preserveObj);
        int statusCode;

        try {
            statusCode = Integer.parseInt(statusStr);
        } catch (NumberFormatException e) {
            statusCode = 302;
        }

        if (targetUrl == null || targetUrl.isEmpty()) {
            return (exchange, forwarder) -> {
                exchange.setStatusCode(502);
                exchange.getResponseSender().send("No redirect target configured");
            };
        }

        final String target = targetUrl;
        final int status = statusCode;

        return (exchange, forwarder) -> {
            String location = target;
            if (preservePath) {
                String path = exchange.getRelativePath();
                String query = exchange.getQueryString();
                location = target + path;
                if (query != null && !query.isEmpty()) {
                    location += "?" + query;
                }
            }
            exchange.setStatusCode(status);
            exchange.getResponseHeaders().put(Headers.LOCATION, location);
            exchange.endExchange();
        };
    }
}
