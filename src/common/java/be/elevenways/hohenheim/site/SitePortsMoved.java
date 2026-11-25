package be.elevenways.hohenheim.site;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * What moving one TCP/UDP stream's public ports into another did: the receipt the move's command stores.
 *
 * @param from  the emptied site's id, now in the trash
 * @param into  the id of the site now forwarding the ports
 * @param ports the moved ports as a list names them ({@code 465, 53/udp})
 * @author Jelle De Loecker
 * @since  0.10.0
 */
@HawkeyeClass
public record SitePortsMoved(int from, int into, @NonNull List<String> ports) {
}
