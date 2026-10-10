package be.elevenways.hohenheim;

import be.elevenways.hawkeye.common.annotation.HawkeyeGlobal;
import be.elevenways.zenit.cms.common.resource.HealthTone;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * How urgently a dashboard attention item needs an operator: the declaring home of the severity vocabulary.
 *
 * AIDEV-NOTE: {@link #key()} is what the attention widget stamps as {@code data-severity} and what
 * app.scss's {@code .hh-attention-item[data-severity="..."]} rules match, so the rendered value is a
 * fact ON the member, never a literal in a collector. DashboardVocabularyDriftTest binds every
 * member to its stylesheet rule. The keys are the strings the pre-enum collectors wrote, so a test or
 * selector reading {@code data-severity='error'} keeps working.
 *
 * AIDEV-NOTE: "raises attention, and how loudly" is answered here once (DD4): a state vocabulary carries a nullable
 * member of this enum (null raises nothing, as DelegationVerdict, HostStanding and DatabaseVerdict.State do), and a
 * framework verdict is read through {@link #ofTone}, never a private tone switch.
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

    /** @return the severity a verdict of this tone raises, null for one that raises nothing (fine, or not known yet) */
    public static @Nullable AttentionSeverity ofTone(@NonNull HealthTone tone) {
        return switch (tone) {
            case BROKEN -> ERROR;
            case ATTENTION -> WARNING;
            case OK, UNKNOWN -> null;
        };
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
