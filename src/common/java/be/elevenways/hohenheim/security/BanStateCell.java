package be.elevenways.hohenheim.security;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;

/**
 * Ban-list state cell: whether the ban still holds, was lifted by someone, or ran out.
 *
 * @param token one of {@link #ACTIVE}/{@link #LIFTED}/{@link #EXPIRED}
 */
@HawkeyeClass
public record BanStateCell(@NonNull String token) {

    /** The ban is enforced. */
    public static final String ACTIVE = "active";

    /** An operator lifted the ban before it expired. */
    public static final String LIFTED = "lifted";

    /** The ban reached its expiry (or was deactivated by the expiry sweep). */
    public static final String EXPIRED = "expired";

    /**
     * Derive the state from the stored facts; a lift stamp beats everything, so a ban
     * lifted after its expiry still reads as the operator's act.
     */
    public static @NonNull BanStateCell of(boolean active, @Nullable Instant liftedAt,
                                           @Nullable Instant expiresAt, @NonNull Instant now) {
        if (liftedAt != null) {
            return new BanStateCell(LIFTED);
        }
        if (!active || (expiresAt != null && !expiresAt.isAfter(now))) {
            return new BanStateCell(EXPIRED);
        }
        return new BanStateCell(ACTIVE);
    }

    /** The pl-badge variant for this state (derived, so it never crosses the wire). */
    public @NonNull String variant() {
        return switch (this.token) {
            case ACTIVE -> "destructive";
            case LIFTED -> "secondary";
            default -> "outline";
        };
    }

    /** The translated wording for this state. */
    public @NonNull Microcopy label() {
        return Microcopy.of(this.token).withFilter("scope", "ban_state");
    }
}
