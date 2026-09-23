package be.elevenways.hohenheim;

import be.elevenways.hawkeye.common.annotation.HawkeyeGlobal;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * How urgently a dashboard attention item needs an operator: the declaring home of the severity vocabulary.
 *
 * AIDEV-NOTE: {@link #key()} is what the attention widget stamps as {@code data-severity} and what
 * app.scss's {@code .hh-attention-item[data-severity="..."]} rules match, so the rendered value is a
 * fact ON the member, never a literal in a collector. AttentionSeverityVocabularyTest binds every
 * member to its stylesheet rule. The keys are the strings the pre-enum collectors wrote, so a test or
 * selector reading {@code data-severity='error'} keeps working.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
@HawkeyeGlobal(namespace = "AttentionSeverities")
public enum AttentionSeverity {

    /** Something is broken: a failed deploy, an unreachable daemon, an expired certificate. */
    ERROR("error"),

    /** Something will break or is degraded unless an operator acts. */
    WARNING("warning"),

    /** Information, not a fault: the edge says "note" and nothing else. */
    INFO("info");

    private final String key;

    AttentionSeverity(@NonNull String key) {
        this.key = key;
    }

    /** @return the rendered {@code data-severity} value the stylesheet matches */
    public @NonNull String key() {
        return this.key;
    }

    /**
     * The member a legacy string spelling names.
     *
     * @throws IllegalArgumentException for an unknown spelling: an unknown severity fails closed
     *         instead of rendering an item the stylesheet cannot tint
     */
    public static @NonNull AttentionSeverity of(@NonNull String key) {
        for (AttentionSeverity severity : values()) {
            if (severity.key.equals(key)) {
                return severity;
            }
        }
        throw new IllegalArgumentException("Unknown attention severity: " + key);
    }
}
