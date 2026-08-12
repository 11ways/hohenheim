package be.elevenways.hohenheim.host;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The stored posture acknowledgement as data: who accepted which warning version, when,
 * and whether that acceptance still answers for the posture the host declares today.
 *
 * AIDEV-NOTE: required, not decoration. The same lesson the host fingerprint carries --
 * an operator who cannot SEE a trust record can never notice it is missing or stale --
 * and here the consequence is a host silently refusing every new tenant container with
 * the reason visible only in a deploy-time violation.
 *
 * @param needed  whether this host's posture demands an acknowledgement at all
 * @param current whether the stored one answers for today's posture AND warning version
 */
@HawkeyeClass
public record PostureAcknowledgementView(
    boolean needed,
    boolean current,
    @Nullable String posture,
    @Nullable Integer version,
    int requiredVersion,
    @Nullable String acknowledgedAtIso,
    String actorLabel
) {

    /** A stored acknowledgement exists but no longer answers -- a version bump, typically. */
    public boolean stale() {
        return this.needed && !this.current && this.posture != null;
    }

    /** The posture demands an acknowledgement and nobody has given one. */
    public boolean missing() {
        return this.needed && this.posture == null;
    }
}
