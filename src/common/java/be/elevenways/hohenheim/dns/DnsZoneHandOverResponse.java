package be.elevenways.hohenheim.dns;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;

/**
 * The peer API answer of a hand-over: the zone is now a copy pulling from the caller, serving this serial.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
@HawkeyeClass
public record DnsZoneHandOverResponse(String status, int serial) {
}
