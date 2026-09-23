package be.elevenways.hohenheim;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.routing.RouteTarget;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One step on the dashboard readiness checklist.
 *
 * A {@link OnboardingState#BLOCKED} step's own precondition failed and {@code detail} carries the
 * refusal in the gate's own words.
 *
 * @param icon the step's SUBJECT icon, shown only while its state has no marker of its own
 *
 * @author Jelle De Loecker
 * @since  0.5.0
 */
@HawkeyeClass
public record OnboardingStep(
        @NonNull OnboardingState state,
        String icon,
        Microcopy title,
        @Nullable Microcopy detail,
        @Nullable RouteTarget target
) {

    public boolean isDone() {
        return this.state.done();
    }

    /**
     * The icon the checklist marker shows: the state's marker, else the step's subject.
     *
     * AIDEV-NOTE: the marker states the step's STATE, never its subject. A step whose own icon
     * happened to be "circle-check" used to render a completed-looking tick while it was blocking
     * the whole checklist.
     */
    public @NonNull String markerIcon() {
        String marker = this.state.marker();
        return marker != null ? marker : this.icon;
    }

    /** @return whether the step renders its "Open" link */
    public boolean offersAction() {
        return this.target != null && this.state.actionable();
    }
}
