package be.elevenways.hohenheim.dns;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;

/**
 * What setting one name and type of a zone did: the counts the record-set answer says, on this server or as the
 * server keeping the zone's records answered them over the peer API.
 *
 * @param dropped true when the entry asked its name and type be removed; {@code removed} then counts the rows gone
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
@HawkeyeClass
public record DnsRecordSetOutcome(String name, String type, int added, int changed, int kept, int removed,
                                  boolean dropped) {
}
