package be.elevenways.hohenheim.site;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.plumage.component.Pager;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.ui.BadgeVariant;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * A site's Access log tab as its live part: one page of the lines the filters ask for, newest first, and what the
 * read could say about the rest.
 *
 * AIDEV-NOTE: ONE shape for the page render and the live re-read, so a new line of the site redraws this part in
 * place while the filters around it stay as the reader set them.
 *
 * @param lines   the page's lines
 * @param summary which lines the page shows of how many, in the reader's words
 * @param stopped why an incomplete read stopped and how far back it came, null when it read the whole range
 * @param pager   the pages, null when one page holds every line
 * @author Jelle De Loecker
 * @since  0.1.0
 */
@HawkeyeClass
public record AccessLogView(@NonNull List<Line> lines, @NonNull Microcopy summary, @Nullable Microcopy stopped,
                            @Nullable Pager pager) {

    /**
     * One line as the tab's nine columns show it.
     *
     * @param key       unique on the page: the line's position among every match
     * @param time      when it was answered, in the reader's words
     * @param visitor   the visitor's address
     * @param method    the request method
     * @param path      the path the visitor asked for, with its query
     * @param status    the status answered
     * @param variant   the status badge's variant, null for a status shown plainly
     * @param size      the bytes sent, in the reader's words
     * @param took      how long it took, null on a line from before that was logged
     * @param referrer  the host the visitor came from, as the referrers table counts it; "" when none was sent
     * @param browser   the browser and system the user agent names, in the reader's words
     * @param userAgent the user agent as sent, "" when none was sent
     */
    @HawkeyeClass
    public record Line(@NonNull String key, @NonNull Microcopy time, @NonNull String visitor, @NonNull String method,
                       @NonNull String path, int status, @Nullable BadgeVariant variant, @NonNull Microcopy size,
                       @Nullable Microcopy took, @NonNull String referrer, @NonNull Microcopy browser,
                       @NonNull String userAgent) {
    }
}
