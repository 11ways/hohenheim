package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.model.DnsDyndnsCredentialModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.hohenheim.server.security.SecretShapes;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.orm.query.QueryContext;
import be.elevenways.zenit.server.security.SecureTokens;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * Dynamic DNS over the de-facto dyndns2 protocol: an A/AAAA record carries a
 * per-record update token, and a client (router, ddclient, ...) rewrites the
 * record's address by presenting the token. The record row is the single
 * source of truth, so an update flows through the normal serial bump ->
 * re-sign -> NOTIFY path and reaches secondaries within seconds.
 *
 * The credential lives in {@link DnsDyndnsCredentialModel} (its own table): a
 * credential row existing IS the record being dynamic, and only the digest is
 * at rest. Minting and revoking go through {@link #mintFor}/{@link #revokeFor},
 * the one write funnel.
 *
 * AIDEV-NOTE: {@code update} deliberately asks NO hostname-liveness question. A
 * per-update "the FQDN must be covered by a live site" predicate would break the
 * feature's primary use -- operator dyndns names (a home router) are not sites and
 * are covered by nothing. The token-outlives-the-claim problem is closed one tier
 * over instead: {@link DnsClaimReleases} deletes the credential row (and disables
 * the record) the moment a hostname's last covering domain row is released, so a
 * released name's token answers badauth here without this method ever asking
 * whose name it is.
 */
public final class DynamicDnsService {

    /** Wire token prefix, so a presented credential is recognizably ours. */
    public static final String TOKEN_MARKER = "hdyn_";

    /**
     * Marks a STORED value as a SHA-256 digest. The token is a live HTTP-Basic
     * password that drives DNS updates, so only its digest is ever at rest (the
     * {@code SiteApiKeys} shape). The client presents the plaintext {@code hdyn_}
     * token; the service hashes what it is handed and looks the credential up BY
     * DIGEST.
     */
    private static final String DIGEST_PREFIX = "sha256:";

    /** dyndns2 status codes returned to the client (text/plain). */
    public enum Status {
        GOOD, NOCHG, BADAUTH, NOHOST, NOTFQDN, DNSERR, NOTPRIMARY
    }

    /** A dyndns2 result: the status plus the resolved IP for good/nochg replies. */
    public record UpdateResult(@NonNull Status status, @Nullable String ip) {
        /** The bare dyndns2 response line, e.g. {@code good 1.2.3.4} / {@code nochg 1.2.3.4} / {@code badauth}. */
        public @NonNull String wire() {
            return switch (this.status) {
                case GOOD -> "good " + this.ip;
                case NOCHG -> "nochg " + this.ip;
                case BADAUTH -> "badauth";
                case NOHOST -> "nohost";
                case NOTFQDN -> "notfqdn";
                case DNSERR -> "dnserr";
                case NOTPRIMARY -> "!yours";
            };
        }
    }

    private final DnsZoneStore store;

    public DynamicDnsService(@NonNull DnsZoneStore store) {
        this.store = store;
    }

    /** @return a fresh plaintext update token; only the mint action's one-shot toast ever sees it */
    public static @NonNull String mintToken() {
        return TOKEN_MARKER + SecureTokens.randomToken();
    }

    /** @return the stored form (digest) of a plaintext dyndns token */
    public static @NonNull String digest(@NonNull String plaintext) {
        return DIGEST_PREFIX + SecureTokens.sha256Hex(plaintext);
    }

    /**
     * @return true when the stored value is already a digest, not a legacy plaintext token
     *
     * AIDEV-NOTE: the COMPLETE shape is validated ({@code sha256:} + exactly 64
     * lowercase hex chars), never just the prefix -- a legacy plaintext token that
     * happens to start with "sha256:" must be hashed like any other plaintext,
     * not misclassified as a digest and silently invalidated (M091 relies on this
     * during the credential copy).
     */
    public static boolean isDigest(@Nullable String stored) {
        return SecretShapes.isSha256Digest(stored, DIGEST_PREFIX);
    }

    /** @return the credential row for a record, or null when the record is not dynamic */
    public static @Nullable Row credentialFor(@Nullable Integer recordId) {
        if (recordId == null) {
            return null;
        }
        return Models.get(DnsDyndnsCredentialModel.class).find()
            .where(DnsDyndnsCredentialModel.RECORD_ID.eq(recordId)).first();
    }

    /**
     * Arm (or re-key) a record's dyndns credential and return the plaintext token,
     * which the caller must disclose exactly once.
     */
    public static @NonNull String mintFor(int recordId) {
        String token = mintToken();
        DnsDyndnsCredentialModel credentials = Models.get(DnsDyndnsCredentialModel.class);
        Row credential = credentialFor(recordId);
        if (credential == null) {
            credential = credentials.createEmptyRow();
            credential.set(DnsDyndnsCredentialModel.RECORD_ID, recordId);
        }
        credential.set(DnsDyndnsCredentialModel.TOKEN_DIGEST, digest(token));
        credentials.save(credential);
        return token;
    }

    private static volatile boolean cascadeInstalled;

    /**
     * A credential dies WITH its record, whatever deletes it (resource, peer API,
     * zone-file import replace): otherwise a token would keep resolving to whatever
     * row id gets recycled. Installed AFTER TenantWrites so an unauthorized record
     * delete refuses before this ever runs; the cascade itself runs authorized (the
     * record delete already passed its own gate).
     */
    public static synchronized void installCredentialCascade() {
        if (cascadeInstalled) {
            return;
        }
        cascadeInstalled = true;
        DnsRecordModel.SCHEMA.addBeforeRemoveHook(context -> {
            Model model = context.getModel();
            if (model == null) {
                return;
            }
            QueryBuilder<Row> doomed = model.find();
            QueryContext queryContext = context.getQueryContext();
            if (queryContext != null && queryContext.getCriteria() != null) {
                doomed.where(queryContext.getCriteria());
            }
            for (Row record : doomed.all()) {
                Integer recordId = record.get(DnsRecordModel.ID);
                if (recordId != null) {
                    TenantWrites.inAuthorizedOperation(() -> revokeFor(recordId));
                }
            }
        });
    }

    /** @return true when a credential existed and was deleted (the record stops being dynamic) */
    public static boolean revokeFor(int recordId) {
        DnsDyndnsCredentialModel credentials = Models.get(DnsDyndnsCredentialModel.class);
        boolean revoked = false;
        Row credential;
        while ((credential = credentialFor(recordId)) != null) {
            credentials.delete(credential);
            revoked = true;
        }
        return revoked;
    }

    /**
     * Applies a dyndns2 update.
     *
     * @param presentedToken the token from HTTP Basic auth, the ONLY lane that carries one
     * @param hostname       the optional dyndns2 {@code hostname} param; when present it must match the record's FQDN
     * @param myip           the optional dyndns2 {@code myip} param; when absent the caller IP is used
     * @param callerIp       the trusted-proxy-resolved client IP
     */
    public @NonNull UpdateResult update(@Nullable String presentedToken, @Nullable String hostname,
                                        @Nullable String myip, @Nullable String callerIp) {
        Row record = authenticate(presentedToken);
        if (record == null) {
            return new UpdateResult(Status.BADAUTH, null);
        }

        DnsRecordModel model = Models.get(DnsRecordModel.class);
        Row zone = Models.get(DnsZoneModel.class).find()
            .where(DnsZoneModel.ID.eq(record.get(DnsRecordModel.ZONE_ID))).first();
        if (zone == null) {
            return new UpdateResult(Status.NOHOST, null);
        }
        // Only the owning primary may apply the write; a replica points at its owner.
        if (DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(zone))) {
            return new UpdateResult(Status.NOTPRIMARY, null);
        }

        String origin = zone.get(DnsZoneModel.ORIGIN);
        String fqdn = DnsNames.absolute(origin, record.get(DnsRecordModel.NAME));
        if (hostname != null && !hostname.isBlank()
                && !stripTrailingDot(hostname).equalsIgnoreCase(fqdn)) {
            return new UpdateResult(Status.NOHOST, null);
        }

        String type = record.get(DnsRecordModel.TYPE);
        // dyndns2 writes an ADDRESS, so only an address record can be dynamic. The mint
        // action's type predicate refuses arming anything else, but a credential row
        // could still point at a row whose type was edited afterwards; refusing HERE
        // turns the 500 the write pipeline's refusal would be into the protocol's own
        // dnserr answer.
        if (!DnsRecordModel.isAddressType(type)) {
            return new UpdateResult(Status.DNSERR, null);
        }
        String newIp = resolveIp(type, myip, callerIp);
        if (newIp == null) {
            // A caller behind a v4-only path can't set an AAAA (and vice versa); not an error the
            // client can fix by retrying, so report notfqdn/dnserr rather than looping.
            return new UpdateResult(Status.DNSERR, null);
        }

        String current = record.get(DnsRecordModel.VALUE);
        if (newIp.equals(current)) {
            return new UpdateResult(Status.NOCHG, newIp);
        }

        record.set(DnsRecordModel.VALUE, newIp);
        model.save(record);
        ActivityLog.record(model, record.get(DnsRecordModel.ID), "dyndns_update", newIp);
        this.store.bumpSerialAndReload(zone.get(DnsZoneModel.ID));
        Blast.log("DNS: dyndns update", fqdn, type, "->", newIp);
        return new UpdateResult(Status.GOOD, newIp);
    }

    /** @return the dynamic record the token unlocks, or null when it does not verify */
    public @Nullable Row authenticate(@Nullable String presented) {
        if (presented == null || !presented.startsWith(TOKEN_MARKER) || presented.length() < 16) {
            return null;
        }
        // The table holds the DIGEST, so hash the presented plaintext and look up
        // by that. token_digest carries an index (M091) because this runs on every
        // router poll.
        String digest = digest(presented);
        Row credential = Models.get(DnsDyndnsCredentialModel.class).find()
            .where(DnsDyndnsCredentialModel.TOKEN_DIGEST.eq(digest)).first();
        if (credential == null) {
            return null;
        }
        // The lookup above compares DIGESTS, which the caller already knows (it is a
        // pure function of what they presented), so its timing leaks nothing about the
        // secret. The decisive comparison is still made constant-time here so no future
        // change to the lookup can turn it into an oracle.
        if (!SecureTokens.constantTimeEquals(digest,
                credential.get(DnsDyndnsCredentialModel.TOKEN_DIGEST))) {
            return null;
        }
        return Models.get(DnsRecordModel.class)
            .findById(credential.get(DnsDyndnsCredentialModel.RECORD_ID));
    }

    /** Picks the address matching the record type from an explicit myip (possibly comma-separated) or the caller IP. */
    private static @Nullable String resolveIp(@NonNull String recordType, @Nullable String myip,
                                              @Nullable String callerIp) {
        boolean wantV6 = DnsRecordModel.TYPE_AAAA.equals(recordType);
        if (myip != null && !myip.isBlank()) {
            for (String candidate : myip.split(",")) {
                String match = matchFamily(candidate.trim(), wantV6);
                if (match != null) {
                    return match;
                }
            }
            return null; // an explicit myip that carries no address of the record's family is a hard error
        }
        return matchFamily(callerIp, wantV6);
    }

    /** @return the address in canonical form when it parses AND matches the wanted family, else null */
    private static @Nullable String matchFamily(@Nullable String ip, boolean wantV6) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        InetAddress parsed = parseNumeric(ip.trim());
        if (parsed == null) {
            return null;
        }
        boolean isV6 = parsed instanceof Inet6Address;
        if (isV6 != wantV6) {
            return null;
        }
        return parsed.getHostAddress();
    }

    /** Parses a numeric IP literal WITHOUT triggering a DNS lookup (guards against hostname inputs). */
    private static @Nullable InetAddress parseNumeric(@NonNull String ip) {
        if (ip.isEmpty() || (!ip.contains(":") && !isDottedQuad(ip))) {
            return null;
        }
        try {
            InetAddress parsed = InetAddress.getByName(ip);
            // getByName resolves hostnames; only accept literals we recognize as such.
            if (parsed instanceof Inet4Address && !isDottedQuad(ip)) {
                return null;
            }
            return parsed;
        }
        catch (Exception e) {
            return null;
        }
    }

    private static boolean isDottedQuad(@NonNull String ip) {
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    private static @NonNull String stripTrailingDot(@NonNull String host) {
        String trimmed = host.trim();
        return trimmed.endsWith(".") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
