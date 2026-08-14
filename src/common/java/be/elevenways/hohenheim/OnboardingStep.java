package be.elevenways.hohenheim;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.routing.RouteTarget;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One step on the dashboard readiness checklist.
 *
 * {@code state} is "done", "todo" or "blocked"; blocked means the step's own precondition
 * failed and {@code detail} carries the refusal in the gate's own words.
 *
 * @author Jelle De Loecker
 * @since  0.5.0
 */
@HawkeyeClass
public record OnboardingStep(
        String state,
        String icon,
        Microcopy title,
        @Nullable Microcopy detail,
        @Nullable RouteTarget target
) {

    public static final String DONE = "done";
    public static final String TODO = "todo";
    public static final String BLOCKED = "blocked";

    public boolean isDone() {
        return DONE.equals(this.state);
    }
}
