package be.elevenways.hohenheim.test;

import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.Zenit;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Words a Microcopy for a test reader through the process-default message resolver.
 *
 * AIDEV-NOTE: the process resolver, not ShippedCatalogs alone: it is what a render reads, translations included.
 * ApiSupport.shippedText and HohenheimTestBase.toastText read the shipped catalogs on purpose.
 *
 * @author Jelle De Loecker
 * @since 0.10.0
 */
public final class MicrocopyText {

    private static final LocaleChain EN = LocaleChain.ofTags("en");

    private MicrocopyText() {
    }

    /** @return the copy's English words, "" for no copy */
    public static @NonNull String say(@Nullable Microcopy copy) {
        return copy == null ? "" : say(copy, EN);
    }

    /** @return the copy's words for a reader of {@code locales} */
    public static @NonNull String say(@NonNull Microcopy copy, @NonNull LocaleChain locales) {
        return copy.resolve(locales, Zenit.getMessageResolver());
    }
}
