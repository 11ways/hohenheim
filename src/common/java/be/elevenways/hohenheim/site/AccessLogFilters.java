package be.elevenways.hohenheim.site;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * The Access log tab's filter row as the reader left it: what each filter holds, the choices they offer and where the
 * tab's own address and its download lead.
 *
 * @param listUrl     the tab without filters, which the filter form submits to
 * @param downloadUrl the tab's filtered lines as a file
 * @param status      the status class token, "" for every status
 * @param path        the text the path holds, "" for every path
 * @param when        the time choice's token
 * @param from        the range's start as the date-time control holds it, "" when the choice is no range
 * @param to          the range's end as the date-time control holds it, "" when the choice is no range
 * @param visitor     the visitor address, "" for every visitor
 * @param following   whether the tab follows new lines when it opens: on its newest page only
 * @param statuses    the status classes; none chosen reads every status
 * @param times       the time choices
 * @param keeping     how long lines are kept, in the reader's words
 * @author Jelle De Loecker
 * @since  0.1.0
 */
@HawkeyeClass
public record AccessLogFilters(@NonNull String listUrl, @NonNull String downloadUrl, @NonNull String status,
                               @NonNull String path, @NonNull String when, @NonNull String from, @NonNull String to,
                               @NonNull String visitor, boolean following, @NonNull List<Choice> statuses,
                               @NonNull List<Choice> times, @NonNull Microcopy keeping) {

    /**
     * One choice of a filter's select.
     *
     * @param value the value the form submits
     * @param label its words
     */
    @HawkeyeClass
    public record Choice(@NonNull String value, @NonNull Microcopy label) {
    }
}
