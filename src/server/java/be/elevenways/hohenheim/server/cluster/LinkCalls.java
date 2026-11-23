package be.elevenways.hohenheim.server.cluster;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.cluster.ClusterEndpoints;
import be.elevenways.hohenheim.cluster.LinkFailure;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.routing.Endpoint;
import be.elevenways.zenit.server.net.FetchOutcome;
import be.elevenways.zenit.server.net.FetchRequest;
import be.elevenways.zenit.server.net.OutboundUrlGuard;
import be.elevenways.zenit.server.net.PinnedFetcher;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * THE way this server calls another Hohenheim it is linked to: one pinned fetcher, a deadline, TLS verified, no
 * redirect followed, the public internet unless the host declared private networks for linked servers.
 *
 * AIDEV-NOTE: a log line names at most the address's origin, never a key, a path or any part of an answer (a rows
 * answer carries every secret of its kind); "nothing was sent" and "sent, no answer" are told apart by FetchOutcome's
 * requestSent alone.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
final class LinkCalls {

    /** How long one call may take, connecting included. */
    static final Duration DEADLINE = Duration.ofSeconds(10);

    /**
     * The largest answer read, the size of one page of shared rows (PeerRecords.PAGE rows, every value of each in
     * clear): 32 MiB, so a row averages 160 KiB before a page is refused. A status answer is a few hundred bytes; the
     * one fetcher holds one limit, so the largest answer sets it.
     */
    static final long MAX_ANSWER_BYTES = 32L * 1024 * 1024;

    private static volatile @Nullable PinnedFetcher fetcher;

    private LinkCalls() {
    }

    /** What one call came back with. */
    sealed interface Answer permits Answered, Failed {
    }

    /**
     * The other server answered.
     *
     * @param status its HTTP status
     * @param body   its JSON object, empty when it sent none or nothing readable
     */
    record Answered(int status, @NonNull Map<String, Object> body) implements Answer {
    }

    /** No usable answer: why, as the stored failure. */
    record Failed(@NonNull LinkFailure failure) implements Answer {
    }

    /** @return the status answer of the server at {@code baseUrl}, asked with the key this server presents */
    static @NonNull Answer status(@NonNull String baseUrl, @NonNull String key) {
        return call(baseUrl, ClusterEndpoints.STATUS, Map.of(), key);
    }

    /** @return the answer of the server at {@code baseUrl} to a join presenting a code, in {@code body} */
    static @NonNull Answer join(@NonNull String baseUrl, @NonNull Map<String, Object> body) {
        return call(baseUrl, ClusterEndpoints.JOIN, body, null);
    }

    /** @return the answer of the server at {@code baseUrl} to this server ending the link */
    static @NonNull Answer leave(@NonNull String baseUrl, @NonNull String key) {
        return call(baseUrl, ClusterEndpoints.LEAVE, Map.of(), key);
    }

    /**
     * @param key the key presented, null for none
     * @return the answer of the server at {@code baseUrl} to its master asking the generation of every shared kind
     */
    static @NonNull Answer generations(@NonNull String baseUrl, @Nullable String key) {
        return call(baseUrl, ClusterEndpoints.GENERATIONS, Map.of(), key);
    }

    /**
     * @param key the key presented, null for none
     * @return the answer of the server at {@code baseUrl} to its master asking one page of one shared kind's rows
     */
    static @NonNull Answer rows(@NonNull String baseUrl, @Nullable String key, @NonNull String model,
                                @Nullable String after, int limit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("after", after);
        body.put("limit", limit);
        return call(baseUrl, ClusterEndpoints.ROWS, body, key);
    }

    /**
     * @param key the key presented, null for none
     * @return the answer of the master at {@code baseUrl} to this server saying it changed a shared kind
     */
    static @NonNull Answer changed(@NonNull String baseUrl, @Nullable String key) {
        return call(baseUrl, ClusterEndpoints.CHANGED, Map.of(), key);
    }

    private static @NonNull Answer call(@NonNull String baseUrl, @NonNull Endpoint<?> endpoint,
                                        @NonNull Map<String, Object> body, @Nullable String key) {
        String url = baseUrl + endpoint.toUrl();
        FetchRequest request = FetchRequest.postJson(url, body);
        if (key != null) {
            request = request.withHeader("Authorization", "Bearer " + key);
        }
        FetchOutcome outcome = fetcher().fetch(request);
        return switch (outcome) {
            case FetchOutcome.Fetched fetched -> new Answered(fetched.status(), jsonOf(fetched.body()));
            case FetchOutcome.Refused refused -> failed(url,
                refused.requestSent() ? LinkFailure.SILENT : LinkFailure.UNREACHABLE, refused.reason());
            case FetchOutcome.Failed failed -> failed(url,
                failed.requestSent() ? LinkFailure.SILENT : LinkFailure.UNREACHABLE, failed.reason());
            case FetchOutcome.Redirected redirected -> failed(url, LinkFailure.BAD_ANSWER,
                "redirected (HTTP " + redirected.status() + ")");
        };
    }

    private static @NonNull Answer failed(@NonNull String url, @NonNull LinkFailure failure, @NonNull String reason) {
        Blast.log("CLUSTER:", OutboundUrlGuard.originOf(URI.create(url)), failure.token(), "-", reason);
        return new Failed(failure);
    }

    /** @return the answer's JSON object, empty for none or for anything that is no object */
    @SuppressWarnings("unchecked")
    private static @NonNull Map<String, Object> jsonOf(byte @NonNull [] body) {
        if (body.length == 0) {
            return Map.of();
        }
        try {
            Object parsed = Zenit.DRY.parseJsonTree(new String(body, StandardCharsets.UTF_8));
            return parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        } catch (RuntimeException unreadable) {
            return Map.of();
        }
    }

    /** @return the one fetcher every linked-server call rides, built at first use from the host's declaration */
    private static @NonNull PinnedFetcher fetcher() {
        PinnedFetcher current = fetcher;
        if (current == null) {
            synchronized (LinkCalls.class) {
                current = fetcher;
                if (current == null) {
                    current = PinnedFetcher.builder(OutboundUrlGuard.optingIn(
                            HohenheimSettings.Cluster.ALLOW_PRIVATE_NETWORKS).forSchemes("https"))
                        .connectTimeout(Duration.ofSeconds(5))
                        .deadline(DEADLINE)
                        .maxBodyBytes(MAX_ANSWER_BYTES)
                        .redirects(PinnedFetcher.Redirects.response())
                        .build();
                    fetcher = current;
                }
            }
        }
        return current;
    }
}
