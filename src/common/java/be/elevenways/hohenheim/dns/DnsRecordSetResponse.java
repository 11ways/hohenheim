package be.elevenways.hohenheim.dns;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;

import java.util.List;

/**
 * The peer API response to a whole record set applied on the server keeping a zone's records: one outcome per entry,
 * in the order sent.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
@HawkeyeClass
public record DnsRecordSetResponse(String zone, List<DnsRecordSetOutcome> entries) {
}
