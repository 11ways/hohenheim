package be.elevenways.hohenheim.host;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.ui.BadgeVariant;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * THE preflight verdict vocabulary: the token a stored check carries in a host's
 * capabilities JSON, plus the pl-badge variant that renders it.
 *
 * AIDEV-NOTE: this lives in common (not beside {@code HostPreflight}, which is server-only)
 * because the badge fact is read while RENDERING. The three view/reader satellites used to
 * re-spell pass/warn/fail themselves; a fourth verdict would have rendered "destructive"
 * there while meaning something else. The token strings stay the persisted shape -- stored
 * reports are already on disk with them, so a member's token is part of the record format.
 */
public enum PreflightStatus {

    PASS("pass", BadgeVariant.SUCCESS),
    WARN("warn", BadgeVariant.WARNING),
    FAIL("fail", BadgeVariant.DESTRUCTIVE);

    private final String token;
    private final BadgeVariant badgeVariant;

    PreflightStatus(String token, BadgeVariant badgeVariant) {
        this.token = token;
        this.badgeVariant = badgeVariant;
    }

    /** The persisted token; also what a Check carries as its status. */
    public @NonNull String token() {
        return this.token;
    }

    /** The pl-badge variant this verdict renders as. */
    public @NonNull BadgeVariant badgeVariant() {
        return this.badgeVariant;
    }

    /** @return this verdict in words ("Passed", "Advice", "Failed"), as the host page's badge reads it */
    public @NonNull Microcopy label() {
        return Microcopy.of("verdict_" + this.token).withFilter("scope", "host_check");
    }

    /** Whether this verdict is the clean one -- the only member for which it is true. */
    public boolean passed() {
        return this == PASS;
    }

    /**
     * The verdict a reader is shown for a check that is or is not required.
     *
     * AIDEV-NOTE: advice never blocks admission, so an advisory check that did not pass reads as advice in the
     * warning tone whatever it stored; the Incus battery stores FAIL on its advisory lsm check when the probe never
     * answered, which used to draw a red "Failed" under "Advice never blocks admission".
     *
     * @return this verdict for a required check or a pass, else {@link #WARN}
     */
    public @NonNull PreflightStatus shownFor(boolean required) {
        return required || this.passed() ? this : WARN;
    }

    /**
     * The member behind a stored token.
     *
     * AIDEV-NOTE: fails CLOSED. A token this build does not know (an older/newer controller
     * wrote the record, or the JSON is damaged) is NOT evidence a host is healthy, so it
     * reads as FAIL rather than as an optional or a pass.
     */
    public static @NonNull PreflightStatus fromToken(@Nullable String token) {
        for (PreflightStatus status : values()) {
            if (status.token.equals(token)) {
                return status;
            }
        }
        return FAIL;
    }
}
