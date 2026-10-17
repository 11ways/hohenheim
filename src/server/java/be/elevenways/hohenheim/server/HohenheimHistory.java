package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.protoblast.common.annotation.BlastAutoLoad;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.history.HistoryFacts;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Permits each Hohenheim fact kind on the models its {@link HohenheimActivityAction#subjects()} names, once at class
 * load, so a fact recorded anywhere else is refused.
 *
 * AIDEV-NOTE: which models record, how deep and whether they are internal is each model's own HistoryBehaviour
 * declaration; this class holds no list of its own.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
@BlastAutoLoad
public final class HohenheimHistory {

    /** Every model a Hohenheim fact kind is permitted on. */
    public static final @NonNull Set<Identifier> SUBJECTS = permitAll();

    private HohenheimHistory() {
    }

    private static @NonNull Set<Identifier> permitAll() {
        Set<Identifier> subjects = new LinkedHashSet<>();
        for (HohenheimActivityAction kind : HohenheimActivityAction.values()) {
            for (Identifier subject : kind.subjects()) {
                HistoryFacts.permit(subject, kind);
                subjects.add(subject);
            }
        }
        return Set.copyOf(subjects);
    }
}
