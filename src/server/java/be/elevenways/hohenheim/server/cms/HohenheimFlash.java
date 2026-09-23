package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.flash.FlashLevel;
import be.elevenways.zenit.server.flash.Flash;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * THE way a Hohenheim handler hands an outcome message to the page it redirects to.
 *
 * AIDEV-NOTE: this replaced a hand-rolled query-parameter channel (error/saved/
 * restored/imported/skipped). Those put operator-facing text -- including error
 * detail -- into shareable, bookmarkable, access-logged URLs; zenit's flash
 * is session-carried, one-shot and per-tab. Never add a query parameter for a
 * notification again.
 */
public final class HohenheimFlash {

    private HohenheimFlash() {
    }

    /** Stash a refusal the destination page renders as an error toast. */
    public static void error(@NonNull Conduit conduit, @NonNull Microcopy message) {
        Flash.stash(conduit, message, FlashLevel.ERROR);
    }

    /** Stash a confirmation the destination page renders as a success toast. */
    public static void success(@NonNull Conduit conduit, @NonNull Microcopy message) {
        Flash.stash(conduit, message, FlashLevel.SUCCESS);
    }

    /** Stash a partial-success message the destination page renders as a warning toast. */
    public static void warning(@NonNull Conduit conduit, @NonNull Microcopy message) {
        Flash.stash(conduit, message, FlashLevel.WARNING);
    }
}
