package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
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

    /** @return the owner's live record rows for the zone (maps with id/name/type/ttl/value/...) */
    @SuppressWarnings("unchecked")
    public @NonNull List<Map<String, Object>> listRecords(@NonNull String origin) {
        Object parsed = request("GET", recordsPath(origin), null);
        if (parsed instanceof Map<?, ?> map && map.get("records") instanceof List<?> records) {
            return (List<Map<String, Object>>) records;
        }
        throw new PeerApiException("Unexpected response from peer", null, null);
    }

    public void createRecord(@NonNull String origin, @NonNull Map<String, String> fields) {
        request("POST", recordsPath(origin), fields);
    }

    public void updateRecord(@NonNull String origin, int recordId, @NonNull Map<String, String> fields) {
        request("POST", recordsPath(origin) + "/" + recordId, fields);
    }

    public void deleteRecord(@NonNull String origin, int recordId) {
        request("POST", recordsPath(origin) + "/" + recordId + "/delete", Map.of());
    }

    private static @NonNull String recordsPath(@NonNull String origin) {
        return "/api/dns/zones/" + URLEncoder.encode(origin, StandardCharsets.UTF_8) + "/records";
    }

    private @Nullable Object request(@NonNull String method, @NonNull String path,
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

        Object parsed = parseQuietly(response.body());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return parsed;
        }
        if (parsed instanceof Map<?, ?> map) {
            Object key = map.get("key");
            Object field = map.get("field");
            Object message = map.get("message");
            throw new PeerApiException(
                message != null ? String.valueOf(message) : "Peer refused the edit (" + response.statusCode() + ")",
                key != null ? String.valueOf(key) : null,
                field != null ? String.valueOf(field) : null);
        }
        throw new PeerApiException("Peer returned HTTP " + response.statusCode(), null, null);
    }

    private static @Nullable Object parseQuietly(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return Zenit.DRY.parse(body);
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
