package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.AttentionSubject;
import be.elevenways.hohenheim.OnboardingStep;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * The admin dashboard's readiness checklist and attention band read together, so every problem is shown once, at its
 * root, with its one action.
 *
 * AIDEV-NOTE: two declared relations decide what folds, never a comparison of words. An open checklist step presents
 * the item stating its stage ({@link OnboardingCollector#presentedBy}, the same question the step was built with), so
 * that item leaves the band: the boards draw a fresh install's open steps in the checklist and nothing in the band.
 * An item caused by a record ({@link AttentionItem#causedBy}, from the app verdict's cause half) leaves the band while
 * that record's own item is shown, in the band or presented by a step; its root says what it holds back. An item
 * whose root is not shown stays, so a fold can never hide a problem nobody else names.
 *
 * AIDEV-NOTE: the checklist retires once the first app is online (board Main), and the fold applies only while it
 * shows: a retired checklist presents nothing, so the conditions its open steps stood for (backups that stay on this
 * machine, a host that takes no new apps) are the band's items from then on.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
final class DashboardAttention {

    /**
     * What the dashboard draws.
     *
     * @param checklist the readiness steps, open ones presenting their items; the widget is drawn while any is open
     * @param attention the band's items, without those the checklist presents or a shown root holds back
     */
    record Reading(@NonNull List<OnboardingStep> checklist, @NonNull List<AttentionItem> attention) {}

    private DashboardAttention() {
    }

    /** @return the checklist and the band, folded */
    static @NonNull Reading read() {
        return read(AttentionCollector.collect(), OnboardingCollector.retired());
    }

    /**
     * @param items   every attention item, unfolded
     * @param retired whether the checklist has retired ({@link OnboardingCollector#retired}: something is online)
     * @return the checklist (empty once retired) and the band, folded
     */
    static @NonNull Reading read(@NonNull List<AttentionItem> items, boolean retired) {
        return fold(retired ? List.of() : OnboardingCollector.collect(items, false), items);
    }

    /**
     * @param steps the checklist as {@link OnboardingCollector#collect(List)} built it from these items
     * @param items every attention item, unfolded
     */
    static @NonNull Reading fold(@NonNull List<OnboardingStep> steps, @NonNull List<AttentionItem> items) {
        Set<AttentionItem> presented = Collections.newSetFromMap(new IdentityHashMap<>());
        for (OnboardingStep step : steps) {
            AttentionItem item = OnboardingCollector.presentedBy(step, items);
            if (item != null) {
                presented.add(item);
            }
        }
        Set<AttentionSubject> roots = new HashSet<>();
        for (AttentionItem item : items) {
            if (item.about() != null) {
                roots.add(item.about());
            }
        }
        List<AttentionItem> band = new ArrayList<>(items.size());
        for (AttentionItem item : items) {
            if (!presented.contains(item) && (item.causedBy() == null || !roots.contains(item.causedBy()))) {
                band.add(item);
            }
        }
        return new Reading(steps, List.copyOf(band));
    }
}
