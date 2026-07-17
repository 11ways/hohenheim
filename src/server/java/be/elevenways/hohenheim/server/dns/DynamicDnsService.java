package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
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
 */
public final class DynamicDnsService {

    /** Wire token prefix, so a presented credential is recognizably ours. */
    public static final String TOKEN_MARKER = "hdyn_";

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

    /** @return a fresh update token to store on a record's {@code dyndns_token} column */
    public static @NonNull String mintToken() {
        return TOKEN_MARKER + SecureTokens.randomToken();
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
        Row record = Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.DYNDNS_TOKEN.eq(presented)).first();
        if (record == null || !Boolean.TRUE.equals(record.get(DnsRecordModel.DYNAMIC))) {
            return null;
        }
        // Re-check under constant time; the indexed lookup above is the fast path.
        if (!SecureTokens.constantTimeEquals(presented, record.get(DnsRecordModel.DYNDNS_TOKEN))) {
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
