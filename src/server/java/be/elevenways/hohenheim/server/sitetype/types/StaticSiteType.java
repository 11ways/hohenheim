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

    public static final BooleanField INDEXES = SETTINGS_SCHEMA.addField(
        BooleanField.builder("indexes").defaultValue(true).build());

    public static final BooleanField SHOW_HIDDEN_FILES = SETTINGS_SCHEMA.addField(
        BooleanField.builder("show_hidden_files").defaultValue(false).build());

    public static final IntegerField DELAY = SETTINGS_SCHEMA.addField(
        IntegerField.builder().name("delay").build());

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
        boolean autoindex = Boolean.TRUE.equals(settings.get("autoindex"));
        boolean indexes = !Boolean.FALSE.equals(settings.get("indexes"));
        boolean showHidden = Boolean.TRUE.equals(settings.get("show_hidden_files"));

        if (rootPathStr == null || rootPathStr.isEmpty()) {
            return (exchange, forwarder) -> {
                exchange.setStatusCode(502);
                exchange.getResponseSender().send("No root path configured");
            };
        }

        Path rootPath = Path.of(rootPathStr);

        return (exchange, forwarder) -> serveStatic(exchange, rootPath, fallbackFile, autoindex,
            indexes, showHidden);
    }

    private static void serveStatic(HttpServerExchange exchange, Path rootPath,
                                     String fallbackFile, boolean autoindex,
                                     boolean indexes, boolean showHidden) {
        String requestPath = exchange.getRelativePath();
        if (requestPath.startsWith("/")) requestPath = requestPath.substring(1);

        Path filePath;
        if (requestPath.isEmpty()) {
            filePath = rootPath;
            if (indexes) {
                filePath = rootPath.resolve("index.html");
            }

            if (!Files.isRegularFile(filePath) && autoindex) {
                serveDirectoryListing(exchange, rootPath, rootPath, showHidden);
                return;
            }
        } else {
            filePath = rootPath.resolve(requestPath).normalize();
        }

        // Prevent directory traversal
        if (!filePath.startsWith(rootPath)) {
            exchange.setStatusCode(403);
            exchange.getResponseSender().send("Forbidden");
            return;
        }

        // Block hidden files unless explicitly allowed (checked after normalization)
        if (!showHidden) {
            Path relative = rootPath.relativize(filePath);
            for (Path segment : relative) {
                if (segment.toString().startsWith(".")) {
                    exchange.setStatusCode(403);
                    exchange.getResponseSender().send("Forbidden");
                    return;
                }
            }
        }

        // If it's a directory, try index.html or autoindex
        if (Files.isDirectory(filePath)) {
            Path indexFile = indexes ? filePath.resolve("index.html") : null;
            if (indexFile != null && Files.isRegularFile(indexFile)) {
                filePath = indexFile;
            } else if (autoindex) {
                serveDirectoryListing(exchange, rootPath, filePath, showHidden);
                return;
            }
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
            long fileSize = Files.size(filePath);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, contentType);
            exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, fileSize);

            try (var channel = java.nio.channels.FileChannel.open(filePath, java.nio.file.StandardOpenOption.READ)) {
                exchange.getResponseSender().transferFrom(channel, null);
            }
        } catch (IOException e) {
            if (!exchange.isResponseStarted()) {
                exchange.setStatusCode(500);
                exchange.getResponseSender().send("Read error");
            }
        }
    }

    private static void serveDirectoryListing(HttpServerExchange exchange, Path rootPath,
                                               Path dirPath, boolean showHidden) {
        try {
            String relativePath = rootPath.relativize(dirPath).toString();
            if (relativePath.isEmpty()) relativePath = "/";
            else relativePath = "/" + relativePath + "/";

            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">");
            String safeRelPath = escapeHtml(relativePath);
            html.append("<title>Index of ").append(safeRelPath).append("</title>");
            html.append("<style>body{font-family:monospace;padding:2rem}a{color:#06c}");
            html.append("table{border-collapse:collapse}td{padding:0.25rem 1rem}</style>");
            html.append("</head><body><h1>Index of ").append(safeRelPath).append("</h1>");
            html.append("<table>");

            // Parent directory link
            if (!dirPath.equals(rootPath)) {
                html.append("<tr><td><a href=\"../\">..</a></td><td></td></tr>");
            }

            try (var stream = Files.list(dirPath)) {
                stream.sorted().forEach(entry -> {
                    String name = entry.getFileName().toString();
                    if (!showHidden && name.startsWith(".")) return;

                    boolean isDir = Files.isDirectory(entry);
                    String display = isDir ? name + "/" : name;
                    String href = isDir ? name + "/" : name;
                    String size = "";
                    try {
                        if (!isDir) size = formatSize(Files.size(entry));
                    } catch (IOException ignored) {}

                    html.append("<tr><td><a href=\"").append(escapeHtml(href)).append("\">")
                        .append(escapeHtml(display)).append("</a></td><td>").append(size)
                        .append("</td></tr>");
                });
            }

            html.append("</table></body></html>");
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html; charset=UTF-8");
            exchange.getResponseSender().send(html.toString());
        } catch (IOException e) {
            exchange.setStatusCode(500);
            exchange.getResponseSender().send("Error listing directory");
        }
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)) + " MB";
        return (bytes / (1024 * 1024 * 1024)) + " GB";
    }
}
