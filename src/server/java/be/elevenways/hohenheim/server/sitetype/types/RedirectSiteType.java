package be.elevenways.hohenheim.server.sitetype.types;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.SiteTypeHandler;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Schema;
import io.undertow.util.Headers;

import java.util.Map;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Sends an HTTP redirect to a target URL.
 */
public class RedirectSiteType implements SiteTypeHandler {

    public static final Identifier ID = Identifier.of("hohenheim", "redirect");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    public static final StringField TARGET_URL = SETTINGS_SCHEMA.addField(
        StringField.builder().name("target_url").label(HohenheimFormCopy.label("target_url"))
            .help(HohenheimFormCopy.help("target_url")).build());

    public static final EnumField HTTP_STATUS = SETTINGS_SCHEMA.addField(
        EnumField.builder("http_status")
            .value("301", "301 Permanent")
            .value("302", "302 Found")
            .value("307", "307 Temporary Redirect")
            .value("308", "308 Permanent Redirect")
            .label(HohenheimFormCopy.label("redirect_status"))
            .help(HohenheimFormCopy.help("redirect_status"))
            .build());

    public static final BooleanField PRESERVE_PATH = SETTINGS_SCHEMA.addField(
        BooleanField.builder("preserve_path").defaultValue(false)
            .label(HohenheimFormCopy.label("preserve_path")).help(HohenheimFormCopy.help("preserve_path")).build());

    // Honored generically by SiteDispatcher's per-route delay scheduler.
    public static final IntegerField DELAY = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("delay").suffix("ms").label(HohenheimFormCopy.label("delay"))
            .help(HohenheimFormCopy.help("delay")).build());

    @Override
    public Identifier typeId() { return ID; }

    @Override
    public String getDisplayName() { return "Redirect"; }

    @Override
    public @NonNull Microcopy getLabel() {
        return Microcopy.of("redirect").withFilter("scope", "site_type");
    }

    @Override
    public String getDescription() { return "Redirect requests to another URL"; }

    @Override
    public Icon getIcon() { return Icon.of("up-right-from-square"); }

    @Override
    public String getColor() { return "cyan"; }

    @Override
    public Schema getSchema() { return SETTINGS_SCHEMA; }

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

        // Validate URL scheme to prevent open redirect / XSS
        if (targetUrl != null && !targetUrl.isEmpty()
                && !targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")
                && !targetUrl.startsWith("/")) {
            final String bad = targetUrl;
            return (exchange, forwarder) -> {
                exchange.setStatusCode(502);
                exchange.getResponseSender().send("Invalid redirect target: must start with http://, https://, or /");
            };
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
