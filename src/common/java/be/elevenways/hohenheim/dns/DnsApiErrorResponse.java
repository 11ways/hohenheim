package be.elevenways.hohenheim.dns;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;

/** A non-validation refusal from the public DNS peer API. */
@HawkeyeClass
public record DnsApiErrorResponse(String error) {
}
