package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * How "Put something online" gives a new address its certificate: the HTTPS step's cards, one home for their tokens,
 * words and what each one asks and does.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public enum HttpsChoice {

    /** A Let's Encrypt certificate for the address alone, ordered over HTTP-01 once the address exists. */
    AUTOMATIC,

    /** A Let's Encrypt wildcard certificate for the address's parent domain, ordered over DNS-01. */
    WILDCARD,

    /** A certificate Hohenheim already holds that covers the address, a wildcard one included. */
    EXISTING,

    /** The operator's own certificate chain and private key, pasted on the step. */
    UPLOAD,

    /** None now: the address answers over plain HTTP until a certificate is added. */
    LATER;

    /** @return the value the HTTPS entry holds for this choice */
    public @NonNull String token() {
        return switch (this) {
            case AUTOMATIC -> "automatic";
            case WILDCARD -> "wildcard";
            case EXISTING -> "existing";
            case UPLOAD -> "upload";
            case LATER -> "later";
        };
    }

    /** @return the choice an HTTPS entry holds, null for a value this vocabulary does not know */
    public static @Nullable HttpsChoice ofToken(@Nullable String token) {
        for (HttpsChoice choice : values()) {
            if (choice.token().equals(token)) {
                return choice;
            }
        }
        return null;
    }

    /** Whether the step asks for a certificate chain and its private key. */
    public boolean takesUpload() {
        return switch (this) {
            case UPLOAD -> true;
            case AUTOMATIC, WILDCARD, EXISTING, LATER -> false;
        };
    }

    /** Whether the run orders a certificate from Let's Encrypt, which its setting may have switched off. */
    public boolean ordersFromLetsEncrypt() {
        return switch (this) {
            case AUTOMATIC, WILDCARD -> true;
            case EXISTING, UPLOAD, LATER -> false;
        };
    }

    /** @return the card's title */
    public @NonNull Microcopy label() {
        return HohenheimMicrocopy.PUT_ONLINE.of("https_" + this.token());
    }

    /** @return the card's line */
    public @NonNull Microcopy help() {
        return HohenheimMicrocopy.PUT_ONLINE.of("https_" + this.token() + "_help");
    }

    /** @return the line the run's certificate step leaves when this choice set HTTPS up; null for none set up */
    public @Nullable Microcopy doneLine() {
        String key = switch (this) {
            case AUTOMATIC -> "done_certificate";
            case WILDCARD -> "done_certificate_wildcard";
            case EXISTING -> "done_certificate_existing";
            case UPLOAD -> "done_certificate_uploaded";
            case LATER -> null;
        };
        return key == null ? null : HohenheimMicrocopy.PUT_ONLINE.of(key);
    }
}
