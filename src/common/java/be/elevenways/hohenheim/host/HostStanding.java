package be.elevenways.hohenheim.host;

import be.elevenways.hohenheim.AttentionSeverity;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.WordedState;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.ui.BadgeVariant;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Whether a host takes new apps, as the placement gate answers it: the state the Hosts list, the host page and the
 * attention band word, never the stored admission token alone.
 *
 * AIDEV-NOTE: an ADMITTED host the gate still refuses (a stale memory reading, a posture, a check that no longer
 * passes) is {@link #REFUSING}, never {@link #TAKING}: DEP9 found Starfleet's local host reading "Takes new apps"
 * while placement refused it. This enum is the ONE declaration of the host's state badge: each stored
 * {@link ServerModel#ADMISSION} value is derived from the standing that carries its token, so a stored "blocked" and
 * a waiting host can never wear two colours (DD3).
 *
 * AIDEV-NOTE: the host attention item's title is a fact on the member, so a never-admitted host and an admitted one
 * the gate refuses are told apart in one place: "local cannot run apps yet" reads oddly for a host already running
 * apps (D9), which is why an admitted standing says "takes no new apps".
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public enum HostStanding implements WordedState {

    // Declaration order is the order the dashboard's Hosts tile tallies them in ("1 takes new apps, 1 waiting").

    /** Admitted, and the gate places new apps here. */
    TAKING("taking", ServerModel.ADMISSION_ADMITTED, HohenheimMicrocopy.HOST_ADMISSION.of("admitted"),
        BadgeVariant.SUCCESS, "circle-check", null,
        HohenheimMicrocopy.ATTENTION_TITLE.of("host_holds_apps_back"),
        HohenheimMicrocopy.HOST_ADMISSION.of("tally_taking")),

    /** Admitted, but the gate refuses every new app here. */
    REFUSING("refusing", null, HohenheimMicrocopy.HOST_ADMISSION.of("refusing"),
        BadgeVariant.WARNING, "triangle-exclamation", AttentionSeverity.WARNING,
        HohenheimMicrocopy.ATTENTION_TITLE.of("host_takes_no_new_apps"),
        HohenheimMicrocopy.HOST_ADMISSION.of("tally_refusing")),

    /** Not admitted yet: waiting for Check and admit. */
    WAITING("waiting", ServerModel.ADMISSION_BLOCKED, HohenheimMicrocopy.HOST_ADMISSION.of("blocked"),
        BadgeVariant.WARNING, "hourglass-half", AttentionSeverity.WARNING,
        HohenheimMicrocopy.ATTENTION_TITLE.of("host_not_admitted"),
        HohenheimMicrocopy.HOST_ADMISSION.of("tally_waiting")),

    /** Cordoned on purpose: what runs here keeps running. */
    PAUSED("paused", ServerModel.ADMISSION_CORDONED, HohenheimMicrocopy.HOST_ADMISSION.of("cordoned"),
        BadgeVariant.SECONDARY, "circle-pause", null,
        HohenheimMicrocopy.ATTENTION_TITLE.of("host_takes_no_new_apps"),
        HohenheimMicrocopy.HOST_ADMISSION.of("tally_paused"));

    private final String token;
    private final @Nullable String admission;
    private final Microcopy label;
    private final BadgeVariant variant;
    private final String icon;
    private final @Nullable AttentionSeverity severity;
    private final Microcopy attentionTitle;
    private final Microcopy tally;

    HostStanding(@NonNull String token, @Nullable String admission, @NonNull Microcopy label,
                 @NonNull BadgeVariant variant, @NonNull String icon, @Nullable AttentionSeverity severity,
                 @NonNull Microcopy attentionTitle, @NonNull Microcopy tally) {
        this.token = token;
        this.admission = admission;
        this.label = label;
        this.variant = variant;
        this.icon = icon;
        this.severity = severity;
        this.attentionTitle = attentionTitle;
        this.tally = tally;
    }

    /** @return the stable token a state cell renders */
    @Override
    public @NonNull String token() {
        return this.token;
    }

    /** @return the stored admission token this standing is the badge of, null for one only the gate derives */
    public @Nullable String admission() {
        return this.admission;
    }

    /** @return the state in words */
    @Override
    public @NonNull Microcopy label() {
        return this.label;
    }

    /** @return the badge variant it wears */
    @Override
    public @NonNull BadgeVariant variant() {
        return this.variant;
    }

    /** @return the plumage icon beside the words */
    public @NonNull String icon() {
        return this.icon;
    }

    /**
     * @return how loud this state's own attention item is, null when it raises none by itself; a paused or taking host
     *         raises one only while it holds apps back
     */
    public @Nullable AttentionSeverity severity() {
        return this.severity;
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
