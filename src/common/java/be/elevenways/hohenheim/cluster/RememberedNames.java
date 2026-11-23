package be.elevenways.hohenheim.cluster;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.zenit.common.routing.RouteTarget;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * One page of the names a master remembers of one kind of a linked server, as the page listing them draws it.
 *
 * @param heading what the page lists ("Sites Robbedoes remembers of Kuifje"), in the reader's language
 * @param names   this page's records, by key
 * @param next    the next page, null on the last one
 * @param back    the linked server's page
 * @author Jelle De Loecker
 * @since  0.10.0
 */
@HawkeyeClass
public record RememberedNames(@NonNull String heading, @NonNull List<RememberedName> names,
                              @Nullable RouteTarget next, @NonNull RouteTarget back) {

    public RememberedNames {
        names = List.copyOf(names);
    }
}
