package be.elevenways.hohenheim.server.tls;

import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The ACME problem types an operator can act on, each with the plain sentence the admin shows for it.
 *
 * AIDEV-NOTE: the raw problem (type, detail, sub-problems) stays in the certificate's renewal error and the log; the
 * admin answers with the sentence. A type not listed here fails onto {@link #OTHER}, which says where the detail is,
 * never a guess at a cause.
 *
 * @author Jelle De Loecker
 * @since  0.2.0
 */
public enum AcmeProblem {

    UNAUTHORIZED("unauthorized"),
    CONNECTION("connection"),
    DNS("dns"),
    RATE_LIMITED("rateLimited"),
    CAA("caa"),
    REJECTED_IDENTIFIER("rejectedIdentifier"),
    INCORRECT_RESPONSE("incorrectResponse"),
    OTHER(null);

    private static final String URN = "urn:ietf:params:acme:error:";

    private final @Nullable String type;

    AcmeProblem(@Nullable String type) {
        this.type = type;
    }

    /** @return the problem a stored or thrown failure text names, OTHER when it names none this enum knows */
    public static @NonNull AcmeProblem of(@Nullable String failure) {
        if (failure != null) {
            for (AcmeProblem problem : values()) {
                if (problem.type != null && failure.contains(URN + problem.type)) {
                    return problem;
                }
            }
        }
        return OTHER;
    }

    /** @return the admin's sentence for the failure, never its raw text */
    public static @NonNull Microcopy sentenceFor(@Nullable String failure) {
        String key = switch (of(failure)) {
            case UNAUTHORIZED, INCORRECT_RESPONSE -> "acme_unauthorized";
            case CONNECTION -> "acme_connection";
            case DNS -> "acme_dns";
            case RATE_LIMITED -> "acme_rate_limited";
            case CAA -> "acme_caa";
            case REJECTED_IDENTIFIER -> "acme_rejected_identifier";
            case OTHER -> "acme_other";
        };
        return Microcopy.of(key).withFilter("scope", "certificate_request_error");
    }
}
