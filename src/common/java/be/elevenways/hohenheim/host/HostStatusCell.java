package be.elevenways.hohenheim.host;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.time.RelativeTimeWording;
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
 * @param errorKind   the typed failure class when {@code state} is {@link HostState#ERROR}
 * @param lastSeenIso last daemon contact, null when never reached
 * @param wording     request-independent relative-time wording (server default locale,
 *                    like every host stat computed without a conduit)
 */
@HawkeyeClass
public record HostStatusCell(
    @NonNull HostState state,
    String daemon,
    @Nullable String errorKind,
    @Nullable String lastSeenIso,
    @Nullable RelativeTimeWording wording
) {

    /**
     * The pl-status-dot token for this state.
     *
     * AIDEV-NOTE: deliberately a METHOD, not a record component. Only components cross the
     * DRY wire, so a derived value stored as one would be shipped instead of recomputed
     * after revival. Hawkeye resolves a plain zero-arg method for property access
     * ({@code {% value.dot %}}) exactly like a component -- but only in PROPERTY spelling;
     * call syntax on a @HawkeyeClass is a compile error.
     */
    public @NonNull String dot() {
        return this.state.dot();
    }

    /** The stable state token, for {@code data-host-state} and template branching. */
    public @NonNull String stateToken() {
        return this.state.token();
    }

    /**
     * The state's wording, with the failure class already bound for {@link HostState#ERROR}.
     *
     * @return null for {@link HostState#OK}, which shows the daemon label instead
     */
    public @Nullable Microcopy stateText() {
        Microcopy wording = this.state.wording();
        if (wording != null && this.state == HostState.ERROR) {
            return wording.withArg("kind", this.errorKind != null ? this.errorKind : "");
        }
        return wording;
    }
}
