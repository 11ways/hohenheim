package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.model.RedirectStatuses;
import be.elevenways.hohenheim.model.SiteRedirectModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import io.undertow.server.HttpServerExchange;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One compiled site redirect ({@link SiteRedirectModel}), answered before the site's gates and handler, and THE one
 * home of what its path means: how it is spelled ({@link #canonicalPath}) and which requests it answers
 * ({@link #matches}), for the proxy and the write invariant alike.
 *
 * @param path   the canonical path it matches
 * @param prefix whether it also matches everything under the path, the rest of which follows the target
 * @param target where it sends the visitor
 * @param status the redirect status it answers
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public record SiteRedirect(@NonNull String path, boolean prefix, @NonNull String target, int status) {

    /** The order the proxy asks redirects in: exact ones first, then prefixes, the longest path first. */
    private static final Comparator<SiteRedirect> ORDER = Comparator.comparing(SiteRedirect::prefix)
        .thenComparing(redirect -> -redirect.path().length());

    /**
     * Compiles a site's stored redirects: a row the invariant would refuse (written before it, or by hand) is
     * skipped, never answered. Exact redirects come first, then prefixes longest first.
     */
    static @NonNull List<SiteRedirect> compile(@Nullable List<Row> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<SiteRedirect> compiled = new ArrayList<>();
        for (Row row : rows) {
            String path = canonicalPath(row.get(SiteRedirectModel.FROM_PATH));
            Object target = row.get(SiteRedirectModel.TARGET);
            if (path == null || target == null || !RedirectStatuses.isTarget(String.valueOf(target))) {
                continue;
            }
            compiled.add(new SiteRedirect(path,
                SiteRedirectModel.MATCH_PREFIX.equals(row.get(SiteRedirectModel.MATCH)), String.valueOf(target),
                RedirectStatuses.codeOf(row.get(SiteRedirectModel.HTTP_STATUS), SiteRedirectModel.DEFAULT_STATUS)));
        }
        compiled.sort(ORDER);
        return List.copyOf(compiled);
    }

    /**
     * @return the path as a redirect matches it: a leading slash, no trailing slash but the root's; null for a value
     *         no request's canonical path can be (blank, a doubled slash, a dot segment, whitespace, an escape, a query
     *         or a fragment), so a row that could never answer is refused instead of stored
     */
    public static @Nullable String canonicalPath(@Nullable Object raw) {
        String path = raw == null ? "" : String.valueOf(raw).strip();
        if (path.isEmpty() || !path.startsWith("/") || path.contains("//") || path.chars().anyMatch(
                c -> Character.isWhitespace(c) || Character.isISOControl(c) || c == '?' || c == '#' || c == '%'
                    || c == '\\')) {
            return null;
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                return null;
            }
        }
        return path;
    }

    /** @return the first of {@code redirects} matching the canonical request path, null when none does */
    static @Nullable SiteRedirect firstMatching(@NonNull List<SiteRedirect> redirects, @NonNull String canonicalPath) {
        for (SiteRedirect redirect : redirects) {
            if (redirect.matches(canonicalPath)) {
                return redirect;
            }
        }
        return null;
    }

    /**
     * @return whether following {@code redirect}'s same-site target through the site's {@code others}, as the proxy
     *         answers each step, comes back to {@code redirect}: a visitor would bounce forever
     */
    public static boolean loopsBack(@NonNull SiteRedirect redirect, @NonNull List<SiteRedirect> others) {
        List<SiteRedirect> all = new ArrayList<>(others);
        all.add(redirect);
        all.sort(ORDER);
        SiteRedirect current = redirect;
        for (int step = 0; step < all.size(); step++) {
            String targetPath = current.targetPath();
            SiteRedirect next = targetPath == null ? null : firstMatching(all, targetPath);
            if (next == null) {
                return false;
            }
            if (next == redirect) {
                return true;
            }
            current = next;
        }
        return false;
    }

    /** @return whether it answers this canonical request path; a trailing slash asks for the same path */
    public boolean matches(@NonNull String canonicalPath) {
        String asked = canonicalPath;
        while (asked.length() > 1 && asked.endsWith("/")) {
            asked = asked.substring(0, asked.length() - 1);
        }
        if (asked.equals(this.path)) {
            return true;
        }
        return this.prefix && (this.path.equals("/") || asked.startsWith(this.path + "/"));
    }

    /** @return the same-site path its target names, without query or fragment; null for an outside address */
    public @Nullable String targetPath() {
        return this.target.startsWith("/") ? this.target.split("[?#]", 2)[0] : null;
    }

    /**
     * Answers the exchange with this redirect; a prefix redirect appends the rest of the RAW request path, so an
     * encoded %0a or %3F stays encoded in the Location header.
     */
    void send(@NonNull HttpServerExchange exchange, @Nullable String rawPath) {
        String rest = null;
        if (this.prefix && rawPath != null) {
            String under = this.path.equals("/") ? "" : this.path;
            if (rawPath.startsWith(under) && rawPath.length() > under.length()) {
                rest = rawPath.substring(under.length());
                if (this.target.endsWith("/") && rest.startsWith("/")) {
                    rest = rest.substring(1);
                }
            }
        }
        RedirectLocation.send(exchange, this.status, this.target, rest, true);
    }
}
