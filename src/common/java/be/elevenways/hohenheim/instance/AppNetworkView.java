package be.elevenways.hohenheim.instance;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.plumage.chart.ChartSeries;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * An app's Network tab: what it received and sent over a span, per step, and what its sites answered visitors.
 *
 * @param spans      the span choices, each a link to the tab reading it
 * @param range      the span read, in the reader's words
 * @param received   what the app received over the span
 * @param sent       what it sent
 * @param toVisitors what its sites answered visitors through the proxy over the span
 * @param chart      received and sent per step, in {@code unit}
 * @param chartLead  how the chart counts, in the reader's words
 * @param counted    whether anything was counted over the span; false draws the empty state instead of the chart
 * @param sites      the app's sites with what each brought, busiest first
 * @author Jelle De Loecker
 * @since  0.1.0
 */
@HawkeyeClass
public record AppNetworkView(@NonNull List<Span> spans, @NonNull Microcopy range, @NonNull String received,
                             @NonNull String sent, @NonNull String toVisitors, @NonNull List<ChartSeries> chart,
                             @NonNull Microcopy chartLead, boolean counted, @NonNull List<Site> sites) {

    /**
     * One span choice.
     *
     * @param key     the period's token, what the tool's {@code period} spells
     * @param href    the tab reading this span
     * @param current whether the tab reads it now
     */
    @HawkeyeClass
    public record Span(@NonNull String key, @NonNull Microcopy label, @NonNull String href, boolean current) {
    }

    /**
     * One site of the app, as the incoming side lists it.
     *
     * @param key      the site's id, unique in the list
     * @param requests the requests it answered over the span
     * @param sent     what it answered visitors
     */
    @HawkeyeClass
    public record Site(@NonNull String key, @NonNull String name, @NonNull Microcopy requests,
                       @NonNull Microcopy sent) {
    }
}
