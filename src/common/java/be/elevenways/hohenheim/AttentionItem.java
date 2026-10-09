package be.elevenways.hohenheim;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.routing.RouteTarget;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/** One typed operational issue rendered by the dashboard attention widget.
 *
 * AIDEV-NOTE: about, causedBy, stage and heldBack are the dashboard's fold, read before anything renders
 * (DashboardAttention): an item {@code causedBy} a subject another shown item is {@code about} is not drawn, its root says how much it holds
 * back ({@code heldBack}) instead; an item declaring a checklist {@code stage} is presented by that stage's open step
 * rather than drawn twice. The Hosts list's band draws every item unfolded; the Databases band folds the apps a shown
 * database holds back (DashboardAttention.band).
 *
 * @param action   what following {@code target} does, in words ("Check and admit"); null exactly when target is
 * @param about    the record this item is the root problem of, null when nothing can be caused by it
 * @param causedBy the record whose own item is this one's root, null for a root
 * @param stage    the checklist stage whose open step states this same condition, null for none
 * @param heldBack what this root holds back, in words ("2 apps wait for it"), null for nothing
 * @param note     a further line under the detail: a second fact about the same subject (the old engine a database's
 *                 move left behind) or the technical words after the worded ones; null for none
 * @author Jelle De Loecker
 * @since 0.2.0
 */
@HawkeyeClass
public record AttentionItem(
        @NonNull AttentionSeverity severity,
        String icon,
        Microcopy title,
        @Nullable Microcopy detail,
        @Nullable RouteTarget target,
        @Nullable Microcopy action,
        @Nullable AttentionSubject about,
        @Nullable AttentionSubject causedBy,
        @Nullable OnboardingStage stage,
        @Nullable Microcopy heldBack,
        @Nullable Microcopy note
) {

    /** An item standing on its own: no root, no consequence, no checklist step. */
    public AttentionItem(@NonNull AttentionSeverity severity, String icon, Microcopy title, @Nullable Microcopy detail,
                         @Nullable RouteTarget target, @Nullable Microcopy action) {
        this(severity, icon, title, detail, target, action, null, null, null, null, null);
    }

    /** @return a copy that is the root problem of this record, holding back what {@code heldBack} says */
    public @NonNull AttentionItem about(@NonNull AttentionSubject subject, @Nullable Microcopy heldBack) {
        return new AttentionItem(this.severity, this.icon, this.title, this.detail, this.target, this.action, subject,
            this.causedBy, this.stage, heldBack, this.note);
    }

    /** @return a copy whose root is this record's own item */
    public @NonNull AttentionItem causedBy(@Nullable AttentionSubject root) {
        return new AttentionItem(this.severity, this.icon, this.title, this.detail, this.target, this.action,
            this.about, root, this.stage, this.heldBack, this.note);
    }

    /** @return a copy stating the condition of this checklist stage's open step */
    public @NonNull AttentionItem forStage(@NonNull OnboardingStage stage) {
        return new AttentionItem(this.severity, this.icon, this.title, this.detail, this.target, this.action,
            this.about, this.causedBy, stage, this.heldBack, this.note);
    }

    /** @return a copy carrying this further line under its detail */
    public @NonNull AttentionItem withNote(@Nullable Microcopy line) {
        return new AttentionItem(this.severity, this.icon, this.title, this.detail, this.target, this.action,
            this.about, this.causedBy, this.stage, this.heldBack, line);
    }
}
