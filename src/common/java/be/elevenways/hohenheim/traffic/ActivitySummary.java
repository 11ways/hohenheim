package be.elevenways.hohenheim.traffic;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.zenit.traffic.common.StatusClass;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;

/**
 * The last minute of a live Activity picture as its page words it, as the picture last drew it: the figures, the line
 * that says when it was heard last, the picture in words, and what a screen reader is told at each interval.
 *
 * @param heard       whether any frame arrived yet
 * @param latest      the second the last frame was stamped with, null before the first
 * @param requests    the requests of the last minute's heard seconds
 * @param bytes       the bytes they sent
 * @param visitors    the distinct visitors among them
 * @param visitorsCut whether a frame left visitors out, so {@code visitors} is at least that many
 * @param refused     those answered refused or not found (4xx)
 * @param failed      those that failed (5xx)
 * @param notDrawn    those the picture counts without drawing them
 * @param missed      the seconds of the minute no frame was heard for
 * @param picture     every row of the picture with its figure, in words; null before the picture drew
 * @param speak       whether a screen reader is told about this minute now (once every {@link #SPEAK_EVERY} seconds)
 * @param ended       whether the live link ended (revoked, or no longer allowed), so nothing more arrives
 * @author Jelle De Loecker
 * @since  0.1.0
 */
@HawkeyeClass
public record ActivitySummary(boolean heard, @Nullable Instant latest, long requests, long bytes, long visitors,
                              boolean visitorsCut, long refused, long failed, long notDrawn, int missed,
                              @Nullable String picture, boolean speak, boolean ended) {

    /** How many seconds apart a screen reader is told what the last minute held. */
    public static final int SPEAK_EVERY = 15;

    /** @return the model's last minute, with the picture's words as it was just drawn */
    public static @NonNull ActivitySummary of(@NonNull ActivityModel model, @Nullable String picture,
                                              boolean ended) {
        long latest = model.latest();
        boolean heard = latest != Long.MIN_VALUE;
        return new ActivitySummary(heard, heard ? Instant.ofEpochSecond(latest) : null, model.requests(),
            model.bytes(), model.visitors(), model.visitorsCut(), model.count(StatusClass.CLIENT_ERROR),
            model.count(StatusClass.SERVER_ERROR), model.notDrawn(), model.missed(), picture,
            !ended && model.takeSpeech(SPEAK_EVERY), ended);
    }
}
