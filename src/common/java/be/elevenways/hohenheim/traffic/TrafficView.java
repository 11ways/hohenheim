package be.elevenways.hohenheim.traffic;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.plumage.chart.ChartSeries;
import be.elevenways.plumage.chart.ChartType;
import be.elevenways.plumage.component.NavTab;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * One traffic screen as it is drawn (a site's Traffic tab, the server's Traffic report, a DNS zone's Queries tab): the
 * period switch, the headline figures against the span before, the charts and the top tables, every word in the
 * reader's language.
 *
 * AIDEV-NOTE: built server-side from the read its tool makes too (TrafficReads), by TrafficScreens; the three screens
 * share this shape and one partial (cms/traffic-body), so they differ only by what their read lists.
 *
 * @param periods     the spans the switch offers, the shown one active, each a link
 * @param span        the span shown, from its first moment to its end
 * @param previousUrl the span just before, as long
 * @param nextUrl     the span just after, null while the shown one ends now
 * @param figures     the headline figures
 * @param charts      the charts, each across the screen
 * @param tables      the top tables, two across
 * @param empty       what the screen says while the span counted nothing, null while it counted something
 * @author Jelle De Loecker
 * @since  0.1.0
 */
@HawkeyeClass
public record TrafficView(@NonNull List<NavTab> periods, @NonNull Microcopy span, @NonNull String previousUrl,
                          @Nullable String nextUrl, @NonNull List<Figure> figures, @NonNull List<Chart> charts,
                          @NonNull List<Table> tables, @Nullable Microcopy empty) {

    /**
     * One headline figure.
     *
     * @param key         stable on the screen, for tests and styling
     * @param trend       its change against the span before ("+42%"), null when it shows none
     * @param up          whether that change is a rise
     * @param description the line under it, null for none
     */
    @HawkeyeClass
    public record Figure(@NonNull String key, @NonNull Microcopy label, @NonNull Microcopy value,
                         @Nullable Microcopy trend, boolean up, @Nullable Microcopy description) {
    }

    /**
     * One chart.
     *
     * @param key        stable on the screen
     * @param lead       the sentence under its title, null for none
     * @param stacked    whether its series stack (requests per status class)
     * @param horizontal whether its bars lie down (a ranking of names)
     * @param points     whether a line marks each step it has, so a span answered in one step still shows
     */
    @HawkeyeClass
    public record Chart(@NonNull String key, @NonNull Microcopy title, @Nullable Microcopy lead,
                        @NonNull ChartType type, boolean stacked, boolean horizontal,
                        boolean points, @NonNull List<ChartSeries> series) {
    }

    /**
     * One top table.
     *
     * @param key     stable on the screen
     * @param lead    the sentence under its title, null for none
     * @param columns its heads, in order
     * @param rows    its rows, the busiest first, the last holding every one not listed when there is one
     * @param empty   what it says while it holds no row
     */
    @HawkeyeClass
    public record Table(@NonNull String key, @NonNull Microcopy title, @Nullable Microcopy lead,
                        @NonNull List<Column> columns, @NonNull List<Line> rows, @NonNull Microcopy empty) {
    }

    /**
     * One head of a table.
     *
     * @param figure whether its cells are figures, right-aligned and as wide as their widest
     * @param share  whether its cells are each row's share of the span's requests, drawn as a bar
     */
    @HawkeyeClass
    public record Column(@NonNull Microcopy label, boolean figure, boolean share) {
    }

    /**
     * One row of a table.
     *
     * @param key   unique in its table
     * @param cells one per column
     */
    @HawkeyeClass
    public record Line(@NonNull String key, @NonNull List<Cell> cells) {
    }

    /**
     * One cell.
     *
     * @param text    its words; for a share cell, what the bar says to a reader who cannot see it
     * @param mono    whether its words are a path, a name or an address, drawn in the monospace face
     * @param figure  whether it is a figure, right-aligned
     * @param share   for a share cell, the row's part of {@code shareOf}; null for any other cell
     * @param shareOf what a share cell's part is out of
     * @param href    where the cell leads (a site's Traffic tab, a path's access log lines), null for nowhere
     */
    @HawkeyeClass
    public record Cell(@NonNull Microcopy text, boolean mono, boolean figure, @Nullable Integer share, int shareOf,
                       @Nullable String href) {
    }
}
