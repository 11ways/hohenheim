package be.elevenways.hohenheim.server.proxy;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;
import io.undertow.util.URLUtils;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * THE proxy's reading of a request path: the raw wire form that is forwarded, and the one
 * canonical form route selection and every protected-path guard judge.
 *
 * AIDEV-NOTE: Undertow hands out two paths and neither is safe on its own. The DECODED path
 * (getRelativePath) turns %0d%0a, %20, %3F and %25 back into bytes that change the meaning
 * of an HTTP request line, so it may never be written to the upstream; ProxyHandler writes
 * the RAW requestURI verbatim, which is why strip_path rewrites the raw form here. And the
 * decoded path is not canonical: it keeps dot-segments, empty segments and the %2F / %5C /
 * backslash spellings an upstream may read as separators, so a guard on /private compared
 * against it was bypassed by /x/../private or /./private while the upstream normalized them
 * back. The decision: REFUSE what no browser sends (a dot-segment in any spelling, including
 * "..;" and %2e%2e, and CR/LF/NUL anywhere in a decoded segment), and COLLAPSE what real
 * clients do send (//, %2F, %5C, backslash) into one canonical form for matching, while the
 * upstream still receives the original encoding untouched. The canonical form is derived
 * from the RAW path segment by segment, so a double-encoded %252F stays literal text instead
 * of becoming a separator after Undertow's single decode.
 *
 * AIDEV-NOTE: the ONE exception to "forwarded untouched" is a LEADING run of literal '/' or
 * '\'. A request target starting with "//" is a network-path reference to every URI parser
 * that resolves it against a base (java.net.URI, WHATWG URL in Node, the JDK HttpServer that
 * answers 400 to it): "//public/private/x" becomes host "public", path "/private/x". The
 * guard judged "/public/private/x", so forwarding it verbatim would let such an upstream serve
 * a guarded folder without its password. The raw form therefore starts with exactly one '/';
 * nothing is decoded or re-encoded, only duplicate leading separator characters dropped.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public final class RequestPath {

    private static final AttachmentKey<RequestPath> KEY = AttachmentKey.create(RequestPath.class);

    private final String raw;
    private final String canonical;
    private final List<Segment> segments;

    private RequestPath(String raw, String canonical, List<Segment> segments) {
        this.raw = raw;
        this.canonical = canonical;
        this.segments = segments;
    }

    /** One raw segment: its span in the raw path and its decoded name, parameters dropped. */
    private record Segment(int start, int end, String name) {
    }

    /**
     * The path as the client encoded it, without query, scheme or authority, and with a leading
     * run of separator characters reduced to one '/'; this is what the upstream receives.
     */
    public @NonNull String raw() {
        return this.raw;
    }

    /** The decoded, separator-collapsed, dot-segment-free form every route and guard matches. */
    public @NonNull String canonical() {
        return this.canonical;
    }

    /**
     * Read and attach the request's path.
     *
     * @return null when the request must be refused with 400 (a control character, a
     *         dot-segment or an undecodable segment), in which case nothing is attached
     */
    static @Nullable RequestPath attach(@NonNull HttpServerExchange exchange) {
        String wire = rawPathOf(exchange);
        RequestPath path = parse(wire);
        if (path != null) {
            exchange.putAttachment(KEY, path);
            if (!path.raw.equals(wire)) {
                // Only a leading separator run differs: forward the single-'/' form.
                exchange.setRequestURI(path.raw, false);
            }
        }
        return path;
    }

    /** The attached reading; only null for an exchange that never passed the dispatcher. */
    static @Nullable RequestPath of(@NonNull HttpServerExchange exchange) {
        return exchange.getAttachment(KEY);
    }

    /**
     * The raw request path of this exchange as it will be forwarded (after any strip_path),
     * for a handler that echoes the path into a Location header: the encoded form keeps a
     * %3F a literal character instead of a query boundary.
     */
    public static @NonNull String rawPathOf(@NonNull HttpServerExchange exchange) {
        String uri = exchange.getRequestURI();
        if (uri == null || uri.isEmpty()) {
            return "/";
        }
        if (exchange.isHostIncludedInRequestURI()) {
            int authority = uri.indexOf("//");
            int pathStart = authority < 0 ? -1 : uri.indexOf('/', authority + 2);
            return pathStart < 0 ? "/" : uri.substring(pathStart);
        }
        return uri;
    }

    /**
     * Parse one still-encoded request path.
     *
     * @return null when the request must be refused
     */
    static @Nullable RequestPath parse(@NonNull String raw) {
        String source = raw.isEmpty() ? "/" : withSingleLeadingSeparator(raw);
        List<Segment> segments = new ArrayList<>();
        int position = 0;
        int length = source.length();
        while (position < length) {
            int separator = separatorLength(source, position);
            if (separator > 0) {
                position += separator;
                continue;
            }
            int start = position;
            boolean inParameters = false;
            while (position < length) {
                char c = source.charAt(position);
                if (c == '/') {
                    break;
                }
                // Undertow ends a path parameter only at a real '/', so an encoded
                // separator inside one is parameter text, not a segment boundary.
                if (c == ';') {
                    inParameters = true;
                } else if (!inParameters && separatorLength(source, position) > 0) {
                    break;
                }
                position++;
            }
            String name = decodeSegment(source.substring(start, position));
            if (name == null || isDotSegment(name) || hasControlCharacter(name)) {
                return null;
            }
            segments.add(new Segment(start, position, name));
        }
        StringBuilder canonical = new StringBuilder();
        for (Segment segment : segments) {
            canonical.append('/').append(segment.name());
        }
        if (canonical.isEmpty()) {
            canonical.append('/');
        } else if (!segments.isEmpty() && segments.get(segments.size() - 1).end() < length) {
            // A trailing separator is part of what the client asked for (a directory).
            canonical.append('/');
        }
        return new RequestPath(source, canonical.toString(), List.copyOf(segments));
    }

    /**
     * The raw path left after removing a route's prefix, for strip_path: the original encoding
     * of the remainder, never a re-encoding of the decoded form.
     *
     * @param routePath the canonical route prefix this request matched
     * @return the remainder, starting with '/', or null when the raw path does not carry the
     *         prefix segment for segment (the caller refuses rather than guess)
     */
    @Nullable String rawRemainderAfter(@NonNull String routePath) {
        String[] expected = routePath.split("/");
        int consumed = 0;
        int end = 0;
        for (String wanted : expected) {
            if (wanted.isEmpty()) {
                continue;
            }
            if (consumed >= this.segments.size()) {
                return null;
            }
            Segment segment = this.segments.get(consumed++);
            if (!segment.name().equals(wanted)) {
                return null;
            }
            end = segment.end();
        }
        if (end >= this.raw.length()) {
            return "/";
        }
        String rest = this.raw.charAt(end) == '/'
            ? this.raw.substring(end)
            : this.raw.substring(end + separatorLength(this.raw, end));
        rest = withSingleLeadingSeparator(rest);
        return rest.startsWith("/") ? rest : "/" + rest;
    }

    /**
     * The path with a leading run of literal '/' and '\' replaced by exactly one '/', so it can
     * never read as a network-path reference (see the class note); a path that does not start
     * with a separator is returned unchanged.
     */
    private static String withSingleLeadingSeparator(String path) {
        int start = 0;
        while (start < path.length() && (path.charAt(start) == '/' || path.charAt(start) == '\\')) {
            start++;
        }
        if (start == 0 || (start == 1 && path.charAt(0) == '/')) {
            return path;
        }
        return "/" + path.substring(start);
    }

    /**
     * Whether a decoded segment is "." or "..", also when a path parameter trails it:
     * Undertow keeps the ';' on a dot-segment so "..;" survives, and servlet containers
     * behind the proxy read it as "..".
     */
    private static boolean isDotSegment(String name) {
        int parameters = name.indexOf(';');
        String bare = parameters < 0 ? name : name.substring(0, parameters);
        return bare.equals(".") || bare.equals("..");
    }

    private static boolean hasControlCharacter(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '\r' || c == '\n' || c == '\0') {
                return true;
            }
        }
        return false;
    }

    /** The length of the separator at this position: '/', '\', %2F or %5C; zero when none. */
    private static int separatorLength(String path, int position) {
        char c = path.charAt(position);
        if (c == '/' || c == '\\') {
            return 1;
        }
        if (c == '%' && position + 2 < path.length()) {
            char high = path.charAt(position + 1);
            char low = Character.toLowerCase(path.charAt(position + 2));
            if ((high == '2' && low == 'f') || (high == '5' && low == 'c')) {
                return 3;
            }
        }
        return 0;
    }

    /**
     * One raw segment decoded, its raw path parameters dropped (Undertow drops them too) except
     * on a dot-segment, where the parameter stays so "..;x" is still recognized.
     *
     * @return null when the segment does not decode
     */
    private static @Nullable String decodeSegment(String rawSegment) {
        int parameters = rawSegment.indexOf(';');
        String name = rawSegment;
        if (parameters >= 0) {
            String bare = rawSegment.substring(0, parameters);
            name = bare.equals(".") || bare.equals("..") ? rawSegment : bare;
        }
        try {
            return URLUtils.decode(name, StandardCharsets.UTF_8.name(), true, false, new StringBuilder());
        } catch (RuntimeException malformed) {
            return null;
        }
    }
}
