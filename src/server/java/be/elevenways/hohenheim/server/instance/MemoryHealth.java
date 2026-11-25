package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.AttentionSeverity;
import be.elevenways.hohenheim.HohenheimSettings.Capacity;
import be.elevenways.protoblast.common.i18n.wording.ByteUnit;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static be.elevenways.hohenheim.HohenheimSettings.positiveOrDefault;

/**
 * What an app's memory history ({@link MemoryHistory}) says about the two amounts it declares: use that stays above
 * what it reserves, use that stays far below it, and use close to its maximum.
 *
 * AIDEV-NOTE: a reservation finding judges a WINDOW the history covers whole ({@link MemoryHistory.Track#covering}),
 * never one moment, so a short peak says nothing; only the maximum is judged on the newest sample, because the kernel
 * kills at the maximum the moment use reaches it. A history whose newest sample is older than
 * {@link MemoryHistory#MAX_GAP} says nothing at all: a sweep that stopped reading must not leave yesterday's verdict on
 * the dashboard.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class MemoryHealth {

    /** A suggested reservation is a whole number of these steps (MB), so it reads as a value a person would pick. */
    static final int STEP_MB = 64;

    /** What one app's history says; the declaring home of the memory findings. */
    public enum Finding {

        /** Use stayed above the reservation for the whole sustained window: raise it. */
        ABOVE_RESERVED(AttentionSeverity.WARNING, "app_memory_above_reserved"),

        /** The highest use over the far-below window stayed under its share of the reservation: lower it. */
        FAR_BELOW_RESERVED(AttentionSeverity.INFO, "app_memory_far_below_reserved"),

        /** The newest use reached its share of the maximum, where the kernel kills. */
        NEAR_MAXIMUM(AttentionSeverity.WARNING, "app_memory_near_maximum");

        private final AttentionSeverity severity;
        private final String key;

        Finding(@NonNull AttentionSeverity severity, @NonNull String key) {
            this.severity = severity;
            this.key = key;
        }

        /** @return how loudly the dashboard raises it */
        public @NonNull AttentionSeverity severity() {
            return this.severity;
        }

        /** @return its attention title and detail key */
        public @NonNull String key() {
            return this.key;
        }
    }

    /**
     * The thresholds the findings are judged by, the {@code capacity} settings.
     *
     * @param sustained          how long use must stay above the reservation
     * @param nearMaximumPercent the share of the maximum that warns
     * @param farBelowPercent    the share of the reservation the highest use must stay under
     * @param farBelow           how long it must stay under it
     * @param headroomPercent    what a suggestion adds to the highest use
     */
    public record Thresholds(@NonNull Duration sustained, int nearMaximumPercent, int farBelowPercent,
                             @NonNull Duration farBelow, int headroomPercent) {

        /** @return the thresholds as the settings say now */
        public static @NonNull Thresholds current() {
            return new Thresholds(
                Duration.ofMinutes(positiveOrDefault(Capacity.MEMORY_SUSTAINED_MINUTES)),
                positiveOrDefault(Capacity.MEMORY_NEAR_MAXIMUM_PERCENT),
                positiveOrDefault(Capacity.MEMORY_FAR_BELOW_PERCENT),
                Duration.ofDays(positiveOrDefault(Capacity.MEMORY_FAR_BELOW_DAYS)),
                positiveOrDefault(Capacity.MEMORY_HEADROOM_PERCENT));
        }

        /** @return the longest window a finding judges, which is how long the history keeps its samples */
        public @NonNull Duration longest() {
            return this.sustained.compareTo(this.farBelow) >= 0 ? this.sustained : this.farBelow;
        }
    }

    /**
     * One finding about one app.
     *
     * @param usedBytes   the use it is about: the highest in its window, the newest for {@link Finding#NEAR_MAXIMUM}
     * @param suggestedMb the reservation to set instead, 0 for {@link Finding#NEAR_MAXIMUM}
     */
    public record Judgement(@NonNull Finding finding, long usedBytes, long suggestedMb) {
    }

    private MemoryHealth() {
    }

    /**
     * @param track      the app's history, null when it has none
     * @param reservedMb what the app reserves ({@link InstanceCapacity#reservedMbOf})
     * @param maximumMb  what it may grow to ({@link InstanceCapacity#maximumMbOf})
     * @return its findings, empty when all is well or the history cannot say
     */
    public static @NonNull List<Judgement> judge(MemoryHistory.@Nullable Track track, long reservedMb,
                                                 long maximumMb, @NonNull Thresholds thresholds,
                                                 @NonNull Instant now) {
        List<Judgement> found = new ArrayList<>();
        if (track == null || track.samples().isEmpty()) {
            return found;
        }
        MemoryHistory.Sample newest = track.samples().get(track.samples().size() - 1);
        if (newest.at().isBefore(now.minus(MemoryHistory.MAX_GAP))) {
            return found;
        }
        long reservedBytes = ByteUnit.MEGABYTE.bytes(reservedMb);
        List<MemoryHistory.Sample> sustained = reservedMb > 0 ? track.covering(thresholds.sustained(), now) : null;
        if (sustained != null && sustained.stream().allMatch(sample -> sample.usedBytes() > reservedBytes)) {
            long peak = peakOf(sustained);
            long suggested = suggestionOf(peak, thresholds);
            found.add(new Judgement(Finding.ABOVE_RESERVED, peak,
                maximumMb > 0 ? Math.min(suggested, maximumMb) : suggested));
        }
        List<MemoryHistory.Sample> week = reservedMb > 0 ? track.covering(thresholds.farBelow(), now) : null;
        if (week != null) {
            long peak = peakOf(week);
            long suggested = suggestionOf(peak, thresholds);
            if (peak * 100 < reservedBytes * thresholds.farBelowPercent() && suggested < reservedMb) {
                found.add(new Judgement(Finding.FAR_BELOW_RESERVED, peak, suggested));
            }
        }
        if (maximumMb > 0
                && newest.usedBytes() * 100 >= ByteUnit.MEGABYTE.bytes(maximumMb) * thresholds.nearMaximumPercent()) {
            found.add(new Judgement(Finding.NEAR_MAXIMUM, newest.usedBytes(), 0));
        }
        return found;
    }

    private static long peakOf(@NonNull List<MemoryHistory.Sample> samples) {
        long peak = 0;
        for (MemoryHistory.Sample sample : samples) {
            peak = Math.max(peak, sample.usedBytes());
        }
        return peak;
    }

    /** @return the highest use plus the headroom, in MB, rounded up to a whole {@link #STEP_MB} */
    private static long suggestionOf(long peakBytes, @NonNull Thresholds thresholds) {
        long withHeadroom = peakBytes + peakBytes * thresholds.headroomPercent() / 100;
        long stepBytes = ByteUnit.MEGABYTE.bytes(STEP_MB);
        long steps = Math.max(1, (withHeadroom + stepBytes - 1) / stepBytes);
        return steps * STEP_MB;
    }
}
