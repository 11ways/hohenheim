package be.elevenways.hohenheim.server.sitetype.types;

import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.UpstreamForwarder;
import io.undertow.io.IoCallback;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Serves static files from a directory. Thread-safe and stateless per instance.
 */
public class StaticFileHandler implements SiteRequestHandler {

    private final Path rootPath;
    private final String fallbackFile;
    private final boolean autoindex;
    private final boolean indexes;
    private final boolean showHidden;

    public StaticFileHandler(Path rootPath, String fallbackFile, boolean autoindex,
                             boolean indexes, boolean showHidden) {
        this.rootPath = rootPath;
        this.fallbackFile = fallbackFile;
        this.autoindex = autoindex;
        this.indexes = indexes;
        this.showHidden = showHidden;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange, UpstreamForwarder forwarder) {
        serveStatic(exchange);
    }

    private void serveStatic(HttpServerExchange exchange) {
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

        // Real-path containment: normalize() collapses ".." lexically but does NOT
        // resolve symlinks, so a symlink inside the root pointing outside it would
        // otherwise be served. Re-check against the resolved real paths.
        if (!withinRoot(rootPath, filePath)) {
            exchange.setStatusCode(403);
            exchange.getResponseSender().send("Forbidden");
            return;
        }

        FileChannel channel = null;
        try {
            String contentType = Files.probeContentType(filePath);
            if (contentType == null) contentType = "application/octet-stream";
            long fileSize = Files.size(filePath);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, contentType);
            exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, fileSize);

            channel = FileChannel.open(filePath, StandardOpenOption.READ);
            FileChannel owned = channel;
            exchange.getResponseSender().transferFrom(owned, new IoCallback() {
                @Override
                public void onComplete(HttpServerExchange exchange, io.undertow.io.Sender sender) {
                    closeQuietly(owned);
                    IoCallback.END_EXCHANGE.onComplete(exchange, sender);
                }

                @Override
                public void onException(HttpServerExchange exchange, io.undertow.io.Sender sender,
                                        IOException exception) {
                    closeQuietly(owned);
                    IoCallback.END_EXCHANGE.onException(exchange, sender, exception);
                }
            });
            // Ownership transferred to the callback; don't close in finally.
            channel = null;
        } catch (IOException e) {
            if (!exchange.isResponseStarted()) {
                exchange.setStatusCode(500);
                exchange.getResponseSender().send("Read error");
            }
        } finally {
            if (channel != null) closeQuietly(channel);
        }
    }

    static void serveDirectoryListing(HttpServerExchange exchange, Path rootPath,
                                      Path dirPath, boolean showHidden) {
        // Same symlink-escape guard as file serving (see withinRoot).
        if (!withinRoot(rootPath, dirPath)) {
            exchange.setStatusCode(403);
            exchange.getResponseSender().send("Forbidden");
            return;
        }
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

    private static void closeQuietly(FileChannel channel) {
        try { channel.close(); } catch (IOException ignored) {}
    }

    /**
     * Real-path containment check; defends against symlinks inside the root that
     * resolve outside it. Denies (returns false) if the real path can't be read.
     */
    private static boolean withinRoot(Path root, Path target) {
        try {
            return target.toRealPath().startsWith(root.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }

    static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }

    static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)) + " MB";
        return (bytes / (1024 * 1024 * 1024)) + " GB";
    }
}
