package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.dns.DnsRecordDto;
import be.elevenways.hohenheim.dns.DnsRecordSetOutcome;
import be.elevenways.hohenheim.dns.DnsRecordSetResponse;
import be.elevenways.hohenheim.dns.DnsPeerKeyResponse;
import be.elevenways.hohenheim.dns.DnsPeerMirrorResponse;
import be.elevenways.hohenheim.dns.DnsRecordListResponse;
import be.elevenways.hohenheim.dns.DnsValidationErrorResponse;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.util.Json;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.protoblast.common.http.HttpMethod;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.validation.UrlProblem;
import be.elevenways.zenit.server.net.FetchOutcome;
import be.elevenways.zenit.server.net.FetchRequest;
import be.elevenways.zenit.server.net.OutboundUrlGuard;
import be.elevenways.zenit.server.net.PinnedFetcher;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for a peer Hohenheim's DNS-records API: the edit-forwarding
 * channel that lets this instance edit zones the peer owns. Authenticates
 * with the peer's stored znit_ API key.
 *
 * AIDEV-NOTE: the key is an administrator's, so every call rides one {@link PinnedFetcher}: https only, the public
 * internet unless the host boot declares {@link HohenheimSettings.Dns#PEER_ALLOW_PRIVATE_NETWORKS}, the connection
 * pinned to the vetted address, and a redirect never followed (its target would receive the bearer). Messages name
 * at most the peer's origin, never the key or a path.
 */
public final class DnsPeerApi {

    /**
     * A peer refusal or transport failure; {@code violationKey} is set for 422 validation refusals.
     *
     * AIDEV-NOTE: {@link #status} 0 means no HTTP answer came; {@link #sent} then tells an address refused or not
     * reached (nothing happened there) from a request sent and never answered (it may have been applied there).
     */
    public static final class PeerApiException extends RuntimeException {
        private final @Nullable String violationKey;
        private final @Nullable String violationField;
        private final int status;
        private final boolean sent;

        PeerApiException(String message, @Nullable String violationKey, @Nullable String violationField) {
            this(message, violationKey, violationField, 0, false);
        }

        PeerApiException(String message, @Nullable String violationKey, @Nullable String violationField, int status,
                         boolean sent) {
            super(message);
            this.violationKey = violationKey;
            this.violationField = violationField;
            this.status = status;
            this.sent = sent;
        }

        public @Nullable String getViolationKey() { return violationKey; }
        public @Nullable String getViolationField() { return violationField; }

        /** @return the peer's HTTP status, 0 when it gave no HTTP answer */
        public int status() { return status; }

        /** @return whether the request left this server: true for any HTTP answer and for one sent unanswered */
        public boolean sent() { return sent; }
    }

    /**
     * The row columns the peer wire carries FLAT: a deliberate SUBSET of the model (no
     * zone_id, no generated_* -- those are the server's, not a caller's). {@code managed_by}
     * is handled apart: it is settable on CREATE only, and only to a value
     * {@link DnsRecordModel#MANAGED_BY_VALUES} declares.
     */
    private static final List<String> COLUMN_FIELDS = List.of(
        DnsRecordModel.NAME.getName(), DnsRecordModel.TYPE.getName(),
        DnsRecordModel.VALUE.getName(), DnsRecordModel.TTL.getName(),
        DnsRecordModel.ENABLED.getName());

    /**
     * The record fields the wire carries: what the owner's record API reads and what the remote-record form forwards.
     *
     * AIDEV-NOTE: the type-specific half is DERIVED from the model's per-type sub-schemas
     * ({@link DnsRecordModel#DATA_FIELD_NAMES}), never re-listed. Until 2026-08-17 this
     * spelled "priority", "weight", "port" here AND partitioned on the same three names
     * again in the owner's record API -- so adding a field to the SRV schema needed two
     * edits that nothing tied together, and forgetting either silently
     * dropped the field off the API instead of failing.
     */
    public static final List<String> RECORD_FIELDS = recordFields();

    private static List<String> recordFields() {
        List<String> fields = new ArrayList<>(COLUMN_FIELDS);
        fields.addAll(DnsRecordModel.DATA_FIELD_NAMES);
        return List.copyOf(fields);
    }

    /** The record-set route's fields: the entries as JSON, the forwarding server's name, the person it acts for. */
    public static final String SET_RECORDS = "records";
    public static final String SET_SERVER = "server";
    public static final String SET_PERSON = "person";

    /** The whole exchange, DNS, connect, request and answer together. */
    private static final Duration DEADLINE = Duration.ofSeconds(15);

    /** One connection attempt to one address of the peer. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final String baseUrl;
    private final String apiKey;
    /**
     * AIDEV-NOTE: built per client, never a static: the opt-in is an immutable host-boot fact, but a static would
     * freeze whatever was read when this class happened to load, which may precede that declaration.
     */
    private final PinnedFetcher fetcher;

    private DnsPeerApi(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.fetcher = PinnedFetcher.builder(
                OutboundUrlGuard.optingIn(HohenheimSettings.Dns.PEER_ALLOW_PRIVATE_NETWORKS).forSchemes("https"))
            .connectTimeout(CONNECT_TIMEOUT)
            .deadline(DEADLINE)
            .redirects(PinnedFetcher.Redirects.handBack())
            .build();
    }

    /**
     * @return a client for the peer, or null when it is not a Hohenheim peer or its
     *         credentials are missing
     *
     * AIDEV-NOTE: the TYPE decides first. Before it existed, "has a base URL and a key"
     * WAS the definition of a Hohenheim peer, so a half-configured one silently behaved
     * like a plain nameserver; now a hohenheim-typed peer missing a credential still
     * yields null (fail closed) but the form refuses to create one in the first place.
     */
    public static @Nullable DnsPeerApi forPeer(@Nullable Row peer) {
        if (peer == null || !DnsPeerModel.isHohenheim(peer)) {
            return null;
        }
        String baseUrl = peer.get(DnsPeerModel.BASE_URL);
        String apiKey = peer.get(DnsPeerModel.API_KEY);
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            return null;
        }
        return new DnsPeerApi(baseUrl.trim(), apiKey.trim());
    }

    /** @return a client for the peer owning a secondary zone, or null when it has none or is no Hohenheim peer */
    public static @Nullable DnsPeerApi forZone(@NonNull Row zone) {
        return forPeer(Models.get(DnsPeerModel.class).findById(zone.get(DnsZoneModel.PRIMARY_PEER_ID)));
    }

    // AIDEV-NOTE: the paths below address a REMOTE peer, but a peer runs THIS software,
    // so its record API is this app's own declared route. Rendering that declaration is
    // what keeps the two ends from drifting -- a hand-copied literal is exactly the
    // drift this forbids, and it is also why these are toUrl(Map) rather than a
    // RouteTarget: the base URL is the peer's, only the PATH comes from here.

    /** @return the owner's live record rows for the zone */
    public @NonNull List<DnsRecordDto> listRecords(@NonNull String origin) {
        DnsRecordListResponse response = parseQuietly(
            request(HttpMethod.GET, HohenheimEndpoints.API_DNS_RECORDS.toUrl(
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
        request(HttpMethod.POST, HohenheimEndpoints.API_DNS_RECORD_CREATE.toUrl(
            Map.of(HohenheimEndpoints.DNS_ORIGIN, origin)), fields);
    }

    public void updateRecord(@NonNull String origin, int recordId, @NonNull Map<String, String> fields) {
        request(HttpMethod.POST, HohenheimEndpoints.API_DNS_RECORD_UPDATE.toUrl(Map.of(
            HohenheimEndpoints.DNS_ORIGIN, origin,
            HohenheimEndpoints.DNS_RECORD_ID, recordId)), fields);
    }

    public void deleteRecord(@NonNull String origin, int recordId) {
        request(HttpMethod.POST, HohenheimEndpoints.API_DNS_RECORD_DELETE.toUrl(Map.of(
            HohenheimEndpoints.DNS_ORIGIN, origin,
            HohenheimEndpoints.DNS_RECORD_ID, recordId)), Map.of());
    }

    /**
     * Applies a whole record set on the owner, all entries or none, in the shape the record-set tool takes them.
     *
     * @param records the entries as the tool validated them
     * @param server  the name this server announces to peers, for the owner's history
     * @param person  the person the set is done for, null when no person stands behind it
     * @return one outcome per entry, in the order sent
     * @throws PeerApiException with status 404 from a peer that does not know this route (an older Hohenheim)
     */
    public @NonNull List<DnsRecordSetOutcome> setRecords(@NonNull String origin, @NonNull List<?> records,
                                                         @NonNull String server, @Nullable String person) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(SET_RECORDS, Json.stringify(records));
        fields.put(SET_SERVER, server);
        fields.put(SET_PERSON, person);
        DnsRecordSetResponse response = parseQuietly(request(HttpMethod.POST, HohenheimEndpoints.API_DNS_RECORDS_SET
            .toUrl(Map.of(HohenheimEndpoints.DNS_ORIGIN, origin)), fields), DnsRecordSetResponse.class);
        if (response == null || response.entries() == null || response.entries().size() != records.size()) {
            // Sent and answered, yet unreadable: the owner may have applied it, so this reads as unanswered.
            throw new PeerApiException("Unexpected response from peer", null, null, 0, true);
        }
        return response.entries();
    }

    /**
     * Installs a freshly minted shared TSIG key on the peer, announcing where that peer
     * transfers from us.
     *
     * @param localName    the name this instance announces; the peer files us under it
     * @param transferHost our own AXFR/NOTIFY address, or null to let the peer read it off
     *                     the connection this request arrives on
     * @return the peer's confirmation, naming the key and the endpoint it filed us under
     */
    public @NonNull DnsPeerKeyResponse negotiateTransferKey(@NonNull String localName,
                                                            @NonNull String keyName,
                                                            @NonNull String algorithm,
                                                            @NonNull String secret,
                                                            @Nullable String transferHost,
                                                            int transferPort) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("peer", localName);
        fields.put("key_name", keyName);
        fields.put("algorithm", algorithm);
        fields.put("secret", secret);
        if (transferHost != null && !transferHost.isBlank()) {
            fields.put("transfer_host", transferHost.trim());
        }
        fields.put("transfer_port", String.valueOf(transferPort));

        DnsPeerKeyResponse response = parseQuietly(
            request(HttpMethod.POST, HohenheimEndpoints.API_DNS_PEER_KEY.toUrl(), fields),
            DnsPeerKeyResponse.class);
        if (response == null || response.key_name() == null || response.key_name().isBlank()) {
            throw new PeerApiException("Unexpected response from peer", null, null);
        }
        return response;
    }
    /**
     * Tells the peer to keep exactly {@code origins} as secondary zones pulling from this instance, which it knows by
     * the transfer key the two share; {@code signature} proves this side holds that key's secret
     * (DnsPeerMirrors.signature).
     *
     * @return what the peer created, removed and left alone
     */
    public @NonNull DnsPeerMirrorResponse mirrorZones(@NonNull String keyName, long signedAt,
                                                      @NonNull String signature, @NonNull List<String> origins) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("key_name", keyName);
        fields.put("signed_at", String.valueOf(signedAt));
        fields.put("signature", signature);
        fields.put("origins", String.join("\n", origins));
        DnsPeerMirrorResponse response = parseQuietly(
            request(HttpMethod.POST, HohenheimEndpoints.API_DNS_PEER_MIRROR.toUrl(), fields),
            DnsPeerMirrorResponse.class);
        if (response == null || response.created() == null || response.removed() == null
            || response.conflicts() == null) {
            throw new PeerApiException("Unexpected response from peer", null, null);
        }
        return response;
    }

    private @Nullable String request(@NonNull HttpMethod method, @NonNull String path,
                                     @Nullable Map<String, String> formFields) {
        FetchRequest request = HttpMethod.GET.equals(method)
            ? FetchRequest.get(this.baseUrl + path)
            : new FetchRequest(method, this.baseUrl + path, Map.of(),
                encodeForm(formFields).getBytes(StandardCharsets.UTF_8))
                .withHeader("Content-Type", "application/x-www-form-urlencoded");
        FetchOutcome outcome = this.fetcher.fetch(request.withHeader("Authorization", "Bearer " + this.apiKey));
        FetchOutcome.Fetched response = switch (outcome) {
            case FetchOutcome.Fetched fetched -> fetched;
            case FetchOutcome.Refused refused -> throw new PeerApiException(refusalOf(refused), null, null, 0,
                refused.requestSent());
            case FetchOutcome.Redirected redirected -> throw new PeerApiException("Peer answered with a redirect (HTTP "
                + redirected.status() + "), which is never followed", null, null, redirected.status(), true);
            case FetchOutcome.Failed failed -> throw new PeerApiException(failed.requestSent()
                ? "Peer did not answer (the request was sent): " + failed.reason()
                : "Peer unreachable: " + failed.reason(), null, null, 0, failed.requestSent());
        };

        String body = new String(response.body(), StandardCharsets.UTF_8);
        if (response.status() >= 200 && response.status() < 300) {
            return body;
        }

        DnsValidationErrorResponse refusal = parseQuietly(body, DnsValidationErrorResponse.class);
        if (refusal != null && (refusal.error() != null || refusal.message() != null
            || refusal.key() != null || refusal.field() != null)) {
            throw new PeerApiException(
                refusal.message() != null
                    ? refusal.message()
                    : "Peer refused the edit (" + response.status() + ")",
                refusal.key(), refusal.field(), response.status(), true);
        }
        throw new PeerApiException("Peer returned HTTP " + response.status(), null, null, response.status(), true);
    }

    /**
     * @return the words of a peer address the guard refused before anything was sent, naming at most its origin; a
     *         name that did not resolve keeps the words of any other unreachable peer
     */
    private @NonNull String refusalOf(FetchOutcome.@NonNull Refused refused) {
        if (OutboundUrlGuard.RefusalKind.of(refused.problem()) != OutboundUrlGuard.RefusalKind.POLICY) {
            return "Peer unreachable: " + refused.reason();
        }
        if (refused.problem() == UrlProblem.SCHEME_NOT_ALLOWED) {
            return "Peer address refused: it must start with https:// (" + refused.reason() + ")";
        }
        if (OutboundUrlGuard.awaitsOptIn(HohenheimSettings.Dns.PEER_ALLOW_PRIVATE_NETWORKS, this.baseUrl)) {
            return "Peer address refused: a peer on a private network needs the host declaration "
                + HohenheimSettings.Dns.PEER_ALLOW_PRIVATE_NETWORKS.configurationPath() + " = true ("
                + refused.reason() + ")";
        }
        return "Peer address refused: " + refused.reason();
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
