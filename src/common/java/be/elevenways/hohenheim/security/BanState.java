package be.elevenways.hohenheim.security;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.WordedState;
import be.elevenways.hohenheim.model.BanModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.ui.BadgeVariant;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;

/**
 * Whether a ban still holds, was lifted by someone, or ran out: the ban list's state cell.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public enum BanState implements WordedState {

    /** The ban is enforced. */
    ACTIVE("active", BadgeVariant.DESTRUCTIVE, HohenheimMicrocopy.BAN_STATE.of("active")),

    /** An operator lifted the ban before it expired. */
    LIFTED("lifted", BadgeVariant.SECONDARY, HohenheimMicrocopy.BAN_STATE.of("lifted")),

    /** The ban reached its expiry (or was deactivated by the expiry sweep). */
    EXPIRED("expired", BadgeVariant.OUTLINE, HohenheimMicrocopy.BAN_STATE.of("expired"));

    private final String token;
    private final BadgeVariant variant;
    private final Microcopy label;

    BanState(@NonNull String token, @NonNull BadgeVariant variant, @NonNull Microcopy label) {
        this.token = token;
        this.variant = variant;
        this.label = label;
    }

    /**
     * Derive the state from the stored facts; a lift stamp beats everything, so a ban lifted after its expiry still
     * reads as the operator's act, and ACTIVE is exactly {@link BanModel#blockedNow(Row, Instant)}.
     *
     * @param now the instant asked about, read through {@code Now}
     */
    public static @NonNull BanState of(@NonNull Row ban, @NonNull Instant now) {
        if (ban.get(BanModel.LIFTED_AT) != null) {
            return LIFTED;
        }
        return BanModel.blockedNow(ban, now) ? ACTIVE : EXPIRED;
    }

    /**
     * When the ban stops or stopped blocking, the list's "Until": a lift beats the expiry, as in {@link #of}.
     *
     * @return the lift instant, else the expiry; null for a permanent ban nobody lifted
     */
    public static @Nullable Instant until(@NonNull Row ban) {
        Instant lifted = ban.get(BanModel.LIFTED_AT);
        return lifted != null ? lifted : ban.get(BanModel.EXPIRES_AT);
    }

    @Override
    public @NonNull String token() {
        return this.token;
    }

    @Override
    public @NonNull BadgeVariant variant() {
        return this.variant;
    }

    @Override
    public @NonNull Microcopy label() {
        return this.label;
    }
}
