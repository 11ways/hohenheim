package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.i18n.Locale;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.setting.ContentLocales;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * THE one way a code-declared widget tree spells a localized config label: the
 * per-content-locale map a {@code Localized} config entry stores, resolved from one
 * microcopy key.
 *
 * AIDEV-NOTE: three dashboards and both record front doors spelled this loop
 * privately; a label whose config shape differs per call site is exactly how one of
 * them ends up storing a bare String that the config validation then refuses.
 *
 * @author Jelle De Loecker
 * @since  0.5.0
 */
final class HohenheimWidgetCopy {

    private HohenheimWidgetCopy() {}

    /** @param scope the microcopy scope filter, e.g. {@code instance_overview} */
    static @NonNull Map<Locale, String> localized(@NonNull String key, @NonNull String scope) {
        Microcopy copy = Microcopy.of(key).withFilter("scope", scope);
        Map<Locale, String> label = new LinkedHashMap<>();
        for (Locale locale : ContentLocales.get()) {
            label.put(locale, copy.resolve(LocaleChain.of(locale), Zenit.getMessageResolver()));
        }
        return label;
    }
}
