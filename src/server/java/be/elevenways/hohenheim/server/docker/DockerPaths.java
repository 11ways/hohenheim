package be.elevenways.hohenheim.server.docker;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * THE spelling of every Engine API request target: each caller-supplied name is
 * percent-encoded into its path position, each query value form-encoded.
 *
 * AIDEV-NOTE: before this, container ids, exec ids and image references were CONCATENATED
 * into the request line raw (only volumes and networks were encoded), so a name carrying
 * {@code ?}, {@code #}, a space or a CRLF rewrote the request the daemon received. A path
 * segment here may carry only unreserved characters plus {@code :} and {@code @} literally
 * (an image digest needs both); everything else is escaped, and a {@code .}/{@code ..} or
 * empty segment is refused outright because the daemon's router would clean it into a
 * DIFFERENT route. Http11 still refuses any control byte as the last line of defence.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
final class DockerPaths {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private DockerPaths() {
    }

    /** {@code /containers/{id}}. */
    static @NonNull String container(@NonNull String id) {
        return "/containers/" + segment(id);
    }

    /** {@code /exec/{id}}. */
    static @NonNull String exec(@NonNull String execId) {
        return "/exec/" + segment(execId);
    }

    /** {@code /volumes/{name}}. */
    static @NonNull String volume(@NonNull String name) {
        return "/volumes/" + segment(name);
    }

    /** {@code /networks/{idOrName}}. */
    static @NonNull String network(@NonNull String idOrName) {
        return "/networks/" + segment(idOrName);
    }

    /**
     * {@code /images/{reference}}: the reference keeps its own {@code /} separators (the
     * Engine API routes {@code /images/{name:.*}}), every other byte is encoded as usual.
     */
    static @NonNull String image(@NonNull String reference) {
        for (String part : reference.split("/", -1)) {
            requireSegment(part, reference);
        }
        return "/images/" + encode(reference, true);
    }

    /** One percent-encoded path segment. */
    static @NonNull String segment(@NonNull String value) {
        requireSegment(value, value);
        return encode(value, false);
    }

    /** One form-encoded query value. */
    static @NonNull String query(@NonNull String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void requireSegment(@NonNull String segment, @NonNull String whole) {
        if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
            throw new IllegalArgumentException("Refusing Docker API name '" + whole
                + "': an empty, '.' or '..' path segment would route elsewhere");
        }
    }

    private static @NonNull String encode(@NonNull String value, boolean keepSlash) {
        StringBuilder out = new StringBuilder(value.length());
        for (byte raw : value.getBytes(StandardCharsets.UTF_8)) {
            int c = raw & 0xFF;
            boolean literal = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~'
                || c == ':' || c == '@' || (keepSlash && c == '/');
            if (literal) {
                out.append((char) c);
            } else {
                out.append('%').append(HEX[c >> 4]).append(HEX[c & 0x0F]);
            }
        }
        return out.toString();
    }
}
