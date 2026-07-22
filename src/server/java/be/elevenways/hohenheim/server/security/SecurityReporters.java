package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.model.SecurityReporterModel;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.security.SecureTokens;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;

/**
 * Security reporter auth: zsec_ ingest tokens are stored as SHA-256 hashes and
 * the raw value exists only at mint time (the HubProjects shape).
 */
public final class SecurityReporters {

    public static final String TOKEN_PREFIX = "zsec_";
    private static final long LAST_SEEN_THROTTLE_SECONDS = 60;

    /** A freshly minted ingest token: the raw value exists only here. */
    public record MintedToken(@NonNull String raw, int reporterId) {}

    private SecurityReporters() {
    }

    /** Creates a reporter and mints its ingest token. */
    public static @NonNull MintedToken create(@NonNull String name, @Nullable Integer siteId,
                                              boolean enabled) {
        SecurityReporterModel reporters = Models.get(SecurityReporterModel.class);
        String raw = TOKEN_PREFIX + SecureTokens.randomToken(32);

        Row row = reporters.createEmptyRow();
        row.set(SecurityReporterModel.NAME, name);
        row.set(SecurityReporterModel.SITE_ID, siteId);
        row.set(SecurityReporterModel.TOKEN_HASH, SecureTokens.sha256Hex(raw));
        row.set(SecurityReporterModel.ENABLED, enabled);
        reporters.save(row);

        Integer id = row.get(SecurityReporterModel.ID);
        if (id == null) {
            throw new IllegalStateException("Security reporter save did not populate the id");
        }
        return new MintedToken(raw, id);
    }

    /**
     * Mints a replacement token for an existing reporter; the old token stops
     * working immediately.
     *
     * @throws IllegalArgumentException when the reporter does not exist
     */
    public static @NonNull MintedToken rotate(int reporterId) {
        SecurityReporterModel reporters = Models.get(SecurityReporterModel.class);
        String raw = TOKEN_PREFIX + SecureTokens.randomToken(32);

        int updated = reporters.find()
            .where(SecurityReporterModel.ID.eq(reporterId))
            .assign(SecurityReporterModel.TOKEN_HASH, SecureTokens.sha256Hex(raw))
            .updateAll();

        if (updated == 0) {
            throw new IllegalArgumentException("Unknown security reporter " + reporterId);
        }
        return new MintedToken(raw, reporterId);
    }

    /**
     * Resolves the request's bearer token to its enabled reporter row, or null
     * when missing/invalid/disabled. The hash lookup is inherently constant-time
     * with respect to the stored value; last_seen writes are throttled to one
     * per minute.
     */
    public static @Nullable Row authenticate(@NonNull Conduit conduit) {
        String raw = null;
        String authorization = conduit.getRequestHeader("authorization");

        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            raw = authorization.substring(7).trim();
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }

        SecurityReporterModel reporters = Models.get(SecurityReporterModel.class);
        Row reporter = reporters.find()
            .where(SecurityReporterModel.TOKEN_HASH.eq(SecureTokens.sha256Hex(raw)))
            .first();

        if (reporter == null || !Boolean.TRUE.equals(reporter.get(SecurityReporterModel.ENABLED))) {
            return null;
        }

        Integer id = reporter.get(SecurityReporterModel.ID);
        Instant lastSeen = reporter.get(SecurityReporterModel.LAST_SEEN_AT);

        if (id != null && (lastSeen == null
                || lastSeen.isBefore(Instant.now().minusSeconds(LAST_SEEN_THROTTLE_SECONDS)))) {
            reporters.find().where(SecurityReporterModel.ID.eq(id))
                .assign(SecurityReporterModel.LAST_SEEN_AT, Instant.now())
                .updateAll();
        }
        return reporter;
    }
}
