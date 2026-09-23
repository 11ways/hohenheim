package be.elevenways.hohenheim.server.source;

import be.elevenways.protoblast.common.dry.Dry;
import be.elevenways.protoblast.common.http.HttpMethod;
import be.elevenways.zenit.server.net.FetchOutcome;
import be.elevenways.zenit.server.net.FetchRequest;
import be.elevenways.zenit.server.net.OutboundUrlGuard;
import be.elevenways.zenit.server.net.PinnedFetcher;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared HTTP plumbing of the concrete {@link GitProviderClient} implementations:
 * bearer-authenticated JSON GET/POST through a {@link PinnedFetcher}, and the repository
 * path validation both providers gate their URL building on.
 *
 * AIDEV-NOTE: HTTP redirects are deliberately NOT followed -- a redirecting "provider"
 * must never walk an Authorization header onto another host.
 *
 * AIDEV-NOTE: the base URL is chosen by whoever registered the provider, which may be a
 * tenant. Every call therefore rides the pinned fetcher under the provider's own guard
 * ({@link SourceOwnership#providerGuard}): a tenant-owned provider reaches public
 * addresses only, checked per request and connected to exactly the vetted address, so the
 * "Test connection" toast cannot be turned into a probe of 169.254.169.254 or of the
 * controller's own loopback ports. An operator-owned forge on a private network keeps
 * working through the any-address guard, still pinned.
 */
abstract class ApiProviderClient implements GitProviderClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    /** The public-internet fetcher every tenant-owned provider rides. */
    private static final PinnedFetcher PUBLIC_FETCHER = fetcherFor(OutboundUrlGuard.PUBLIC_INTERNET);

    /** The any-address fetcher of operator-owned providers. */
    private static final PinnedFetcher ANY_ADDRESS_FETCHER = fetcherFor(OutboundUrlGuard.ANY_ADDRESS);

    private final @NonNull PinnedFetcher fetcher;

    /** @param guard the reach of this provider, from {@link SourceOwnership#providerGuard} */
    protected ApiProviderClient(@NonNull OutboundUrlGuard guard) {
        this.fetcher = guard == OutboundUrlGuard.PUBLIC_INTERNET ? PUBLIC_FETCHER
            : guard == OutboundUrlGuard.ANY_ADDRESS ? ANY_ADDRESS_FETCHER : fetcherFor(guard);
    }

    private static @NonNull PinnedFetcher fetcherFor(@NonNull OutboundUrlGuard guard) {
        return PinnedFetcher.builder(guard)
            .connectTimeout(CONNECT_TIMEOUT)
            .deadline(REQUEST_TIMEOUT)
            .redirects(PinnedFetcher.Redirects.handBack())
            .userAgent("Hohenheim")
            .build();
    }

    /** Extra request headers (e.g. an Accept header); default adds none. */
    protected @NonNull Map<String, String> extraHeaders() {
        return Map.of();
    }

    protected final @Nullable Object getJson(@NonNull String url, @NonNull String token)
            throws IOException {
        Response response = exchange(HttpMethod.GET, url, Map.of("Authorization", "Bearer " + token),
            null);
        requireSuccess(url, response);
        return new Dry().parse(response.body());
    }

    protected final void postJson(@NonNull String url, @NonNull String token, @NonNull String body)
            throws IOException {
        Response response = exchange(HttpMethod.POST, url, Map.of(
                "Authorization", "Bearer " + token,
                "Content-Type", "application/json"),
            body.getBytes(StandardCharsets.UTF_8));
        requireSuccess(url, response);
    }

    /** One answered provider request: its status and its body as text. */
    protected record Response(int status, @NonNull String body) {
    }

    /**
     * One request through the pinned fetcher, with {@link #extraHeaders} beside the given
     * ones (a given header wins).
     *
     * @throws IOException when the guard refused the address, the exchange failed, or the
     *         provider answered with a redirect (never followed)
     */
    protected final @NonNull Response exchange(@NonNull HttpMethod method, @NonNull String url,
                                               @NonNull Map<String, String> headers,
                                               byte @Nullable [] body) throws IOException {
        Map<String, List<String>> all = new LinkedHashMap<>();
        extraHeaders().forEach((name, value) -> all.put(name, List.of(value)));
        headers.forEach((name, value) -> all.put(name, List.of(value)));
        FetchRequest request;
        try {
            request = new FetchRequest(method, url, all, body);
        } catch (IllegalArgumentException unsendable) {
            throw new IOException("Provider request refused: " + unsendable.getMessage());
        }
        return switch (this.fetcher.fetch(request)) {
            case FetchOutcome.Fetched fetched ->
                new Response(fetched.status(), new String(fetched.body(), StandardCharsets.UTF_8));
            case FetchOutcome.Redirected redirected ->
                throw new IOException("Provider redirected " + pathOf(url) + ": HTTP "
                    + redirected.status() + " (redirects are never followed)");
            case FetchOutcome.Refused refused -> throw new IOException(refused.reason());
            case FetchOutcome.Failed failed -> throw new IOException("Provider request to "
                + pathOf(url) + " failed: " + failed.reason());
        };
    }

    private static void requireSuccess(@NonNull String url, @NonNull Response response)
            throws IOException {
        if (response.status() < 200 || response.status() >= 300) {
            throw new IOException("Provider refused " + pathOf(url) + ": HTTP " + response.status());
        }
    }

    /**
     * The URL without its query string: it never carries a secret here, but refusal
     * messages travel into toasts and logs, so keep them to the path.
     */
    private static @NonNull String pathOf(@NonNull String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }

    /**
     * Refuses path tricks: every segment is a plain name, no {@code .}/{@code ..}
     * (both match the name charset and would URL-normalize into another endpoint).
     *
     * @param allowNested whether more than two segments are legal (GitLab subgroups)
     */
    protected static @NonNull String validRepoPath(@NonNull String repository, boolean allowNested) {
        String trimmed = repository.trim();
        String[] segments = trimmed.split("/", -1);
        boolean valid = segments.length >= 2 && (allowNested || segments.length == 2);
        if (valid) {
            for (String segment : segments) {
                if (!segment.matches("[A-Za-z0-9_.-]+") || segment.equals(".") || segment.equals("..")) {
                    valid = false;
                    break;
                }
            }
        }
        if (!valid) {
            throw new IllegalArgumentException("Not a valid repository path: " + repository);
        }
        return trimmed;
    }

    protected static @NonNull String trimSlash(@NonNull String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    protected static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    protected static @NonNull String truncate(@NonNull String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }
}
