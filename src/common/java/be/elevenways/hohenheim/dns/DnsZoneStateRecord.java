package be.elevenways.hohenheim.dns;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One record row of a zone's state as stored, beside what the record wire leaves out.
 *
 * @param generated_by  which part of Hohenheim made the row, null for a person's
 * @param dyndns_digest the digest of the row's dynamic DNS token, never the token; null for a row without one
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
@HawkeyeClass
public record DnsZoneStateRecord(DnsRecordDto record, @Nullable String generated_by,
                                 @Nullable String dyndns_digest) {
}
