package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsZonePeerModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.xbill.DNS.Name;

import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

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
            link.set(DnsZonePeerModel.LAST_AXFR_AT, Instant.now());
            link.set(DnsZonePeerModel.LAST_AXFR_SERIAL, (int) zone.getSerial());
            Models.get(DnsZonePeerModel.class).save(link);
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
        link.set(DnsZonePeerModel.LAST_NOTIFY_AT, Instant.now());
        link.set(DnsZonePeerModel.LAST_NOTIFY_OUTCOME,
            outcome.length() > 255 ? outcome.substring(0, 255) : outcome);
        Models.get(DnsZonePeerModel.class).save(link);
    }

    /** @return the zone's link to the enabled peer holding the TSIG key, or null */
    private static @Nullable Row linkFor(int zoneId, @NonNull String keyName) {
        DnsPeerModel peerModel = Models.get(DnsPeerModel.class);
        for (Row link : Models.get(DnsZonePeerModel.class).findByZoneId(zoneId)) {
            Integer peerId = link.get(DnsZonePeerModel.PEER_ID);
            Row peer = peerId != null ? peerModel.findById(peerId) : null;
            String peerKey = peer != null ? peer.get(DnsPeerModel.TSIG_KEY_NAME) : null;
            if (peerKey != null && plain(DnsTsig.canonicalKeyName(peerKey)).equals(keyName)) {
                return link;
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
