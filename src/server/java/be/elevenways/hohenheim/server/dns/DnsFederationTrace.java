package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsZonePeerModel;
import be.elevenways.hohenheim.server.HohenheimSystemPurposes;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.security.ExecutionIdentity;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.xbill.DNS.Name;

import be.elevenways.zenit.common.orm.query.QueryBuilder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The primary's own trace of what it did for each secondary: one structured log line per
 * served or refused AXFR and per NOTIFY sent, and the last of each stamped on the
 * {@code dns_zone_peers} link so a lagging secondary is diagnosable from this side.
 *
 * AIDEV-NOTE: before this, every transfer trace lived on the SECONDARY only; the first real
 * starfleet-to-OVH federation run showed a primary that journaled nothing for a transfer it
 * had just streamed. A served AXFR is attributed to the peer by the TSIG key name the
 * request carried, which is the only identity an AXFR request has.
 */
public final class DnsFederationTrace {

    private DnsFederationTrace() {}

    /** A zone was streamed to the holder of {@code keyName}. */
    public static void axfrServed(@NonNull DnsZoneSnapshot zone, @NonNull Name keyName) {
        String key = plain(keyName);
        Blast.slog("dns.axfr_served", fields(zone.getOriginString(), zone.getSerial(), key,
            "outcome", "ok"));
        Row link = linkFor(zone.getZoneId(), key);
        if (link != null) {
            stamp(link, columns -> columns.assign(DnsZonePeerModel.LAST_AXFR_AT, Now.instant())
                .assign(DnsZonePeerModel.LAST_AXFR_SERIAL, (int) zone.getSerial()));
        }
    }

    /** An AXFR request for a hosted zone was refused. */
    public static void axfrRefused(@NonNull DnsZoneSnapshot zone, @Nullable Name keyName,
                                   @NonNull String reason) {
        Blast.slog("dns.axfr_refused", fields(zone.getOriginString(), zone.getSerial(),
            keyName != null ? plain(keyName) : "", "reason", reason));
    }

    /** A NOTIFY for the zone went to the peer behind {@code link}; {@code outcome} is what came back. */
    public static void notifySent(@NonNull Row link, @NonNull Row peer, @NonNull String origin,
                                  long serial, @NonNull String outcome) {
        Blast.slog("dns.notify_sent", fields(origin, serial,
            String.valueOf(peer.get(DnsPeerModel.NAME)), "outcome", outcome));
        stamp(link, columns -> columns.assign(DnsZonePeerModel.LAST_NOTIFY_AT, Now.instant())
            .assign(DnsZonePeerModel.LAST_NOTIFY_SERIAL, (int) serial)
            .assign(DnsZonePeerModel.LAST_NOTIFY_OUTCOME,
                outcome.length() > 255 ? outcome.substring(0, 255) : outcome));
    }

    /**
     * Writes a trace stamp's own columns on the link, as the system's DNS federation work, where the link still is.
     *
     * AIDEV-NOTE: a trace stamp is bookkeeping, like {@link DnsSecondaryFreshness}'s probe: DnsZonePeerModel captures
     * none of its fields, so the write records nothing. The AXFR responder runs on the DNS server's own thread with no
     * identity, which is why the write runs under its own purpose. The structured log line above is the trace. It
     * assigns the stamp's columns by the link's id and never saves the row: a NOTIFY reads its links, then waits up to
     * five seconds for an ack, and a whole-row save of what it read put back a link removed or changed meanwhile (a
     * FOREIGN KEY failure on the dns-notify thread, and a take-over finding its owner no longer listing it).
     */
    private static void stamp(@NonNull Row link, @NonNull UnaryOperator<QueryBuilder<Row>> columns) {
        Integer id = link.get(DnsZonePeerModel.ID);
        ExecutionIdentity.runAsSystem(HohenheimSystemPurposes.DNS_FEDERATION, () -> columns.apply(
            Models.get(DnsZonePeerModel.class).find().where(DnsZonePeerModel.ID.eq(id))).updateAll());
    }

    /** @return the zone's link to the enabled peer holding the TSIG key, or null */
    private static @Nullable Row linkFor(int zoneId, @NonNull String keyName) {
        for (DnsZonePeers.Linked linked : DnsZonePeers.linked(zoneId)) {
            String peerKey = linked.peer().get(DnsPeerModel.TSIG_KEY_NAME);
            if (peerKey != null && plain(DnsTsig.canonicalKeyName(peerKey)).equals(keyName)) {
                return linked.link();
            }
        }
        return null;
    }

    private static @NonNull Map<String, Object> fields(@NonNull String zone, long serial,
                                                       @NonNull String peer, @NonNull String key,
                                                       @NonNull String value) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("zone", zone);
        fields.put("serial", serial);
        fields.put("peer", peer);
        fields.put(key, value);
        return fields;
    }

    private static @NonNull String plain(@NonNull Name name) {
        return name.toString(true).toLowerCase(Locale.ROOT);
    }
}
