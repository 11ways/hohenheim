package be.elevenways.hohenheim.server.sitetype.types;

import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.SiteTypeHandler;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Schema;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Serves static files from a directory.
 */
public class StaticSiteType implements SiteTypeHandler {

    public static final Identifier ID = Identifier.of("hohenheim", "static");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    public static final StringField ROOT_PATH = SETTINGS_SCHEMA.addField(
        StringField.builder().name("root_path").build());

    public static final BooleanField AUTOINDEX = SETTINGS_SCHEMA.addField(
        BooleanField.builder("autoindex").defaultValue(false).build());

    public static final StringField FALLBACK_FILE = SETTINGS_SCHEMA.addField(
        StringField.builder().name("fallback_file").build());

    @Override
    public String getDisplayName() { return "Static"; }

    @Override
    public String getDescription() { return "Serve static files from a directory"; }

    @Override
    public String getIcon() { return "folder"; }

    @Override
    public Schema getSchema() { return SETTINGS_SCHEMA; }

    @Override
    public SiteRequestHandler createHandler(Row site, Map<String, Object> settings) {
        String rootPathStr = (String) settings.get("root_path");
        String fallbackFile = (String) settings.get("fallback_file");

        if (rootPathStr == null || rootPathStr.isEmpty()) {
            return (exchange, forwarder) -> {
                exchange.setStatusCode(502);
                exchange.getResponseSender().send("No root path configured");
            };
        }

        Path rootPath = Path.of(rootPathStr);

        return (exchange, forwarder) -> serveStatic(exchange, rootPath, fallbackFile);
    }

    private static void serveStatic(HttpServerExchange exchange, Path rootPath, String fallbackFile) {
        String requestPath = exchange.getRelativePath();
        if (requestPath.startsWith("/")) requestPath = requestPath.substring(1);
        if (requestPath.isEmpty()) requestPath = "index.html";

        Path filePath = rootPath.resolve(requestPath).normalize();

        // Prevent directory traversal
        if (!filePath.startsWith(rootPath)) {
            exchange.setStatusCode(403);
            exchange.getResponseSender().send("Forbidden");
            return;
        }

        if (!Files.isRegularFile(filePath) && fallbackFile != null && !fallbackFile.isEmpty()) {
            filePath = rootPath.resolve(fallbackFile);
        }

        if (!Files.isRegularFile(filePath)) {
            exchange.setStatusCode(404);
            exchange.getResponseSender().send("Not Found");
            return;
        }

        try {
            String contentType = Files.probeContentType(filePath);
            if (contentType == null) contentType = "application/octet-stream";
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, contentType);
            exchange.getResponseSender().send(java.nio.ByteBuffer.wrap(Files.readAllBytes(filePath)));
        } catch (IOException e) {
            exchange.setStatusCode(500);
            exchange.getResponseSender().send("Read error");
        }
    }
}
