package be.elevenways.hohenheim.dns;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One peer keeping a copy of a zone, as the server keeping its records links it.
 *
 * @param key_name      the transfer key the two share, null when they share none
 * @param transfer_host where that server transfers from the peer, null when it knows no address
 * @param calls_back    whether that server holds an API key of the peer, so it can send record changes there
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
@HawkeyeClass
public record DnsZoneStateSecondary(String name, @Nullable String key_name, @Nullable String transfer_host,
                                    boolean calls_back) {
}
