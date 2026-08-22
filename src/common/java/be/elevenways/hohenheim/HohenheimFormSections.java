package be.elevenways.hohenheim;

import be.elevenways.zenit.common.edit.FormSection;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * THE collapsible-section vocabulary of Hohenheim's type-switched settings forms.
 *
 * AIDEV-NOTE: the ids are TOPICAL and deliberately never {@code advanced}. A kind's
 * settings render as a sub-form INSIDE a record form that already ends in its own
 * {@link FormSection#advanced} fold, so a second bar reading "Advanced" a few hundred
 * pixels above the first says nothing about which half of the form it folds. A label
 * naming what is inside costs one microcopy key and answers that on sight.
 *
 * @author Jelle De Loecker
 * @since  0.5.0
 */
public final class HohenheimFormSections {

    /** How a checkout becomes an artifact: builder, paths, timeouts, checkout options. */
    public static final String BUILD = "build";

    /** When and how a new revision reaches the host, previews included. */
    public static final String DEPLOYMENT = "deployment";

    /** What the workload gets while it runs: environment, resource ceilings. */
    public static final String RUNTIME = "runtime";

    /** Resource ceilings alone, where the runtime is not otherwise configurable. */
    public static final String LIMITS = "limits";

    /** How a container port reaches the outside world. */
    public static final String PORTS = "ports";

    /** Guest-side configuration of a virtual machine. */
    public static final String GUEST = "guest";

    /** The health probe and its cadence. */
    public static final String HEALTH = "health";

    /** Values a generator owns: shown so they are not invisible, folded so they are not noise. */
    public static final String MANAGED = "managed";

    /** How a request is forwarded once the upstream is chosen. */
    public static final String FORWARDING = "forwarding";

    private HohenheimFormSections() {}

    /**
     * A folded section carrying this id's shared label.
     *
     * @param id         one of the constants above; it is also the microcopy key
     * @param entryNames the fields it claims, in render order
     */
    public static @NonNull FormSection collapsed(@NonNull String id, @NonNull List<String> entryNames) {
        return new FormSection(id, HohenheimFormCopy.section(id), null, null, true, entryNames);
    }

    /** @return one list, so a section can name a shared group plus its own members */
    @SafeVarargs
    public static @NonNull List<String> join(@NonNull List<String>... groups) {
        List<String> joined = new ArrayList<>();
        for (List<String> group : groups) {
            joined.addAll(group);
        }
        return joined;
    }
}
