package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.site.SignedLinks;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.zenit.server.security.SecureTokens;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;
import io.undertow.util.Methods;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.List;

/**
 * THE signed-link contract: a request passes when its address carries {@code expires} (Unix seconds, in the future,
 * at most {@link #LONGEST} ahead) and {@code signature}, the lowercase hex HMAC-SHA256 of
 * {@code "<path>\n<expires>"} under a secret in force, the path being the raw path the client sent, query left out.
 *
 * AIDEV-NOTE: the one home of the signing function: the proxy's check, the panel's worked example, its Make a link
 * and the site_link_make tool all call {@link #signature}, so the documented form is the enforced one. The key is the
 * secret's text as UTF-8, exactly what a customer's API holding the shown secret signs with; Hohenheim looks nothing
 * up per request. The path is the client's raw spelling (RequestPath.raw), never the canonical one the guard matches:
 * another spelling of the same file needs its own signature, which only narrows what a link opens.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class SignedLink {

    /** The query parameter carrying the link's end, in Unix seconds. */
    public static final String EXPIRES = "expires";

    /** The query parameter carrying the signature. */
    public static final String SIGNATURE = "signature";

    /** The furthest ahead an expiry may lie: a leaked long link never lives longer than this. */
    public static final Duration LONGEST = Duration.ofMinutes(SignedLinks.LONGEST_MINUTES);

    /** Set on an exchange a signed link admitted, so the forwarding stage takes the two parameters out. */
    private static final AttachmentKey<Boolean> ADMITTED = AttachmentKey.create(Boolean.class);

    /** The longest digit run an expiry may be; more cannot be a Unix second this side of the year 2286. */
    private static final int EXPIRES_DIGITS = 10;

    /** A SHA-256 signature's length in hex. */
    private static final int SIGNATURE_LENGTH = 64;

    private SignedLink() {
    }

    /**
     * One secret in force.
     *
     * @param secret the secret's text, the HMAC key as UTF-8
     * @param until  when it stops working, null while it is the current one
     */
    public record Key(@NonNull String secret, @Nullable Instant until) {

        /** @return whether it still signs at this moment */
        public boolean inForce(@NonNull Instant now) {
            return this.until == null || now.isBefore(this.until);
        }

        @Override
        public @NonNull String toString() {
            return "Key[secret withheld, until " + this.until + "]";
        }
    }

    /** @return the lowercase hex signature of the path and expiry under the secret */
    public static @NonNull String signature(@NonNull String secret, @NonNull String rawPath, long expires) {
        return SecureTokens.hmacSha256Hex(secret, rawPath + "\n" + expires);
    }

    /**
     * @param address the site's address with its scheme ({@code https://downloads.example}), null for a link
     *                relative to the site
     * @return the link opening the raw path until the expiry
     */
    public static @NonNull String link(@Nullable String address, @NonNull String rawPath, long expires,
                                       @NonNull String secret) {
        return (address == null ? "" : address) + rawPath + "?" + EXPIRES + "=" + expires + "&" + SIGNATURE + "="
            + signature(secret, rawPath, expires);
    }

    /**
     * The raw path a browser asks for a typed one: each segment percent-encoded, its slashes kept.
     *
     * @return null when no request could ask it (a dot-segment, a control character)
     */
    public static @Nullable String rawPathOf(@NonNull String typed) {
        String path = typed.strip();
        RequestPath parsed = RequestPath.parse(Uri.encodePath(path.startsWith("/") ? path : "/" + path));
        return parsed == null ? null : parsed.raw();
    }

    /** @return whether the raw path lies under the protected prefix, judged as the guard judges a request */
    public static boolean under(@NonNull String prefix, @NonNull String rawPath) {
        RequestPath parsed = RequestPath.parse(rawPath);
        return parsed != null && RouteEntry.pathCovers(prefix, parsed.canonical());
    }

    /**
     * Whether this request carries a link signed with one of the keys: a GET or HEAD, one expiry in the future and at
     * most {@link #LONGEST} ahead, one signature matching in constant time. An admitted exchange is marked so its two
     * parameters never reach an upstream.
     */
    static boolean admits(@NonNull HttpServerExchange exchange, @NonNull List<Key> keys, @NonNull Instant now) {
        if (!Methods.GET.equals(exchange.getRequestMethod()) && !Methods.HEAD.equals(exchange.getRequestMethod())) {
            return false;
        }
        String expiresText = only(exchange, EXPIRES);
        String signature = only(exchange, SIGNATURE);
        if (expiresText == null || signature == null || signature.length() != SIGNATURE_LENGTH
                || expiresText.isEmpty() || expiresText.length() > EXPIRES_DIGITS
                || !expiresText.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return false;
        }
        long expires = Long.parseLong(expiresText);
        long nowSeconds = now.getEpochSecond();
        if (expires <= nowSeconds || expires > nowSeconds + LONGEST.toSeconds()) {
            return false;
        }
        String rawPath = RequestPath.askedOf(exchange);
        boolean admitted = false;
        // Every key is compared, so the time taken never says which one matched.
        for (Key key : keys) {
            if (key.inForce(now) && SecureTokens.constantTimeEquals(signature(key.secret(), rawPath, expires),
                    signature)) {
                admitted = true;
            }
        }
        if (admitted) {
            exchange.putAttachment(ADMITTED, Boolean.TRUE);
        }
        return admitted;
    }

    /** Takes the two parameters out of a request a signed link admitted, every other pair kept as spelled. */
    static void stripFrom(@NonNull HttpServerExchange exchange) {
        if (!Boolean.TRUE.equals(exchange.getAttachment(ADMITTED))) {
            return;
        }
        Uri query = new Uri("/?" + exchange.getQueryString());
        query.removeQueryParam(EXPIRES);
        query.removeQueryParam(SIGNATURE);
        String search = query.getSearch();
        exchange.setQueryString(search == null || search.length() <= 1 ? "" : search.substring(1));
        exchange.getQueryParameters().remove(EXPIRES);
        exchange.getQueryParameters().remove(SIGNATURE);
    }

    /** @return the parameter's one value, null when it is absent or given twice */
    private static @Nullable String only(@NonNull HttpServerExchange exchange, @NonNull String name) {
        Deque<String> values = exchange.getQueryParameters().get(name);
        return values == null || values.size() != 1 ? null : values.peekFirst();
    }
}
