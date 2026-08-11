package be.elevenways.hohenheim.host;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.time.RelativeTimeWording;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Structured host-list status cell: a state token, the daemon label and the last-contact
 * instant -- rendered by {@code hohenheim:cms/cell/host-status} as a status dot plus a
 * live relative time, never a fully-resolved sentence.
 *
 * AIDEV-NOTE: QUARANTINED wins over everything and reads its OWN column, because the
 * transient error kind is overwritten by any later probe (the M078 lesson: a security
 * state a later success hides is worse than no state).
 *
 * @param state       {@code quarantined} / {@code error} / {@code silent} /
 *                    {@code never_probed} / {@code ok}
 * @param daemon      "Docker 27.1.1" / "Incus 7.3" -- the daemon plus its stored version
 * @param errorKind   the typed failure class when {@code state} is {@code error}
 * @param lastSeenIso last daemon contact, null when never reached
 * @param wording     request-independent relative-time wording (server default locale,
 *                    like every host stat computed without a conduit)
 */
@HawkeyeClass
public record HostStatusCell(
    String state,
    String dot,
    String daemon,
    @Nullable String errorKind,
    @Nullable String lastSeenIso,
    @Nullable RelativeTimeWording wording
) {

    /** Build with the pl-status-dot token derived from the state. */
    public static HostStatusCell of(String state, String daemon, @Nullable String errorKind,
                                    @Nullable String lastSeenIso,
                                    @Nullable RelativeTimeWording wording) {
        String dot = switch (state) {
            case "quarantined", "error" -> "error";
            case "silent" -> "warning";
            case "never_probed" -> "idle";
            default -> "online";
        };
        return new HostStatusCell(state, dot, daemon, errorKind, lastSeenIso, wording);
    }
}
