package be.elevenways.hohenheim.server.tls;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.time.Now;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.time.Duration;
import java.time.Instant;

/**
 * When a certificate expires, in the one wording every surface says it with: whole days ("Expires in 35 days", the
 * boards' "in 12 days"), today, or how long ago it expired.
 *
 * AIDEV-NOTE: DEP10's Apps list said "a month and 5 days from now" (the browser's relative time) beside the dashboard's
 * "in 35 days"; the Addresses and Apps HTTPS cells, the app overview, the Certificates list, the dashboard tile, its
 * expiring item and the expiry alert all read this class now. The sentence-case spelling ({@link #inSentence}) is the
 * catalog's {@code case=sentence} variant, for a sentence that carries it ("The certificate shop expires in 3 days").
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class CertificateExpiry {

    private CertificateExpiry() {
    }

    /** @return the whole days left until this moment, negative once it has passed */
    public static long daysLeft(@NonNull Instant expires) {
        return Duration.between(Now.instant(), expires).toDays();
    }

    /** @return when it expires, as a line of its own ("Expires in 35 days") */
    public static @NonNull Microcopy of(@NonNull Instant expires) {
        long days = daysLeft(expires);
        if (days < 0) {
            return copy("certificate_expired").withArg("days", -days);
        }
        return days == 0 ? copy("certificate_expires_today") : copy("certificate_expiry").withArg("days", days);
    }

    /** @return {@link #of} spelled for the middle of a sentence ("expires in 35 days") */
    public static @NonNull Microcopy inSentence(@NonNull Instant expires) {
        return of(expires).withFilter("case", "sentence");
    }

    private static @NonNull Microcopy copy(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "certificate");
    }
}
