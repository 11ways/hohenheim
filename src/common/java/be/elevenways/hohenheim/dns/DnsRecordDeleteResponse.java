package be.elevenways.hohenheim.dns;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;

/** The public peer API response for a deleted DNS record. */
@HawkeyeClass
public record DnsRecordDeleteResponse(String status) {
}
