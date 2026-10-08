package be.elevenways.hohenheim.security;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.hohenheim.model.BanModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.ui.BadgeVariant;
import org.checkerframework.checker.nullness.qual.NonNull;

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
     * Derive the state from the stored facts; a lift stamp beats everything, so a ban lifted after its expiry still
     * reads as the operator's act, and ACTIVE is exactly {@link BanModel#blockedNow(Row, Instant)}.
     *
     * @param now the instant asked about, read through {@code Now}
     */
    public static @NonNull BanStateCell of(@NonNull Row ban, @NonNull Instant now) {
        if (ban.get(BanModel.LIFTED_AT) != null) {
            return new BanStateCell(LIFTED);
        }
        return new BanStateCell(BanModel.blockedNow(ban, now) ? ACTIVE : EXPIRED);
    }

    /** The pl-badge variant for this state (derived, so it never crosses the wire). */
    public @NonNull BadgeVariant variant() {
        return switch (this.token) {
            case ACTIVE -> BadgeVariant.DESTRUCTIVE;
            case LIFTED -> BadgeVariant.SECONDARY;
            default -> BadgeVariant.OUTLINE;
        };
    }

    /** The translated wording for this state. */
    public @NonNull Microcopy label() {
        return Microcopy.of(this.token).withFilter("scope", "ban_state");
    }
}
