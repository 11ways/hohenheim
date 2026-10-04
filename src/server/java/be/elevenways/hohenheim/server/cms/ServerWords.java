package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.routing.RouteLocales;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Host-scoped catalog words and their server-default-locale projection.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
final class ServerWords {
    private ServerWords() {}
    static @NonNull Microcopy serverCopy(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "server");
    }
    static @NonNull String hostCopy(@NonNull Microcopy words) {
        return words.resolve(LocaleChain.of(RouteLocales.get().getDefaultLocale()), Zenit.getMessageResolver());
    }
}
