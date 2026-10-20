package be.elevenways.hohenheim;

import be.elevenways.hawkeye.common.annotation.HawkeyeGlobal;
import be.elevenways.zenit.cms.common.resource.HealthTone;
import be.elevenways.zenit.common.ui.BadgeVariant;
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
 * AIDEV-NOTE: "raises attention, and how loudly" is answered here once: a state vocabulary carries a nullable
 * member of this enum (null raises nothing, as DelegationVerdict, HostStanding and DatabaseVerdict.State do), and a
 * framework verdict is read through {@link #ofTone}, never a private tone switch.
 *
 * AIDEV-NOTE: a vocabulary of its own, checked against the homes it resembles (cohesion I10): zenit's
 * ActivitySeverity names what a logged verb MEANT (created, changed, removed, reversed), never an urgency, and
 * protoblast's DiagnosticSeverity grades a compiler finding; neither raises a dashboard item.
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

    /** @return the tone the item's tinted icon mark wears (pl-icon-mark) */
    public @NonNull BadgeVariant badge() {
        return switch (this) {
            case ERROR -> BadgeVariant.DESTRUCTIVE;
            case WARNING -> BadgeVariant.WARNING;
            case INFO -> BadgeVariant.INFO;
        };
    }

    /**
     * @return the severity a verdict of this tone raises, null for one that raises nothing (fine, not known yet, or
     *         retired on purpose)
     */
    public static @Nullable AttentionSeverity ofTone(@NonNull HealthTone tone) {
        return switch (tone) {
            case BROKEN -> ERROR;
            case ATTENTION -> WARNING;
            case OK, UNKNOWN, RETIRED -> null;
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
