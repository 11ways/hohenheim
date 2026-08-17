package be.elevenways.hohenheim.host;

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

    PASS("pass", "success"),
    WARN("warn", "warning"),
    FAIL("fail", "destructive");

    private final String token;
    private final String badgeVariant;

    PreflightStatus(String token, String badgeVariant) {
        this.token = token;
        this.badgeVariant = badgeVariant;
    }

    /** The persisted token; also what a Check carries as its status. */
    public @NonNull String token() {
        return this.token;
    }

    /** The pl-badge variant this verdict renders as. */
    public @NonNull String badgeVariant() {
        return this.badgeVariant;
    }

    /** Whether this verdict is the clean one -- the only member for which it is true. */
    public boolean passed() {
        return this == PASS;
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
