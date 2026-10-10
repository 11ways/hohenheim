package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.WordedState;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.ui.BadgeVariant;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * What a certificate's state means to an operator, in a word: the Certificates list's state cell
 * ({@link CertificateParts#stateCell}).
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
enum CertificateState implements WordedState {

    /** Its renewal failed, even while the old certificate still serves. */
    RENEWAL_FAILING("renewal_failing", BadgeVariant.DESTRUCTIVE, HohenheimMicrocopy.CERTIFICATE.of("state_failing")),

    /** A manual DNS order waits for its record. */
    WAITING_DNS("waiting_dns", BadgeVariant.WARNING, HohenheimMicrocopy.CERTIFICATE.of("state_waiting_dns")),

    /** Not issued yet. */
    ISSUING("issuing", BadgeVariant.WARNING, HohenheimMicrocopy.CERTIFICATE.of("state_issuing")),

    /** Past its expiry. */
    EXPIRED("expired", BadgeVariant.DESTRUCTIVE, HohenheimMicrocopy.CERTIFICATE.of("state_expired")),

    /** Stored as working, but the running proxy did not load it, so it serves nobody. */
    NOT_LOADED("not_loaded", BadgeVariant.DESTRUCTIVE, HohenheimMicrocopy.CERTIFICATE.of("state_not_loaded")),

    /** Expires inside the expiry alert's window. */
    EXPIRING("expiring", BadgeVariant.WARNING, HohenheimMicrocopy.CERTIFICATE.of("state_expiring")),

    /** Works. */
    WORKS("works", BadgeVariant.SUCCESS, HohenheimMicrocopy.CERTIFICATE.of("state_works"));

    private final String token;
    private final BadgeVariant variant;
    private final Microcopy label;

    CertificateState(@NonNull String token, @NonNull BadgeVariant variant, @NonNull Microcopy label) {
        this.token = token;
        this.variant = variant;
        this.label = label;
    }

    @Override
    public @NonNull String token() {
        return this.token;
    }

    @Override
    public @NonNull BadgeVariant variant() {
        return this.variant;
    }

    @Override
    public @NonNull Microcopy label() {
        return this.label;
    }
}
