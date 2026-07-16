package be.elevenways.hohenheim;

import be.elevenways.protoblast.common.i18n.Microcopy;

/** Shared localized labels and help tokens for Hohenheim form fields. */
public final class HohenheimFormCopy {

    private HohenheimFormCopy() {}

    public static Microcopy label(String key) {
        return Microcopy.of(key).withFilter("scope", "field");
    }

    public static Microcopy help(String key) {
        return Microcopy.of(key).withFilter("scope", "help");
    }
}
