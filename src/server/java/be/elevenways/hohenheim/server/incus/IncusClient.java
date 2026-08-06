package be.elevenways.hohenheim.server.incus;

import be.elevenways.hohenheim.server.util.Http11;
import be.elevenways.hohenheim.server.util.Json;
import be.elevenways.protoblast.common.dry.Dry;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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

        /**
         * The daemon refused a CREATE because the resource is already there -- the
         * lost half of a create/create race, not a failure of intent.
         */
        public boolean isAlreadyExists() {
            return this.status == 400 && String.valueOf(getMessage()).contains("already exists");
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

    // -- images -----------------------------------------------------------------

    /**
     * The resolved fingerprint of one alias in the daemon's OWN image store, or null
     * when the alias does not exist there. Never resolves against a remote server --
     * the prepared-template lane is the one consumer, and a prepared image is by
     * definition already local.
     */
    public @Nullable String imageFingerprintForAlias(@NonNull String alias) throws IOException {
        try {
            Map<String, Object> metadata = syncMetadata("GET",
                "/1.0/images/aliases/" + alias, null, DEFAULT_TIMEOUT_MS);
            Object target = metadata.get("target");
            return target instanceof String fingerprint && !fingerprint.isBlank()
                ? fingerprint : null;
        } catch (ApiException e) {
            if (e.isNotFound()) {
                return null;
            }
            throw e;
        }
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

    /**
     * Start a VGA (SPICE) console operation WITHOUT waiting; the returned operation's
     * {@code metadata.fds["0"]} secret is the ticket every SPICE channel websocket
     * links with. {@code force} preempts an existing session (a stale operation would
     * otherwise refuse a second attach).
     */
    public @NonNull Map<String, Object> startVgaConsole(@NonNull String name, boolean force)
            throws IOException {
        Http11.Raw raw = this.transport.exchange("POST", "/1.0/instances/" + name + "/console",
            Json.stringify(Map.of("width", 0, "height", 0, "type", "vga", "force", force)),
            DEFAULT_TIMEOUT_MS);
        Map<String, Object> envelope = envelopeOf(raw);
        Object metadata = envelope.get("metadata");
        if (!(metadata instanceof Map<?, ?> operation)) {
            throw new IOException("vga console operation of '" + name + "' carried no metadata");
        }
        return castMap(operation);
    }

    /**
     * One PNG snapshot of the instance's live VGA framebuffer (the hypervisor-side
     * screenshot Incus renders from the SPICE surface; works before the guest agent is
     * up, which is the rescue console's whole point). Returns the raw image bytes.
     */
    public byte @NonNull [] vgaScreenshot(@NonNull String name) throws IOException {
        Http11.Raw raw = this.transport.exchange("GET",
            "/1.0/instances/" + name + "/console?type=vga", null, DEFAULT_TIMEOUT_MS);
        if (raw.status() >= 200 && raw.status() < 300) {
            return raw.body();
        }
        envelopeOf(raw);   // throws the typed refusal (e.g. "Instance is not running")
        throw new IOException("unreachable");
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

    // -- exec -----------------------------------------------------------------

    /** One finished exec run: the process's exit code and its captured output. */
    public record ExecResult(int exitCode, @NonNull String output) {}

    /**
     * Run one command inside a RUNNING instance, wait for it, and collect the recorded
     * output (record-output mode: no websockets; the daemon writes stdout/stderr to
     * instance log files, which are read and then deleted here -- leaving them behind
     * would slowly fill the daemon's log directory).
     *
     * @param timeoutMs hard wall-clock cap on the wait; expiry throws while the daemon
     *                  side keeps running (the caller owns the container's fate)
     */
    public @NonNull ExecResult exec(@NonNull String name, @NonNull List<String> command,
                                    @NonNull Map<String, String> environment, long timeoutMs)
            throws IOException {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("command", command);
        request.put("environment", environment);
        request.put("record-output", true);
        request.put("interactive", false);
        request.put("wait-for-websocket", false);
        String operation = asyncOperation("POST", "/1.0/instances/" + name + "/exec",
            Json.stringify(request), DEFAULT_TIMEOUT_MS);
        // NOT waitOperation: the daemon marks an exec whose process exits 127 as a
        // FAILED operation ("Command not found", status 400) while metadata.return and
        // the output files are all present -- for exec, a carried return code IS a
        // completed run, whatever the operation's own verdict says.
        long seconds = Math.max(1, timeoutMs / 1000);
        Map<String, Object> finished = syncMetadata("GET",
            operation + "/wait?timeout=" + seconds, null, timeoutMs + 5000);
        boolean carriedReturn = finished.get("metadata") instanceof Map<?, ?> meta
            && meta.get("return") instanceof Number;
        Object statusCode = finished.get("status_code");
        if (!carriedReturn
                && (!(statusCode instanceof Number number) || number.intValue() != 200)) {
            Object err = finished.get("err");
            throw new ApiException(500, "Incus operation " + finished.get("description")
                + " " + finished.get("status")
                + (err != null && !String.valueOf(err).isEmpty() ? ": " + err : ""));
        }

        int exitCode = -1;
        StringBuilder output = new StringBuilder();
        if (finished.get("metadata") instanceof Map<?, ?> meta) {
            if (meta.get("return") instanceof Number code) {
                exitCode = code.intValue();
            }
            if (meta.get("output") instanceof Map<?, ?> files) {
                // Key "1" = stdout, "2" = stderr; TreeMap keeps that order stable. The
                // metadata's values are FULL API paths (logs/exec-output/... on current
                // daemons) and are used verbatim -- reconstructing them broke once.
                for (Object logPath : new TreeMap<>(castMap(files)).values()) {
                    String path = String.valueOf(logPath);
                    try {
                        output.append(rawText(path));
                    } finally {
                        try {
                            syncMetadata("DELETE", path, null, DEFAULT_TIMEOUT_MS);
                        } catch (IOException cleanupFailed) {
                            // the read succeeded or threw already; a leftover log file
                            // is a daemon-side crumb, not a run outcome
                        }
                    }
                }
            }
        }
        return new ExecResult(exitCode, output.toString());
    }

    /** One raw-text API path's content (log files; plain text, NOT an envelope). */
    private @NonNull String rawText(@NonNull String path) throws IOException {
        Http11.Raw raw = this.transport.exchange("GET", path, null, DEFAULT_TIMEOUT_MS);
        if (raw.status() >= 200 && raw.status() < 300) {
            return new String(raw.body(), StandardCharsets.UTF_8);
        }
        envelopeOf(raw);   // throws the typed refusal
        throw new IOException("unreachable");
    }

    // -- snapshots (daemon-side, pool-resident: NOT backups) ------------------

    /** Create one named snapshot (running or stopped instance; crash-consistent). */
    public void createSnapshot(@NonNull String name, @NonNull String snapshot)
            throws IOException {
        waitOperation(asyncOperation("POST", "/1.0/instances/" + name + "/snapshots",
            Json.stringify(Map.of("name", snapshot, "stateful", false)),
            DEFAULT_TIMEOUT_MS), LONG_OP_TIMEOUT_MS);
    }

    /** The snapshot object; 404 surfaces as {@link ApiException#isNotFound}. */
    public @NonNull Map<String, Object> snapshot(@NonNull String name,
                                                 @NonNull String snapshot) throws IOException {
        return syncMetadata("GET", "/1.0/instances/" + name + "/snapshots/" + snapshot,
            null, DEFAULT_TIMEOUT_MS);
    }

    /** Roll the instance back to the named snapshot (same PUT endpoint as update). */
    public void restoreSnapshot(@NonNull String name, @NonNull String snapshot)
            throws IOException {
        waitOperation(asyncOperation("PUT", "/1.0/instances/" + name,
            Json.stringify(Map.of("restore", snapshot)), DEFAULT_TIMEOUT_MS),
            LONG_OP_TIMEOUT_MS);
    }

    /** Delete one snapshot; 404 surfaces as {@link ApiException#isNotFound}. */
    public void deleteSnapshot(@NonNull String name, @NonNull String snapshot)
            throws IOException {
        waitOperation(asyncOperation("DELETE",
            "/1.0/instances/" + name + "/snapshots/" + snapshot, null,
            DEFAULT_TIMEOUT_MS), LONG_OP_TIMEOUT_MS);
    }

    // -- backups (portable whole-instance exports) ----------------------------

    /**
     * Create one daemon-side backup object. {@code optimized_storage=false} on
     * purpose: the export must restore onto ANY pool driver, not only the one that
     * wrote it.
     */
    public void createBackup(@NonNull String name, @NonNull String backup)
            throws IOException {
        createBackup(name, backup, true);
    }

    /**
     * @param instanceOnly false ALSO packs the instance's snapshots into the export
     *        (the cold-migration lane, so pool-resident snapshots survive the move);
     *        true is the backup lane's instance-only shape
     */
    public void createBackup(@NonNull String name, @NonNull String backup,
                             boolean instanceOnly) throws IOException {
        waitOperation(asyncOperation("POST", "/1.0/instances/" + name + "/backups",
            Json.stringify(Map.of("name", backup, "instance_only", instanceOnly,
                "optimized_storage", false)), DEFAULT_TIMEOUT_MS), LONG_OP_TIMEOUT_MS);
    }

    /**
     * Stream one backup's export tarball to {@code destination}.
     *
     * @param maxBytes hard cap enforced during the download
     * @return the exported size in bytes
     */
    public long exportBackup(@NonNull String name, @NonNull String backup,
                             @NonNull Path destination, long maxBytes)
            throws IOException {
        Http11.Raw raw = this.transport.exchangeDownload("GET",
            "/1.0/instances/" + name + "/backups/" + backup + "/export", destination,
            maxBytes, LONG_OP_TIMEOUT_MS);
        if (raw.body().length > 0 || raw.status() < 200 || raw.status() >= 300) {
            envelopeOf(raw);   // an error envelope throws the typed refusal
            throw new IOException("Incus backup export of '" + name
                + "' answered HTTP " + raw.status() + " without a payload");
        }
        return Files.size(destination);
    }

    /** Delete one backup object; 404 surfaces as {@link ApiException#isNotFound}. */
    public void deleteBackup(@NonNull String name, @NonNull String backup)
            throws IOException {
        waitOperation(asyncOperation("DELETE",
            "/1.0/instances/" + name + "/backups/" + backup, null,
            DEFAULT_TIMEOUT_MS), LONG_OP_TIMEOUT_MS);
    }

    /** Import an exported tarball as a NEW instance named {@code newName}. */
    public void importInstance(@NonNull Path archive, @NonNull String newName)
            throws IOException {
        Http11.Raw raw = this.transport.exchangeUpload("POST", "/1.0/instances", archive,
            "application/octet-stream", Map.of("X-Incus-Name", newName),
            LONG_OP_TIMEOUT_MS);
        Map<String, Object> envelope = envelopeOf(raw);
        Object operation = envelope.get("operation");
        if (!(operation instanceof String operationPath) || operationPath.isEmpty()) {
            throw new IOException("Incus backup import answered no operation to wait on");
        }
        waitOperation(operationPath, LONG_OP_TIMEOUT_MS);
    }

    // -- instance definition updates ------------------------------------------

    /** Replace the instance's mutable definition (full PUT; async). */
    public void updateInstance(@NonNull String name, @NonNull Map<String, Object> definition)
            throws IOException {
        waitOperation(asyncOperation("PUT", "/1.0/instances/" + name,
            Json.stringify(definition), DEFAULT_TIMEOUT_MS), LONG_OP_TIMEOUT_MS);
    }

    // -- network ACLs ---------------------------------------------------------

    /**
     * One network ACL's definition, or null when it does not exist.
     *
     * @throws IOException on any daemon error other than 404
     */
    public @Nullable Map<String, Object> networkAcl(@NonNull String name) throws IOException {
        try {
            return syncMetadata("GET", "/1.0/network-acls/" + name, null, DEFAULT_TIMEOUT_MS);
        } catch (ApiException e) {
            if (e.isNotFound()) {
                return null;
            }
            throw e;
        }
    }

    /** Create a network ACL (sync; {@code POST /1.0/network-acls}). */
    public void createNetworkAcl(@NonNull Map<String, Object> definition) throws IOException {
        syncPayload("POST", "/1.0/network-acls", Json.stringify(definition), DEFAULT_TIMEOUT_MS);
    }

    /** Replace a network ACL's rules and config (sync PUT). */
    public void updateNetworkAcl(@NonNull String name, @NonNull Map<String, Object> definition)
            throws IOException {
        syncPayload("PUT", "/1.0/network-acls/" + name, Json.stringify(definition),
            DEFAULT_TIMEOUT_MS);
    }

    /** Delete a network ACL; 404 surfaces as {@link ApiException#isNotFound}. */
    public void deleteNetworkAcl(@NonNull String name) throws IOException {
        syncPayload("DELETE", "/1.0/network-acls/" + name, null, DEFAULT_TIMEOUT_MS);
    }

    // -- networks (managed) ---------------------------------------------------

    /**
     * One managed network's definition, or null when it does not exist.
     *
     * @throws IOException on any daemon error other than 404
     */
    public @Nullable Map<String, Object> network(@NonNull String name) throws IOException {
        try {
            return syncMetadata("GET", "/1.0/networks/" + name, null, DEFAULT_TIMEOUT_MS);
        } catch (ApiException e) {
            if (e.isNotFound()) {
                return null;
            }
            throw e;
        }
    }

    /** Create a managed network ({@code POST /1.0/networks}; empty config = auto subnets). */
    public void createNetwork(@NonNull Map<String, Object> definition) throws IOException {
        syncPayload("POST", "/1.0/networks", Json.stringify(definition), DEFAULT_TIMEOUT_MS);
    }

    /** Delete a managed network; 404 surfaces as {@link ApiException#isNotFound}. */
    public void deleteNetwork(@NonNull String name) throws IOException {
        syncPayload("DELETE", "/1.0/networks/" + name, null, DEFAULT_TIMEOUT_MS);
    }

    // -- custom storage volumes -----------------------------------------------

    /**
     * One custom volume's definition, or null when it does not exist.
     *
     * @throws IOException on any daemon error other than 404
     */
    public @Nullable Map<String, Object> customVolume(@NonNull String pool,
                                                      @NonNull String name)
            throws IOException {
        try {
            return syncMetadata("GET",
                "/1.0/storage-pools/" + pool + "/volumes/custom/" + name, null,
                DEFAULT_TIMEOUT_MS);
        } catch (ApiException e) {
            if (e.isNotFound()) {
                return null;
            }
            throw e;
        }
    }

    /** Create a custom volume (block or filesystem; possibly async per pool driver). */
    public void createCustomVolume(@NonNull String pool,
                                   @NonNull Map<String, Object> definition)
            throws IOException {
        settleMaybeAsync("POST", "/1.0/storage-pools/" + pool + "/volumes/custom",
            Json.stringify(definition));
    }

    /** Replace a custom volume's config (the resize lane; PUT, possibly async). */
    public void updateCustomVolume(@NonNull String pool, @NonNull String name,
                                   @NonNull Map<String, Object> definition)
            throws IOException {
        settleMaybeAsync("PUT", "/1.0/storage-pools/" + pool + "/volumes/custom/" + name,
            Json.stringify(definition));
    }

    /** Delete a custom volume; 404 surfaces as {@link ApiException#isNotFound}. */
    public void deleteCustomVolume(@NonNull String pool, @NonNull String name)
            throws IOException {
        settleMaybeAsync("DELETE", "/1.0/storage-pools/" + pool + "/volumes/custom/" + name,
            null);
    }

    /**
     * One call whose envelope may be sync (settled) or async (an operation to wait on)
     * -- pool drivers differ on volume operations, so both shapes are legal.
     */
    private void settleMaybeAsync(@NonNull String method, @NonNull String path,
                                  @Nullable String body) throws IOException {
        Map<String, Object> envelope = envelopeOf(
            this.transport.exchange(method, path, body, DEFAULT_TIMEOUT_MS));
        if ("async".equals(envelope.get("type"))
                && envelope.get("operation") instanceof String operation
                && !operation.isEmpty()) {
            waitOperation(operation, LONG_OP_TIMEOUT_MS);
        }
    }

    // -- storage --------------------------------------------------------------

    /** One profile's definition ({@code GET /1.0/profiles/{name}}). */
    public @NonNull Map<String, Object> profile(@NonNull String name) throws IOException {
        return syncMetadata("GET", "/1.0/profiles/" + name, null, DEFAULT_TIMEOUT_MS);
    }

    /** One pool's usage ({@code GET /1.0/storage-pools/{pool}/resources}). */
    public @NonNull Map<String, Object> storagePoolResources(@NonNull String pool)
            throws IOException {
        return syncMetadata("GET", "/1.0/storage-pools/" + pool + "/resources", null,
            DEFAULT_TIMEOUT_MS);
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
