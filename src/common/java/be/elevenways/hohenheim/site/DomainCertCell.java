package be.elevenways.hohenheim.site;

import be.elevenways.hohenheim.CertCoverage;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A site's Domains tab certificate cell: whether a certificate covers the hostname, and which one.
 *
 * AIDEV-NOTE: the certificate's name and link are set only for a reader who may open the certificate; everyone else
 * reads the coverage state and the expiry, never the operator's certificate name.
 *
 * @param status     the coverage's {@link CertCoverage#key()}, rendered as {@code data-cert-status}
 * @param variant    the coverage's badge variant
 * @param label      the coverage's wording
 * @param name       the covering certificate's name, null for a reader who may not open it
 * @param url        the covering certificate's detail URL, null when {@code name} is
 * @param expiresIso the covering certificate's expiry, null when it has none
 */
@HawkeyeClass
public record DomainCertCell(
    @NonNull String status,
    @NonNull String variant,
    @NonNull Microcopy label,
    @Nullable String name,
    @Nullable String url,
    @Nullable String expiresIso
) {
}
