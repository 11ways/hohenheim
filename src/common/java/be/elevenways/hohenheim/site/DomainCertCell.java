package be.elevenways.hohenheim.site;

import be.elevenways.hohenheim.CertCoverage;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.zenit.common.ui.BadgeVariant;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An address's HTTPS cell: what HTTPS gives the name in a word, why when it does not work, and the certificate behind
 * it with its expiry.
 *
 * AIDEV-NOTE: the badge is always the coverage's word (board Domains: "Works", "Not working"), never the certificate's
 * name; the certificate's name and link are set only for a reader who may open the certificate, everyone else reads
 * the coverage state and the expiry, never the operator's certificate name.
 *
 * @param status     the coverage's {@link CertCoverage#key()}, rendered as {@code data-cert-status}
 * @param variant    the coverage's badge variant
 * @param label      the coverage's wording
 * @param detail     why HTTPS does not work (or only partly) for this name, null when it works
 * @param name       the covering certificate's name, null for a reader who may not open it
 * @param url        the covering certificate's detail URL, null when {@code name} is
 * @param expiry     when the covering certificate expires, in the one expiry wording ("Expires in 35 days"), null
 *                   when it has no expiry
 */
@HawkeyeClass
public record DomainCertCell(
    @NonNull String status,
    @NonNull BadgeVariant variant,
    @NonNull Microcopy label,
    @Nullable Microcopy detail,
    @Nullable String name,
    @Nullable String url,
    @Nullable Microcopy expiry
) {
}
