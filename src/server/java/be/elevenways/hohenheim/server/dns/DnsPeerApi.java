package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.dns.DnsRecordDto;
import be.elevenways.hohenheim.dns.DnsRecordListResponse;
import be.elevenways.hohenheim.dns.DnsValidationErrorResponse;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.routing.RouteTarget;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for a peer Hohenheim's DNS-records API: the edit-forwarding
 * channel that lets this instance edit zones the peer owns. Authenticates
 * with the peer's stored znit_ API key.
 */
public final class DnsPeerApi {

    /** A peer refusal or transport failure; {@code violationKey} is set for 422 validation refusals. */
    public static final class PeerApiException extends RuntimeException {
        private final @Nullable String violationKey;
        private final @Nullable String violationField;

        PeerApiException(String message, @Nullable String violationKey, @Nullable String violationField) {
            super(message);
            this.violationKey = violationKey;
            this.violationField = violationField;
        }

        public @Nullable String getViolationKey() { return violationKey; }
        public @Nullable String getViolationField() { return violationField; }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    private final String baseUrl;
    private final String apiKey;

    private DnsPeerApi(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
    }

    /** @return a client for the peer, or null when it has no base URL / API key configured */
    public static @Nullable DnsPeerApi forPeer(@Nullable Row peer) {
        if (peer == null) {
            return null;
        }
        String baseUrl = peer.get(DnsPeerModel.BASE_URL);
        String apiKey = peer.get(DnsPeerModel.API_KEY);
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            return null;
        }
        return new DnsPeerApi(baseUrl.trim(), apiKey.trim());
    }

    // AIDEV-NOTE: the paths below address a REMOTE peer, but a peer runs THIS software,
    // so its record API is this app's own declared route. Rendering that declaration is
    // what keeps the two ends from drifting -- a hand-copied literal is exactly the
    // drift this forbids, and it is also why these are toUrl(Map) rather than a
    // RouteTarget: the base URL is the peer's, only the PATH comes from here.

    /** @return the owner's live record rows for the zone */
    public @NonNull List<DnsRecordDto> listRecords(@NonNull String origin) {
        DnsRecordListResponse response = parseQuietly(
            request("GET", HohenheimEndpoints.API_DNS_RECORDS.toUrl(
            Map.of(HohenheimEndpoints.DNS_ORIGIN, origin)), null), DnsRecordListResponse.class);
        if (response == null || response.records() == null) {
            throw new PeerApiException("Unexpected response from peer", null, null);
        }
        for (DnsRecordDto record : response.records()) {
            if (record == null || record.id() == null || record.name() == null
                || record.type() == null || record.value() == null) {
                throw new PeerApiException("Unexpected response from peer", null, null);
            }
        }
        return response.records();
    }

    public void createRecord(@NonNull String origin, @NonNull Map<String, String> fields) {
        request("POST", HohenheimEndpoints.API_DNS_RECORD_CREATE.toUrl(
            Map.of(HohenheimEndpoints.DNS_ORIGIN, origin)), fields);
    }

    public void updateRecord(@NonNull String origin, int recordId, @NonNull Map<String, String> fields) {
        request("POST", HohenheimEndpoints.API_DNS_RECORD_UPDATE.toUrl(Map.of(
            HohenheimEndpoints.DNS_ORIGIN, origin,
            HohenheimEndpoints.DNS_RECORD_ID, recordId)), fields);
    }

    public void deleteRecord(@NonNull String origin, int recordId) {
        request("POST", HohenheimEndpoints.API_DNS_RECORD_DELETE.toUrl(Map.of(
            HohenheimEndpoints.DNS_ORIGIN, origin,
            HohenheimEndpoints.DNS_RECORD_ID, recordId)), Map.of());
    }

    private @Nullable String request(@NonNull String method, @NonNull String path,
                                     @Nullable Map<String, String> formFields) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer " + apiKey);
        if ("GET".equals(method)) {
            builder.GET();
        }
        else {
            builder.header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encodeForm(formFields)));
        }

        HttpResponse<String> response;
        try {
            response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
        catch (Exception e) {
            throw new PeerApiException("Peer unreachable: " + e.getMessage(), null, null);
        }

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return response.body();
        }

        DnsValidationErrorResponse refusal = parseQuietly(
            response.body(), DnsValidationErrorResponse.class);
        if (refusal != null && (refusal.error() != null || refusal.message() != null
            || refusal.key() != null || refusal.field() != null)) {
            throw new PeerApiException(
                refusal.message() != null
                    ? refusal.message()
                    : "Peer refused the edit (" + response.statusCode() + ")",
                refusal.key(), refusal.field());
        }
        throw new PeerApiException("Peer returned HTTP " + response.statusCode(), null, null);
    }

    private static <T> @Nullable T parseQuietly(@Nullable String body, @NonNull Class<T> type) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return Zenit.DRY.fromJson(body, type);
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    private static @NonNull String encodeForm(@Nullable Map<String, String> fields) {
        if (fields == null || fields.isEmpty()) {
            return "";
        }
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return body.toString();
    }
}
