package be.elevenways.hohenheim.test;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.regex.Pattern;

/**
 * THE in-order text check of the browser-test suite: a pattern that finds its parts in a text one after another.
 *
 * AIDEV-NOTE: AssertJ 3.27.3's {@code containsSubsequence} on a CharSequence locates each part by calling
 * {@code substring(i).startsWith(part)} at every position, which copies the rest of the text once per character: on
 * a rendered admin page (some 300 KB) one such assertion burned 12 seconds of CPU (AppOverviewJourneyTest and
 * DashboardAttentionJourneyTest, 2026-10-18). Assert a page body with {@code containsPattern(InOrder.of(...))}
 * instead; a short text (a trail, a tab strip) may keep {@code containsSubsequence}.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class InOrder {

    private InOrder() {
    }

    /**
     * @return a pattern found where every part occurs literally, each after the end of the one before it, exactly
     *         what {@code containsSubsequence} accepts
     * @throws IllegalArgumentException when no part is given
     */
    public static @NonNull Pattern of(@NonNull String @NonNull ... parts) {
        if (parts.length == 0) {
            throw new IllegalArgumentException("An in-order check needs at least one part");
        }
        StringBuilder expression = new StringBuilder(Pattern.quote(parts[0]));
        for (int i = 1; i < parts.length; i++) {
            expression.append("[\\s\\S]*?").append(Pattern.quote(parts[i]));
        }
        return Pattern.compile(expression.toString());
    }
}
