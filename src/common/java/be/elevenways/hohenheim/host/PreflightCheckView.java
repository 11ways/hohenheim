package be.elevenways.hohenheim.host;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.ui.BadgeVariant;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One stored preflight check, exactly as {@code HostPreflight.store} persisted it.
 *
 * @param atIso when THIS verdict was produced -- can be older than {@code probed_at},
 *              because the store merges; null for a pre-stamp record
 * @param fix   what an operator does to make a check that did not pass pass, null when it passed or names no
 *              check the batteries declare
 */
@HawkeyeClass
public record PreflightCheckView(
    String name,
    String status,
    BadgeVariant statusVariant,
    boolean required,
    String detail,
    @Nullable String atIso,
    @Nullable Microcopy fix
) {

    /** Build with the pl-badge variant READ OFF the verdict, never re-spelled here. */
    public static PreflightCheckView of(String name, String status, boolean required,
                                        String detail, @Nullable String atIso) {
        BadgeVariant variant = PreflightStatus.fromToken(status).badgeVariant();
        return new PreflightCheckView(name, status, variant, required, detail, atIso, null);
    }

    /** @return whether this check did not pass, which is what puts it first and gives it a fix */
    public boolean notPassing() {
        return !PreflightStatus.fromToken(this.status).passed();
    }

    /** @return this check carrying the given fix */
    public PreflightCheckView withFix(@Nullable Microcopy fix) {
        return new PreflightCheckView(this.name, this.status, this.statusVariant, this.required, this.detail,
            this.atIso, fix);
    }
}
