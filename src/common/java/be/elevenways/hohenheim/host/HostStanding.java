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
 * AIDEV-NOTE: the host attention item's title is a fact on the member, so a never-admitted host and an admitted one
 * the gate refuses are told apart in one place: "local cannot run apps yet" reads oddly for a host already running
 * apps (D9), which is why an admitted standing says "takes no new apps".
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public enum HostStanding {

    // Declaration order is the order the dashboard's Hosts tile tallies them in ("1 takes new apps, 1 waiting").

    /** Admitted, and the gate places new apps here. */
    TAKING("taking", Microcopy.of("admitted").withFilter("scope", "host_admission"),
        BadgeVariant.SUCCESS, "circle-check", false,
        Microcopy.of("host_holds_apps_back").withFilter("scope", "attention_title"),
        Microcopy.of("tally_taking").withFilter("scope", "host_admission")),

    /** Admitted, but the gate refuses every new app here. */
    REFUSING("refusing", Microcopy.of("refusing").withFilter("scope", "host_admission"),
        BadgeVariant.WARNING, "triangle-exclamation", true,
        Microcopy.of("host_takes_no_new_apps").withFilter("scope", "attention_title"),
        Microcopy.of("tally_refusing").withFilter("scope", "host_admission")),

    /** Not admitted yet: waiting for Check and admit. */
    WAITING("waiting", Microcopy.of("blocked").withFilter("scope", "host_admission"),
        BadgeVariant.WARNING, "hourglass-half", true,
        Microcopy.of("host_not_admitted").withFilter("scope", "attention_title"),
        Microcopy.of("tally_waiting").withFilter("scope", "host_admission")),

    /** Cordoned on purpose: what runs here keeps running. */
    PAUSED("paused", Microcopy.of("cordoned").withFilter("scope", "host_admission"),
        BadgeVariant.SECONDARY, "circle-pause", false,
        Microcopy.of("host_takes_no_new_apps").withFilter("scope", "attention_title"),
        Microcopy.of("tally_paused").withFilter("scope", "host_admission"));

    private final String token;
    private final Microcopy label;
    private final BadgeVariant variant;
    private final String icon;
    private final boolean raisesAttention;
    private final Microcopy attentionTitle;
    private final Microcopy tally;

    HostStanding(@NonNull String token, @NonNull Microcopy label, @NonNull BadgeVariant variant,
                 @NonNull String icon, boolean raisesAttention, @NonNull Microcopy attentionTitle,
                 @NonNull Microcopy tally) {
        this.token = token;
        this.label = label;
        this.variant = variant;
        this.icon = icon;
        this.raisesAttention = raisesAttention;
        this.attentionTitle = attentionTitle;
        this.tally = tally;
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

    /**
     * @param name the host's name
     * @return the host attention item's title for a host in this state; a taking host raises one only while the gate
     *         refuses some of the apps placed on it
     */
    public @NonNull Microcopy attentionTitle(@NonNull Object name) {
        return this.attentionTitle.withArg("name", name);
    }

    /**
     * @param count how many hosts stand so
     * @return the count in words ("2 waiting"), a part of the dashboard's Hosts tile line
     */
    public @NonNull Microcopy tally(int count) {
        return this.tally.withArg("count", count);
    }
}
