package be.elevenways.hohenheim.activity;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * How the activity log tells that a Hohenheim operation ran: one past-tense sentence per operation, keyed by the
 * operation's id path under Hohenheim's own scope, with the arguments {@code actor} and {@code subject}.
 *
 * AIDEV-NOTE: every Hohenheim operation declares its sentence through this (or a sentence of its own); the
 * OperationSentencesTest drift test derives the set from the operation registry, so an operation registered without
 * one, or a sentence missing from a shipped catalogue, fails the build.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class OperationSentences {

    private OperationSentences() {
    }

    /** @return the sentence of the operation whose id path is {@code operation} */
    public static @NonNull Microcopy of(@NonNull String operation) {
        return HohenheimMicrocopy.HOHENHEIM.of(operation).withFilter("target", "happened");
    }
}
