package be.elevenways.hohenheim.server.upstream.kinds;

import be.elevenways.protoblast.common.i18n.Microcopy;

/**
 * The translation tokens for the enum values the upstream kinds offer; shared because
 * the scheme vocabulary is the same one whether the upstream is an address or an instance.
 */
final class UpstreamCopy {

    private UpstreamCopy() {}

    /** The key IS the stored scheme. */
    static Microcopy scheme(String scheme) {
        return Microcopy.of(scheme).withFilter("scope", "upstream_scheme");
    }

    /** The key IS the stored HTTP version token. */
    static Microcopy protocol(String protocol) {
        return Microcopy.of(protocol).withFilter("scope", "upstream_protocol");
    }

    /** The key IS the stored status code. */
    static Microcopy redirectStatus(String status) {
        return Microcopy.of(status).withFilter("scope", "redirect_status");
    }
}
