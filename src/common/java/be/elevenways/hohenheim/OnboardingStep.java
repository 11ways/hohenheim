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
 * refusal in the gate's own words. An open step whose condition an attention item states presents that item
 * ({@link #presenting}): its words, what it holds back and its one action.
 *
 * @param icon     the step's SUBJECT icon, shown only while its state has no marker of its own
 * @param action   what following {@code target} does, in words; null reads as the checklist's plain "Open"
 * @param heldBack what the presented item holds back ("2 apps wait for it"), null for nothing
 *
 * @author Jelle De Loecker
 * @since  0.5.0
 */
@HawkeyeClass
public record OnboardingStep(
        @NonNull OnboardingStage stage,
        @NonNull OnboardingState state,
        String icon,
        Microcopy title,
        @Nullable Microcopy detail,
        @Nullable RouteTarget target,
        @Nullable Microcopy action,
        @Nullable Microcopy heldBack
) {

    /** A step that says its own words and leads to its page with the plain "Open". */
    public OnboardingStep(@NonNull OnboardingStage stage, @NonNull OnboardingState state, String icon,
                          Microcopy title, @Nullable Microcopy detail, @Nullable RouteTarget target) {
        this(stage, state, icon, title, detail, target, null, null);
    }

    public boolean isDone() {
        return this.state.done();
    }

    /**
     * This step as the item stating its condition says it: the item's detail, what it holds back, its target and its
     * worded action, under the step's own title.
     */
    public @NonNull OnboardingStep presenting(@NonNull AttentionItem item) {
        return new OnboardingStep(this.stage, this.state, this.icon, this.title,
            item.detail() != null ? item.detail() : this.detail,
            item.target() != null ? item.target() : this.target,
            item.target() != null ? item.action() : this.action,
            item.heldBack());
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
