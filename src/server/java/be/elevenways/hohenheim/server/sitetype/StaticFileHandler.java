package be.elevenways.hohenheim.server.sitetype;

import be.elevenways.hohenheim.server.files.StoredFileTree;
import be.elevenways.protoblast.common.i18n.MessageResolvers;
import be.elevenways.protoblast.common.i18n.wording.Reader;
import be.elevenways.protoblast.common.i18n.wording.Wording;
import be.elevenways.protoblast.common.text.HtmlEscape;
import be.elevenways.protoblast.common.util.Closeables;
import be.elevenways.zenit.common.setting.ContentLocales;
import be.elevenways.zenit.server.http.AssetVersions;
import be.elevenways.zenit.server.http.ConditionalRequest;
import be.elevenways.zenit.server.http.HttpDate;
import be.elevenways.zenit.server.http.RangeHeader;
import io.undertow.io.IoCallback;
import io.undertow.io.Sender;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.Methods;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import be.elevenways.zenit.server.http.MimeTypes;
import be.elevenways.zenit.server.io.FilesystemNodeKind;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Serves static files: a directory of this host, or a site's own stored files ({@link StoredFileTree}). Thread-safe
 * and stateless per instance.
 *
 * AIDEV-NOTE: one set of rules for both sources, in request-path space: a lexically normalized path that never climbs
 * above the top, no dot-leading part unless the site shows hidden files, an index file or a listing for a folder, the
 * fallback and the not-found page under the same rules. A file answers a single byte range (zenit's
 * {@link RangeHeader}: 206, or 416, or the whole file for a range it cannot honour) and is streamed, never held whole:
 * a whole host file goes out zero-copy ({@link Sender#transferFrom}), anything else is copied through one buffer on a
 * worker thread, so a 500 MB download costs a buffer, not 500 MB. Every file carries nosniff, so a browser never
 * treats an uploaded file as another type than it is served as. A file answered 200 carries its validators (an ETag
 * of its size and modification time, as zenit's own files do, and Last-Modified) and answers 304 to a browser holding
 * it, so a site telling browsers to ask again before every use (SiteSharing) costs a header, not the file; a range
 * whose If-Range no longer matches is answered whole.
 */
public class StaticFileHandler implements SiteRequestHandler {

    /** The not-found page a site's files carry by convention, used when the site names none. */
    public static final String DEFAULT_NOT_FOUND_PAGE = "404.html";

    private static final String OCTET_STREAM = "application/octet-stream";

    private final @NonNull ServedFiles files;
    private final @Nullable String fallbackFile;
    private final @Nullable String notFoundFile;
    private final boolean autoindex;
    private final boolean indexes;
    private final boolean showHidden;

    /**
     * Serves a directory of this host.
     *
     * @param fallbackFile the file answered 200 for a path that names no file (a single-page app's index), or blank
     * @param notFoundFile the page answered 404 for a path that names no file, when there is no fallback, or blank
     */
    public StaticFileHandler(Path rootPath, String fallbackFile, @Nullable String notFoundFile, boolean autoindex,
                             boolean indexes, boolean showHidden) {
        this(new HostFolder(rootPath), fallbackFile, notFoundFile, autoindex, indexes, showHidden);
    }

    private StaticFileHandler(@NonNull ServedFiles files, @Nullable String fallbackFile, @Nullable String notFoundFile,
                              boolean autoindex, boolean indexes, boolean showHidden) {
        this.files = files;
        this.fallbackFile = fallbackFile;
        this.notFoundFile = notFoundFile;
        this.autoindex = autoindex;
        this.indexes = indexes;
        this.showHidden = showHidden;
    }

    /** @return a handler serving a site's own stored files under the same rules as a host directory */
    public static @NonNull StaticFileHandler stored(@NonNull StoredFileTree tree, int maxEntries,
                                                    @Nullable String fallbackFile, @Nullable String notFoundFile,
                                                    boolean autoindex, boolean indexes, boolean showHidden) {
        return new StaticFileHandler(new StoredFiles(tree, maxEntries), fallbackFile, notFoundFile, autoindex, indexes,
            showHidden);
    }

    @Override
    public void handleRequest(HttpServerExchange exchange, UpstreamForwarder forwarder) {
        // Every source question is file I/O (an S3 storage asks over the network), never the I/O thread's work.
        if (exchange.isInIoThread()) {
            exchange.dispatch(() -> this.serve(exchange));
            return;
        }
        this.serve(exchange);
    }

    /** A refused path answers what a missing one does: the fallback file, the not-found page, or a plain 404. */
    @Override
    public boolean answerMissing(HttpServerExchange exchange) {
        if (exchange.isInIoThread()) {
            exchange.dispatch(() -> this.answer(exchange, null));
        } else {
            this.answer(exchange, null);
        }
        return true;
    }

    private void serve(@NonNull HttpServerExchange exchange) {
        String target = normalize(exchange.getRelativePath());
        if (target == null || (!this.showHidden && hidden(target))) {
            plain(exchange, 403, "Forbidden");
            return;
        }

        try {
            if (this.files.kindOf(target) == Kind.DIRECTORY) {
                String index = join(target, "index.html");
                if (this.indexes && this.files.kindOf(index) == Kind.FILE) {
                    target = index;
                } else if (this.autoindex) {
                    this.serveListing(exchange, target);
                    return;
                }
            }
            this.answer(exchange, this.files.kindOf(target) == Kind.FILE ? target : null);
        } catch (IOException e) {
            failed(exchange);
        }
    }

    /**
     * The asked file, or for none what a path naming no file answers: the fallback (200), else the not-found page.
     *
     * @param found the file asked for, null when the path names none
     */
    private void answer(@NonNull HttpServerExchange exchange, @Nullable String found) {
        try {
            String target = found;
            int status = 200;
            if (target == null && this.fallbackFile != null && !this.fallbackFile.isBlank()) {
                target = normalize(this.fallbackFile);
            }
            if (target == null || this.files.kindOf(target) != Kind.FILE) {
                target = normalize(this.notFoundFile == null || this.notFoundFile.isBlank()
                    ? DEFAULT_NOT_FOUND_PAGE : this.notFoundFile.strip());
                status = 404;
            }

            // The substituted page answers under the same rules as an asked one: inside the top, and not hidden
            // unless the site shows hidden files.
            if (target == null || this.files.kindOf(target) != Kind.FILE || (!this.showHidden && hidden(target))) {
                plain(exchange, 404, "Not Found");
                return;
            }
            if (!this.files.contained(target)) {
                plain(exchange, 403, "Forbidden");
                return;
            }
            this.serveFile(exchange, target, status);
        } catch (IOException e) {
            failed(exchange);
        }
    }

    /** A read that failed: 500 before the answer started, else the exchange ends where it is. */
    private static void failed(@NonNull HttpServerExchange exchange) {
        if (!exchange.isResponseStarted()) {
            plain(exchange, 500, "Read error");
        } else {
            exchange.endExchange();
        }
    }

    /** One file, whole or as the asked range; a substituted page (404) answers whole. */
    private void serveFile(@NonNull HttpServerExchange exchange, @NonNull String target, int status)
            throws IOException {
        long size = this.files.size(target);
        // A file answered 200 carries validators and answers a range or a 304; the not-found page answers whole.
        boolean ranged = false;
        if (status == 200) {
            long modified = this.files.modifiedMillis(target);
            String etag = "\"" + AssetVersions.validatorOf(size, modified) + "\"";
            exchange.getResponseHeaders().put(Headers.ETAG, etag);
            exchange.getResponseHeaders().put(Headers.LAST_MODIFIED, HttpDate.format(Instant.ofEpochMilli(modified)));
            if (ConditionalRequest.isNotModified(exchange.getRequestHeaders().getFirst(Headers.IF_NONE_MATCH),
                    exchange.getRequestHeaders().getFirst(Headers.IF_MODIFIED_SINCE), etag, modified)) {
                exchange.setStatusCode(304);
                exchange.endExchange();
                return;
            }
            ranged = ConditionalRequest.rangeStillApplies(exchange.getRequestHeaders().getFirst(Headers.IF_RANGE),
                etag, modified);
        }
        String asked = ranged ? exchange.getRequestHeaders().getFirst(Headers.RANGE) : null;
        RangeHeader.ResponsePlan plan = RangeHeader.resolve(asked, size).plan(size);
        String contentType = this.files.contentType(target);
        exchange.setStatusCode(plan.status() == 200 ? status : plan.status());
        exchange.getResponseHeaders().put(Headers.ACCEPT_RANGES, plan.acceptRanges());
        exchange.getResponseHeaders().put(Headers.X_CONTENT_TYPE_OPTIONS, "nosniff");
        if (plan.contentRange() != null) {
            exchange.getResponseHeaders().put(Headers.CONTENT_RANGE, plan.contentRange());
        }
        if (!plan.sendsBody()) {
            exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, 0);
            exchange.endExchange();
            return;
        }
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, contentType == null ? OCTET_STREAM : contentType);
        exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, plan.bodyLength());
        if (Methods.HEAD.equals(exchange.getRequestMethod())) {
            exchange.endExchange();
            return;
        }
        this.files.send(exchange, target, plan.bodyStart(), plan.bodyLength(), size);
    }

    private void serveListing(@NonNull HttpServerExchange exchange, @NonNull String folder) throws IOException {
        // Same symlink-escape guard as file serving.
        if (!this.files.contained(folder)) {
            plain(exchange, 403, "Forbidden");
            return;
        }
        String relativePath = folder.isEmpty() ? "/" : "/" + folder + "/";

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">");
        // Attribute escaping (the double quote too) in every position keeps the listing byte-identical to before.
        String safeRelPath = HtmlEscape.attribute(relativePath);
        html.append("<title>Index of ").append(safeRelPath).append("</title>");
        html.append("<style>:root{color-scheme:light dark}body{font-family:monospace;padding:2rem}a{color:LinkText}");
        html.append("table{border-collapse:collapse}td{padding:0.25rem 1rem}</style>");
        html.append("</head><body><h1>Index of ").append(safeRelPath).append("</h1>");
        html.append("<table>");

        if (!folder.isEmpty()) {
            html.append("<tr><td><a href=\"../\">..</a></td><td></td></tr>");
        }

        // The listing reads in the installation's content locale: a visitor of a static site has no reader here.
        Reader listing = Reader.of(ContentLocales.chain(), MessageResolvers.getDefault());
        for (Listed entry : this.files.list(folder)) {
            if (!this.showHidden && entry.name().startsWith(".")) {
                continue;
            }
            String shown = entry.directory() ? entry.name() + "/" : entry.name();
            String size = entry.directory() ? "" : Wording.bytes(listing, entry.size(), MessageResolvers.getDefault());
            html.append("<tr><td><a href=\"").append(HtmlEscape.attribute(shown)).append("\">")
                .append(HtmlEscape.attribute(shown)).append("</a></td><td>").append(size).append("</td></tr>");
        }

        html.append("</table></body></html>");
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html; charset=UTF-8");
        exchange.getResponseSender().send(html.toString());
    }

    private static void plain(@NonNull HttpServerExchange exchange, int status, @NonNull String text) {
        exchange.setStatusCode(status);
        exchange.getResponseSender().send(text);
    }

    /**
     * The request path as a path below the top: segments joined by "/", "" for the top itself.
     *
     * @return null for a path that climbs above the top
     */
    static @Nullable String normalize(@NonNull String requested) {
        List<String> segments = new ArrayList<>();
        for (String segment : requested.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (segments.isEmpty()) {
                    return null;
                }
                segments.removeLast();
                continue;
            }
            segments.add(segment);
        }
        return String.join("/", segments);
    }

    private static @NonNull String join(@NonNull String folder, @NonNull String name) {
        return folder.isEmpty() ? name : folder + "/" + name;
    }

    /** @return whether a path below the top passes through a hidden (dot) segment */
    private static boolean hidden(@NonNull String relative) {
        for (String segment : relative.split("/")) {
            if (segment.startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    /** What a path below the top is. */
    private enum Kind { FILE, DIRECTORY }

    /** One entry of a folder listing. */
    private record Listed(@NonNull String name, boolean directory, long size) {}

    /** Where served files come from; every path is a normalized path below the top ("" for the top). */
    private interface ServedFiles {

        /** @return the path's kind, null when nothing servable is there */
        @Nullable Kind kindOf(@NonNull String path) throws IOException;

        long size(@NonNull String path) throws IOException;

        /** @return when the file last changed, in epoch milliseconds */
        long modifiedMillis(@NonNull String path) throws IOException;

        @Nullable String contentType(@NonNull String path) throws IOException;

        /** @return whether the path resolves inside the top, links followed */
        boolean contained(@NonNull String path);

        /** @return the folder's entries, sorted by name */
        @NonNull List<Listed> list(@NonNull String folder) throws IOException;

        /** Sends {@code length} bytes of the file from {@code start}; the headers are set. */
        void send(@NonNull HttpServerExchange exchange, @NonNull String path, long start, long length, long size)
            throws IOException;
    }

    /** A directory of this host. */
    private record HostFolder(@NonNull Path root) implements ServedFiles {

        private @NonNull Path resolve(@NonNull String path) {
            return path.isEmpty() ? this.root : this.root.resolve(path);
        }

        @Override
        public @Nullable Kind kindOf(@NonNull String path) {
            Path resolved = this.resolve(path);
            return Files.isDirectory(resolved) ? Kind.DIRECTORY : Files.isRegularFile(resolved) ? Kind.FILE : null;
        }

        @Override
        public long size(@NonNull String path) throws IOException {
            return Files.size(this.resolve(path));
        }

        @Override
        public long modifiedMillis(@NonNull String path) throws IOException {
            return Files.getLastModifiedTime(this.resolve(path)).toMillis();
        }

        @Override
        public @Nullable String contentType(@NonNull String path) throws IOException {
            return Files.probeContentType(this.resolve(path));
        }

        /**
         * Real-path containment: normalization collapses ".." lexically but does NOT resolve symlinks, so a symlink
         * inside the root pointing outside it would otherwise be served. Denies when the real path can't be read.
         */
        @Override
        public boolean contained(@NonNull String path) {
            try {
                return this.resolve(path).toRealPath().startsWith(this.root.toRealPath());
            } catch (IOException e) {
                return false;
            }
        }

        @Override
        public @NonNull List<Listed> list(@NonNull String folder) throws IOException {
            List<Listed> entries = new ArrayList<>();
            try (var stream = Files.list(this.resolve(folder))) {
                for (Path entry : stream.sorted().toList()) {
                    boolean directory = Files.isDirectory(entry);
                    long size = 0;
                    if (!directory) {
                        try {
                            size = Files.size(entry);
                        } catch (IOException ignored) {
                            // A file gone since the listing shows without its size.
                        }
                    }
                    entries.add(new Listed(entry.getFileName().toString(), directory, size));
                }
            }
            return entries;
        }

        /** A whole file goes out zero-copy; a range is copied from its position through one buffer. */
        @Override
        public void send(@NonNull HttpServerExchange exchange, @NonNull String path, long start, long length,
                         long size) throws IOException {
            FileChannel channel = FileChannel.open(this.resolve(path), StandardOpenOption.READ);
            if (start == 0 && length == size) {
                exchange.getResponseSender().transferFrom(channel, new IoCallback() {
                    @Override
                    public void onComplete(HttpServerExchange done, Sender sender) {
                        Closeables.closeQuietly(channel);
                        IoCallback.END_EXCHANGE.onComplete(done, sender);
                    }

                    @Override
                    public void onException(HttpServerExchange failed, Sender sender, IOException exception) {
                        Closeables.closeQuietly(channel);
                        IoCallback.END_EXCHANGE.onException(failed, sender, exception);
                    }
                });
                return;
            }
            try (channel) {
                channel.position(start);
                copy(exchange, Channels.newInputStream(channel), length);
            }
        }
    }

    /** A site's own stored files. */
    private record StoredFiles(@NonNull StoredFileTree tree, int maxEntries) implements ServedFiles {

        private static @NonNull String treePath(@NonNull String path) {
            return "/" + path;
        }

        @Override
        public @Nullable Kind kindOf(@NonNull String path) throws IOException {
            try {
                return switch (this.tree.stat("", treePath(path)).kind()) {
                    case DIRECTORY -> Kind.DIRECTORY;
                    case FILE -> Kind.FILE;
                    case SYMLINK, OTHER -> null;
                };
            } catch (FileNotFoundException | StoredFileTree.InvalidName absent) {
                return null;
            }
        }

        @Override
        public long size(@NonNull String path) throws IOException {
            return this.tree.stat("", treePath(path)).size();
        }

        @Override
        public long modifiedMillis(@NonNull String path) throws IOException {
            return this.tree.stat("", treePath(path)).modified() * 1000;
        }

        @Override
        public @Nullable String contentType(@NonNull String path) {
            return MimeTypes.forFileName(path);
        }

        /** A stored tree holds no link, so every normalized path is inside it. */
        @Override
        public boolean contained(@NonNull String path) {
            return true;
        }

        @Override
        public @NonNull List<Listed> list(@NonNull String folder) throws IOException {
            List<Listed> entries = new ArrayList<>();
            for (var entry : this.tree.listDirectory("", treePath(folder), this.maxEntries)) {
                entries.add(new Listed(entry.name(), entry.kind() == FilesystemNodeKind.DIRECTORY, entry.size()));
            }
            entries.sort(Comparator.comparing(Listed::name));
            return entries;
        }

        @Override
        public void send(@NonNull HttpServerExchange exchange, @NonNull String path, long start, long length,
                         long size) throws IOException {
            try (InputStream in = this.tree.open(treePath(path), start, length)) {
                copy(exchange, in, length);
            }
        }
    }

    /** Copies at most {@code length} bytes to the response through one buffer, on this worker thread. */
    private static void copy(@NonNull HttpServerExchange exchange, @NonNull InputStream in, long length)
            throws IOException {
        exchange.startBlocking();
        OutputStream out = exchange.getOutputStream();
        byte[] buffer = new byte[64 * 1024];
        long left = length;
        while (left > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (read < 0) {
                break;
            }
            out.write(buffer, 0, read);
            left -= read;
        }
        out.flush();
        exchange.endExchange();
    }
}
