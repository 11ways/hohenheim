package be.elevenways.hohenheim.instance;

import be.elevenways.zenit.common.ui.BadgeVariant;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Where a service's live figure stands: read from its last answer, unanswered by its last probe, not asked because
 * the instance's own state says it is not meant to run, or not asked yet.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public enum LiveFigureState {

    /** The last probe was answered; the figure is that answer. */
    ANSWERING("answering", BadgeVariant.SUCCESS),

    /** The last probe went unanswered, so there is no figure: never the last answer passed off as current. */
    SILENT("silent", BadgeVariant.OUTLINE),

    /**
     * The instance's own state says it is not meant to be running (stopped, not deployed yet, being destroyed), so it
     * is never asked; the figure says that state in its own words.
     */
    NOT_RUNNING("not_running", BadgeVariant.OUTLINE),

    /** No probe has finished since the page opened. */
    WAITING("waiting", BadgeVariant.SECONDARY);

    private final @NonNull String token;
    private final @NonNull BadgeVariant variant;

    LiveFigureState(@NonNull String token, @NonNull BadgeVariant variant) {
        this.token = token;
        this.variant = variant;
    }

    /** @return the wire and markup value */
    public @NonNull String token() {
        return this.token;
    }

    public @NonNull BadgeVariant variant() {
        return this.variant;
    }

    /** @return the state a wire token names; an unknown or absent one is {@link #WAITING}, which claims nothing */
    public static @NonNull LiveFigureState forToken(@Nullable Object token) {
        for (LiveFigureState state : values()) {
            if (state.token.equals(token)) {
                return state;
            }
        }
        return WAITING;
    }
}
