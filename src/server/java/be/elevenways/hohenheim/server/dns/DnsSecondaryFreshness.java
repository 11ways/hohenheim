package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.DnsZonePeerModel;
import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.xbill.DNS.Name;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * What each linked secondary actually SERVES for a primary zone, probed from this primary
 * with a real SOA query over the peer's transfer channel and persisted on the link row.
 *
 * AIDEV-NOTE: the transfer bookkeeping on a SECONDARY zone row records what that secondary
 * believes; this is the primary's own view, which is the only one that can notice a
 * secondary that silently stopped pulling. A peer is BEHIND from the first probe that finds
 * it silent or on an older serial; the attention item and the alert wait for
 * {@link #STALE_AFTER} so a NOTIFY still in flight never pages anyone, and the alert fires
 * exactly once per lag (the stamp clears when the peer catches up).
 */
public final class DnsSecondaryFreshness {

    /**
     * How long a secondary may lag before it is stale. A constant, not a setting: a
     * refresh poll or a NOTIFY closes a real lag within seconds, so a window an operator
     * could widen would only hide a broken secondary instead of fixing it.
     */
    public static final Duration STALE_AFTER = Duration.ofMinutes(15);

    private DnsSecondaryFreshness() {}

    /** One probed link: the peer, what it served, and whether that is current. */
    public record Outcome(@NonNull Row link, @NonNull Row peer, @Nullable Long servedSerial,
                          @Nullable String error, boolean current) {}

    /** Probes every linked secondary of every enabled primary zone. */
    public static void probeAll() {
        for (Row zone : Models.get(DnsZoneModel.class).findEnabled()) {
            if (DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(zone))) {
                continue;
            }
            probeZone(zone);
        }
    }

    /** Probes every linked secondary of one primary zone and persists the outcome per link. */
    public static @NonNull List<Outcome> probeZone(@NonNull Row zone) {
        List<Outcome> outcomes = new ArrayList<>();
        Integer zoneId = zone.get(DnsZoneModel.ID);
        String originString = zone.get(DnsZoneModel.ORIGIN);
        if (zoneId == null || originString == null) {
            return outcomes;
        }
        Name origin;
        try {
            origin = Name.fromString(originString + ".");
        }
        catch (Exception e) {
            return outcomes;
        }
        long ourSerial = valueOr(zone.get(DnsZoneModel.SERIAL), 0);
        DnsPeerModel peerModel = Models.get(DnsPeerModel.class);
        for (Row link : Models.get(DnsZonePeerModel.class).findByZoneId(zoneId)) {
            Integer peerId = link.get(DnsZonePeerModel.PEER_ID);
            Row peer = peerId != null ? peerModel.findById(peerId) : null;
            if (peer == null || !Boolean.TRUE.equals(peer.get(DnsPeerModel.ENABLED))) {
                continue;
            }
            outcomes.add(probeLink(originString, origin, ourSerial, link, peer));
        }
        return outcomes;
    }

    private static @NonNull Outcome probeLink(@NonNull String originString, @NonNull Name origin,
                                              long ourSerial, @NonNull Row link, @NonNull Row peer) {
        String host = peer.get(DnsPeerModel.TRANSFER_HOST);
        int port = valueOr(peer.get(DnsPeerModel.TRANSFER_PORT), 53);
        Long served = null;
        String error;
        if (host == null || host.isBlank()) {
            error = "peer has no transfer host";
        }
        else {
            served = DnsSoaProbe.serial(host.trim(), port, origin);
            error = served == null ? "no authoritative SOA answer from " + host.trim() + ":" + port : null;
        }
        boolean current = served != null && DnsSoaProbe.serialReached(served, ourSerial);

        Instant now = Instant.now();
        link.set(DnsZonePeerModel.PROBED_AT, now);
        link.set(DnsZonePeerModel.SERVED_SERIAL, served != null ? (int) (long) served : null);
        link.set(DnsZonePeerModel.PROBE_ERROR, error);
        if (current) {
            link.set(DnsZonePeerModel.BEHIND_SINCE, null);
            link.set(DnsZonePeerModel.STALE_ALERTED_AT, null);
        }
        else {
            Instant behindSince = link.get(DnsZonePeerModel.BEHIND_SINCE);
            if (behindSince == null) {
                behindSince = now;
                link.set(DnsZonePeerModel.BEHIND_SINCE, now);
            }
            if (isStale(behindSince, now) && link.get(DnsZonePeerModel.STALE_ALERTED_AT) == null) {
                link.set(DnsZonePeerModel.STALE_ALERTED_AT, now);
                String peerName = String.valueOf(peer.get(DnsPeerModel.NAME));
                Alerts.send(NotificationEvents.DNS_SECONDARY_STALE,
                    "Secondary '" + peerName + "' of zone " + originString + " is stale",
                    error != null ? error
                        : "serves serial " + served + " while the primary serves " + ourSerial);
                Blast.slog("dns.secondary_stale", java.util.Map.of(
                    "zone", originString, "peer", peerName,
                    "served", served != null ? served : -1, "primary", ourSerial));
            }
        }
        Models.get(DnsZonePeerModel.class).save(link);
        return new Outcome(link, peer, served, error, current);
    }

    /** @return true when a link has been behind or silent for longer than {@link #STALE_AFTER} */
    public static boolean isStale(@Nullable Instant behindSince, @NonNull Instant now) {
        return behindSince != null && behindSince.plus(STALE_AFTER).isBefore(now);
    }

    /** @return true when the link row records a lag that has outlived the window */
    public static boolean isStale(@NonNull Row link) {
        return isStale(link.get(DnsZonePeerModel.BEHIND_SINCE), Instant.now());
    }

    private static int valueOr(@Nullable Integer value, int fallback) {
        return value != null ? value : fallback;
    }
}
