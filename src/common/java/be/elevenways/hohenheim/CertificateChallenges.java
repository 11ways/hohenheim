package be.elevenways.hohenheim;

import be.elevenways.hawkeye.common.annotation.HawkeyeGlobal;
import be.elevenways.hohenheim.model.CertificateModel;

/**
 * The certificate-request form's option values, exposed to templates from CertificateModel's own constants.
 *
 * AIDEV-NOTE: the challenge types and DNS publishers are declared ONCE, on CertificateModel (its
 * CHALLENGE_TYPE and DNS publisher EnumFields); this class only lets a template name them as
 * {@code CertificateChallenges.HTTP} instead of re-spelling "http" in an option value.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class CertificateChallenges {

    @HawkeyeGlobal(namespace = "CertificateChallenges")
    public static final String HTTP = CertificateModel.CHALLENGE_HTTP;

    @HawkeyeGlobal(namespace = "CertificateChallenges")
    public static final String DNS = CertificateModel.CHALLENGE_DNS;

    @HawkeyeGlobal(namespace = "CertificateChallenges")
    public static final String DNS_MANUAL = CertificateModel.DNS_PUBLISHER_MANUAL;

    @HawkeyeGlobal(namespace = "CertificateChallenges")
    public static final String DNS_INTERNAL = CertificateModel.DNS_PUBLISHER_INTERNAL;

    @HawkeyeGlobal(namespace = "CertificateChallenges")
    public static final String DNS_COMMAND = CertificateModel.DNS_PUBLISHER_COMMAND;

    private CertificateChallenges() {
    }
}
