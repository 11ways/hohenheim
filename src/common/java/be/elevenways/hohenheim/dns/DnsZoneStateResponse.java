package be.elevenways.hohenheim.dns;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * The peer API answer of a zone's whole editable state, as the server keeping it stores it: what a server holding its
 * copy needs to take the zone over.
 *
 * @param role          the zone's role there; a secondary answers no records
 * @param primary_key   the transfer key name of the peer a secondary pulls from, null for a primary
 * @param can_hand_over true from every Hohenheim that knows the move; an older one has no such route at all
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
@HawkeyeClass
public record DnsZoneStateResponse(String zone, String role, @Nullable String primary_key, int serial,
                                   boolean dnssec, boolean can_hand_over, @Nullable String soa_primary_ns,
                                   @Nullable String soa_contact, @Nullable Integer default_ttl,
                                   @Nullable Integer negative_ttl, @Nullable Integer soa_refresh,
                                   @Nullable Integer soa_retry, @Nullable Integer soa_expire,
                                   List<DnsZoneStateRecord> records, List<DnsZoneStateSecondary> secondaries) {
}
