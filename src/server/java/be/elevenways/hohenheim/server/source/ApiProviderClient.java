package be.elevenways.hohenheim.server.source;

import be.elevenways.protoblast.common.dry.Dry;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Shared HTTP plumbing of the concrete {@link GitProviderClient} implementations:
 * one bounded-timeout client, bearer-authenticated JSON GET/POST, and the repository
 * path validation both providers gate their URL building on.
 *
 * AIDEV-NOTE: HTTP redirects are deliberately NOT followed -- a redirecting "provider"
 * must never walk an Authorization header onto another host.
 */
abstract class ApiProviderClient implements GitProviderClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    /** Extra request headers (e.g. an Accept header); default adds none. */
    protected void decorate(HttpRequest.@NonNull Builder request) {
    }

    protected final @Nullable Object getJson(@NonNull String url, @NonNull String token)
            throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer " + token)
            .GET();
        decorate(request);
        HttpResponse<String> response = send(request.build());
        requireSuccess(url, response);
        return new Dry().parse(response.body());
    }

    protected final void postJson(@NonNull String url, @NonNull String token, @NonNull String body)
            throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        decorate(request);
        HttpResponse<String> response = send(request.build());
        requireSuccess(url, response);
    }

    protected final @NonNull HttpResponse<String> send(@NonNull HttpRequest request)
            throws IOException {
        try {
            return this.http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Provider request interrupted");
        }
    }

    private static void requireSuccess(@NonNull String url, @NonNull HttpResponse<String> response)
            throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // Strip the query string: it never carries a secret here, but refusal
            // messages travel into toasts and logs, so keep them to the path.
            int query = url.indexOf('?');
            throw new IOException("Provider refused " + (query < 0 ? url : url.substring(0, query))
                + ": HTTP " + response.statusCode());
        }
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
