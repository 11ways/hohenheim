package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The app's one way to declare an action's confirmation: the verb titles the dialog and labels its confirm button.
 *
 * AIDEV-NOTE: the only ConfirmationSpec.builder() in Hohenheim. A delete dialog is zenit-cms's
 * DeleteConfirmation.body and an "Are you sure?" ask is ConfirmationSpec.generic; everything else is a verb dialog
 * built here. zenit-cms offers no verb factory and no way to add a typed phrase to a finished spec, so this class is
 * the single place a framework factory later replaces.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
final class Confirmations {

    private Confirmations() {}

    /** @return the verb's dialog, whose confirm button repeats the verb */
    static @NonNull ConfirmationSpec of(@NonNull Microcopy verb, @NonNull Microcopy body, @NonNull ActionStyle style) {
        return of(verb, verb, body, style);
    }

    /** @param confirmLabel the button's own words, for a title that is not the verb the button performs */
    static @NonNull ConfirmationSpec of(@NonNull Microcopy title, @NonNull Microcopy confirmLabel,
                                        @NonNull Microcopy body, @NonNull ActionStyle style) {
        return ConfirmationSpec.builder().title(title).body(body).confirmLabel(confirmLabel).style(style).build();
    }

    /** @return the same dialog gated on typing the phrase, or the dialog itself for a null or blank phrase */
    static @NonNull ConfirmationSpec typed(@NonNull ConfirmationSpec spec, @Nullable String phrase) {
        if (phrase == null || phrase.isBlank()) {
            return spec;
        }
        return ConfirmationSpec.builder().title(spec.title()).body(spec.body()).confirmLabel(spec.confirmLabel())
            .cancelLabel(spec.cancelLabel()).style(spec.style()).requireTypedConfirmation(phrase).build();
    }
}
