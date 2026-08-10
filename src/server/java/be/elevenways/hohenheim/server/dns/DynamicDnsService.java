package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.security.SecretShapes;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
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
 * AIDEV-NOTE: {@code update} deliberately asks NO hostname-liveness question. A
 * per-update "the FQDN must be covered by a live site" predicate would break the
 * feature's primary use -- operator dyndns names (a home router) are not sites and
 * are covered by nothing. The token-outlives-the-claim problem is closed one tier
 * over instead: {@link DnsClaimReleases} clears {@code dynamic}/{@code dyndns_token}
 * (and disables the record) the moment a hostname's last covering domain row is
 * released, so a released name's token answers badauth here without this method
 * ever asking whose name it is.
 */
public final class DynamicDnsService {

    /** Wire token prefix, so a presented credential is recognizably ours. */
    public static final String TOKEN_MARKER = "hdyn_";

    /**
     * Marks a STORED value as a SHA-256 digest rather than a legacy plaintext
     * token. The token is a live HTTP-Basic password that drives DNS updates, so
     * only its digest is ever at rest (the {@code SiteApiKeys} shape). The client
     * still presents the same plaintext {@code hdyn_} token; the service hashes
     * what it is handed and looks up the record BY DIGEST, which is what lets an
     * existing token keep working after the stored value is hashed in place.
     */
    private static final String DIGEST_PREFIX = "sha256:";

    private static volatile boolean hookInstalled;

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
     * not misclassified as a digest and silently invalidated.
     */
    public static boolean isDigest(@Nullable String stored) {
        return SecretShapes.isSha256Digest(stored, DIGEST_PREFIX);
    }

    /**
     * Register the write-time normalization hook so no plaintext dyndns token ever
     * reaches the datasource, whichever path writes the record (mint action, admin
     * form, clone). Idempotent, and runs BEFORE validation so revisions and activity
     * deltas see the digest too.
     */
    public static synchronized void installTokenHashing() {
        if (hookInstalled) {
            return;
        }
        hookInstalled = true;
        DnsRecordModel.SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row == null || !row.has(DnsRecordModel.DYNDNS_TOKEN.getName())) {
                return;
            }
            Object stored = row.get(DnsRecordModel.DYNDNS_TOKEN.getName());
            if (stored == null) {
                return;
            }
            String value = String.valueOf(stored);
            if (value.isBlank() || isDigest(value)) {
                return;
            }
            // Adoption entropy floor: a weak operator-typed token is dictionary-
            // recoverable from its fast SHA-256 digest, and a token without the
            // hdyn_ marker could never authenticate anyway (authenticate() requires
            // it), so both are refused instead of silently stored. Legacy stored
            // plaintext is unaffected: the seeder hashes it BEFORE save, so this
            // hook only sees digests for grandfathered values.
            String plaintext = value.trim();
            if (!plaintext.startsWith(TOKEN_MARKER) || !SecretShapes.isAdoptablePlaintext(plaintext)) {
                // Never echo the credential back; the violation value stays blank.
                throw Violations.ofField("dyndns_token", "",
                    Microcopy.of("weak_dyndns_token")
                        .withFilter("scope", "violations")
                        .withArg("marker", TOKEN_MARKER));
            }
            row.set(DnsRecordModel.DYNDNS_TOKEN, digest(plaintext));
        });
    }

    /**
     * Rewrite every stored plaintext dyndns token to its digest, in place. NO TOKEN
     * IS INVALIDATED: the plaintext its client presents still hashes to the stored
     * digest. Idempotent, so it is safe to run again at any time.
     *
     * @return the number of records rewritten
     */
    public static int hashStoredTokens() {
        DnsRecordModel model = Models.get(DnsRecordModel.class);
        int rewritten = 0;
        for (Row record : model.find().where(DnsRecordModel.DYNDNS_TOKEN.isNotNull()).all()) {
            String stored = record.get(DnsRecordModel.DYNDNS_TOKEN);
            if (stored == null || stored.isBlank() || isDigest(stored)) {
                continue;
            }
            record.set(DnsRecordModel.DYNDNS_TOKEN, digest(stored));
            model.save(record);
            rewritten++;
        }
        return rewritten;
    }

    /**
     * Applies a dyndns2 update.
     *
     * @param presentedToken the token from HTTP Basic auth (or the token query param)
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
        // dyndns2 writes an ADDRESS, so only an address record can be dynamic. Without this
        // an operator who flagged, say, an MX row dynamic would have had its VALUE rewritten
        // to an IP by an anonymous caller; the write pipeline refuses that now (a dyndns
        // update is a tenant-originated write and MX is outside the tenant type allow-list),
        // and refusing it HERE turns the 500 that refusal would be into the protocol's own
        // dnserr answer.
        if (!DnsRecordModel.TYPE_A.equals(type) && !DnsRecordModel.TYPE_AAAA.equals(type)) {
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
        // The column holds the DIGEST, so hash the presented plaintext and look up
        // by that. An existing client keeps working because it still presents the
        // same plaintext, which hashes to the stored digest.
        // AIDEV-NOTE: dyndns_token carries an index (M046_DyndnsTokenIndex) because this
        // runs on every router poll; it used to be a full scan of dns_records.
        String digest = digest(presented);
        Row record = Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.DYNDNS_TOKEN.eq(digest)).first();
        if (record == null || !Boolean.TRUE.equals(record.get(DnsRecordModel.DYNAMIC))) {
            return null;
        }
        // The lookup above compares DIGESTS, which the caller already knows (it is a
        // pure function of what they presented), so its timing leaks nothing about the
        // secret. The decisive comparison is still made constant-time here so no future
        // change to the lookup can turn it into an oracle.
        if (!SecureTokens.constantTimeEquals(digest, record.get(DnsRecordModel.DYNDNS_TOKEN))) {
            return null;
        }
        return record;
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
