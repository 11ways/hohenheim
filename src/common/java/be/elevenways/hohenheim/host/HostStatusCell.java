package be.elevenways.hohenheim.host;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.plumage.component.StatusDotStatus;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Structured host-list status cell: a typed state, the daemon label and the last-contact
 * instant -- rendered by {@code hohenheim:cms/cell/host-status} as a status dot plus a
 * live relative time, never a fully-resolved sentence.
 *
 * AIDEV-NOTE: QUARANTINED wins over everything and reads its OWN column, because the
 * transient error kind is overwritten by any later probe (the M078 lesson: a security
 * state a later success hides is worse than no state).
 *
 * @param state       the typed verdict; see {@link HostState} for why it is not a String
 * @param daemon      "Docker 27.1.1" / "Incus 7.3" -- the daemon plus its stored version
 * @param error       the failure class in words when {@code state} is {@link HostState#ERROR}
 * @param lastSeenIso last daemon contact, null when never reached; its relative time is worded by the render
 */
@HawkeyeClass
public record HostStatusCell(
    @NonNull HostState state,
    String daemon,
    @Nullable Microcopy error,
    @Nullable String lastSeenIso
) {

    /**
     * The typed pl-status-dot state.
     *
     * AIDEV-NOTE: deliberately a METHOD, not a record component. Only components cross the
     * DRY wire, so a derived value stored as one would be shipped instead of recomputed
     * after revival. Hawkeye resolves a plain zero-arg method for property access
     * ({@code {% value.dot %}}) exactly like a component -- but only in PROPERTY spelling;
     * call syntax on a @HawkeyeClass is a compile error.
     */
    public @NonNull StatusDotStatus dot() {
        return this.state.dot();
    }

    /** The stable state token, rendered as {@code data-host-state}. */
    public @NonNull String stateToken() {
        return this.state.token();
    }

    /** {@link HostState#loud}, in property spelling for the cell template. */
    public boolean loud() {
        return this.state.loud();
    }

    /**
     * The state's wording, with the failure class or the daemon already bound.
     *
     * @return the words beside the dot, before the last-contact time
     */
    public @NonNull Microcopy stateText() {
        Microcopy wording = this.state.wording();
        if (this.state == HostState.ERROR) {
            // The relative time after the words is the last contact: say so, or there is none to name.
            Microcopy failed = this.lastSeenIso != null
                ? HohenheimMicrocopy.SERVER.of("state_error_seen") : wording;
            return failed.withArg("kind", this.error != null ? this.error : Microcopy.literal(""));
        }
        return this.state.namesDaemon() ? wording.withArg("daemon", this.daemon != null ? this.daemon : "")
            : wording;
    }
}
