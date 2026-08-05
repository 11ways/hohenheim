package be.elevenways.hohenheim.server.incus;

import be.elevenways.hohenheim.server.util.Http11;
import be.elevenways.hohenheim.server.util.Json;
import be.elevenways.protoblast.common.dry.Dry;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minimal Incus REST client over an {@link IncusTransport}. Incus wraps every answer
 * in an envelope ({@code sync} / {@code async} / {@code error}); long operations are
 * ASYNC -- the POST returns an operation and {@link #waitOperation} collects its real
 * outcome -- and the streaming endpoints are WEBSOCKETS reached through
 * {@link #operationWebSocket}. Driver #2's wire shape, deliberately nothing like
 * DockerClient's.
 */
public class IncusClient {

    /** Default per-request deadline. */
    public static final long DEFAULT_TIMEOUT_MS = 30_000;

    /** Deadline for operations that legitimately download images. */
    public static final long LONG_OP_TIMEOUT_MS = 600_000;

    /**
     * A refusal from a REACHED Incus daemon, carrying the HTTP-shaped error code so
     * callers can tell "absent" (404, an observed fact) from "could not ask" (a plain
     * IOException) -- the same distinction DockerClient.ApiException carries.
     */
    public static class ApiException extends IOException {

        private final int status;

        ApiException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return this.status;
        }

        /** The daemon answered and the resource does not exist. */
        public boolean isNotFound() {
            return this.status == 404;
        }
    }

    private final @NonNull IncusTransport transport;

    public IncusClient(@NonNull IncusTransport transport) {
        this.transport = transport;
    }

    public @NonNull IncusTransport transport() {
        return this.transport;
    }

    // -- daemon ---------------------------------------------------------------

    /** The {@code GET /1.0} payload: api_version, auth, environment (when trusted). */
    public @NonNull Map<String, Object> server() throws IOException {
        return syncMetadata("GET", "/1.0", null, DEFAULT_TIMEOUT_MS);
    }

    /** Whether the daemon trusts THIS client's identity ({@code auth: trusted}). */
    public boolean trusted() throws IOException {
        return "trusted".equals(server().get("auth"));
    }

    /**
     * Enroll this connection's client certificate using an operator-minted trust token
     * ({@code incus config trust add}); afterwards {@link #trusted()} must flip to true
     * -- the caller asserts that, never assumes it.
     */
    public void enrollWithToken(@NonNull String token) throws IOException {
        syncMetadata("POST", "/1.0/certificates",
            Json.stringify(Map.of("type", "client", "trust_token", token)),
            DEFAULT_TIMEOUT_MS);
    }

    /** All storage pools (recursed). */
    @SuppressWarnings("unchecked")
    public @NonNull List<Map<String, Object>> storagePools() throws IOException {
        return (List<Map<String, Object>>) (List<?>) listOf(
            syncPayload("GET", "/1.0/storage-pools?recursion=1", null, DEFAULT_TIMEOUT_MS));
    }

    /** The host's resource inventory ({@code GET /1.0/resources}; trusted clients only). */
    public @NonNull Map<String, Object> resources() throws IOException {
        return syncMetadata("GET", "/1.0/resources", null, DEFAULT_TIMEOUT_MS);
    }

    /** All instances of the default project, recursed to full objects. */
    @SuppressWarnings("unchecked")
    public @NonNull List<Map<String, Object>> instances() throws IOException {
        return (List<Map<String, Object>>) (List<?>) listOf(
            syncPayload("GET", "/1.0/instances?recursion=1", null, DEFAULT_TIMEOUT_MS));
    }

    /** All networks (recursed). */
    @SuppressWarnings("unchecked")
    public @NonNull List<Map<String, Object>> networks() throws IOException {
        return (List<Map<String, Object>>) (List<?>) listOf(
            syncPayload("GET", "/1.0/networks?recursion=1", null, DEFAULT_TIMEOUT_MS));
    }

    // -- instances ------------------------------------------------------------

    /** The instance's definition ({@code GET /1.0/instances/{name}}). */
    public @NonNull Map<String, Object> instance(@NonNull String name) throws IOException {
        return syncMetadata("GET", "/1.0/instances/" + name, null, DEFAULT_TIMEOUT_MS);
    }

    /** The instance's live state ({@code GET /1.0/instances/{name}/state}). */
    public @NonNull Map<String, Object> instanceState(@NonNull String name) throws IOException {
        return syncMetadata("GET", "/1.0/instances/" + name + "/state", null,
            DEFAULT_TIMEOUT_MS);
    }

    /**
     * Create one instance and WAIT for the operation (image download included, hence
     * the long deadline).
     */
    public void createInstance(@NonNull Map<String, Object> definition) throws IOException {
        waitOperation(asyncOperation("POST", "/1.0/instances", Json.stringify(definition),
            DEFAULT_TIMEOUT_MS), LONG_OP_TIMEOUT_MS);
    }

    /**
     * Drive the instance's power state and wait for the outcome.
     *
     * @param action  {@code start} / {@code stop} / {@code restart}
     * @param timeout seconds the daemon gives a graceful transition; -1 = its default
     * @param force   kill after the timeout instead of failing the operation
     */
    public void changeState(@NonNull String name, @NonNull String action, int timeout,
                            boolean force) throws IOException {
        String operation = asyncOperation("PUT", "/1.0/instances/" + name + "/state",
            Json.stringify(Map.of("action", action, "timeout", timeout, "force", force)),
            DEFAULT_TIMEOUT_MS);
        // The wait outlives the daemon-side timeout so the OPERATION reports the verdict.
        waitOperation(operation, (Math.max(timeout, 0) + 60) * 1000L);
    }

    /** Delete one instance and wait; 404 surfaces as {@link ApiException#isNotFound}. */
    public void deleteInstance(@NonNull String name) throws IOException {
        waitOperation(asyncOperation("DELETE", "/1.0/instances/" + name, null,
            DEFAULT_TIMEOUT_MS), LONG_OP_TIMEOUT_MS);
    }

    /**
     * Start a console operation WITHOUT waiting (the operation runs for the console's
     * lifetime); the returned map is the operation object whose {@code metadata.fds}
     * carries the websocket secrets.
     */
    public @NonNull Map<String, Object> startConsole(@NonNull String name) throws IOException {
        Http11.Raw raw = this.transport.exchange("POST", "/1.0/instances/" + name + "/console",
            Json.stringify(Map.of("width", 120, "height", 30, "type", "console")),
            DEFAULT_TIMEOUT_MS);
        Map<String, Object> envelope = envelopeOf(raw);
        Object metadata = envelope.get("metadata");
        if (!(metadata instanceof Map<?, ?> operation)) {
            throw new IOException("console operation of '" + name + "' carried no metadata");
        }
        return castMap(operation);
    }

    /** The instance's console log ring buffer (plain text, NOT an envelope). */
    public @NonNull String consoleLog(@NonNull String name) throws IOException {
        Http11.Raw raw = this.transport.exchange("GET", "/1.0/instances/" + name + "/console",
            null, DEFAULT_TIMEOUT_MS);
        if (raw.status() >= 200 && raw.status() < 300) {
            String body = new String(raw.body(), StandardCharsets.UTF_8);
            // An envelope here means the daemon refused with JSON instead of the log.
            if (body.startsWith("{") && body.contains("\"type\"")) {
                envelopeOf(raw);
            }
            return body;
        }
        envelopeOf(raw);   // throws the typed refusal
        throw new IOException("unreachable");
    }

    // -- operations -----------------------------------------------------------

    /**
     * Wait for one operation and return its final object.
     *
     * @param operation the operation PATH the async envelope named
     * @throws ApiException carrying the operation's failure when it did not succeed
     */
    public @NonNull Map<String, Object> waitOperation(@NonNull String operation, long timeoutMs)
            throws IOException {
        long seconds = Math.max(1, timeoutMs / 1000);
        Map<String, Object> finished = syncMetadata("GET",
            operation + "/wait?timeout=" + seconds, null, timeoutMs + 5000);
        Object statusCode = finished.get("status_code");
        int code = statusCode instanceof Number number ? number.intValue() : -1;
        if (code != 200) {
            Object err = finished.get("err");
            throw new ApiException(code == 404 ? 404 : 500, "Incus operation "
                + finished.get("description") + " " + finished.get("status")
                + (err != null && !String.valueOf(err).isEmpty() ? ": " + err : ""));
        }
        return finished;
    }

    /** Open the websocket of one operation stream ({@code fds} secret). */
    public @NonNull IncusWebSocket operationWebSocket(@NonNull String operationPath,
                                                      @NonNull String secret)
            throws IOException {
        return this.transport.openWebSocket(operationPath + "/websocket?secret=" + secret,
            DEFAULT_TIMEOUT_MS);
    }

    // -- envelope plumbing ----------------------------------------------------

    /** One sync call, returning the envelope's {@code metadata} MAP. */
    private @NonNull Map<String, Object> syncMetadata(@NonNull String method,
                                                      @NonNull String path,
                                                      @Nullable String body,
                                                      long timeoutMs) throws IOException {
        Object metadata = syncPayload(method, path, body, timeoutMs);
        if (metadata == null) {
            return Map.of();
        }
        if (!(metadata instanceof Map<?, ?> map)) {
            throw new IOException("Incus " + method + " " + path
                + " answered non-object metadata: " + metadata);
        }
        return castMap(map);
    }

    /** One sync call, returning the envelope's {@code metadata} of whatever shape. */
    private @Nullable Object syncPayload(@NonNull String method, @NonNull String path,
                                         @Nullable String body, long timeoutMs)
            throws IOException {
        Map<String, Object> envelope = envelopeOf(
            this.transport.exchange(method, path, body, timeoutMs));
        return envelope.get("metadata");
    }

    /** One async call, returning the operation PATH to wait on. */
    private @NonNull String asyncOperation(@NonNull String method, @NonNull String path,
                                           @Nullable String body, long timeoutMs)
            throws IOException {
        Map<String, Object> envelope = envelopeOf(
            this.transport.exchange(method, path, body, timeoutMs));
        Object operation = envelope.get("operation");
        if (!(operation instanceof String operationPath) || operationPath.isEmpty()) {
            throw new IOException("Incus " + method + " " + path
                + " answered no operation to wait on");
        }
        return operationPath;
    }

    /**
     * Parse and POLICE the envelope: an {@code error} type (or bare HTTP error) becomes
     * a typed {@link ApiException} carrying the daemon's own words.
     */
    private @NonNull Map<String, Object> envelopeOf(Http11.@NonNull Raw raw) throws IOException {
        String text = new String(raw.body(), StandardCharsets.UTF_8);
        Object parsed;
        try {
            parsed = new Dry().parse(text);
        } catch (Exception notJson) {
            throw new IOException("Incus answered HTTP " + raw.status()
                + " with a non-JSON body: " + text.trim());
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IOException("Incus answered HTTP " + raw.status()
                + " with a non-envelope body: " + text.trim());
        }
        Map<String, Object> envelope = castMap(map);
        if ("error".equals(envelope.get("type"))) {
            Object code = envelope.get("error_code");
            int status = code instanceof Number number ? number.intValue() : raw.status();
            throw new ApiException(status, "Incus API error " + status + ": "
                + envelope.get("error"));
        }
        if (raw.status() < 200 || raw.status() >= 300) {
            throw new ApiException(raw.status(), "Incus API returned HTTP " + raw.status()
                + ": " + text.trim());
        }
        return envelope;
    }

    private static @NonNull List<Object> listOf(@Nullable Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        throw new IllegalStateException("expected a JSON array, got: " + value);
    }

    @SuppressWarnings("unchecked")
    private static @NonNull Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
