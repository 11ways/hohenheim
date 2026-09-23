package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsZonePeerModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * THE walk from a hosted zone to the peers linked to it (its NOTIFY targets and AXFR
 * authorizations).
 *
 * AIDEV-NOTE: five hand-rolled copies of this walk (AXFR authorization, NOTIFY, the
 * federation trace, the secondary freshness probe and the ACME propagation wait) each
 * re-spelled the link -> peer id -> peer row -> enabled chain, and they did not agree on
 * whether a disabled peer counts. {@link #enabled} is the answer every operational lane
 * uses; {@link #linked} keeps disabled peers for the one reader that must still see them.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class DnsZonePeers {

    private DnsZonePeers() {
    }

    /**
     * One zone-peer link with the peer it names.
     *
     * @param link the {@code dns_zone_peers} row
     * @param peer the {@code dns_peers} row it points at
     */
    public record Linked(@NonNull Row link, @NonNull Row peer) {
    }

    /** Every link of the zone whose peer still exists, enabled or not. */
    public static @NonNull List<Linked> linked(int zoneId) {
        DnsPeerModel peers = Models.get(DnsPeerModel.class);
        List<Linked> linked = new ArrayList<>();
        for (Row link : Models.get(DnsZonePeerModel.class).findByZoneId(zoneId)) {
            Integer peerId = link.get(DnsZonePeerModel.PEER_ID);
            Row peer = peerId != null ? peers.findById(peerId) : null;
            if (peer != null) {
                linked.add(new Linked(link, peer));
            }
        }
        return linked;
    }

    /** Every link of the zone whose peer exists AND is enabled: the operational set. */
    public static @NonNull List<Linked> enabled(int zoneId) {
        List<Linked> enabled = new ArrayList<>();
        for (Linked linked : linked(zoneId)) {
            if (Boolean.TRUE.equals(linked.peer().get(DnsPeerModel.ENABLED))) {
                enabled.add(linked);
            }
        }
        return enabled;
    }
}
