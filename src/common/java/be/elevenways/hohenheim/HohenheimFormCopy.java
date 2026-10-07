package be.elevenways.hohenheim;

import be.elevenways.protoblast.common.i18n.Microcopy;

/**
 * Shared localized labels and help tokens for Hohenheim form fields and its navigation groups.
 *
 * AIDEV-NOTE: labels ship under Hohenheim's own scopes, never plumage's {@code field} or {@code nav}: two modules
 * declaring one (key, filters) identity keep only one text. Templates spell the same values as literals
 * ({@code t("name", scope: "hohenheim_field")}), which the catalog scope gate ties to these constants.
 */
public final class HohenheimFormCopy {

    /** The scope filter value of Hohenheim's field labels. */
    public static final String FIELD_SCOPE = "hohenheim_field";

    /** The scope filter value of Hohenheim's navigation group labels. */
    public static final String NAV_SCOPE = "hohenheim_nav";

    private HohenheimFormCopy() {}

    public static Microcopy label(String key) {
        return Microcopy.of(key).withFilter("scope", FIELD_SCOPE);
    }

    public static Microcopy help(String key) {
        return Microcopy.of(key).withFilter("scope", "help");
    }

    /** Label of a collapsible form section; the key is its {@code HohenheimFormSections} id. */
    public static Microcopy section(String key) {
        return Microcopy.of(key).withFilter("scope", "form_section");
    }

    /** Label of a navigation group; the key is its group id. */
    public static Microcopy navGroup(String key) {
        return Microcopy.of(key).withFilter("scope", NAV_SCOPE);
    }
}
