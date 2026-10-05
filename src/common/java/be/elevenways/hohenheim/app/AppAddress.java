package be.elevenways.hohenheim.app;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.ui.BadgeVariant;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One address an app answers on, as its overview's Addresses card draws it: the hostname, whether HTTPS works for it,
 * and what the reader should know about it.
 *
 * @param url  the address's own record page, null for a reader who may not open it
 * @param note what else there is to say (forced without a certificate, an alias), blank when nothing
 * @author Jelle De Loecker
 * @since  0.1.0
 */
@HawkeyeClass
public record AppAddress(
    @NonNull String hostname,
    @Nullable String url,
    @NonNull Microcopy state,
    @NonNull BadgeVariant variant,
    @NonNull String note
) {
}
