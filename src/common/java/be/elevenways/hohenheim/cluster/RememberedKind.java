package be.elevenways.hohenheim.cluster;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.zenit.common.routing.RouteTarget;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * One kind a master keeps a record of for a linked server, as that server's page lists it.
 *
 * @param kind   the kind's noun, plural, in the reader's language
 * @param count  how many records of it the master keeps
 * @param target the page listing their names
 * @author Jelle De Loecker
 * @since  0.10.0
 */
@HawkeyeClass
public record RememberedKind(@NonNull String kind, long count, @NonNull RouteTarget target) {
}
