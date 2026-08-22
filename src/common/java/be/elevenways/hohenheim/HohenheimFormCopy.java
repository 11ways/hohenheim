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

    /** Label of a collapsible form section; the key is its {@code HohenheimFormSections} id. */
    public static Microcopy section(String key) {
        return Microcopy.of(key).withFilter("scope", "form_section");
    }
}
