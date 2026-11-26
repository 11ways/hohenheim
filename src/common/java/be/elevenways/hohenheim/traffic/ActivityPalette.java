package be.elevenways.hohenheim.traffic;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.traffic.common.StatusClass;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * THE colour of each status class in the live traffic pictures: the legend's swatches and the picture's dots and bars
 * read this one switch, so a class always wears the same theme colour and is always named in words beside it.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class ActivityPalette {

    /** The classes in the order the legend names them: the answered first. */
    public static final @NonNull List<StatusClass> LEGEND = List.of(StatusClass.SUCCESS, StatusClass.REDIRECTION,
        StatusClass.CLIENT_ERROR, StatusClass.SERVER_ERROR, StatusClass.INFORMATIONAL, StatusClass.OTHER);

    /**
     * One legend entry: a class's words and its colour.
     *
     * @param colour the theme colour, as CSS
     */
    @HawkeyeClass
    public record Entry(@NonNull Microcopy label, @NonNull String colour) {

        public Entry {
            Objects.requireNonNull(label, "label cannot be null");
            Objects.requireNonNull(colour, "colour cannot be null");
        }
    }

    private ActivityPalette() {
    }

    /** @return the theme colour the class is drawn in, as CSS a picture resolves once per theme */
    public static @NonNull String colour(@NonNull StatusClass statusClass) {
        return switch (statusClass) {
            case SUCCESS -> "var(--pl-color-success)";
            case REDIRECTION -> "var(--pl-color-info)";
            case CLIENT_ERROR -> "var(--pl-color-warning)";
            case SERVER_ERROR -> "var(--pl-color-destructive)";
            case INFORMATIONAL -> "var(--pl-color-primary)";
            case OTHER -> "var(--pl-color-text-muted)";
        };
    }

    /** @return the legend: every class's words and colour, in {@link #LEGEND}'s order */
    public static @NonNull List<Entry> legend() {
        List<Entry> entries = new ArrayList<>(LEGEND.size());
        for (StatusClass statusClass : LEGEND) {
            entries.add(new Entry(statusClass.label(), colour(statusClass)));
        }
        return entries;
    }
}
