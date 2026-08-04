package be.elevenways.hohenheim.server.build;

import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.server.security.SecureTokens;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * THE credential lane of a build: every secret a sandbox is allowed to see is issued as
 * a LEASE -- bound to one build, bounded by a TTL, revoked when the build ends, and
 * redacted out of the captured log unconditionally.
 *
 * The four properties this mechanism actually enforces, none of which is a promise about
 * someone else's system:
 * <ol>
 *   <li>A leased secret is never written to a table, never onto the build operation and
 *       never into a settings map -- it lives only here, in the controller's memory, and
 *       dies with the process. A crashed controller therefore REVOKES by construction.</li>
 *   <li>{@link #resolve} refuses after {@link Lease#revoke()} and after the TTL, so a
 *       token that escapes (a log line, a layer, a leaked env) buys nothing later.</li>
 *   <li>The sandbox materializes a secret at container-create time ONLY, from a token --
 *       there is no path that hands a build a value it did not lease.</li>
 *   <li>{@link BuildLog} redacts every leased VALUE from build output, so the credential
 *       cannot leave through the one channel the tenant reads.</li>
 * </ol>
 *
 * AIDEV-NOTE: what this does NOT do, stated so nobody reads more into it. Hohenheim
 * shortens the life of ITS LEASE, not of an upstream provider's credential: a Docker Hub
 * password leased for 15 minutes is still a Docker Hub password afterwards. Genuinely
 * short-lived UPSTREAM credentials need a provider that mints them (GitHub App
 * installation tokens, registry-scoped JWTs), which arrives with the git-provider
 * installation wave -- and plugs into {@link #issue} unchanged, because the sandbox only
 * ever sees a token. Until then the honest claim is "scoped, leased and redacted", and
 * that is the claim the tests make.
 *
 * AIDEV-NOTE: SOURCE credentials deliberately do not reach a build at all today. The
 * control plane clones the repository itself and pushes only the checked-out TREE into
 * the sandbox, so there is no git credential inside the builder to leak. That is
 * stronger than short-lived, and it is why the shipped lease purpose is the registry.
 */
public final class BuildCredentials {

    /** What a leased secret is for; the sandbox decides how to materialize each. */
    public enum Purpose {
        /** Registry auth for base-image pulls (and pushes, once a build pushes). */
        REGISTRY_AUTH,
        /** Source (git) credential; unused today -- the control plane clones. */
        SOURCE_AUTH
    }

    /** Default lease lifetime; a build that outlives it loses its credentials, loudly. */
    public static final long DEFAULT_TTL_MS = 30 * 60 * 1000L;

    private static final Map<String, Entry> LEASES = new ConcurrentHashMap<>();

    private record Entry(int buildId, @NonNull Purpose purpose, @NonNull String value,
                         long expiresAtMillis) {}

    private BuildCredentials() {}

    /** A leased secret: the token is passable, the value is not. */
    public static final class Lease {

        private final @NonNull String token;
        private final @NonNull Purpose purpose;
        private final @NonNull String value;

        private Lease(@NonNull String token, @NonNull Purpose purpose, @NonNull String value) {
            this.token = token;
            this.purpose = purpose;
            this.value = value;
        }

        /** The opaque handle the sandbox exchanges for the secret. */
        public @NonNull String token() {
            return this.token;
        }

        public @NonNull Purpose purpose() {
            return this.purpose;
        }

        /** The leased value, for redaction only -- never for storage or display. */
        @NonNull String secretForRedaction() {
            return this.value;
        }

        /** Idempotent end of life; every build path runs this in a finally block. */
        public void revoke() {
            LEASES.remove(this.token);
        }
    }

    /**
     * Issue a lease over one secret for one build.
     *
     * @param ttlMs lifetime; clamped to {@link #DEFAULT_TTL_MS} when non-positive
     * @throws IllegalArgumentException on a blank secret -- a lease over nothing would
     *                                  read as "credentials configured" at every call site
     */
    public static @NonNull Lease issue(int buildId, @NonNull Purpose purpose,
                                       @NonNull String secret, long ttlMs) {
        if (secret.isBlank()) {
            throw new IllegalArgumentException("Refusing to lease a blank " + purpose
                + " secret for build " + buildId + ": an empty lease looks like a configured"
                + " credential at every call site and is one nowhere");
        }
        String token = SecureTokens.randomToken();
        long ttl = ttlMs > 0 ? ttlMs : DEFAULT_TTL_MS;
        LEASES.put(token, new Entry(buildId, purpose, secret, System.currentTimeMillis() + ttl));
        return new Lease(token, purpose, secret);
    }

    /**
     * Exchange a token for its secret.
     *
     * @param buildId the build claiming the lease; a token issued for ANOTHER build is
     *                refused, so a leaked token cannot be replayed by a sibling build
     * @return the secret, or null when the token is unknown, revoked, expired or another
     *         build's -- the four refusals are deliberately indistinguishable to a caller
     */
    public static @Nullable String resolve(int buildId, @NonNull String token) {
        Entry entry = LEASES.get(token);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAtMillis() <= System.currentTimeMillis()) {
            LEASES.remove(token, entry);
            Blast.log("BUILD: credential lease of build", entry.buildId(), "expired before use");
            return null;
        }
        if (entry.buildId() != buildId) {
            Blast.log("BUILD: REFUSED credential lease of build", entry.buildId(),
                "claimed by build", buildId);
            return null;
        }
        return entry.value();
    }

    /** Revoke every lease of one build; the sandbox's unconditional teardown. */
    public static void revokeAll(int buildId) {
        List<String> tokens = new ArrayList<>();
        LEASES.forEach((token, entry) -> {
            if (entry.buildId() == buildId) {
                tokens.add(token);
            }
        });
        tokens.forEach(LEASES::remove);
    }

    /** @return the live lease count of one build (diagnostics and tests) */
    public static int leaseCount(int buildId) {
        int count = 0;
        for (Entry entry : LEASES.values()) {
            if (entry.buildId() == buildId) {
                count++;
            }
        }
        return count;
    }

    /**
     * The docker {@code config.json} a daemonless builder reads registry auth from.
     *
     * @param registry registry host, e.g. {@code ghcr.io}; blank targets Docker Hub
     * @return the file content, or null when the token no longer resolves
     */
    public static @Nullable String dockerConfigJson(int buildId, @NonNull String token,
                                                    @NonNull String registry,
                                                    @NonNull String username) {
        String secret = resolve(buildId, token);
        if (secret == null) {
            return null;
        }
        String host = registry.isBlank() ? "https://index.docker.io/v1/" : registry;
        String encoded = java.util.Base64.getEncoder().encodeToString(
            (username + ":" + secret).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "{\"auths\":{" + quote(host) + ":{\"auth\":" + quote(encoded) + "}}}";
    }

    private static @NonNull String quote(@NonNull String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.append('"').toString();
    }
}
