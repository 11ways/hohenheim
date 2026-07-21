package be.elevenways.hohenheim.dns;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;

/** The public peer API response for a created or updated DNS record. */
@HawkeyeClass
public record DnsRecordMutationResponse(Integer id) {
}
