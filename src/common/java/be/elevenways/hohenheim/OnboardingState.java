package be.elevenways.hohenheim;

import be.elevenways.hawkeye.common.annotation.HawkeyeGlobal;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Where one readiness-checklist step stands: the declaring home of the step-state vocabulary.
 *
 * AIDEV-NOTE: how a state RENDERS is a fact on the member -- the {@code data-state} key app.scss
 * matches, the state marker that replaces the step's own subject icon, and whether the step offers its
 * "Open" link -- so the checklist template compares no literal. The keys are the strings the pre-enum
 * collector wrote. OnboardingStateVocabularyTest binds the keys to the stylesheet.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
@HawkeyeGlobal(namespace = "OnboardingStates")
public enum OnboardingState {

    /** The step's gate passes. */
    DONE("done", "circle-check", false),

    /** Nothing refuses the step; the operator simply has not taken it yet. */
    TODO("todo", null, true),

    /** The step's own precondition failed; the step's detail carries the refusal in the gate's words. */
    BLOCKED("blocked", "triangle-exclamation", true);

    private final String key;
    private final @Nullable String marker;
    private final boolean actionable;

    OnboardingState(@NonNull String key, @Nullable String marker, boolean actionable) {
        this.key = key;
        this.marker = marker;
        this.actionable = actionable;
    }

    /** @return the rendered {@code data-state} value the stylesheet matches */
    public @NonNull String key() {
        return this.key;
    }

    /**
     * The icon that states this STATE over the step's subject icon.
     *
     * @return the marker icon, or null when the step shows its own subject icon
     */
    public @Nullable String marker() {
        return this.marker;
    }

    /** @return whether a step in this state still offers the link to the page that resolves it */
    public boolean actionable() {
        return this.actionable;
    }

    /** @return whether the step needs nothing more */
    public boolean done() {
        return this == DONE;
    }
}
