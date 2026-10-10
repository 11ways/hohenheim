package be.elevenways.hohenheim.server.host;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Every reason the placement gate refuses a host for a new app, with what clears it: the one vocabulary the gate's
 * throw sites word themselves through and the host's verdict reads its remedy from.
 *
 * AIDEV-NOTE: a refusal is recognised by its catalog key, which each member declares once; a refusal no member
 * declares (a kind's own, an older one) reads with {@link Remedy#OPEN_HOST}, the remedy that claims nothing.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public enum PlacementRefusal {

    NOT_ADMITTED(HohenheimMicrocopy.VIOLATIONS.of("host_not_admitted"), Remedy.CHECK_AND_ADMIT),
    KEY_UNVERIFIED(HohenheimMicrocopy.VIOLATIONS.of("host_key_unverified"), Remedy.CONFIRM_KEY),
    QUARANTINED(HohenheimMicrocopy.VIOLATIONS.of("host_quarantined"), Remedy.REVIEW_KEY),
    POSTURE_REFUSES(HohenheimMicrocopy.VIOLATIONS.of("host_posture_refuses"), Remedy.OPEN_HOST),
    POSTURE_REQUIRES_VM(HohenheimMicrocopy.VIOLATIONS.of("host_posture_requires_vm"), Remedy.OPEN_HOST),
    POSTURE_UNACKNOWLEDGED(HohenheimMicrocopy.VIOLATIONS.of("host_posture_unacknowledged"), Remedy.OPEN_HOST),
    DEDICATED_TO_OTHER(HohenheimMicrocopy.VIOLATIONS.of("host_dedicated_to_other"), Remedy.OPEN_HOST),
    KERNEL_LANE_MISSING(HohenheimMicrocopy.VIOLATIONS.of("host_kernel_lane_missing"), Remedy.OPEN_HOST),
    KERNEL_LANE_UNPROVEN(HohenheimMicrocopy.VIOLATIONS.of("host_kernel_lane_unproven"), Remedy.CHECK_AGAIN),
    CHECK_NOW_REQUIRED(HohenheimMicrocopy.VIOLATIONS.of("host_preflight_check_now_required"), Remedy.CHECK_AGAIN),
    CONTACT_LAPSED(HohenheimMicrocopy.VIOLATIONS.of("host_contact_lapsed"), Remedy.OPEN_HOST),
    /** No memory reading inside the freshness bound: the chooser cannot ration the host, so it never picks it. */
    CAPACITY_UNPROVEN(HohenheimMicrocopy.VIOLATIONS.of("host_capacity_unproven"), Remedy.CHECK_AGAIN);

    /** What clears a refusal, as the operator does it on the host. */
    public enum Remedy {

        /** The host waits for admission: Check and admit. */
        CHECK_AND_ADMIT,

        /** A fresh check re-measures or re-proves what is missing: Check again. */
        CHECK_AGAIN,

        /** Nobody confirmed the key the host offers: compare it on the host page and confirm it. */
        CONFIRM_KEY,

        /** The host offers a key other than the confirmed one: compare the new key and re-pin it deliberately. */
        REVIEW_KEY,

        /** A decision on the host page (posture, trust, cordon, contact): open the host. */
        OPEN_HOST
    }

    private final Microcopy text;
    private final Remedy remedy;

    PlacementRefusal(@NonNull Microcopy text, @NonNull Remedy remedy) {
        this.text = text;
        this.remedy = remedy;
    }

    /** @return the refusal's words, before its arguments */
    public @NonNull Microcopy text() {
        return this.text;
    }

    /** @return what clears it */
    public @NonNull Remedy remedy() {
        return this.remedy;
    }

    /** @return the remedy of a refusal worded as {@code words}, {@link Remedy#OPEN_HOST} for one no member declares */
    public static @NonNull Remedy remedyOf(@NonNull Microcopy words) {
        PlacementRefusal refusal = of(words);
        return refusal == null ? Remedy.OPEN_HOST : refusal.remedy;
    }

    /** @return the member whose words these are, null for a refusal this vocabulary does not declare */
    public static @Nullable PlacementRefusal of(@NonNull Microcopy words) {
        for (PlacementRefusal refusal : values()) {
            if (refusal.text.key().equals(words.key())) {
                return refusal;
            }
        }
        return null;
    }
}
