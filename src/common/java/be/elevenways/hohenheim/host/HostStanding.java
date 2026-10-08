package be.elevenways.hohenheim.host;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.ui.BadgeVariant;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Whether a host takes new apps, as the placement gate answers it: the state the Hosts list, the host page and the
 * attention band word, never the stored admission token alone.
 *
 * AIDEV-NOTE: an ADMITTED host the gate still refuses (a stale memory reading, a posture, a check that no longer
 * passes) is {@link #REFUSING}, never {@link #TAKING}: DEP9 found Starfleet's local host reading "Takes new apps"
 * while placement refused it. The three admission words stay the {@code host_admission} entries the ADMISSION
 * enum's labels read.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public enum HostStanding {

    /** Not admitted yet: waiting for Check and admit. */
    WAITING("waiting", Microcopy.of("blocked").withFilter("scope", "host_admission"),
        BadgeVariant.WARNING, "hourglass-half", true),

    /** Admitted, and the gate places new apps here. */
    TAKING("taking", Microcopy.of("admitted").withFilter("scope", "host_admission"),
        BadgeVariant.SUCCESS, "circle-check", false),

    /** Admitted, but the gate refuses every new app here. */
    REFUSING("refusing", Microcopy.of("refusing").withFilter("scope", "host_admission"),
        BadgeVariant.WARNING, "triangle-exclamation", true),

    /** Cordoned on purpose: what runs here keeps running. */
    PAUSED("paused", Microcopy.of("cordoned").withFilter("scope", "host_admission"),
        BadgeVariant.SECONDARY, "circle-pause", false);

    private final String token;
    private final Microcopy label;
    private final BadgeVariant variant;
    private final String icon;
    private final boolean raisesAttention;

    HostStanding(@NonNull String token, @NonNull Microcopy label, @NonNull BadgeVariant variant,
                 @NonNull String icon, boolean raisesAttention) {
        this.token = token;
        this.label = label;
        this.variant = variant;
        this.icon = icon;
        this.raisesAttention = raisesAttention;
    }

    /** @return the stable token a state cell renders */
    public @NonNull String token() {
        return this.token;
    }

    /** @return the state in words */
    public @NonNull Microcopy label() {
        return this.label;
    }

    /** @return the badge variant it wears */
    public @NonNull BadgeVariant variant() {
        return this.variant;
    }

    /** @return the plumage icon beside the words */
    public @NonNull String icon() {
        return this.icon;
    }

    /**
     * @return whether this state is an attention item by itself; a paused or taking host raises one only while it
     *         holds apps back
     */
    public boolean raisesAttention() {
        return this.raisesAttention;
    }
}
